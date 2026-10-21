package tv.own.owntv.poterx

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.sync.work.CatalogSyncState
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.features.setup.playlistAutoRefreshLabel
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Poterx: Settings › Playlists on a locked build. The playlist is built in, so there is nothing to
 * add, edit or delete and the server is never printed. What remains is what a user legitimately
 * needs: refresh the channel list now, and choose how often it refreshes by itself.
 */
@Composable
fun LockedSourcesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val autoRefresh by vm.playlistAutoRefresh.collectAsStateWithLifecycle()
    val first = rememberLockedFirstFocus()
    val modes = remember { listOf(PlaylistAutoRefresh.OFF, PlaylistAutoRefresh.STARTUP, PlaylistAutoRefresh.HOURS_6, PlaylistAutoRefresh.HOURS_12, PlaylistAutoRefresh.MANUAL) }

    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_sources)),
        title = stringResource(R.string.poterx_sources_title),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = first,
    ) {
        if (sources.isEmpty()) StageSettingsNote(stringResource(R.string.settings_sources_empty), null)
        sources.forEachIndexed { index, source ->
            val syncState by remember(source.id) { vm.syncState(source.id) }.collectAsStateWithLifecycle(CatalogSyncState.Idle)
            val syncing = syncState is CatalogSyncState.Syncing
            val refresh = autoRefresh[source.id] ?: PlaylistRefresh.OFF
            val lastSync = source.lastSyncAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
            // "Refresh now": the same enqueue the stock sources page uses; OK again cancels a running one.
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = source.name,
                desc = when {
                    syncing -> stringResource(R.string.sync_progress_syncing)
                    lastSync != null -> stringResource(R.string.poterx_last_refresh, lastSync)
                    else -> stringResource(R.string.poterx_last_refresh, stringResource(R.string.common_never))
                },
                value = SettingValue.Choice(stringResource(if (syncing) R.string.settings_sources_cancel else R.string.poterx_refresh_now)),
                onClick = { if (syncing) vm.cancelResync(source) else vm.resync(source) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
            // Auto refresh: ◀ ▶ or OK step through Off · At startup · 6 h · 12 h · every N days.
            val selected = modes.indexOf(refresh.mode).coerceAtLeast(0)
            val pick: (Int) -> Unit = { step ->
                val next = modes[(selected + step + modes.size) % modes.size]
                vm.setPlaylistAutoRefresh(source.id, PlaylistRefresh(next, refresh.manualDays))
            }
            StageSettingRow(
                icon = OwnTVIcon.EPG,
                title = stringResource(R.string.poterx_refresh_auto),
                desc = stringResource(R.string.poterx_refresh_auto_description),
                value = SettingValue.Choice(playlistAutoRefreshLabel(refresh)),
                onClick = { pick(1) },
                onStep = pick,
            )
        }
        StageSettingsNote(stringResource(R.string.poterx_locked_body, stringResource(R.string.app_name)), null)
    }
    LaunchedEffect(sources.size) { if (sources.isNotEmpty()) runCatching { first.requestFocus() } }
}
