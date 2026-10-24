package tv.own.owntv.poterx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.core.repository.SourceTester
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Poterx: why the panel cannot be reached, told apart so the customer gets the right answer.
 *
 * A failed sign-in has four different causes that used to share one message: the credentials are
 * wrong, the line has run out, the television has no internet, or the customer's internet provider
 * blocks the panel. The last one is the one the built-in VPN exists for, so it is diagnosed in
 * steps, each one answering a question the previous one could not:
 *
 * 1. the panel's own account call ([SourceTester]): the panel answered, and said yes, no, or expired;
 * 2. the system's connectivity and the provider's update server over HTTPS: is there any internet;
 * 3. the panel's host name: does the provider's resolver answer, and with a real address
 *    (an ISP blocking by DNS returns nothing, or a page of its own on a private address);
 * 4. a TCP connection to the panel's port: an ISP blocking by address drops or resets it;
 * 5. an HTTP request to `player_api.php` without credentials: a panel answers JSON whatever the
 *    credentials; an ISP's block page answers HTML or 403/451.
 *
 * Nothing here writes to the database or stores anything; the diagnosis is a handful of requests
 * that take a few seconds at most.
 */
class PoterxReachability(
    private val http: OkHttpClient,
    private val connectivity: ConnectivityObserver,
    private val tester: SourceTester,
) {

    sealed interface Verdict {
        /** The panel answered as a panel; for a sign-in, it also accepted the credentials. */
        data object Ok : Verdict
        data object AuthFailed : Verdict
        data class Expired(val expiryMs: Long?) : Verdict
        /** No internet at all, or nothing beyond the local network. */
        data object Offline : Verdict
        /** Internet works, the panel is cut off between the television and the server. */
        data class Blocked(val how: Block) : Verdict
        /** Internet works and the panel's host is reachable, yet the panel did not answer properly. */
        data object PanelDown : Verdict
    }

    enum class Block { DNS, CONNECTION, PAGE }

    // The pure part of the diagnosis, kept separate so the decision table is testable.
    enum class Dns { OK, FAILED, BOGUS }
    enum class Tcp { OK, FAILED }
    sealed interface Http {
        data class Answered(val code: Int, val json: Boolean) : Http
        /** No HTTP answer; [reset] when the connection was closed on us rather than timing out. */
        data class Failed(val reset: Boolean) : Http
    }

    private val probeClient: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** A sign-in's worth of checking: the panel's answer first, the network only when it gave none. */
    suspend fun signIn(server: String, username: String, password: String, userAgent: String): Verdict {
        val probe = SourceEntity(
            name = "probe", type = SourceType.XTREAM, url = server.trim(),
            username = username.trim(), password = password, userAgent = userAgent.takeIf { it.isNotBlank() },
        )
        val result = runCatching { tester.test(probe) }.getOrElse { SourceTestResult.Unreachable(it.message) }
        return when (result) {
            is SourceTestResult.Ok -> Verdict.Ok
            SourceTestResult.AuthFailed -> Verdict.AuthFailed
            is SourceTestResult.Expired -> Verdict.Expired(result.expiryMs)
            is SourceTestResult.Unreachable -> diagnose(server).let { if (it is Verdict.Ok) Verdict.PanelDown else it }
        }
    }

    /**
     * Why the panel at [server] does not answer: [Verdict.Offline], [Verdict.Blocked] or
     * [Verdict.PanelDown]; [Verdict.Ok] when its host answers like a panel after all.
     */
    suspend fun diagnose(server: String): Verdict = withContext(Dispatchers.IO) {
        if (!connectivity.isOnlineNow()) return@withContext Verdict.Offline
        val neutral = LockedSource.account?.updateUrl?.takeIf { it.startsWith("https://") }
        if (neutral != null && !answers(neutral.trimEnd('/') + "/update.json")) return@withContext Verdict.Offline
        val url = server.trim().toHttpUrlOrNull() ?: return@withContext Verdict.PanelDown
        val dns = resolve(url.host)
        val tcp = if (dns.first == Dns.OK) connect(dns.second, url.port) else Tcp.FAILED
        val httpResult = if (tcp == Tcp.OK) request(url.newBuilder().encodedPath("/player_api.php").build().toString()) else Http.Failed(reset = false)
        val verdict = classify(dns.first, tcp, httpResult)
        Log.i(TAG, "diagnose: dns=${dns.first} tcp=$tcp http=$httpResult -> $verdict")
        verdict
    }

    private fun answers(url: String): Boolean = try {
        probeClient.newCall(Request.Builder().url(url).header("User-Agent", PoterxAccount.userAgent).head().build()).execute().use { true }
    } catch (e: IOException) {
        false
    }

    private fun resolve(host: String): Pair<Dns, List<InetAddress>> = try {
        val addresses = InetAddress.getAllByName(host).toList()
        val real = addresses.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }
        if (real.isEmpty()) Dns.BOGUS to emptyList() else Dns.OK to real
    } catch (e: UnknownHostException) {
        Dns.FAILED to emptyList()
    }

    private fun connect(addresses: List<InetAddress>, port: Int): Tcp {
        for (address in addresses) {
            try {
                Socket().use { it.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MS.toInt()) }
                return Tcp.OK
            } catch (e: IOException) {
                // Try the next address.
            }
        }
        return Tcp.FAILED
    }

    private fun request(url: String): Http = try {
        probeClient.newCall(Request.Builder().url(url).header("User-Agent", PoterxAccount.userAgent).build()).execute().use { resp ->
            val head = resp.peekBody(256).string()
            Http.Answered(resp.code, head.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("{"))
        }
    } catch (e: SocketTimeoutException) {
        Http.Failed(reset = false)
    } catch (e: IOException) {
        Http.Failed(reset = true)
    }

    companion object {
        private const val TAG = "PoterxReach"
        private const val PROBE_TIMEOUT_MS = 6_000L

        /** The decision table: what the three probes together say about the panel. */
        fun classify(dns: Dns, tcp: Tcp, http: Http): Verdict = when {
            dns != Dns.OK -> Verdict.Blocked(Block.DNS)
            tcp == Tcp.FAILED -> Verdict.Blocked(Block.CONNECTION)
            http is Http.Failed -> if (http.reset) Verdict.Blocked(Block.CONNECTION) else Verdict.PanelDown
            http is Http.Answered && http.json -> Verdict.Ok
            http is Http.Answered && (http.code == 403 || http.code == 451) -> Verdict.Blocked(Block.PAGE)
            http is Http.Answered && http.code >= 500 -> Verdict.PanelDown
            // 2xx/3xx/4xx with something other than JSON: a page put in the panel's place.
            else -> Verdict.Blocked(Block.PAGE)
        }
    }
}
