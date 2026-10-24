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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Poterx: an in-app WireGuard tunnel (Settings › Network › VPN). The customer imports a `.conf` from
 * any WireGuard provider (Surfshark's manual set-up, a private server); the tunnel then carries this
 * application's traffic only, the rest of the television stays on the ordinary connection. Built on
 * the official `com.wireguard.android:tunnel` library and Android's VpnService.
 *
 * Profiles hold a private key, so they are kept encrypted with a key in the Android Keystore; the
 * active profile and the connect-on-start switch are ordinary preferences.
 */
class PoterxVpn(private val context: Context) {

    /** A saved `.conf`: [config] is the file's text as imported, routing is added at connect time. */
    data class Profile(val id: String, val name: String, val endpoint: String, val config: String, val createdAt: Long)

    sealed interface State {
        data object Off : State
        data object Connecting : State
        /** Up, with the latest peer handshake (0 = none yet) and the bytes moved since connect. */
        data class On(val sinceMs: Long, val handshakeMs: Long, val rxBytes: Long, val txBytes: Long) : State
        data class Failed(val reason: Failure) : State
    }

    enum class Failure { NOT_AUTHORIZED, NO_PROFILE, BAD_CONFIG, DNS, START, UNKNOWN }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val backend: GoBackend by lazy { GoBackend(context) }
    private var statsJob: Job? = null
    private var connectedAt = 0L

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, false))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

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
        scope.launch { _profiles.value = runCatching { store.load() }.getOrDefault(emptyList()) }
    }

    /** Null when the system already allows this app to open a VPN; else the consent screen to show once. */
    fun prepareIntent(): Intent? = android.net.VpnService.prepare(context)

    val activeProfile: Profile? get() = _profiles.value.firstOrNull { it.id == _activeId.value }

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
                store.save(next)
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
                runCatching { store.save(next) }
                _profiles.value = next
                if (_activeId.value == id) {
                    if (_state.value !is State.Off) disconnectNow()
                    val fallback = next.firstOrNull()?.id
                    prefs.edit().putString(KEY_ACTIVE, fallback).apply()
                    _activeId.value = fallback
                }
            }
        }
    }

    /** Brings the active profile up. The consent screen must have been accepted ([prepareIntent] null). */
    fun connect() {
        val profile = activeProfile
        if (profile == null) { _state.value = State.Failed(Failure.NO_PROFILE); return }
        if (prepareIntent() != null) { _state.value = State.Failed(Failure.NOT_AUTHORIZED); return }
        _state.value = State.Connecting
        scope.launch {
            lock.withLock {
                try {
                    val config = parse(withAppRouting(profile.config, context.packageName))
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

    /** At app start: the active profile comes up by itself when the switch is on and consent was given. */
    fun autoConnectIfReady() {
        if (!_autoConnect.value) return
        scope.launch {
            // The profiles load asynchronously; give the store a moment before deciding there is none.
            repeat(20) { if (_profiles.value.isNotEmpty()) return@repeat; delay(100) }
            if (activeProfile != null && prepareIntent() == null && _state.value is State.Off) connect()
        }
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_PERIOD_MS)
                val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: continue
                val handshake = stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
                if (_state.value is State.On) _state.value = State.On(connectedAt, handshake, stats.totalRx(), stats.totalTx())
            }
        }
    }

    private fun parse(text: String): Config = Config.parse(ByteArrayInputStream(text.toByteArray()))

    // ---- encrypted profile store ----------------------------------------------------------------

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

        fun load(): List<Profile> {
            val f = file
            if (!f.exists()) return emptyList()
            val bytes = f.readBytes()
            if (bytes.size < GCM_IV_BYTES + 1) return emptyList()
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES))
            val json = JSONArray(String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8))
            return (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optString("endpoint"), o.getString("config"), o.optLong("createdAt"))
            }
        }

        fun save(profiles: List<Profile>) {
            val json = JSONArray()
            profiles.forEach { p ->
                json.put(JSONObject().put("id", p.id).put("name", p.name).put("endpoint", p.endpoint).put("config", p.config).put("createdAt", p.createdAt))
            }
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val f = file
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(f)) { f.writeBytes(out); tmp.delete() }
        }
    }

    companion object {
        private const val TUNNEL_NAME = "poterx"
        private const val PREFS = "poterx_vpn"
        private const val KEY_ACTIVE = "active"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val STORE_DIR = "poterx_vpn"
        private const val STORE_FILE = "profiles.bin"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "poterx_vpn_v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val STATS_PERIOD_MS = 3_000L

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
