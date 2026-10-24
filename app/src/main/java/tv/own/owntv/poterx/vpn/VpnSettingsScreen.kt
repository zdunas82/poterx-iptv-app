package tv.own.owntv.poterx.vpn

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.ui.components.BrowseMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.StorageBrowser
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.stage.StageConfirm

/** This screen's rows as Settings search finds them. */
internal val VPN_SEARCH_ROWS: List<Int> =
    listOf(R.string.settings_vpn_connect, R.string.settings_vpn_import_file, R.string.settings_vpn_paste, R.string.settings_vpn_auto_connect)

/**
 * Poterx: Settings › Network › VPN. Saved WireGuard profiles, import from a `.conf` file or pasted
 * text, connect and disconnect with a live status line, and connect-on-start. The tunnel carries
 * this app only; an external player is outside it.
 */
@Composable
fun VpnSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vpn: PoterxVpn = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by vpn.profiles.collectAsStateWithLifecycle()
    val activeId by vpn.activeId.collectAsStateWithLifecycle()
    val autoConnect by vpn.autoConnect.collectAsStateWithLifecycle()
    val state by vpn.state.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId }

    var importFile by remember { mutableStateOf(false) }
    var pasteConfig by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf(false) }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // The one-time system consent for a VPN; granted, the tunnel comes up at once.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vpn.connect()
    }
    val toggle = {
        when (state) {
            is PoterxVpn.State.Off, is PoterxVpn.State.Failed -> {
                val intent = vpn.prepareIntent()
                if (intent != null) consent.launch(intent) else vpn.connect()
            }
            else -> vpn.disconnect()
        }
    }

    val connected = state is PoterxVpn.State.On
    val statusText = when (val s = state) {
        PoterxVpn.State.Off -> stringResource(R.string.settings_vpn_status_off)
        PoterxVpn.State.Connecting -> stringResource(R.string.settings_vpn_status_connecting)
        is PoterxVpn.State.On -> {
            val age = ((System.currentTimeMillis() - s.handshakeMs) / 1000L).toInt()
            val handshake = if (s.handshakeMs <= 0L) stringResource(R.string.settings_vpn_handshake_none)
            else stringResource(R.string.settings_vpn_handshake_ago, age.coerceAtLeast(0))
            stringResource(
                R.string.settings_vpn_status_on,
                handshake,
                android.text.format.Formatter.formatShortFileSize(context, s.rxBytes),
                android.text.format.Formatter.formatShortFileSize(context, s.txBytes),
            )
        }
        is PoterxVpn.State.Failed -> stringResource(
            when (s.reason) {
                PoterxVpn.Failure.NOT_AUTHORIZED -> R.string.settings_vpn_error_permission
                PoterxVpn.Failure.NO_PROFILE -> R.string.settings_vpn_error_no_profile
                PoterxVpn.Failure.BAD_CONFIG -> R.string.settings_vpn_error_config
                PoterxVpn.Failure.DNS -> R.string.settings_vpn_error_dns
                PoterxVpn.Failure.START, PoterxVpn.Failure.UNKNOWN -> R.string.settings_vpn_error_start
            },
        )
    }

    val rowCount = 5 + profiles.size
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_app), stringResource(R.string.settings_group_network)),
        title = stringResource(R.string.settings_vpn),
        count = pluralStringResource(R.plurals.settings_setting_count, rowCount, rowCount),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = first,
    ) {
        StageSettingRow(
            icon = OwnTVIcon.NETWORK,
            title = stringResource(R.string.settings_vpn_connect),
            desc = active?.let { stringResource(R.string.settings_vpn_connect_description, it.name) }
                ?: stringResource(R.string.settings_vpn_connect_no_profile),
            value = SettingValue.Switch(connected || state is PoterxVpn.State.Connecting),
            onClick = toggle,
            enabled = active != null,
            modifier = Modifier.focusRequester(first),
        )
        StageSettingRow(
            icon = OwnTVIcon.INFO,
            title = stringResource(R.string.settings_vpn_status),
            desc = statusText,
            value = SettingValue.Saved(active?.endpoint?.takeIf { connected }.orEmpty(), any = false),
            onClick = {},
        )
        profiles.forEach { profile ->
            StageSettingRow(
                icon = OwnTVIcon.LIST,
                title = profile.name,
                desc = stringResource(R.string.settings_vpn_profile_description, profile.endpoint),
                value = SettingValue.Choice(stringResource(if (profile.id == activeId) R.string.settings_vpn_profile_active else R.string.settings_vpn_profile_use)),
                onClick = {
                    if (profile.id != activeId) {
                        val wasOn = state !is PoterxVpn.State.Off
                        vpn.setActive(profile.id)
                        if (wasOn) { vpn.disconnect(); vpn.connect() }
                    }
                },
                onLongClick = { deleteId = profile.id },
            )
        }
        StageSettingRow(
            icon = OwnTVIcon.DOWNLOADS,
            title = stringResource(R.string.settings_vpn_import_file),
            desc = stringResource(R.string.settings_vpn_import_file_description),
            value = SettingValue.Opens(null),
            onClick = { importError = false; importFile = true },
        )
        StageSettingRow(
            icon = OwnTVIcon.PENCIL,
            title = stringResource(R.string.settings_vpn_paste),
            desc = stringResource(R.string.settings_vpn_paste_description),
            value = SettingValue.Opens(null),
            onClick = { importError = false; pasteConfig = true },
        )
        StageSettingRow(
            icon = OwnTVIcon.POWER,
            title = stringResource(R.string.settings_vpn_auto_connect),
            desc = stringResource(R.string.settings_vpn_auto_connect_description),
            value = SettingValue.Switch(autoConnect),
            onClick = { vpn.setAutoConnect(!autoConnect) },
            enabled = active != null,
        )
        if (importError) StageSettingsNote(stringResource(R.string.settings_vpn_error_config), null)
        StageSettingsNote(stringResource(R.string.settings_vpn_note_title), stringResource(R.string.settings_vpn_note_body))
    }

    val importName = stringResource(R.string.settings_vpn_import_file)
    if (importFile) {
        StorageBrowser(
            title = importName,
            mode = BrowseMode.FILE,
            fileExtensions = setOf("conf"),
            onPick = { file ->
                importFile = false
                scope.launch {
                    val text = runCatching { file.readText() }.getOrNull()
                    val result = text?.let { vpn.importConfig(it, file.nameWithoutExtension) }
                    importError = result?.isSuccess != true
                }
            },
            onDismiss = { importFile = false },
        )
    }
    if (pasteConfig) {
        TextInputDialog(
            title = stringResource(R.string.settings_vpn_paste),
            label = stringResource(R.string.settings_vpn_paste_label),
            hint = stringResource(R.string.settings_vpn_paste_hint),
            confirmLabel = stringResource(R.string.common_save),
            allowBlank = false,
            onConfirm = { text ->
                pasteConfig = false
                scope.launch { importError = vpn.importConfig(text).isFailure }
            },
            onDismiss = { pasteConfig = false },
        )
    }
    deleteId?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        StageConfirm(
            title = stringResource(R.string.settings_vpn_delete_title, profile?.name.orEmpty()),
            body = stringResource(R.string.settings_vpn_delete_body),
            confirm = stringResource(R.string.common_delete),
            onConfirm = { vpn.delete(id); deleteId = null },
            onCancel = { deleteId = null },
            focusCancel = true,
        )
    }
}
