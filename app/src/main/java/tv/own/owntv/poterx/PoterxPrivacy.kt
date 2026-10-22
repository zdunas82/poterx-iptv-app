package tv.own.owntv.poterx

import java.io.File
import java.net.URI

/**
 * Poterx: the built-in server's address never reaches the screen. Core already masks credentials
 * in URLs it shows (`HttpClient.redactUrl`), but the host alone identifies the provider's panel, so
 * every text that may carry a URL or a host name (stream info, error texts, the playback error log
 * and its export, guide feed rows) passes through [hide] before it is drawn. Stock builds pass
 * everything through unchanged.
 */
object PoterxPrivacy {

    private const val MASK = "•••"

    /** `scheme://host[:port]` and bare `host[:port]` of the built-in server, case-insensitive. */
    private val patterns: List<Regex> by lazy {
        val server = LockedSource.account?.server?.takeIf { LockedSource.enabled }.orEmpty()
        val host = runCatching { URI(server).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@lazy emptyList()
        val h = Regex.escape(host)
        listOf(
            Regex("(?i)[a-z][a-z0-9+.-]*://$h(?::\\d+)?"),
            Regex("(?i)(?<![a-z0-9.-])$h(?::\\d+)?"),
        )
    }

    /** [text] with every mention of the built-in server replaced by a mask. */
    fun hide(text: String): String {
        if (patterns.isEmpty()) return text
        var out = text
        for (p in patterns) out = out.replace(p, MASK)
        return out
    }

    fun hide(text: String?): String? = text?.let { hide(it) }

    /** True when [text] mentions the built-in server at all. */
    fun mentionsServer(text: String): Boolean = patterns.any { it.containsMatchIn(text) }

    /** Rewrites a text file in place with [hide] applied (the exported error log). */
    fun scrubFile(path: String?) {
        if (path == null || patterns.isEmpty()) return
        runCatching {
            val f = File(path)
            if (f.isFile) f.writeText(hide(f.readText()))
        }
    }
}
