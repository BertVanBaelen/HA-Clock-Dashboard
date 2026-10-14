package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.SetupLinks
import alarmclock.vanbaelen.dev.core.SetupUrls
import alarmclock.vanbaelen.dev.core.UrlException
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Connect to the dashboard (AFR-SET-01): values from the setup deep link (panel button or QR
 * code scanned with the camera app) or typed in, then HA login in a Custom Tab (AFR-LOG-01).
 */
@Composable
fun SetupScreen(vm: MainViewModel, onLoggedIn: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val account by vm.account.collectAsState()
    val pending by vm.pendingSetup.collectAsState()
    val loggingIn by vm.loggingIn.collectAsState()
    var ha by rememberSaveable { mutableStateOf(account.haUrl ?: "") }
    var api by rememberSaveable { mutableStateOf(account.apiUrl ?: "") }
    var haError by remember { mutableStateOf<String?>(null) }
    var apiError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val noBrowser = stringResource(R.string.no_browser)
    val scope = rememberCoroutineScope()

    LaunchedEffect(pending) {
        pending?.let {
            ha = it.haUrl
            api = it.apiUrl
            haError = null
            apiError = null
            vm.pendingSetup.value = null
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    fun check(value: String): Pair<String?, String?> = SetupLinks.validate(value).fold(
        { it to null },
        { null to Format.urlError(context, (it as UrlException).error) },
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = ha,
                onValueChange = { ha = it; haError = null },
                label = { Text(stringResource(R.string.ha_url)) },
                placeholder = { Text("https://homeassistant.example.com") },
                isError = haError != null,
                supportingText = haError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = api,
                onValueChange = { api = it; apiError = null },
                label = { Text(stringResource(R.string.api_url)) },
                placeholder = { Text("https://alarms.example.com") },
                isError = apiError != null,
                supportingText = { Text(apiError ?: stringResource(R.string.api_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !loggingIn,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val (haUrl, haErr) = check(ha)
                    val (apiUrl, apiErr) = check(api)
                    haError = haErr
                    apiError = apiErr
                    if (haUrl == null || apiUrl == null) return@Button
                    ha = haUrl
                    api = apiUrl
                    scope.launch {
                        val uri = vm.startLogin(SetupUrls(haUrl, apiUrl))
                        try {
                            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
                        } catch (e: ActivityNotFoundException) {
                            snackbar.showSnackbar(noBrowser)
                        }
                    }
                },
            ) {
                if (loggingIn) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.login))
            }
            Text(stringResource(R.string.setup_login_hint), style = MaterialTheme.typography.bodySmall)
            if (account.loggedIn) {
                androidx.compose.material3.TextButton(onClick = { if (account.loggedIn) onLoggedIn() else onBack() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
