package tv.own.owntv.poterx

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
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.importProgressDisplay
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.features.setup.displayText
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
                    val canSubmit = username.isNotBlank() && password.isNotBlank()
                    val submit = { if (canSubmit) { vm.reset(); attempt++; signingIn = false } }
                    Text(
                        stringResource(R.string.poterx_login_title),
                        style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // After a failed attempt the panel's own reason replaces the invitation.
                        if (s is SourceImporter.ImportState.Failed) s.failure.displayText() else stringResource(R.string.poterx_login_body),
                        style = stageText(20, 500), color = if (s is SourceImporter.ImportState.Failed) StageColors.Danger else StageColors.Muted,
                        textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
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
                        StageButton(stringResource(R.string.setup_continue), onClick = submit, tinted = canSubmit)
                        if (addingProfile) {
                            StageButton(stringResource(R.string.common_cancel), onClick = { vm.cancelImport(); onCancel() })
                        }
                    }
                }
                s is SourceImporter.ImportState.Failed -> {
                    val first = rememberLockedFirstFocus()
                    Text(
                        stringResource(R.string.setup_import_failed),
                        style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.failure.displayText(),
                        style = stageText(20, 500), color = StageColors.Muted, textAlign = TextAlign.Center,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
                        StageButton(
                            stringResource(R.string.setup_try_again_caps),
                            // A server-only build goes back to the sign-in page (the password may be the reason).
                            onClick = { vm.reset(); if (LockedSource.requiresLogin) signingIn = true else attempt++ },
                            tinted = true,
                            modifier = Modifier.focusRequester(first),
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
