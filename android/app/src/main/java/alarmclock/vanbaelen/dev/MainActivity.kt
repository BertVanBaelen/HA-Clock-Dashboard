package alarmclock.vanbaelen.dev

import alarmclock.vanbaelen.dev.alarm.RingingActivity
import alarmclock.vanbaelen.dev.core.NightWindow
import alarmclock.vanbaelen.dev.ui.AppTheme
import alarmclock.vanbaelen.dev.ui.MainViewModel
import alarmclock.vanbaelen.dev.ui.screens.AlarmListScreen
import alarmclock.vanbaelen.dev.ui.screens.ConflictScreen
import alarmclock.vanbaelen.dev.ui.screens.HealthScreen
import alarmclock.vanbaelen.dev.ui.screens.NightScreen
import alarmclock.vanbaelen.dev.ui.screens.SettingsScreen
import alarmclock.vanbaelen.dev.ui.screens.SetupScreen
import alarmclock.vanbaelen.dev.ui.screens.readBattery
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.time.LocalTime

object Routes {
    const val SETUP = "setup"
    const val GUIDE = "guide"
    const val LIST = "list"
    const val CONFLICT = "conflict/{seq}"
    const val SETTINGS = "settings"
    const val HEALTH = "health"
    const val NIGHT = "night"

    fun conflict(seq: Long) = "conflict/$seq"
}

/** The single activity: setup / login deep links, alarm list, editor, settings, night display. */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var nav: NavHostController? = null
    private var pendingIntent: Intent? = null

    /** The phone stands on its long side (landscape), as on a nightstand. */
    private var onSide = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (onSide) maybeStartNight()
        }
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val side = degrees in 60..120 || degrees in 240..300
                if (side && !onSide) maybeStartNight()
                onSide = side
            }
        }
    }

    /** Start by itself (AFR-NGT-01): charging, turned on its side, inside the time window, with the app open. */
    private fun maybeStartNight() {
        val s = vm.settings.value
        if (!s.nightAutoStart || !vm.account.value.loggedIn) return
        if (!readBattery(this, null).plugged) return
        val start = runCatching { LocalTime.parse(s.nightStart) }.getOrNull() ?: return
        val end = runCatching { LocalTime.parse(s.nightEnd) }.getOrNull() ?: return
        if (NightWindow.contains(LocalTime.now(), start, end)) {
            nav?.navigate(Routes.NIGHT) { launchSingleTop = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntent = if (savedInstanceState == null) intent else null
        val start = when {
            !vm.account.value.loggedIn -> Routes.SETUP
            !vm.settings.value.guideDone -> Routes.GUIDE
            else -> Routes.LIST
        }
        setContent {
            AppTheme {
                val controller = rememberNavController()
                nav = controller
                LaunchedEffect(Unit) { pendingIntent?.let(::handleIntent); pendingIntent = null }
                NavHost(controller, startDestination = start) {
                    composable(Routes.SETUP) { SetupScreen(vm, onLoggedIn = ::afterLogin, onBack = { controller.popBackStack() }) }
                    composable(Routes.GUIDE) {
                        HealthScreen(guide = true, onDone = {
                            vm.completeGuide()
                            controller.navigate(Routes.LIST) { popUpTo(0) }
                        })
                    }
                    composable(Routes.LIST) { AlarmListScreen(vm, controller) }
                    composable(Routes.CONFLICT, arguments = listOf(navArgument("seq") { type = NavType.LongType })) { entry ->
                        ConflictScreen(entry.arguments?.getLong("seq") ?: 0L, onDone = { controller.popBackStack() })
                    }
                    composable(Routes.SETTINGS) { SettingsScreen(vm, controller) }
                    composable(Routes.HEALTH) { HealthScreen(guide = false, onDone = { controller.popBackStack() }) }
                    composable(Routes.NIGHT) {
                        NightScreen(
                            vm,
                            onExit = { controller.popBackStack() },
                            onRinging = { startActivity(RingingActivity.intent(this@MainActivity)) },
                            onSettings = { controller.navigate(Routes.SETTINGS) },
                        )
                    }
                }
            }
        }
    }

    private fun afterLogin() {
        val route = if (vm.settings.value.guideDone) Routes.LIST else Routes.GUIDE
        nav?.navigate(route) { popUpTo(0) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "alarmactions") return
        when (data.host) {
            "setup" -> if (vm.handleSetupLink(data)) nav?.navigate(Routes.SETUP) { launchSingleTop = true }
            "auth-callback" -> vm.completeLogin(data) { ok -> if (ok) afterLogin() }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // onSide is kept while paused: coming back to a phone that still lies on its side starts nothing.
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        vm.sync()
    }

    override fun onPause() {
        unregisterReceiver(powerReceiver)
        orientationListener.disable()
        super.onPause()
    }
}
