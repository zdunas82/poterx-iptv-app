package tv.own.owntv.poterx

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.AutoRebuild
import tv.own.owntv.features.settings.runSemiAutoEpgSync

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

    /**
     * The optional modules that talk to third-party services start switched off: the weather chip
     * (once per install) and, per profile, the Sports Hub's automatic board rebuild (set to manual)
     * with its Home row and rail icon hidden. Nothing is fetched until the user turns a module on in
     * Settings (Weather, Sports, Navigation menu, Home). OpenSubtitles needs no switch: it only
     * contacts its service when the user searches for subtitles or signs in.
     *
     * Runs on every start, so a profile created later (sign-in happens after the first start) gets
     * the same defaults the first time it is seen; each profile is marked once and then left alone.
     */
    fun applyModules(context: Context, scope: CoroutineScope, settings: SettingsRepository, profileDao: ProfileDao) {
        if (!LockedSource.enabled) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch {
            runCatching {
                if (!prefs.getBoolean(KEY_MODULES_DONE, false)) {
                    settings.setWeatherEnabled(false)
                    settings.setNavMenuHidden(settings.navMenuHidden.first() + MainSection.SPORTS.name)
                    prefs.edit().putBoolean(KEY_MODULES_DONE, true).apply()
                }
                // The provider's TMDB key, applied as the user's own key (TMDB is then called directly).
                // Keyed on the key itself, so a build carrying a different one re-applies; a key the
                // user typed by hand in Settings stays, as it already differs from "nothing".
                val tmdb = LockedSource.account?.tmdbKey.orEmpty()
                if (tmdb.isNotBlank() && prefs.getString(KEY_TMDB_APPLIED, "") != tmdb.hashCode().toString()) {
                    if (settings.currentTmdbApiKey().isBlank()) settings.setTmdbApiKey(tmdb)
                    prefs.edit().putString(KEY_TMDB_APPLIED, tmdb.hashCode().toString()).apply()
                }
                // Descriptions, genres and trailers in the app's language (TMDB's default is English).
                // Once per install; a language the user picked since is left alone.
                if (!prefs.getBoolean(KEY_METADATA_LANG_DONE, false)) {
                    if (settings.metadataLanguage.first().isBlank()) settings.setMetadataLanguage(DEFAULT_LANGUAGE)
                    prefs.edit().putBoolean(KEY_METADATA_LANG_DONE, true).apply()
                }
                profileDao.getAllOnce().forEach { profile ->
                    val key = KEY_MODULES_PROFILE_PREFIX + profile.id
                    if (prefs.getBoolean(key, false)) return@forEach
                    settings.updateSportsConfig(profile.id) { it.copy(autoRebuild = AutoRebuild.MANUAL) }
                    settings.updateHomeConfig(profile.id) { it.copy(hidden = it.hidden + HomeRow.LIVE_SPORTS) }
                    prefs.edit().putBoolean(key, true).apply()
                }
            }
        }
    }

    /**
     * The Home page's Trending row is built once per playlist after a sync and then only every few
     * days. Its first download can fail on a dropped connection and core does not retry it until
     * the next sync, which left the row missing for hours. A playlist whose row was never built is
     * asked again shortly after start; a built row is left to core's own schedule.
     */
    fun ensureTrending(scope: CoroutineScope, sourceDao: SourceDao, database: tv.own.owntv.core.database.OwnTVDatabase, scheduler: tv.own.owntv.core.sync.work.CatalogSyncScheduler) {
        if (!LockedSource.enabled) return
        scope.launch {
            kotlinx.coroutines.delay(TRENDING_START_DELAY_MS)
            runCatching {
                val dao = database.trendingDao()
                sourceDao.getAllOnce().filter { it.type == SourceType.XTREAM }.forEach { source ->
                    val state = dao.getState(source.id)
                    if (state == null || state.status == tv.own.owntv.core.database.entity.TrendingSnapshotStatus.NEVER_BUILT) {
                        scheduler.enqueueTrendingRefresh(source.id)
                    }
                }
            }
        }
    }

    /**
     * The guide is part of the product, not an option: a locked build whose playlist has no EPG
     * source yet registers the panel's own xmltv.php feed and downloads it, and every guide feed
     * without a refresh choice gets one (every 12 h). Runs on each start and acts only when needed,
     * so an install upgraded from a version that never registered the guide heals itself, and a
     * first start before sign-in (no playlist yet) does nothing.
     */
    fun ensureGuide(
        scope: CoroutineScope,
        sourceDao: SourceDao,
        epgRepository: EpgRepository,
        store: EpgSourceStore,
        settings: SettingsRepository,
    ) {
        if (!LockedSource.enabled) return
        scope.launch {
            runCatching {
                if (store.getAll().isEmpty()) {
                    val playlists = sourceDao.getAllOnce().filter { it.type == SourceType.XTREAM }
                    if (playlists.isEmpty()) return@runCatching
                    playlists.forEach { runSemiAutoEpgSync(it, epgRepository, store) {} }
                }
                val chosen = settings.epgAutoRefresh.first()
                store.getAll().forEach { feed ->
                    if (chosen[feed.id] == null) settings.setEpgAutoRefresh(feed.id, EpgRefresh(EpgAutoRefresh.HOURS_12))
                }
            }
        }
    }

    private const val PREFS = "poterx_defaults"
    private const val KEY_LOCALE_DONE = "locale_v1"
    private const val KEY_PLAYBACK_DONE = "playback_v1"
    private const val KEY_MODULES_DONE = "modules_v1"
    private const val KEY_MODULES_PROFILE_PREFIX = "modules_v1_profile_"
    private const val KEY_TMDB_APPLIED = "tmdb_key_v1"
    private const val KEY_METADATA_LANG_DONE = "metadata_lang_v1"
    /** After the first screen and the guide check, never competing with them. */
    private const val TRENDING_START_DELAY_MS = 25_000L
    private const val DEFAULT_LANGUAGE = "pl"
}
