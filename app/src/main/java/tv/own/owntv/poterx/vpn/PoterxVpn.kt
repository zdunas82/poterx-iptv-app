package tv.own.owntv.poterx.vpn

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Poterx: an in-app WireGuard tunnel (Settings › Network › VPN). Two ways in: a `.conf` imported
 * from any WireGuard provider, or a Surfshark account, for which the app registers its own key,
 * picks the least loaded server of the chosen country and writes the configuration itself. The
 * tunnel carries this application's traffic only, the rest of the television stays on the ordinary
 * connection. Built on the official `com.wireguard.android:tunnel` library and Android's VpnService.
 *
 * Profiles and the Surfshark session hold private keys, so they are kept encrypted with a key in the
 * Android Keystore; the active profile and the connect-on-start switch are ordinary preferences.
 */
class PoterxVpn(private val context: Context, http: OkHttpClient) {

    /** A saved `.conf`: [config] is the file's text as imported, routing is added at connect time. */
    data class Profile(val id: String, val name: String, val endpoint: String, val config: String, val createdAt: Long)

    /** The signed-in Surfshark account and this device's WireGuard key pair. */
    data class SurfsharkAccount(
        val email: String,
        val token: String,
        val renewToken: String,
        val privateKey: String,
        val publicKey: String,
        val keyExpiresAt: String?,
        val countryCode: String,
        /** The server the last connection used, for the status line. */
        val lastServer: String,
    )

    sealed interface State {
        data object Off : State
        data object Connecting : State
        /** Up, with the latest peer handshake (0 = none yet), the bytes moved since connect and the
         *  current rate in bits per second (over the last sampling period). */
        data class On(
            val sinceMs: Long,
            val handshakeMs: Long,
            val rxBytes: Long,
            val txBytes: Long,
            val rxBitsPerSecond: Long = 0L,
            val txBitsPerSecond: Long = 0L,
        ) : State

        /** Bytes moved through the tunnel this calendar month, across connections and restarts. */
        data class Usage(val month: String, val rxBytes: Long, val txBytes: Long)
        data class Failed(val reason: Failure) : State
    }

    enum class Failure { NOT_AUTHORIZED, NO_PROFILE, BAD_CONFIG, DNS, START, ACCOUNT, SERVERS, UNKNOWN }

    sealed interface SignIn {
        data object Ok : SignIn
        data object NeedsCode : SignIn
        data object Rejected : SignIn
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val backend: GoBackend by lazy { GoBackend(context) }
    private val surfshark = SurfsharkClient(http)
    private var statsJob: Job? = null
    private var connectedAt = 0L
    private var pendingCode: SurfsharkClient.Tokens? = null
    private var pendingEmail: String = ""

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _account = MutableStateFlow<SurfsharkAccount?>(null)
    val account: StateFlow<SurfsharkAccount?> = _account.asStateFlow()
    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, false))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _clusters = MutableStateFlow<List<SurfsharkClient.Cluster>>(emptyList())
    val clusters: StateFlow<List<SurfsharkClient.Cluster>> = _clusters.asStateFlow()
    private val _usage = MutableStateFlow(loadUsage())
    val usage: StateFlow<State.Usage> = _usage.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _state.value is State.On) {
                statsJob?.cancel()
                _state.value = State.Off
            }
        }
    }

    init {
        scope.launch {
            runCatching { store.load() }.getOrNull()?.let { (profiles, account) ->
                _profiles.value = profiles
                _account.value = account
            }
        }
    }

    /** Null when the system already allows this app to open a VPN; else the consent screen to show once. */
    fun prepareIntent(): Intent? = android.net.VpnService.prepare(context)

    val activeProfile: Profile? get() = _profiles.value.firstOrNull { it.id == _activeId.value }

    /** True when the active choice is the Surfshark account. */
    val surfsharkActive: Boolean get() = _activeId.value == SURFSHARK_ID && _account.value != null

    /** Something to connect with: a profile, or the Surfshark account. */
    val hasActive: Boolean get() = activeProfile != null || surfsharkActive

    /** The provider built a VPN account into this app: the switch alone signs in and connects. */
    val builtIn: Boolean
        get() = tv.own.owntv.poterx.LockedSource.enabled && !tv.own.owntv.poterx.LockedSource.account?.vpnUser.isNullOrBlank()

    /**
     * The customer's one control on a built-in account: on signs in (once), remembers the choice for
     * every start and connects; off forgets the choice and closes the tunnel. Consent must be given.
     */
    fun enable() {
        setAutoConnect(true)
        scope.launch {
            if (!signInBuiltIn()) return@launch
            if (prepareIntent() == null) connect() else _state.value = State.Failed(Failure.NOT_AUTHORIZED)
        }
    }

    fun disable() {
        setAutoConnect(false)
        disconnect()
    }

    /** Signs in with the built-in account when no session is held yet; true when there is one to use. */
    private suspend fun signInBuiltIn(): Boolean {
        if (_account.value != null) return true
        val creds = tv.own.owntv.poterx.LockedSource.account?.takeIf { builtIn } ?: return false
        _state.value = State.Connecting
        return when (surfsharkSignIn(creds.vpnUser, creds.vpnPass)) {
            SignIn.Ok -> true
            else -> { _state.value = State.Failed(Failure.ACCOUNT); false }
        }
    }

    // ---- profiles from a .conf -------------------------------------------------------------------

    /** Validates [text] as a WireGuard configuration and saves it; the new profile becomes active. */
    suspend fun importConfig(text: String, suggestedName: String? = null): Result<Profile> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = parse(withAppRouting(text, context.packageName))
            val endpoint = parsed.peers.firstOrNull()?.endpoint?.map { it.toString() }?.orElse("") ?: ""
            val name = suggestedName?.trim()?.takeIf { it.isNotBlank() }
                ?: endpoint.substringBefore(':').takeIf { it.isNotBlank() }
                ?: context.getString(tv.own.owntv.R.string.settings_vpn_profile_default_name, _profiles.value.size + 1)
            val profile = Profile(UUID.randomUUID().toString(), name, endpoint, text.trim(), System.currentTimeMillis())
            lock.withLock {
                val next = _profiles.value + profile
                store.save(next, _account.value)
                _profiles.value = next
                setActive(profile.id)
            }
            profile
        }
    }

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
    }

    fun setAutoConnect(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, on).apply()
        _autoConnect.value = on
    }

    fun delete(id: String) {
        scope.launch {
            lock.withLock {
                val next = _profiles.value.filterNot { it.id == id }
                runCatching { store.save(next, _account.value) }
                _profiles.value = next
                if (_activeId.value == id) {
                    if (_state.value !is State.Off) disconnectNow()
                    val fallback = next.firstOrNull()?.id ?: if (_account.value != null) SURFSHARK_ID else null
                    prefs.edit().putString(KEY_ACTIVE, fallback).apply()
                    _activeId.value = fallback
                }
            }
        }
    }

    // ---- Surfshark account -----------------------------------------------------------------------

    /** Signs in; [SignIn.NeedsCode] means [surfsharkCode] must follow with the authenticator's code. */
    suspend fun surfsharkSignIn(email: String, password: String): SignIn {
        return when (val result = runCatching { surfshark.login(email, password) }.getOrNull()) {
            is SurfsharkClient.Login.Ok -> { finishSignIn(email, result.tokens); SignIn.Ok }
            is SurfsharkClient.Login.NeedsCode -> { pendingCode = result.interim; pendingEmail = email; SignIn.NeedsCode }
            else -> SignIn.Rejected
        }
    }

    suspend fun surfsharkCode(code: String): SignIn {
        val interim = pendingCode ?: return SignIn.Rejected
        val tokens = runCatching { surfshark.activate(interim, code) }.getOrNull() ?: return SignIn.Rejected
        pendingCode = null
        finishSignIn(pendingEmail, tokens)
        return SignIn.Ok
    }

    private suspend fun finishSignIn(email: String, tokens: SurfsharkClient.Tokens) {
        val keys = KeyPair()
        val account = SurfsharkAccount(
            email = email.trim(),
            token = tokens.token,
            renewToken = tokens.renewToken,
            privateKey = keys.privateKey.toBase64(),
            publicKey = keys.publicKey.toBase64(),
            keyExpiresAt = null,
            countryCode = _account.value?.countryCode ?: Locale.getDefault().country.ifBlank { DEFAULT_COUNTRY },
            lastServer = "",
        )
        lock.withLock {
            store.save(_profiles.value, account)
            _account.value = account
        }
        setActive(SURFSHARK_ID)
    }

    fun surfsharkSignOut() {
        scope.launch {
            lock.withLock {
                if (_activeId.value == SURFSHARK_ID && _state.value !is State.Off) disconnectNow()
                runCatching { store.save(_profiles.value, null) }
                _account.value = null
                if (_activeId.value == SURFSHARK_ID) {
                    val fallback = _profiles.value.firstOrNull()?.id
                    prefs.edit().putString(KEY_ACTIVE, fallback).apply()
                    _activeId.value = fallback
                }
            }
        }
    }

    fun setSurfsharkCountry(countryCode: String) {
        scope.launch {
            lock.withLock {
                val account = _account.value ?: return@launch
                val next = account.copy(countryCode = countryCode.uppercase())
                runCatching { store.save(_profiles.value, next) }
                _account.value = next
            }
            if (_activeId.value == SURFSHARK_ID && _state.value !is State.Off) { disconnect(); connect() }
        }
    }

    /** The server list, fetched once per process (the country picker and server choice read it). */
    suspend fun loadClusters(): List<SurfsharkClient.Cluster> {
        _clusters.value.takeIf { it.isNotEmpty() }?.let { return it }
        val list = runCatching { surfshark.clusters() }.getOrDefault(emptyList())
        if (list.isNotEmpty()) _clusters.value = list
        return list
    }

    /**
     * The configuration for the Surfshark account: the key registered or renewed (a fresh pair when
     * Surfshark forgot the old one), the least loaded server of the chosen country. Null with the
     * failure to show when any step is refused.
     */
    private suspend fun surfsharkConfig(): Pair<String?, Failure?> {
        var account = _account.value ?: return null to Failure.ACCOUNT
        var tokens = SurfsharkClient.Tokens(account.token, account.renewToken)
        var status = surfshark.registerKey(tokens, account.publicKey)
        if (status is SurfsharkClient.KeyStatus.Unauthorized) {
            tokens = surfshark.renew(tokens) ?: return null to Failure.ACCOUNT
            account = account.copy(token = tokens.token, renewToken = tokens.renewToken)
            status = surfshark.registerKey(tokens, account.publicKey)
        }
        if (status is SurfsharkClient.KeyStatus.Expired) {
            val keys = KeyPair()
            account = account.copy(privateKey = keys.privateKey.toBase64(), publicKey = keys.publicKey.toBase64())
            status = surfshark.registerKey(tokens, account.publicKey)
        }
        if (status is SurfsharkClient.KeyStatus.Unauthorized && builtIn) {
            // The provider's account: a dead session is signed in again rather than reported.
            val creds = tv.own.owntv.poterx.LockedSource.account ?: return null to Failure.ACCOUNT
            val login = surfshark.login(creds.vpnUser, creds.vpnPass) as? SurfsharkClient.Login.Ok ?: return null to Failure.ACCOUNT
            tokens = login.tokens
            account = account.copy(token = tokens.token, renewToken = tokens.renewToken)
            status = surfshark.registerKey(tokens, account.publicKey)
            if (status is SurfsharkClient.KeyStatus.Expired) {
                val keys = KeyPair()
                account = account.copy(privateKey = keys.privateKey.toBase64(), publicKey = keys.publicKey.toBase64())
                status = surfshark.registerKey(tokens, account.publicKey)
            }
        }
        when (status) {
            is SurfsharkClient.KeyStatus.Valid -> account = account.copy(keyExpiresAt = status.expiresAt)
            is SurfsharkClient.KeyStatus.Unauthorized -> return null to Failure.ACCOUNT
            else -> return null to Failure.SERVERS
        }
        val cluster = SurfsharkClient.pick(loadClusters(), account.countryCode) ?: return null to Failure.SERVERS
        account = account.copy(lastServer = cluster.location.ifBlank { cluster.connectionName })
        runCatching { store.save(_profiles.value, account) }
        _account.value = account
        return SurfsharkClient.buildConfig(account.privateKey, cluster) to null
    }

    // ---- the tunnel ------------------------------------------------------------------------------

    /** Brings the active choice up. The consent screen must have been accepted ([prepareIntent] null). */
    fun connect() {
        if (!hasActive) { _state.value = State.Failed(Failure.NO_PROFILE); return }
        if (prepareIntent() != null) { _state.value = State.Failed(Failure.NOT_AUTHORIZED); return }
        _state.value = State.Connecting
        scope.launch {
            lock.withLock {
                try {
                    val text = if (surfsharkActive) {
                        val (config, failure) = surfsharkConfig()
                        if (config == null) { _state.value = State.Failed(failure ?: Failure.UNKNOWN); return@withLock }
                        config
                    } else {
                        activeProfile?.config ?: run { _state.value = State.Failed(Failure.NO_PROFILE); return@withLock }
                    }
                    val config = parse(withAppRouting(text, context.packageName))
                    backend.setState(tunnel, Tunnel.State.UP, config)
                    connectedAt = System.currentTimeMillis()
                    _state.value = State.On(connectedAt, 0L, 0L, 0L)
                    startStats()
                } catch (e: BadConfigException) {
                    _state.value = State.Failed(Failure.BAD_CONFIG)
                } catch (e: BackendException) {
                    _state.value = State.Failed(
                        when (e.reason) {
                            BackendException.Reason.VPN_NOT_AUTHORIZED -> Failure.NOT_AUTHORIZED
                            BackendException.Reason.DNS_RESOLUTION_FAILURE -> Failure.DNS
                            BackendException.Reason.UNABLE_TO_START_VPN, BackendException.Reason.TUN_CREATION_ERROR -> Failure.START
                            else -> Failure.UNKNOWN
                        },
                    )
                } catch (e: Exception) {
                    _state.value = State.Failed(Failure.UNKNOWN)
                }
            }
        }
    }

    fun disconnect() {
        scope.launch { lock.withLock { disconnectNow() } }
    }

    private fun disconnectNow() {
        statsJob?.cancel()
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        _state.value = State.Off
    }

    /** At app start: the active choice comes up by itself when the switch is on and consent was given. */
    fun autoConnectIfReady() {
        if (!_autoConnect.value) return
        scope.launch {
            // The store loads asynchronously; give it a moment before deciding there is nothing to connect.
            repeat(20) { if (hasActive) return@repeat; delay(100) }
            if (!hasActive && builtIn && prepareIntent() == null) { if (signInBuiltIn()) connect(); return@launch }
            if (hasActive && prepareIntent() == null && _state.value is State.Off) connect()
        }
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var silentSince = 0L
            // The tunnel's counters start at zero on connect; the deltas between samples give the
            // rate and are added to the month's usage.
            var lastRx = 0L
            var lastTx = 0L
            var lastAt = 0L
            while (isActive) {
                delay(STATS_PERIOD_MS)
                val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: continue
                val handshake = stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
                val rx = stats.totalRx()
                val tx = stats.totalTx()
                val sampledAt = System.currentTimeMillis()
                val dRx = (rx - lastRx).coerceAtLeast(0L)
                val dTx = (tx - lastTx).coerceAtLeast(0L)
                val periodMs = if (lastAt == 0L) 0L else sampledAt - lastAt
                val rxBps = if (periodMs > 0L) dRx * 8_000L / periodMs else 0L
                val txBps = if (periodMs > 0L) dTx * 8_000L / periodMs else 0L
                lastRx = rx; lastTx = tx; lastAt = sampledAt
                if (dRx > 0L || dTx > 0L) addUsage(dRx, dTx)
                if (_state.value is State.On) _state.value = State.On(connectedAt, handshake, rx, tx, rxBps, txBps)
                // Surfshark forgets a key after some days; the tunnel then stays up but the server goes
                // silent. One reconnect re-registers the key and moves to a live server.
                val now = System.currentTimeMillis()
                val silent = now - connectedAt > HANDSHAKE_GRACE_MS && now - handshake > HANDSHAKE_STALE_MS
                if (silent && surfsharkActive) {
                    if (silentSince == 0L) silentSince = now
                    if (now - silentSince > HANDSHAKE_STALE_MS) { silentSince = 0L; lock.withLock { disconnectNow() }; connect(); return@launch }
                } else silentSince = 0L
            }
        }
    }

    private fun parse(text: String): Config = Config.parse(ByteArrayInputStream(text.toByteArray()))

    // ---- data usage ------------------------------------------------------------------------------

    private fun currentMonth(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d".format(java.util.Locale.ROOT, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
    }

    private fun loadUsage(): State.Usage {
        val month = currentMonth()
        if (prefs.getString(KEY_USAGE_MONTH, null) != month) return State.Usage(month, 0L, 0L)
        return State.Usage(month, prefs.getLong(KEY_USAGE_RX, 0L), prefs.getLong(KEY_USAGE_TX, 0L))
    }

    /** Adds one sample's bytes to this month's total; a new month starts from zero. */
    private fun addUsage(rxBytes: Long, txBytes: Long) {
        val month = currentMonth()
        val base = _usage.value.takeIf { it.month == month } ?: State.Usage(month, 0L, 0L)
        val next = State.Usage(month, base.rxBytes + rxBytes, base.txBytes + txBytes)
        _usage.value = next
        prefs.edit().putString(KEY_USAGE_MONTH, month).putLong(KEY_USAGE_RX, next.rxBytes).putLong(KEY_USAGE_TX, next.txBytes).apply()
    }

    // ---- encrypted store -------------------------------------------------------------------------

    private val store = object {
        private val file get() = File(context.filesDir, STORE_DIR).apply { mkdirs() }.resolve(STORE_FILE)

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }

        fun load(): Pair<List<Profile>, SurfsharkAccount?> {
            val f = file
            if (!f.exists()) return emptyList<Profile>() to null
            val bytes = f.readBytes()
            if (bytes.size < GCM_IV_BYTES + 1) return emptyList<Profile>() to null
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
            val text = String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
            // The first release wrote a bare array of profiles.
            val root = if (text.trimStart().startsWith("[")) JSONObject().put("profiles", JSONArray(text)) else JSONObject(text)
            val list = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optString("endpoint"), o.getString("config"), o.optLong("createdAt"))
            }
            val account = root.optJSONObject("surfshark")?.let { o ->
                SurfsharkAccount(
                    o.getString("email"), o.getString("token"), o.getString("renewToken"), o.getString("privateKey"), o.getString("publicKey"),
                    o.optString("keyExpiresAt").takeIf { it.isNotBlank() }, o.optString("countryCode").ifBlank { DEFAULT_COUNTRY }, o.optString("lastServer"),
                )
            }
            return profiles to account
        }

        fun save(profiles: List<Profile>, account: SurfsharkAccount?) {
            val list = JSONArray()
            profiles.forEach { p ->
                list.put(JSONObject().put("id", p.id).put("name", p.name).put("endpoint", p.endpoint).put("config", p.config).put("createdAt", p.createdAt))
            }
            val root = JSONObject().put("profiles", list)
            account?.let { a ->
                root.put(
                    "surfshark",
                    JSONObject().put("email", a.email).put("token", a.token).put("renewToken", a.renewToken)
                        .put("privateKey", a.privateKey).put("publicKey", a.publicKey).put("keyExpiresAt", a.keyExpiresAt ?: "")
                        .put("countryCode", a.countryCode).put("lastServer", a.lastServer),
                )
            }
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
            val f = file
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(f)) { f.writeBytes(out); tmp.delete() }
        }
    }

    companion object {
        /** The active-choice id that stands for the Surfshark account rather than a saved file. */
        const val SURFSHARK_ID = "surfshark"
        private const val DEFAULT_COUNTRY = "PL"
        private const val TUNNEL_NAME = "poterx"
        private const val PREFS = "poterx_vpn"
        private const val KEY_ACTIVE = "active"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_USAGE_MONTH = "usage_month"
        private const val KEY_USAGE_RX = "usage_rx"
        private const val KEY_USAGE_TX = "usage_tx"
        private const val STORE_DIR = "poterx_vpn"
        private const val STORE_FILE = "profiles.bin"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "poterx_vpn_v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val STATS_PERIOD_MS = 3_000L
        private const val HANDSHAKE_GRACE_MS = 60_000L
        private const val HANDSHAKE_STALE_MS = 180_000L

        /**
         * The configuration with this app as the only routed application, unless the file already
         * says which applications it covers. WireGuard reads `IncludedApplications` from the
         * `[Interface]` section; the key is matched case-insensitively.
         */
        fun withAppRouting(conf: String, packageName: String): String {
            val lines = conf.replace("\r\n", "\n").split("\n")
            val hasRouting = lines.any { line ->
                val key = line.substringBefore('=').trim().lowercase()
                key == "includedapplications" || key == "excludedapplications"
            }
            if (hasRouting) return conf
            val out = ArrayList<String>(lines.size + 1)
            var inserted = false
            for (line in lines) {
                out += line
                if (!inserted && line.trim().equals("[Interface]", ignoreCase = true)) {
                    out += "IncludedApplications = $packageName"
                    inserted = true
                }
            }
            if (!inserted) return conf // not a WireGuard file at all; parsing reports the real problem
            return out.joinToString("\n")
        }
    }
}
