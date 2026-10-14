package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.random.Random

private val NightText = Color(0xFF8A3B2E)

/**
 * Bedside clock (AFR-NGT-01): black, very dim, screen kept on, clock moves slightly every few
 * minutes against burn-in. A ringing alarm takes over; a tap closes the screen.
 */
@Composable
fun NightScreen(vm: MainViewModel, onExit: () -> Unit, onRinging: () -> Unit) {
    val context = LocalContext.current
    val ringing by vm.ringing.collectAsState()
    val next by vm.nextRing.collectAsState()
    var now by remember { mutableStateOf(LocalTime.now()) }
    var dx by remember { mutableIntStateOf(0) }
    var dy by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply { screenBrightness = 0.02f }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            now = LocalTime.now()
            if (++ticks % 180 == 0) {
                dx = Random.nextInt(-24, 25)
                dy = Random.nextInt(-40, 41)
            }
            delay(1_000)
        }
    }
    LaunchedEffect(ringing) { if (ringing != null) onRinging() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onExit),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.offset { IntOffset(dx.dp.roundToPx(), dy.dp.roundToPx()) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                Format.time(context, now.withSecond(0).withNano(0)),
                color = NightText,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                next?.let { stringResource(R.string.next_ring, Format.next(context, it.ringAt)) } ?: stringResource(R.string.no_next_alarm),
                color = NightText.copy(alpha = 0.7f),
                fontSize = 18.sp,
            )
        }
    }
}
