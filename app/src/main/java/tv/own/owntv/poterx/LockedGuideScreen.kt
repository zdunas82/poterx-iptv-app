package tv.own.owntv.poterx

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.sync.work.EpgSyncState
import tv.own.owntv.features.settings.EpgSourcesViewModel
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Poterx: Settings › EPG on a locked build. The guide feed is the panel's own and is registered by
 * the app, so there is nothing to add, edit or delete, and its address (which carries the account
 * and names the panel) is never printed. What remains: refresh now, and how often it refreshes.
 */
@Composable
fun LockedGuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: EpgSourcesViewModel = koinViewModel()
    val feeds by vm.sources.collectAsStateWithLifecycle()
    val autoRefresh by vm.autoRefresh.collectAsStateWithLifecycle()
    val first = rememberLockedFirstFocus()
    val modes = remember { listOf(EpgAutoRefresh.OFF, EpgAutoRefresh.STARTUP, EpgAutoRefresh.HOURS_6, EpgAutoRefresh.HOURS_12, EpgAutoRefresh.HOURS_24, EpgAutoRefresh.MANUAL) }

    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_sources)),
        title = stringResource(R.string.settings_epg_sources),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = first,
    ) {
        if (feeds.isEmpty()) StageSettingsNote(stringResource(R.string.poterx_guide_empty), null)
        feeds.forEachIndexed { index, feed ->
            val syncState by remember(feed.id) { vm.observeSync(feed.id) }.collectAsStateWithLifecycle(EpgSyncState.Idle)
            val syncing = syncState is EpgSyncState.Syncing
            val refresh = autoRefresh[feed.id] ?: EpgRefresh.OFF
            val lastSync = feed.lastSyncAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = stringResource(R.string.poterx_guide_feed),
                desc = when {
                    syncing -> stringResource(R.string.sync_progress_syncing)
                    lastSync != null -> stringResource(R.string.poterx_last_refresh, lastSync)
                    else -> stringResource(R.string.poterx_last_refresh, stringResource(R.string.common_never))
                },
                value = SettingValue.Choice(stringResource(if (syncing) R.string.settings_sources_cancel else R.string.poterx_refresh_now)),
                onClick = { if (syncing) vm.cancelSync(feed) else vm.resync(feed) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
            val selected = modes.indexOf(refresh.mode).coerceAtLeast(0)
            val pick: (Int) -> Unit = { step ->
                val next = modes[(selected + step + modes.size) % modes.size]
                vm.setAutoRefresh(feed, EpgRefresh(next, refresh.manualDays))
            }
            StageSettingRow(
                icon = OwnTVIcon.EPG,
                title = stringResource(R.string.poterx_refresh_auto),
                desc = stringResource(R.string.poterx_guide_refresh_description),
                value = SettingValue.Choice(lockedEpgRefreshLabel(refresh)),
                onClick = { pick(1) },
                onStep = pick,
            )
        }
        StageSettingsNote(stringResource(R.string.poterx_guide_body), null)
    }
    LaunchedEffect(feeds.size) { if (feeds.isNotEmpty()) runCatching { first.requestFocus() } }
}

@Composable
private fun lockedEpgRefreshLabel(refresh: EpgRefresh): String =
    if (refresh.mode == EpgAutoRefresh.MANUAL) {
        pluralStringResource(R.plurals.settings_sources_refresh_days, refresh.manualDays, refresh.manualDays)
    } else {
        stringResource(
            when (refresh.mode) {
                EpgAutoRefresh.OFF -> R.string.settings_sources_refresh_off
                EpgAutoRefresh.STARTUP -> R.string.settings_sources_refresh_startup
                EpgAutoRefresh.HOURS_1 -> R.string.settings_epg_refresh_1h
                EpgAutoRefresh.HOURS_3 -> R.string.settings_epg_refresh_3h
                EpgAutoRefresh.HOURS_6 -> R.string.settings_epg_refresh_6h
                EpgAutoRefresh.HOURS_12 -> R.string.settings_epg_refresh_12h
                EpgAutoRefresh.HOURS_24 -> R.string.settings_epg_refresh_24h
                EpgAutoRefresh.HOURS_48 -> R.string.settings_epg_refresh_48h
                EpgAutoRefresh.MANUAL -> R.string.settings_sources_refresh_manual
            },
        )
    }
