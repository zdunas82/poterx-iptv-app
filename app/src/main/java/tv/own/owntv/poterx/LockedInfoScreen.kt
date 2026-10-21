package tv.own.owntv.poterx

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Poterx: what Settings shows in place of Playlists, Backup and Local sync on a locked build. Each of
 * those could reveal, edit or copy the built-in account, so they are replaced by this one page.
 */
@Composable
fun LockedInfoScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onBack() }
    val first = rememberLockedFirstFocus()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(900.mpx).padding(32.mpx),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.mpx),
        ) {
            Text(
                stringResource(R.string.poterx_locked_title),
                style = stageText(30, 700), color = StageColors.Text, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.poterx_locked_body, stringResource(R.string.app_name)),
                style = stageText(20, 500), color = StageColors.Muted, textAlign = TextAlign.Center,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
            StageButton(stringResource(R.string.common_back), onClick = onBack, tinted = true, modifier = Modifier.focusRequester(first))
        }
    }
}
