package tv.own.owntv.poterx

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Poterx: the defaults a locked build starts with, applied once per install and then left to the
 * user (every one of them stays changeable in Settings). A flag per default records that it was
 * applied, so an update never overrides a choice the user made since.
 */
object PoterxDefaults {

    /**
     * The app speaks Polish on first start whatever the television's own language. Runs in
     * `attachBaseContext`, before the first locale read, so the very first screen is already Polish.
     * A language the user already chose (stored tag) is never touched.
     */
    fun applyLocale(base: Context) {
        if (!LockedSource.enabled) return
        val prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOCALE_DONE, false)) return
        val store = LocaleStore.from(base)
        if (store.readBlocking().isBlank()) {
            // A synchronous SharedPreferences commit; the store itself hops to IO for the write.
            runCatching { runBlocking { store.set(DEFAULT_LANGUAGE) } }
        }
        prefs.edit().putBoolean(KEY_LOCALE_DONE, true).apply()
    }

    /**
     * Playback defaults for a television set-top box: Auto frame rate off and its one-time
     * "turn it on?" suggestion dismissed. Polish channels are 50 fps; on a 60 Hz-only output the
     * switch costs a black screen on every zap and fixes nothing, which is what the suggestion led
     * the first tester into. Anyone who wants it finds it under Settings › Picture.
     */
    fun applySettings(context: Context, scope: CoroutineScope, settings: SettingsRepository) {
        if (!LockedSource.enabled) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PLAYBACK_DONE, false)) return
        scope.launch {
            runCatching {
                settings.setAutoFrameRate(false)
                settings.setAutoFrameRatePrompted()
            }
            prefs.edit().putBoolean(KEY_PLAYBACK_DONE, true).apply()
        }
    }

    private const val PREFS = "poterx_defaults"
    private const val KEY_LOCALE_DONE = "locale_v1"
    private const val KEY_PLAYBACK_DONE = "playback_v1"
    private const val DEFAULT_LANGUAGE = "pl"
}
