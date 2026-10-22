package tv.own.owntv.poterx

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import tv.own.owntv.BuildConfig

/**
 * Poterx: the one Xtream account this build is locked to.
 *
 * The account is handed to Gradle at build time (see `app/build.gradle.kts`, "Poterx locked source")
 * and lands in [BuildConfig] AES-GCM encrypted. That is obfuscation, not secrecy: anyone with the APK
 * and enough patience can recover it, and the stream URLs travel the network in the clear anyway.
 * What it does buy is that the line never sits in plain text in the APK, never shows on screen, and
 * cannot be edited, exported or synced away from inside the app — every entry point that could do
 * that is switched off while [enabled] is true.
 */
object LockedSource {

    data class Account(
        val server: String,
        /** Blank on a server-only build; the user's own then come from the sign-in screen. */
        val username: String,
        val password: String,
        val preferHls: Boolean,
        /** Digits asked for before signing out; blank means signing out needs no PIN. */
        val servicePin: String = "",
        /** The provider's contact line (phone, e-mail, …), blank when none was built in. */
        val contact: String = "",
        /** The provider's TMDB API key (v3) for posters and descriptions; blank when none was built in. */
        val tmdbKey: String = "",
    )

    /** True for a build that carries an account. A build without one behaves like stock OwnTV. */
    val enabled: Boolean get() = BuildConfig.POTERX_LOCKED

    /** The account, decrypted once; null when the build carries none or the blob is damaged. */
    val account: Account? by lazy { if (enabled) decrypt() else null }

    /** True when only the server is built in: the user signs in with a username and password. */
    val requiresLogin: Boolean get() = enabled && account?.username.isNullOrBlank()

    private fun decrypt(): Account? = runCatching {
        val key = Base64.decode(BuildConfig.POTERX_KEY, Base64.NO_WRAP)
        val blob = Base64.decode(BuildConfig.POTERX_BLOB, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
        val lines = String(cipher.doFinal(body), Charsets.UTF_8).split('\n')
        if (lines.size < 3 || lines[0].isBlank()) return null
        Account(
            server = lines[0].trim(),
            username = lines[1].trim(),
            password = lines[2],
            preferHls = lines.getOrNull(3)?.trim() == "1",
            servicePin = lines.getOrNull(4)?.trim().orEmpty(),
            contact = lines.getOrNull(5)?.trim().orEmpty(),
            tmdbKey = lines.getOrNull(6)?.trim().orEmpty(),
        )
    }.getOrNull()

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val ALGORITHM = "AES"
}
