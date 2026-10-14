package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.ui.AppTheme
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen ringing screen, also over the lock screen (AFR-RNG-02). Never needs the network. */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme(dark = true) {
                val ring by app.engine.ringing.collectAsState()
                LaunchedEffect(ring) { if (ring == null) finish() }
                ring?.let { RingingScreen(it.label, Format.time(this, it.time), ::snooze, ::stop) }
            }
        }
    }

    private fun snooze() {
        RingingService.send(this, RingingService.ACTION_SNOOZE)
        finish()
    }

    private fun stop() {
        RingingService.send(this, RingingService.ACTION_DISMISS)
        // Stopping the alarm usually means getting up: offer to unlock.
        getSystemService(KeyguardManager::class.java)?.takeIf { it.isKeyguardLocked }?.requestDismissKeyguard(this, null)
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun RingingScreen(label: String, time: String, onSnooze: () -> Unit, onStop: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(time, fontSize = 96.sp, fontWeight = FontWeight.Light)
            Text(
                label.ifBlank { stringResource(R.string.alarm) },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(64.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text(stringResource(R.string.snooze), fontSize = 22.sp)
                }
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text(stringResource(R.string.stop), fontSize = 22.sp)
                }
            }
        }
    }
}
