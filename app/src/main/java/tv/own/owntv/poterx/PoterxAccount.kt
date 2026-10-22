package tv.own.owntv.poterx

import tv.own.owntv.BuildConfig
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Poterx: what a locked build knows about its one account beyond the credentials.
 */
object PoterxAccount {

    /**
     * The identity every request to the panel carries (playlist, guide, streams), so the provider's
     * logs tell this app from the generic players and can refuse the latter if they wish.
     */
    val userAgent: String get() = "$USER_AGENT_NAME/${BuildConfig.VERSION_NAME}"

    /**
     * Existing installs signed in before the identity existed, or on an older version: every Xtream
     * playlist that does not carry the current identity is updated to it. Nothing else changes.
     */
    suspend fun ensureUserAgent(sourceDao: SourceDao, sourceRepository: SourceRepository) {
        if (!LockedSource.enabled) return
        val wanted = userAgent
        sourceDao.getAllOnce()
            .filter { it.type == SourceType.XTREAM && it.userAgent != wanted }
            .forEach { sourceRepository.updateSource(it.copy(userAgent = wanted)) }
    }

    /**
     * Sign out: the playlists (with everything remembered for them), the guide feeds and the
     * profiles go, and the active profile is cleared, which sends the app back to the sign-in page.
     * The built-in server stays, as does every device-level setting (language, playback).
     */
    suspend fun signOut(
        sourceDao: SourceDao,
        sourceRepository: SourceRepository,
        profileDao: ProfileDao,
        epgStore: EpgSourceStore,
        settings: SettingsRepository,
    ) {
        sourceDao.getAllOnce().forEach { runCatching { sourceRepository.deleteSource(it) } }
        epgStore.getAll().forEach { runCatching { epgStore.remove(it.id) } }
        runCatching { profileDao.deleteAll() }
        settings.setActiveProfile(-1L)
    }

    private const val USER_AGENT_NAME = "PoterxTV"
}
