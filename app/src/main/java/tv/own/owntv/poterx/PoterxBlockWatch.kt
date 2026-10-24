package tv.own.owntv.poterx

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.poterx.vpn.PoterxVpn
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.theme.mpx

private const val FIRST_CHECK_DELAY_MS = 12_000L
private const val RECHECK_EVERY_MS = 15L * 60 * 1000
/** A block is acted on only when a second look, this much later, finds it still there. */
private const val CONFIRM_AFTER_MS = 20_000L
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val PREFS = "poterx_block"
private const val KEY_DECLINED_DAY = "declined_day"

/**
 * Poterx: an ISP block that starts after the customer signed in. The channels simply stop, and
 * nothing on screen says why. While the built-in VPN is off, the saved playlist's panel is asked
 * with its own account shortly after start and then every quarter of an hour
 * ([PoterxReachability.check]); only a panel that gives no answer at all has its network looked
 * at. A block found, and found again twenty seconds later:
 *
 * - with the system's VPN consent already given: the VPN is turned on at once (the switch stays
 *   on, as if the customer had done it) and a card says what happened;
 * - without it: a card offers the switch; Android's own consent screen follows. "Not now" keeps the
 *   card away for the rest of the day.
 *
 * A build without the provider's VPN account has nothing to offer, so it does nothing here.
 */
@Composable
fun PoterxBlockWatch(canShow: Boolean) {
    if (!LockedSource.enabled) return
    val vpn: PoterxVpn = koinInject()
    if (!vpn.builtIn) return
    val reach: PoterxReachability = koinInject()
    val sourceDao: SourceDao = koinInject()
    val context = LocalContext.current
    val autoConnect by vpn.autoConnect.collectAsStateWithLifecycle()
    var offer by remember { mutableStateOf<PoterxReachability.Block?>(null) }
    var turnedOn by remember { mutableStateOf(false) }
    var declined by remember { mutableStateOf(false) }

    LaunchedEffect(autoConnect, declined) {
        if (autoConnect || declined) return@LaunchedEffect
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        delay(FIRST_CHECK_DELAY_MS)
        while (true) {
            val today = System.currentTimeMillis() / DAY_MS
            if (prefs.getLong(KEY_DECLINED_DAY, -1L) != today) {
                val source = withContext(Dispatchers.IO) {
                    sourceDao.observeAll().first().firstOrNull { it.type == SourceType.XTREAM }
                } ?: return@LaunchedEffect
                var verdict = reach.check(source)
                if (verdict is PoterxReachability.Verdict.Blocked) {
                    delay(CONFIRM_AFTER_MS)
                    verdict = reach.check(source)
                }
                if (verdict is PoterxReachability.Verdict.Blocked) {
                    if (vpn.prepareIntent() == null) {
                        vpn.enable()
                        turnedOn = true
                    } else {
                        offer = verdict.how
                    }
                    return@LaunchedEffect
                }
            }
            delay(RECHECK_EVERY_MS)
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { vpn.enable(); turnedOn = true }
        offer = null
    }
    val decline = {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DECLINED_DAY, System.currentTimeMillis() / DAY_MS).apply()
        offer = null
        declined = true
    }

    if (!canShow) return
    val first = remember { FocusRequester() }
    offer?.let { how ->
        LaunchedEffect(how) { delay(60); runCatching { first.requestFocus() } }
        StagePopup(
            onDismiss = decline,
            title = stringResource(R.string.poterx_blocked_title),
            body = blockText(how),
            eyebrow = stringResource(R.string.settings_vpn),
            width = 820.mpx,
            buttons = {
                StageButton(
                    stringResource(R.string.poterx_blocked_vpn_on),
                    onClick = { val intent = vpn.prepareIntent(); if (intent != null) consent.launch(intent) else { vpn.enable(); turnedOn = true; offer = null } },
                    height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(first),
                )
                StageButton(stringResource(R.string.poterx_blocked_not_now), onClick = decline, height = 56.mpx, textSize = 19)
            },
        )
    }
    if (turnedOn) {
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        StagePopup(
            onDismiss = { turnedOn = false },
            title = stringResource(R.string.poterx_blocked_vpn_enabled_title),
            body = stringResource(R.string.poterx_blocked_vpn_enabled_body),
            eyebrow = stringResource(R.string.settings_vpn),
            width = 820.mpx,
            buttons = {
                StageButton(
                    stringResource(R.string.common_ok), onClick = { turnedOn = false },
                    height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(first),
                )
            },
        )
    }
}
