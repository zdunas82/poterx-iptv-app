package tv.own.owntv.poterx

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.gradientWash
import tv.own.owntv.ui.theme.hudInk
import tv.own.owntv.ui.theme.hudShade
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Poterx: the playing channel's own programme guide over the picture, opened with a long press of OK.
 * Along the bottom: the channel (logo, number, name, clock), a strip of its programmes from the
 * archive window to two days ahead with "now" lit, and the selected programme's description.
 *
 * Keys: ◀ ▶ move one programme, long ◀ ▶ keep moving, OK on an aired programme plays it from the
 * archive (channels with catch-up), OK on the one on air returns to live, Back closes. ▲ ▼ close as
 * well, so the next press zaps as usual.
 */
@Composable
fun PoterxChannelGuide(
    channel: ChannelEntity,
    programmes: List<EpgProgrammeEntity>,
    onPlayArchive: (EpgProgrammeEntity) -> Unit,
    onGoLive: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatTime = rememberSystemTimeFormatter()
    val formatDay = rememberBestDateFormatter("EEEd")
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowMs = System.currentTimeMillis() } }
    val accent = stageAccent.accent
    val archiveWindowMs = remember(channel) {
        if (!channel.catchup) 0L else (channel.catchupDays.takeIf { it > 0 } ?: 7) * 24L * 3600_000L
    }
    val nowIndex = remember(programmes, nowMs) {
        programmes.indexOfFirst { nowMs in it.startMs until it.stopMs }
            .takeIf { it >= 0 } ?: programmes.indexOfFirst { it.startMs > nowMs }.coerceAtLeast(0)
    }
    var selected by remember(programmes) { mutableIntStateOf(nowIndex) }
    // The guide opens while OK is still held (a long press), so the remote keeps sending repeats of
    // that same press, and its release lands here too. None of it may count: only a press that began
    // after the guide opened acts (the key event's down time is on the same uptime clock).
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(selected, programmes.size) {
        if (programmes.isNotEmpty()) listState.animateScrollToItem((selected - 1).coerceAtLeast(0))
    }
    BackHandler { onDismiss() }

    fun canPlay(p: EpgProgrammeEntity): Boolean =
        p.startMs <= nowMs && archiveWindowMs > 0L && p.startMs >= nowMs - archiveWindowMs

    Box(
        modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key != Key.Back
                when (e.key) {
                    Key.DirectionLeft -> { if (selected > 0) selected--; true }
                    Key.DirectionRight -> { if (selected < programmes.lastIndex) selected++; true }
                    Key.DirectionUp, Key.DirectionDown -> { onDismiss(); false }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // The opening press (still held) and its repeats are swallowed.
                        val native = e.nativeKeyEvent
                        if (native.repeatCount > 0 || native.downTime < openedAt) return@onPreviewKeyEvent true
                        val p = programmes.getOrNull(selected)
                        when {
                            p == null -> onDismiss()
                            nowMs in p.startMs until p.stopMs -> onGoLive()
                            canPlay(p) -> onPlayArchive(p)
                        }
                        true
                    }
                    Key.Back, Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .gradientWash(vertical = true, 0.0f to Color.Transparent, 0.25f to hudShade(0.78f), 1.0f to hudShade(0.92f))
                .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The channel line.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(hudShade(0.45f)).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!channel.logoUrl.isNullOrBlank()) AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                    else Text(channel.name.take(3).uppercase(), style = stageText(22, 700), color = hudInk, fontWeight = FontWeight.Bold)
                }
                channel.number?.let {
                    Text(it.toString(), style = stageText(26, 600), color = hudInk.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(channel.name, style = stageText(30, 600), color = hudInk, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Text(formatTime(nowMs), style = stageText(32, 600), color = hudInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (programmes.isEmpty()) {
                Text(stringResource(R.string.content_epg_no_programme), style = stageText(22, 500), color = hudInk.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(programmes, key = { _, p -> p.id }) { index, p ->
                        val isNow = nowMs in p.startMs until p.stopMs
                        val isSelected = index == selected
                        val past = p.stopMs <= nowMs
                        val playable = canPlay(p)
                        val fill = when {
                            isSelected -> accent.copy(alpha = 0.92f)
                            isNow -> accent.copy(alpha = 0.28f)
                            else -> hudShade(0.5f)
                        }
                        val ink = if (isSelected) Color(0xFF0B1F3A) else hudInk.copy(alpha = if (past && !playable) 0.5f else 1f)
                        Column(
                            Modifier.width(300.dp).height(104.dp).clip(RoundedCornerShape(12.dp)).background(fill).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.content_live_time_range, formatTime(p.startMs), formatTime(p.stopMs)),
                                    style = stageText(18, 600), color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (!sameDay(p.startMs, nowMs)) {
                                    Text(formatDay(p.startMs), style = stageText(16, 600), color = ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.weight(1f))
                                when {
                                    isNow -> Tag(stringResource(R.string.content_live_now), if (isSelected) ink else accent)
                                    playable -> Tag(stringResource(R.string.content_catchup), ink.copy(alpha = 0.8f))
                                }
                            }
                            Text(p.title, style = stageText(22, 600), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (isNow) {
                                val span = (p.stopMs - p.startMs).toFloat()
                                val progress = if (span > 0f) ((nowMs - p.startMs) / span).coerceIn(0f, 1f) else 0f
                                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = 0.2f))) {
                                    Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink))
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
                programmes.getOrNull(selected)?.let { p ->
                    val desc = p.description?.takeIf { it.isNotBlank() }
                    if (desc != null) {
                        Text(desc, style = stageText(19, 400), color = hudInk.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    val hint = when {
                        nowMs in p.startMs until p.stopMs -> stringResource(R.string.poterx_guide_hint_live)
                        canPlay(p) -> stringResource(R.string.poterx_guide_hint_archive)
                        else -> stringResource(R.string.poterx_guide_hint_browse)
                    }
                    Text(hint, style = stageText(16, 600).copy(letterSpacing = 0.6.sp), color = hudInk.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text.uppercase(),
        style = stageText(14, 700).copy(letterSpacing = 1.0.sp),
        color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

private fun sameDay(aMs: Long, bMs: Long): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    val a = java.time.Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate()
    val b = java.time.Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()
    return a == b
}
