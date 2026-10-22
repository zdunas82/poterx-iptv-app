package tv.own.owntv.poterx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.features.profiles.PinDialog
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.settings.SourceTestUi
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.stage.StageConfirm

/**
 * Poterx: Settings › Account on a locked build. The account is the one thing the user has here that
 * is theirs: who is signed in, what the panel says about the line (status, expiry, connections) and
 * a way to sign out so another account can sign in on the same television. The server is never
 * printed. Signing out asks for the service PIN when the build carries one.
 */
@Composable
fun LockedAccountScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val test by vm.sourceTest.collectAsStateWithLifecycle()
    val source = sources.firstOrNull()
    val first = rememberLockedFirstFocus()
    val formatDate = rememberBestDateFormatter("yMMMd")
    val scope = rememberCoroutineScope()
    val sourceDao = koinInject<tv.own.owntv.core.database.dao.SourceDao>()
    val sourceRepository = koinInject<tv.own.owntv.core.repository.SourceRepository>()
    val profileDao = koinInject<tv.own.owntv.core.database.dao.ProfileDao>()
    val epgStore = koinInject<tv.own.owntv.core.epg.EpgSourceStore>()
    val settings = koinInject<tv.own.owntv.core.settings.SettingsRepository>()
    val contact = LockedSource.account?.contact.orEmpty()
    val servicePin = LockedSource.account?.servicePin.orEmpty()
    var confirmSignOut by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var pinWrong by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }

    // The panel is asked once when the page opens, and again on "Check now".
    LaunchedEffect(source?.id) { source?.let { vm.testSource(it) } }

    val result = (test as? SourceTestUi.Done)?.result
    val ok = result as? SourceTestResult.Ok
    val statusText = when {
        test is SourceTestUi.Running -> stringResource(R.string.poterx_account_checking)
        // The panel's own word, translated when it is one of the usual ones; anything else as written.
        ok != null -> when (ok.status?.trim()?.lowercase()) {
            null, "", "active" -> stringResource(R.string.poterx_account_active)
            "expired" -> stringResource(R.string.poterx_account_expired)
            "banned", "disabled" -> stringResource(R.string.poterx_account_blocked)
            else -> ok.status.orEmpty()
        }
        result is SourceTestResult.Expired -> stringResource(R.string.poterx_account_expired)
        result is SourceTestResult.AuthFailed -> stringResource(R.string.poterx_account_rejected)
        result is SourceTestResult.Unreachable -> stringResource(R.string.poterx_account_unreachable)
        else -> stringResource(R.string.poterx_account_unknown)
    }
    val expiryText = ok?.expiryText?.takeIf { it.isNotBlank() }
        ?: ok?.expiryMs?.let { formatDate(it) }
        ?: (result as? SourceTestResult.Expired)?.expiryMs?.let { formatDate(it) }
        ?: if (ok != null) stringResource(R.string.poterx_account_no_expiry) else null
    val connectionsText = ok?.let {
        when {
            it.maxConnections > 0 && it.activeConnections >= 0 -> stringResource(R.string.poterx_account_connections_of, it.activeConnections, it.maxConnections)
            it.maxConnections > 0 -> it.maxConnections.toString()
            else -> null
        }
    }

    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_sources)),
        title = stringResource(R.string.poterx_account_title),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = first,
    ) {
        if (source == null) {
            StageSettingsNote(stringResource(R.string.poterx_no_account), null)
        } else {
            StageSettingRow(
                icon = OwnTVIcon.PERSON,
                title = stringResource(R.string.setup_username),
                desc = stringResource(R.string.poterx_account_user_description),
                value = SettingValue.Saved(source.username.orEmpty(), any = false),
                onClick = {},
                modifier = Modifier.focusRequester(first),
            )
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = stringResource(R.string.poterx_account_status),
                desc = if (ok?.trial == true) stringResource(R.string.poterx_account_trial) else stringResource(R.string.poterx_account_status_description),
                value = SettingValue.Choice(statusText),
                onClick = { vm.testSource(source) },
            )
            if (expiryText != null) {
                StageSettingRow(
                    icon = OwnTVIcon.EPG,
                    title = stringResource(R.string.poterx_account_expiry),
                    desc = stringResource(R.string.poterx_account_expiry_description),
                    value = SettingValue.Saved(expiryText, any = false),
                    onClick = {},
                )
            }
            if (connectionsText != null) {
                StageSettingRow(
                    icon = OwnTVIcon.NETWORK,
                    title = stringResource(R.string.poterx_account_connections),
                    desc = stringResource(R.string.poterx_account_connections_description),
                    value = SettingValue.Saved(connectionsText, any = false),
                    onClick = {},
                )
            }
            StageSettingRow(
                icon = OwnTVIcon.POWER,
                title = stringResource(R.string.poterx_sign_out),
                desc = stringResource(R.string.poterx_sign_out_description),
                value = SettingValue.Opens(null),
                onClick = { if (!signingOut) confirmSignOut = true },
                enabled = !signingOut,
            )
        }
        if (contact.isNotBlank()) {
            StageSettingsNote(stringResource(R.string.poterx_contact_title), contact)
        }
    }

    val signOut: () -> Unit = {
        signingOut = true
        scope.launch {
            runCatching { vm.dismissSourceTest() }
            runCatching { PoterxAccount.signOut(sourceDao, sourceRepository, profileDao, epgStore, settings) }
            signingOut = false
        }
    }
    if (confirmSignOut) {
        StageConfirm(
            title = stringResource(R.string.poterx_sign_out),
            body = stringResource(R.string.poterx_sign_out_body),
            confirm = stringResource(R.string.poterx_sign_out),
            onConfirm = {
                confirmSignOut = false
                if (servicePin.isNotBlank()) { pinWrong = false; askPin = true } else signOut()
            },
            onCancel = { confirmSignOut = false },
            focusCancel = true,
        )
    }
    if (askPin) {
        PinDialog(
            title = if (pinWrong) stringResource(R.string.profiles_wrong_pin) else stringResource(R.string.poterx_service_pin),
            onSubmit = { pin -> if (pin == servicePin) { askPin = false; signOut() } else pinWrong = true },
            onDismiss = { askPin = false },
        )
    }
    LaunchedEffect(source?.id) { if (source != null) runCatching { first.requestFocus() } }
}
