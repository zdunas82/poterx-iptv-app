package tv.own.owntv.poterx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/** A requester that takes focus once the page is on screen (the wizard keeps its own copy private). */
@Composable
internal fun rememberLockedFirstFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return requester
}
