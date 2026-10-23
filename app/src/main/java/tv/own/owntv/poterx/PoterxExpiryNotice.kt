package tv.own.owntv.poterx

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.features.shell.components.TELEGRAM_LINK
import tv.own.owntv.ui.format.rememberBestDateFormatter
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import kotlin.math.ceil

/** Warn this many days before the panel's expiry date, and every day after it. */
const val POTERX_EXPIRY_WARN_DAYS = 7

/** Two Back presses this close together on a live channel toggle to the previous channel. */
const val POTERX_BACK_TWICE_MS = 400L
private const val EXPIRY_CHECK_DELAY_MS = 8_000L
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val PREFS = "poterx_expiry"
private const val KEY_SHOWN_DAY = "shown_day"

private class ExpiryNotice(val expiryMs: Long, val daysLeft: Int)

/**
 * Poterx: the "subscription ends soon" card. Shortly after start the panel is asked for the
 * line's expiry date; within [POTERX_EXPIRY_WARN_DAYS] of it (or past it) a card names the date,
 * the days left and how to renew: the provider's contact line and the Telegram QR code. Shown at
 * most once a day, and only when nothing is playing fullscreen ([canShow]) — a card found while a
 * channel is up waits for the player to close.
 */
@Composable
fun PoterxExpiryNotice(canShow: Boolean) {
    if (!LockedSource.enabled) return
    val context = LocalContext.current
    val sourceDao = koinInject<SourceDao>()
    val xtream = koinInject<XtreamClient>()
    var notice by remember { mutableStateOf<ExpiryNotice?>(null) }
    LaunchedEffect(Unit) {
        delay(EXPIRY_CHECK_DELAY_MS)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / DAY_MS
        if (prefs.getLong(KEY_SHOWN_DAY, -1L) == today) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            val source = sourceDao.observeAll().first().firstOrNull { it.type == SourceType.XTREAM }
                ?: return@withContext null
            runCatching { xtream.accountExpiryMs(source) }.getOrNull()
        } ?: return@LaunchedEffect
        val remaining = found - System.currentTimeMillis()
        val daysLeft = if (remaining <= 0L) 0 else ceil(remaining / DAY_MS.toDouble()).toInt()
        if (daysLeft > POTERX_EXPIRY_WARN_DAYS) return@LaunchedEffect
        prefs.edit().putLong(KEY_SHOWN_DAY, today).apply()
        notice = ExpiryNotice(found, daysLeft)
    }
    val current = notice ?: return
    if (!canShow) return
    ExpiryPopup(current, onDismiss = { notice = null })
}

@Composable
private fun ExpiryPopup(notice: ExpiryNotice, onDismiss: () -> Unit) {
    val formatDate = rememberBestDateFormatter("yMMMd")
    val date = formatDate(notice.expiryMs)
    val expired = notice.daysLeft <= 0
    val title = if (expired) stringResource(R.string.poterx_expiry_expired_title)
    else pluralStringResource(R.plurals.poterx_expiry_days_title, notice.daysLeft, notice.daysLeft)
    val body = if (expired) stringResource(R.string.poterx_expiry_expired_body, date)
    else stringResource(R.string.poterx_expiry_body, date)
    val contact = LockedSource.account?.contact.orEmpty()
    val accent = stageAccent.accent
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    StagePopup(
        onDismiss = onDismiss,
        title = title,
        body = body,
        eyebrow = stringResource(R.string.poterx_account_title),
        width = 820.mpx,
        buttons = {
            StageButton(
                stringResource(R.string.common_ok), onClick = onDismiss, height = 56.mpx, textSize = 19, tinted = true,
                modifier = Modifier.focusRequester(first),
            )
        },
    ) {
        Row(
            Modifier.padding(top = 18.mpx),
            horizontalArrangement = Arrangement.spacedBy(22.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.telegram_qr),
                contentDescription = stringResource(R.string.settings_telegram_qr),
                modifier = Modifier.size(170.mpx).clip(RoundedCornerShape(14.mpx)).background(Color.White),
            )
            Column {
                Text(stringResource(R.string.poterx_expiry_renew), style = stageText(20, 800), color = StageColors.Text)
                Text(TELEGRAM_LINK, style = stageText(17, 500), color = accent, modifier = Modifier.padding(top = 4.mpx))
                Text(stringResource(R.string.more_about_scan), style = stageText(15.5f, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 8.mpx))
                if (contact.isNotBlank()) {
                    Text(stringResource(R.string.poterx_contact_line, contact), style = stageText(16, 500), color = StageColors.Text, modifier = Modifier.padding(top = 10.mpx))
                }
            }
        }
    }
}
