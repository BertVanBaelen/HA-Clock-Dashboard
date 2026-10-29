package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.ui.AppTheme
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

/**
 * Full-screen ringing screen, also over the lock screen (AFR-RNG-02): the wake-up screen with its
 * sunrise, the snoozed state and the good-morning screen ([WakeScreen]). Ringing never needs the
 * network or credential-protected storage.
 */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme(dark = true) {
                WakeScreen(
                    onSnooze = { RingingService.send(this, RingingService.ACTION_SNOOZE) },
                    onStop = { RingingService.send(this, RingingService.ACTION_DISMISS) },
                    onBrightness = ::setBrightness,
                    onLightBars = ::setLightBars,
                    onClose = ::finish,
                )
            }
        }
    }

    /** Window brightness 0..1, or [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] for the system's. */
    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun setLightBars(light: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
