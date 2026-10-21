package tv.own.owntv.poterx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.gradientWash
import tv.own.owntv.ui.theme.hudInk
import tv.own.owntv.ui.theme.hudShade
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Poterx: the channel banner along the bottom of the picture, the way a set-top box shows it. Drawn
 * for a few seconds after every zap (and after a direct tune) while the controls are hidden: the
 * channel's logo, number and name, what is on now with its progress, what comes next, and the clock.
 * Replaces OwnTV's top-left zap card on a locked build (PlayerHud's `zapBanner` slot).
 */
@Composable
fun PoterxZapBanner(
    title: String?,
    subtitle: String?,
    logoUrl: String?,
    epg: EpgNowNext?,
    modifier: Modifier = Modifier,
) {
    val formatTime = rememberSystemTimeFormatter()
    // Minute precision for the clock, the remaining time and the progress bar; a slow tick is plenty.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); nowMs = System.currentTimeMillis() } }
    val accent = stageAccent.accent

    Box(
        modifier
            .fillMaxWidth()
            .gradientWash(vertical = true, 0.0f to Color.Transparent, 0.3f to hudShade(0.72f), 1.0f to hudShade(0.88f))
            .padding(start = 44.dp, end = 44.dp, top = 56.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // The picon: on a card of its own so a transparent logo still reads on a bright picture.
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(hudShade(0.45f)).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    AsyncImage(model = logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text((title ?: "").take(3).uppercase(), style = stageText(28, 700), color = hudInk, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f)) {
                // Number ahead of the name, as on the remote's own display.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = stageText(30, 600), color = hudInk.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title ?: "", style = stageText(34, 600), color = hudInk, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val now = epg?.now
                val next = epg?.next
                if (now != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(stringResource(R.string.content_live_now), accent)
                        Text(now.title, style = stageText(26, 500), color = hudInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    val remaining = ((now.stopMs - nowMs) / 60_000L).toInt()
                    Text(
                        if (remaining in 1..600) stringResource(R.string.content_live_time_remaining, formatTime(now.stopMs), remaining)
                        else stringResource(R.string.content_live_time_range, formatTime(now.startMs), formatTime(now.stopMs)),
                        style = stageText(20, 500), color = hudInk.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val span = (now.stopMs - now.startMs).toFloat()
                    if (span > 0f) {
                        val progress = ((nowMs - now.startMs) / span).coerceIn(0f, 1f)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(hudInk.copy(alpha = 0.18f))) {
                            Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                        }
                    }
                }
                if (next != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(stringResource(R.string.content_live_next), hudInk.copy(alpha = 0.45f))
                        Text(
                            stringResource(R.string.content_live_time_range, formatTime(next.startMs), formatTime(next.stopMs)),
                            style = stageText(20, 500), color = hudInk.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(next.title, style = stageText(22, 500), color = hudInk.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            // The clock, big enough to read from the sofa.
            Text(formatTime(nowMs), style = stageText(40, 600), color = hudInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(min = 96.dp))
        }
    }
}

@Composable
private fun Label(text: String, color: Color) {
    Text(
        text.uppercase(),
        style = stageText(18, 600).copy(letterSpacing = 1.2.sp),
        color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
