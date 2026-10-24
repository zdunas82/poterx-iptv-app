package tv.own.owntv.poterx.vpn

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.ui.components.BrowseMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.StorageBrowser
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.stage.StageConfirm
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.theme.mpx
import java.util.Locale

/** This screen's rows as Settings search finds them. */
internal val VPN_SEARCH_ROWS: List<Int> = listOf(
    R.string.settings_vpn_connect, R.string.settings_vpn_surfshark_login, R.string.settings_vpn_surfshark_location,
    R.string.settings_vpn_import_file, R.string.settings_vpn_paste, R.string.settings_vpn_auto_connect,
)

/**
 * Poterx: Settings › Network › VPN. A Surfshark account (sign in, pick a country, the app does the
 * rest) or saved WireGuard profiles from a `.conf` file or pasted text; connect and disconnect with
 * a live status line, and connect-on-start. The tunnel carries this app only.
 */
@Composable
fun VpnSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vpn: PoterxVpn = koinInject()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val profiles by vpn.profiles.collectAsStateWithLifecycle()
    val account by vpn.account.collectAsStateWithLifecycle()
    val activeId by vpn.activeId.collectAsStateWithLifecycle()
    val autoConnect by vpn.autoConnect.collectAsStateWithLifecycle()
    val state by vpn.state.collectAsStateWithLifecycle()
    val clusters by vpn.clusters.collectAsStateWithLifecycle()
    val activeProfile = profiles.firstOrNull { it.id == activeId }
    val surfsharkActive = activeId == PoterxVpn.SURFSHARK_ID && account != null
    val hasActive = activeProfile != null || surfsharkActive

    var importFile by remember { mutableStateOf(false) }
    var pasteConfig by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf(false) }
    var askCode by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }
    var pickCountry by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // The one-time system consent for a VPN; granted, the tunnel comes up at once.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { if (vpn.builtIn) vpn.enable() else vpn.connect() }
    }
    val connectNow = {
        val intent = vpn.prepareIntent()
        if (intent != null) consent.launch(intent) else vpn.connect()
    }
    val builtIn = vpn.builtIn
    val toggle = {
        when (state) {
            is PoterxVpn.State.Off, is PoterxVpn.State.Failed -> {
                // Built-in account: the switch is remembered and signs in by itself; a file profile connects once.
                if (builtIn) {
                    val intent = vpn.prepareIntent()
                    if (intent != null) consent.launch(intent) else vpn.enable()
                } else connectNow()
            }
            else -> if (builtIn) vpn.disable() else vpn.disconnect()
        }
    }
    fun countryName(code: String): String = Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }

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
                PoterxVpn.Failure.ACCOUNT -> R.string.settings_vpn_error_account
                PoterxVpn.Failure.SERVERS -> R.string.settings_vpn_error_servers
                PoterxVpn.Failure.START, PoterxVpn.Failure.UNKNOWN -> R.string.settings_vpn_error_start
            },
        )
    }
    val builtInCountry = account?.countryCode ?: locale.country.ifBlank { "PL" }
    val activeName = when {
        builtIn -> stringResource(R.string.settings_vpn_surfshark_name, countryName(builtInCountry))
        surfsharkActive -> stringResource(R.string.settings_vpn_surfshark_name, countryName(account?.countryCode.orEmpty()))
        activeProfile != null -> activeProfile.name
        else -> null
    }
    val endpointText = when {
        !connected -> ""
        surfsharkActive -> account?.lastServer.orEmpty()
        else -> activeProfile?.endpoint.orEmpty()
    }

    val rowCount = if (builtIn) 3 else 5 + profiles.size + (if (account != null) 2 else 3)
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
            desc = activeName?.let { stringResource(R.string.settings_vpn_connect_description, it) }
                ?: stringResource(R.string.settings_vpn_connect_no_profile),
            value = SettingValue.Switch(connected || state is PoterxVpn.State.Connecting || (builtIn && autoConnect && state !is PoterxVpn.State.Failed)),
            onClick = toggle,
            enabled = hasActive || builtIn,
            modifier = Modifier.focusRequester(first),
        )
        StageSettingRow(
            icon = OwnTVIcon.INFO,
            title = stringResource(R.string.settings_vpn_status),
            desc = statusText,
            value = SettingValue.Saved(endpointText, any = false),
            onClick = {},
        )

        // ---- Surfshark: sign in once, the app registers its key and picks a server ----
        val signedIn = account
        if (builtIn) {
            // The provider's account: only the country is the customer's to choose.
            StageSettingRow(
                icon = OwnTVIcon.LIST,
                title = stringResource(R.string.settings_vpn_surfshark_location),
                desc = stringResource(R.string.settings_vpn_surfshark_location_description),
                value = SettingValue.Choice(countryName(builtInCountry)),
                onClick = { pickCountry = true; scope.launch { vpn.loadClusters() } },
                enabled = signedIn != null,
            )
        } else if (signedIn == null) {
            StageSettingsNote(stringResource(R.string.settings_vpn_surfshark_title), stringResource(R.string.settings_vpn_surfshark_intro))
            val emailLabel = stringResource(R.string.settings_vpn_surfshark_email)
            StageFieldRow(OwnTVIcon.PENCIL, emailLabel, email, { email = it }, keyboardType = KeyboardType.Email)
            val passLabel = stringResource(R.string.settings_vpn_surfshark_password)
            StageFieldRow(OwnTVIcon.PENCIL, passLabel, password, { password = it }, password = true)
            StageSettingRow(
                icon = OwnTVIcon.PERSON,
                title = stringResource(if (signingIn) R.string.settings_vpn_surfshark_signing_in else R.string.settings_vpn_surfshark_login),
                desc = if (signInError) stringResource(R.string.settings_vpn_error_login) else stringResource(R.string.settings_vpn_surfshark_login_description),
                value = SettingValue.Action(stringResource(R.string.settings_vpn_surfshark_login_short)),
                enabled = !signingIn && email.isNotBlank() && password.isNotBlank(),
                onClick = {
                    signInError = false
                    signingIn = true
                    scope.launch {
                        when (vpn.surfsharkSignIn(email, password)) {
                            PoterxVpn.SignIn.Ok -> { password = ""; connectNow() }
                            PoterxVpn.SignIn.NeedsCode -> { codeError = false; askCode = true }
                            PoterxVpn.SignIn.Rejected -> signInError = true
                        }
                        signingIn = false
                    }
                },
            )
        } else {
            StageSettingRow(
                icon = OwnTVIcon.PERSON,
                title = stringResource(R.string.settings_vpn_surfshark_title),
                desc = stringResource(R.string.settings_vpn_surfshark_signed_in, signedIn.email),
                value = SettingValue.Choice(stringResource(if (surfsharkActive) R.string.settings_vpn_profile_active else R.string.settings_vpn_profile_use)),
                onClick = {
                    if (!surfsharkActive) {
                        val wasOn = state !is PoterxVpn.State.Off
                        vpn.setActive(PoterxVpn.SURFSHARK_ID)
                        if (wasOn) { vpn.disconnect(); vpn.connect() }
                    }
                },
                onLongClick = { confirmSignOut = true },
            )
            StageSettingRow(
                icon = OwnTVIcon.LIST,
                title = stringResource(R.string.settings_vpn_surfshark_location),
                desc = stringResource(R.string.settings_vpn_surfshark_location_description),
                value = SettingValue.Choice(countryName(signedIn.countryCode)),
                onClick = { pickCountry = true; scope.launch { vpn.loadClusters() } },
            )
        }

        // ---- saved .conf profiles (not offered on a built-in account: nothing to import) ----
        if (!builtIn) profiles.forEach { profile ->
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
        if (!builtIn) StageSettingRow(
            icon = OwnTVIcon.DOWNLOADS,
            title = stringResource(R.string.settings_vpn_import_file),
            desc = stringResource(R.string.settings_vpn_import_file_description),
            value = SettingValue.Opens(null),
            onClick = { importError = false; importFile = true },
        )
        if (!builtIn) StageSettingRow(
            icon = OwnTVIcon.PENCIL,
            title = stringResource(R.string.settings_vpn_paste),
            desc = stringResource(R.string.settings_vpn_paste_description),
            value = SettingValue.Opens(null),
            onClick = { importError = false; pasteConfig = true },
        )
        if (!builtIn) StageSettingRow(
            icon = OwnTVIcon.POWER,
            title = stringResource(R.string.settings_vpn_auto_connect),
            desc = stringResource(R.string.settings_vpn_auto_connect_description),
            value = SettingValue.Switch(autoConnect),
            onClick = { vpn.setAutoConnect(!autoConnect) },
            enabled = hasActive,
        )
        if (importError) StageSettingsNote(stringResource(R.string.settings_vpn_error_config), null)
        StageSettingsNote(
            stringResource(R.string.settings_vpn_note_title),
            stringResource(if (builtIn) R.string.settings_vpn_note_body_builtin else R.string.settings_vpn_note_body),
        )
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
    if (askCode) {
        TextInputDialog(
            title = stringResource(if (codeError) R.string.settings_vpn_error_otp else R.string.settings_vpn_surfshark_otp_title),
            label = stringResource(R.string.settings_vpn_surfshark_otp_label),
            confirmLabel = stringResource(R.string.settings_vpn_surfshark_login_short),
            allowBlank = false,
            onConfirm = { code ->
                scope.launch {
                    when (vpn.surfsharkCode(code)) {
                        PoterxVpn.SignIn.Ok -> { askCode = false; password = ""; connectNow() }
                        else -> codeError = true
                    }
                }
            },
            onDismiss = { askCode = false },
        )
    }
    if (pickCountry) {
        val countries = remember(clusters) {
            clusters.groupBy { it.countryCode }.map { (code, list) -> code to list.minOf { it.load } }
                .sortedBy { countryName(it.first) }
        }
        StagePopup(
            onDismiss = { pickCountry = false },
            title = stringResource(R.string.settings_vpn_surfshark_location),
            body = if (countries.isEmpty()) stringResource(R.string.settings_vpn_loading) else stringResource(R.string.settings_vpn_surfshark_location_description),
            width = 760.mpx,
            scroll = false,
        ) {
            Column(Modifier.heightIn(max = 620.mpx).verticalScroll(rememberScrollState())) {
                countries.forEach { (code, load) ->
                    StageSettingRow(
                        icon = OwnTVIcon.NETWORK,
                        title = countryName(code),
                        desc = stringResource(R.string.settings_vpn_surfshark_load, load),
                        value = if (code.equals(account?.countryCode, ignoreCase = true)) SettingValue.Choice(stringResource(R.string.settings_vpn_profile_active)) else null,
                        onClick = { vpn.setSurfsharkCountry(code); pickCountry = false },
                    )
                }
            }
        }
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
    if (confirmSignOut) {
        StageConfirm(
            title = stringResource(R.string.settings_vpn_surfshark_logout_title),
            body = stringResource(R.string.settings_vpn_surfshark_logout_body),
            confirm = stringResource(R.string.settings_vpn_surfshark_logout),
            onConfirm = { vpn.surfsharkSignOut(); confirmSignOut = false },
            onCancel = { confirmSignOut = false },
            focusCancel = true,
        )
    }
}
