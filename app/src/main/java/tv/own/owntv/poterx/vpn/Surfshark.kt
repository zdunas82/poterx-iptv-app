package tv.own.owntv.poterx.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Poterx: Surfshark's account API as the official clients use it (undocumented, recovered from the
 * Linux client 3.14; every community WireGuard generator speaks the same endpoints). Enough for a
 * television: sign in (with the one-time code when the account asks for one), register this
 * device's WireGuard public key and keep it alive, list the server clusters, and write the client
 * configuration the Surfshark app itself would use. No Surfshark server is contacted for anything
 * else, and nothing here is specific to this provider's panel.
 */
class SurfsharkClient(private val http: OkHttpClient) {

    data class Tokens(val token: String, val renewToken: String)

    sealed interface Login {
        data class Ok(val tokens: Tokens) : Login
        /** The account has two-factor authentication: [interim] authorises the code step. */
        data class NeedsCode(val interim: Tokens) : Login
        data class Rejected(val httpCode: Int) : Login
    }

    sealed interface KeyStatus {
        /** Registered or renewed; [expiresAt] is Surfshark's ISO time when it gave one. */
        data class Valid(val expiresAt: String?) : KeyStatus
        /** The server no longer knows the key: make a new pair and register it. */
        data object Expired : KeyStatus
        /** The session is no longer valid: sign in again. */
        data object Unauthorized : KeyStatus
        data class Failed(val httpCode: Int) : KeyStatus
    }

    data class Cluster(
        val connectionName: String,
        val pubKey: String,
        val country: String,
        val countryCode: String,
        val location: String,
        val load: Int,
    )

    private class Reply(val code: Int, val body: String) {
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    private suspend fun call(method: String, path: String, bearer: String?, json: JSONObject?): Reply = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(BASE + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-device-type", "desktop")
            .header("x-device-platform", "linux")
            .header("x-device-version", CLIENT_VERSION)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val body = json?.toString()?.toRequestBody(JSON)
        builder.method(method, body ?: if (method == "POST") "".toRequestBody(JSON) else null)
        http.newCall(builder.build()).execute().use { resp -> Reply(resp.code, resp.body.string()) }
    }

    private fun JSONObject.tokens(): Tokens? {
        val t = optString("token").takeIf { it.isNotBlank() } ?: return null
        val r = optString("renewToken").takeIf { it.isNotBlank() } ?: return null
        return Tokens(t, r)
    }

    suspend fun login(email: String, password: String): Login {
        val reply = call("POST", "/v1/auth/login", null, JSONObject().put("username", email.trim()).put("password", password))
        val tokens = reply.json()?.tokens()
        return when {
            reply.code == 200 && tokens != null -> Login.Ok(tokens)
            reply.code == 423 && tokens != null -> Login.NeedsCode(tokens)
            else -> Login.Rejected(reply.code)
        }
    }

    /** The second step of a two-factor sign-in; a 200 without new tokens keeps the interim pair. */
    suspend fun activate(interim: Tokens, code: String): Tokens? {
        val reply = call("POST", "/v1/auth/activate", interim.token, JSONObject().put("otp", code.trim()))
        if (reply.code != 200 && reply.code != 204) return null
        return reply.json()?.tokens() ?: interim
    }

    suspend fun renew(tokens: Tokens): Tokens? {
        val reply = call("POST", "/v1/auth/renew", tokens.renewToken, null)
        return if (reply.code == 200) reply.json()?.tokens() else null
    }

    /** Registers [pubKey], or renews it when Surfshark already has it. */
    suspend fun registerKey(tokens: Tokens, pubKey: String): KeyStatus {
        val body = JSONObject().put("pubKey", pubKey)
        val reply = call("POST", "/v1/account/users/public-keys", tokens.token, body)
        return when (reply.code) {
            200, 201 -> KeyStatus.Valid(reply.json()?.optString("expiresAt")?.takeIf { it.isNotBlank() })
            409 -> {
                val check = call("POST", "/v1/account/users/public-keys/validate", tokens.token, body)
                when (check.code) {
                    200 -> KeyStatus.Valid(check.json()?.optString("expiresAt")?.takeIf { it.isNotBlank() })
                    400, 404 -> KeyStatus.Expired
                    401 -> KeyStatus.Unauthorized
                    else -> KeyStatus.Failed(check.code)
                }
            }
            401 -> KeyStatus.Unauthorized
            else -> KeyStatus.Failed(reply.code)
        }
    }

    /** Every generic WireGuard cluster; needs no sign-in. */
    suspend fun clusters(): List<Cluster> {
        val reply = call("GET", "/v5/server/clusters/all", null, null)
        if (reply.code != 200) return emptyList()
        val array = runCatching { JSONArray(reply.body) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("type", "generic")
            val pubKey = o.optString("pubKey").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            if (type != "generic") return@mapNotNull null
            Cluster(
                connectionName = o.optString("connectionName").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                pubKey = pubKey,
                country = o.optString("country"),
                countryCode = o.optString("countryCode").uppercase(),
                location = o.optString("location"),
                load = o.optInt("load", 100),
            )
        }
    }

    companion object {
        private const val BASE = "https://api.surfshark.com"
        private const val CLIENT_VERSION = "3.14.0"
        private const val USER_AGENT = "SurfsharkLinux/3.14.0 device/desktop"
        private val JSON = "application/json".toMediaType()
        private const val ADDRESS = "10.14.0.2/16"
        private const val DNS = "162.252.172.57, 149.154.159.92"
        private const val PORT = 51820

        /** The wg-quick text for [cluster], as Surfshark's own clients configure it. */
        fun buildConfig(privateKey: String, cluster: Cluster): String = buildString {
            append("[Interface]\n")
            append("PrivateKey = ").append(privateKey).append('\n')
            append("Address = ").append(ADDRESS).append('\n')
            append("DNS = ").append(DNS).append('\n')
            append("MTU = 1280\n")
            append('\n')
            append("[Peer]\n")
            append("PublicKey = ").append(cluster.pubKey).append('\n')
            append("AllowedIPs = 0.0.0.0/0, ::/0\n")
            append("Endpoint = ").append(cluster.connectionName).append(':').append(PORT).append('\n')
            append("PersistentKeepalive = 25\n")
        }

        /** The least loaded cluster of [countryCode], or of any country when it has none. */
        fun pick(clusters: List<Cluster>, countryCode: String): Cluster? {
            val inCountry = clusters.filter { it.countryCode.equals(countryCode, ignoreCase = true) }
            return (inCountry.ifEmpty { clusters }).minByOrNull { it.load }
        }
    }
}
