package tv.own.owntv.poterx

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.importProgressDisplay
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.features.setup.displayText
import tv.own.owntv.poterx.vpn.PoterxVpn
import tv.own.owntv.ui.components.BrandLockup
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.detailText
import tv.own.owntv.ui.components.primaryText
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Poterx: the whole first run on one screen. Stock OwnTV walks the user through the setup wizard
 * (language, display size, profile, "add a playlist" with the server typed in). A locked build
 * never shows the server. Two shapes, decided at build time (see LockedSource):
 *
 *  - account built in: the profile is created, the playlist imported, the app opens. The only
 *    thing a user can see is the progress line, and "Try again" if the panel was unreachable.
 *  - server built in: the same, after a sign-in page asking for the username and password only.
 *
 * [addingProfile]: a second profile from the "Who's watching?" gate. The playlist already exists
 * then, so it is linked to the new profile instead of being imported again.
 */
@Composable
fun LockedOnboarding(
    addingProfile: Boolean,
    onDone: (Long?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SetupViewModel = koinViewModel()
    val account = LockedSource.account
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val appName = stringResource(R.string.app_name)
    val profileName = stringResource(if (addingProfile) R.string.poterx_profile_added else R.string.poterx_profile_default)
    var profileId by rememberSaveable { mutableLongStateOf(-1L) }
    // The sign-in page's fields, kept across a failed attempt so a typo is fixed, not retyped.
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // Each submission bumps this; the import effect below keys on it.
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    // Whether the sign-in page is showing (server-only build, nothing submitted or the last attempt failed).
    var signingIn by rememberSaveable { mutableStateOf(LockedSource.requiresLogin) }

    // Poterx: before the import, the panel is asked whether these credentials are right. The answer
    // tells a wrong password, an expired line, no internet and an ISP block apart; a block offers
    // the built-in VPN right here, since nothing else on this screen can help.
    val reach: PoterxReachability = koinInject()
    val vpn: PoterxVpn = koinInject()
    val scope = rememberCoroutineScope()
    var verdict by remember { mutableStateOf<PoterxReachability.Verdict?>(null) }
    var checking by remember { mutableStateOf(false) }
    var vpnJoining by remember { mutableStateOf(false) }
    var vpnFailed by remember { mutableStateOf(false) }
    /** The import failed after the panel had said yes: ask the network why, once per failure. */
    var diagnosed by remember { mutableStateOf<SourceImporter.ImportState.Failed?>(null) }
    val startImport = { vm.reset(); verdict = null; attempt++; signingIn = false }
    val checkSignIn: () -> Unit = {
        if (account != null && !checking) {
            checking = true
            vpnFailed = false
            scope.launch {
                val found = reach.signIn(account.server, username.trim(), password, PoterxAccount.userAgent)
                checking = false
                verdict = found
                if (found is PoterxReachability.Verdict.Ok) startImport()
            }
        }
    }
    /** The VPN is on; wait for the server's first answer, then do what was blocked. */
    val afterVpn: (() -> Unit) -> Unit = { then ->
        vpnJoining = true
        vpnFailed = false
        vpn.enable()
        scope.launch {
            val up = withTimeoutOrNull(VPN_JOIN_TIMEOUT_MS) {
                vpn.state.first { (it is PoterxVpn.State.On && it.handshakeMs > 0L) || it is PoterxVpn.State.Failed }
            }
            vpnJoining = false
            if (up is PoterxVpn.State.On) then() else { vpnFailed = true; vpn.disable() }
        }
    }
    var pendingAfterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val then = pendingAfterConsent
        pendingAfterConsent = null
        if (result.resultCode == Activity.RESULT_OK && then != null) afterVpn(then) else vpnFailed = true
    }
    val vpnThen: (() -> Unit) -> Unit = { then ->
        val intent = vpn.prepareIntent()
        if (intent != null) { pendingAfterConsent = then; consent.launch(intent) } else afterVpn(then)
    }
    LaunchedEffect(state) {
        val failed = state as? SourceImporter.ImportState.Failed ?: run { diagnosed = null; verdict = null; return@LaunchedEffect }
        if (diagnosed == failed || account == null) return@LaunchedEffect
        diagnosed = failed
        val sync = (failed.failure as? SourceImporter.SetupFailure.Sync)?.failure
        if (sync == null || sync is tv.own.owntv.core.util.FriendlySyncFailure.AuthenticationRejected) return@LaunchedEffect
        verdict = reach.signIn(account.server, account.username.ifBlank { username.trim() }, account.password.ifBlank { password }, PoterxAccount.userAgent)
    }

    // 1. The profile. -1 = no avatar yet, the same default the wizard's profile step starts on.
    LaunchedEffect(Unit) {
        if (profileId < 0L) vm.createProfile(profileName, -1, isKids = false, pin = null, picture = null) { profileId = it }
    }
    // 2. The playlist: link the copy an earlier profile imported, else import with the built-in
    //    account or the credentials just typed.
    LaunchedEffect(profileId, attempt, signingIn) {
        if (profileId < 0L || account == null || signingIn) return@LaunchedEffect
        val existing = runCatching { vm.availableExistingSources() }.getOrDefault(emptyList())
        if (existing.isNotEmpty()) {
            vm.linkExisting(existing.map { it.id }.toSet())
        } else {
            vm.startXtream(
                name = appName,
                server = account.server,
                username = account.username.ifBlank { username.trim() },
                password = account.password.ifBlank { password },
                // Every request to the panel carries this app's name, so the provider can tell it apart.
                userAgent = PoterxAccount.userAgent,
                // The channel list refreshes itself twice a day; Settings › Playlists changes that.
                autoRefresh = PlaylistRefresh(PlaylistAutoRefresh.HOURS_12),
                preferHls = account.preferHls,
            )
        }
    }
    // 3. Straight into the app; the wizard's "All set" page has nothing a locked build needs to say.
    //    The wizard would ask before downloading the panel's guide (xmltv.php); a locked build just
    //    starts it, in the background, while the app opens. Linking an existing playlist (a second
    //    profile) carries no source here, and its guide is already registered from the first import.
    LaunchedEffect(state) {
        val done = state as? SourceImporter.ImportState.Success ?: return@LaunchedEffect
        done.source?.let { vm.syncGuideNow(it) }
        vm.dismissPendingEpg()
        vm.finish(onDone)
    }

    Box(modifier.fillMaxSize().stageBackground(stageAccent.accent), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(900.mpx).padding(32.mpx),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.mpx),
        ) {
            BrandLockup(markSize = 56)
            val s = state
            when {
                account == null -> Text(
                    stringResource(R.string.poterx_no_account),
                    style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                signingIn -> {
                    val first = rememberLockedFirstFocus()
                    val canSubmit = username.isNotBlank() && password.isNotBlank() && !checking && !vpnJoining
                    val submit = { if (canSubmit) checkSignIn() }
                    val blocked = verdict as? PoterxReachability.Verdict.Blocked
                    Text(
                        stringResource(if (blocked != null) R.string.poterx_blocked_title else R.string.poterx_login_title),
                        style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    // After a failed attempt the reason replaces the invitation: the panel's answer to
                    // these credentials, or what the network diagnosis found.
                    val reason = when {
                        vpnJoining -> stringResource(R.string.poterx_blocked_vpn_connecting)
                        checking -> stringResource(R.string.poterx_login_checking)
                        vpnFailed -> stringResource(R.string.poterx_blocked_vpn_failed)
                        else -> verdictText(verdict) ?: (s as? SourceImporter.ImportState.Failed)?.failure?.displayText()
                    }
                    val warning = reason != null && !checking && !vpnJoining
                    Text(
                        reason ?: stringResource(R.string.poterx_login_body),
                        style = stageText(20, 500), color = if (warning) StageColors.Danger else StageColors.Muted,
                        textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    if (warning && verdict !is PoterxReachability.Verdict.AuthFailed) LockedContactLine()
                    StageFieldRow(
                        icon = OwnTVIcon.PERSON,
                        label = stringResource(R.string.setup_username),
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(first),
                    )
                    StageFieldRow(
                        icon = OwnTVIcon.PENCIL,
                        label = stringResource(R.string.setup_password),
                        value = password,
                        onValueChange = { password = it },
                        password = true,
                        onDone = submit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
                        if (blocked != null && vpn.builtIn) {
                            // The block is the ISP's; the switch that gets around it belongs here, not
                            // three menus deep in Settings. It stays on afterwards.
                            StageButton(
                                stringResource(R.string.poterx_blocked_vpn_sign_in),
                                onClick = { if (canSubmit) vpnThen(checkSignIn) },
                                tinted = canSubmit,
                            )
                        }
                        StageButton(stringResource(R.string.setup_continue), onClick = submit, tinted = canSubmit && blocked == null)
                        if (addingProfile) {
                            StageButton(stringResource(R.string.common_cancel), onClick = { vm.cancelImport(); onCancel() })
                        }
                    }
                }
                s is SourceImporter.ImportState.Failed -> {
                    val first = rememberLockedFirstFocus()
                    val blocked = verdict as? PoterxReachability.Verdict.Blocked
                    Text(
                        stringResource(if (blocked != null) R.string.poterx_blocked_title else R.string.setup_import_failed),
                        style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            vpnJoining -> stringResource(R.string.poterx_blocked_vpn_connecting)
                            vpnFailed -> stringResource(R.string.poterx_blocked_vpn_failed)
                            else -> verdictText(verdict) ?: s.failure.displayText()
                        },
                        style = stageText(20, 500), color = StageColors.Muted, textAlign = TextAlign.Center,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    LockedContactLine()
                    Row(horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
                        if (blocked != null && vpn.builtIn) {
                            StageButton(
                                stringResource(R.string.poterx_blocked_vpn_retry),
                                onClick = { if (!vpnJoining) vpnThen { vm.reset(); attempt++ } },
                                tinted = true,
                                modifier = Modifier.focusRequester(first),
                            )
                        }
                        StageButton(
                            stringResource(R.string.setup_try_again_caps),
                            // A server-only build goes back to the sign-in page (the password may be the reason).
                            onClick = { vm.reset(); verdict = null; if (LockedSource.requiresLogin) signingIn = true else attempt++ },
                            tinted = blocked == null,
                            modifier = if (blocked != null && vpn.builtIn) Modifier else Modifier.focusRequester(first),
                        )
                        if (addingProfile) {
                            StageButton(stringResource(R.string.common_cancel), onClick = { vm.cancelImport(); onCancel() })
                        }
                    }
                }
                else -> {
                    val display = progress?.importProgressDisplay()
                    Text(
                        stringResource(R.string.poterx_connecting),
                        style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        display?.primaryText() ?: stringResource(R.string.setup_preparing_catalog),
                        style = stageText(20, 500), color = StageColors.Muted, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    display?.detailText()?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it, style = stageText(18, 400), color = StageColors.Muted, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** How long the sign-in page waits for the VPN's first server answer before giving up on it. */
private const val VPN_JOIN_TIMEOUT_MS = 4L * 60 * 1000

/** The sentence for a diagnosis, or null when there is nothing (yet) to say. */
@Composable
private fun verdictText(verdict: PoterxReachability.Verdict?): String? = when (verdict) {
    null, PoterxReachability.Verdict.Ok -> null
    PoterxReachability.Verdict.AuthFailed -> stringResource(R.string.poterx_login_wrong)
    is PoterxReachability.Verdict.Expired -> stringResource(R.string.poterx_login_expired)
    PoterxReachability.Verdict.Offline -> stringResource(R.string.poterx_login_offline)
    PoterxReachability.Verdict.PanelDown -> stringResource(R.string.poterx_login_panel_down)
    is PoterxReachability.Verdict.Blocked -> blockText(verdict.how)
}

@Composable
internal fun blockText(how: PoterxReachability.Block): String {
    val body = stringResource(
        when (how) {
            PoterxReachability.Block.DNS -> R.string.poterx_blocked_dns
            PoterxReachability.Block.CONNECTION -> R.string.poterx_blocked_connection
            PoterxReachability.Block.PAGE -> R.string.poterx_blocked_page
        },
    )
    return if (LockedSource.account?.vpnUser.isNullOrBlank()) body + " " + stringResource(R.string.poterx_blocked_no_vpn) else body
}

/** The provider's contact line under a failure, when the build carries one: who to call about it. */
@Composable
private fun LockedContactLine() {
    val contact = LockedSource.account?.contact.orEmpty()
    if (contact.isBlank()) return
    Text(
        stringResource(R.string.poterx_contact_line, contact),
        style = stageText(20, 600), color = StageColors.Text, textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}
