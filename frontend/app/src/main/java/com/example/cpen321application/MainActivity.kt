package com.example.cpen321application

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private enum class Screen { HOME, LOGIN, LIVE, TIMER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CPEN321ApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding)) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.HOME) }

    BackHandler(screen != Screen.HOME) {
        screen = Screen.HOME;
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        if (screen != Screen.HOME) {
            TextButton(
                onClick = {screen = Screen.HOME}
            ) {
                Text("Go back to Home Page");
            }
        }

        when (screen) {
            Screen.HOME -> HomeScreen(
                onLogin = { screen = Screen.LOGIN },
                onLive = { screen = Screen.LIVE },
                onTimer = {screen = Screen.TIMER }
            )
            // LOGIN, LIVE, TIMER screens directly call their associated functions
            Screen.LOGIN -> LoginScreen()
            Screen.LIVE -> LiveScreen()
            Screen.TIMER -> TimerScreen()
        }
    }
}

/** HOME */
/** Calls the provided functions on button click */
@Composable
fun HomeScreen(
    onLogin: () -> Unit,
    onLive: () -> Unit,
    onTimer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onLogin) { Text("Login + Server") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onLive) { Text("Live Updates") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onTimer) { Text("Timer") }
    }
}

/** HELPERS */
private val httpClient = OkHttpClient()

// WebSocket client: no read timeout, OkHttp pings keep the connection alive
private val wsClient = httpClient.newBuilder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

/** hh:mm:ss GMT+hh:mm (24-hour format, device timezone) */
private fun getClientTime(): String {
    val now = ZonedDateTime.now()
    val offset = now.offset.totalSeconds
    val sign = if (offset >= 0) "+" else "-"
    return "%02d:%02d:%02d GMT%s%02d:%02d".format(
        now.hour, now.minute, now.second, sign, abs(offset) / 3600, abs(offset) % 3600 / 60
    )
}

/** Gets client device IP address */
private fun getClientIp(): String = try {
    /** 1) filters for actively running and non loopback network interfaces
     *  2) Collects IP addresses
     *  3) Filters out loopback and link local address
     *  4) Get first IPv4 address, if null, then just provide first address,
     *     with "unavailable" as final fallback */
    val addrs = Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter{ it.isUp && !it.isLoopback }
        .flatMap{ Collections.list(it.inetAddresses) }
        .filter{ !it.isLoopbackAddress && !it.isLinkLocalAddress }
    (addrs.firstOrNull { it is Inet4Address } ?: addrs.first()).hostAddress ?: "unavailable"
} catch (e: Exception) {
    "unavailable"
}

/** Helper for getting JSON response from backend */
private fun getJson(path: String): JSONObject {
    val url = BuildConfig.API_BASE_URL.trimEnd('/') + path
    httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code} from $url")
        return JSONObject(response.body?.string() ?: error("Empty response from $url"))
    }
}

// ** LOGIN */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("Not logged in")}
    var loading by remember { mutableStateOf(false)}

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Button for signing in with Google
        Button(
            enabled = !loading,
            onClick = {
                loading = true
                scope.launch {
                    message = try {
                        signInAndLoadInfo(context)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    loading = false
                }
            }
        ) {
            Text(if (loading) "Signing in now..." else "Sign in with Google")
        }

        Spacer(Modifier.height(18.dp))

        // Server ip, client ip, etc.
        Text(message)
    }
}

/** Actual logic for signing in with Google */
private suspend fun signInAndLoadInfo(context: Context): String {
    // Google sign-in
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential

    // Checks if credentials are specifically Google provided credentials, error if not
    if (credential !is CustomCredential ||
        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        error("Google login failed")
    }

    // Defaults to empty string if given field is null, if both first and last are empty, then fallsback
    // to Google user
    val google = GoogleIdTokenCredential.createFrom(credential.data)
    val googleName = "${google.givenName ?: ""}, ${google.familyName ?: ""}".trim()
        .ifEmpty{ google.displayName ?: "Google user"}

    // Backend call
    return withContext(Dispatchers.IO) {
        val serverIp = getJson("/api/server-ip").getString("ip")
        val serverTime = getJson("/api/server-time").getString("time")
        val clientTime = getClientTime()
        val developerName = getJson("/api/developer-name")

        listOf(
            "Server IP: $serverIp",
            "Client IP: ${getClientIp()}",
            "Server time: $serverTime",
            "Client time: $clientTime",
            "My name: ${developerName.getString("firstName")}, ${developerName.getString("lastName")}",
            "Name of logged in user: $googleName"
        ).joinToString("\n\n")
    }
}

//* LIVE Updates */
private const val GRID = 16
@Composable
fun LiveScreen() {
    return // TODO: complete
}


//* TIMER */
@Composable
fun TimerScreen() {
    return // TODO: complete
}

//@Composable
//fun Greeting(apiBaseUrl: String, modifier: Modifier = Modifier) {
//    var statusText by remember { mutableStateOf("Checking backend at $apiBaseUrl/health...") }
//
//    LaunchedEffect(apiBaseUrl) {
//        statusText = fetchHealthStatus(apiBaseUrl)
//    }
//
//    Text(
//        text = statusText,
//        modifier = modifier
//    )
//}

private suspend fun fetchHealthStatus(apiBaseUrl: String): String = withContext(Dispatchers.IO) {
    val healthUrl = "${apiBaseUrl.trimEnd('/')}/health"
    try {
        val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
        }

        when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                "Backend healthy ($healthUrl): $body"
            }
            else -> {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                "Backend error ($healthUrl): HTTP $code${errorBody?.let { " — $it" } ?: ""}"
            }
        }
    } catch (e: Exception) {
        "Backend unreachable ($healthUrl): ${e.message ?: e.javaClass.simpleName}"
    }
}