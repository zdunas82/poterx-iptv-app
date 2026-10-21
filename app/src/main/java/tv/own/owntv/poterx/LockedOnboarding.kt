package tv.own.owntv.poterx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.importProgressDisplay
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.features.setup.displayText
import tv.own.owntv.ui.components.BrandLockup
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
 * (language, display size, profile, "add a playlist" with the account typed in). A locked build has
 * nothing to ask: it creates the profile, imports the built-in account and opens the app. The only
 * screen a user can see here is the progress line, and "Try again" if the panel was unreachable.
 *
 * [addingProfile]: a second profile from the "Who's watching?" gate. The built-in playlist already
 * exists then, so it is linked to the new profile instead of being imported again.
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
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var profileId by rememberSaveable { mutableLongStateOf(-1L) }

    // 1. The profile. -1 = no avatar yet, the same default the wizard's profile step starts on.
    LaunchedEffect(Unit) {
        if (profileId < 0L) vm.createProfile(profileName, -1, isKids = false, pin = null, picture = null) { profileId = it }
    }
    // 2. The playlist: import the built-in account, or link the copy an earlier profile imported.
    LaunchedEffect(profileId, attempt) {
        if (profileId < 0L || account == null) return@LaunchedEffect
        val existing = runCatching { vm.availableExistingSources() }.getOrDefault(emptyList())
        if (existing.isNotEmpty()) {
            vm.linkExisting(existing.map { it.id }.toSet())
        } else {
            vm.startXtream(
                name = appName,
                server = account.server,
                username = account.username,
                password = account.password,
                preferHls = account.preferHls,
            )
        }
    }
    // 3. Straight into the app; the wizard's "All set" page has nothing a locked build needs to say.
    LaunchedEffect(state) {
        if (state is SourceImporter.ImportState.Success) vm.finish(onDone)
    }

    Box(modifier.fillMaxSize().stageBackground(stageAccent.accent), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(900.mpx).padding(32.mpx),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.mpx),
        ) {
            BrandLockup(markSize = 56)
            when (val s = state) {
                is SourceImporter.ImportState.Failed -> {
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
                            onClick = { vm.reset(); attempt++ },
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
                        if (account == null) stringResource(R.string.poterx_no_account) else stringResource(R.string.poterx_connecting),
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
