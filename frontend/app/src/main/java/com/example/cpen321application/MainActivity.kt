package com.example.cpen321application

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.socket.client.IO
import io.socket.client.Socket
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
private const val NEW_IMAGE_GAP_MS = 5000L
private val BLANK_CELL = Color.White

private data class Pixel(val x: Int, val y: Int, val color: Color)

/** Parses {"x":<int>,"y":<int>,"color":"<hex>"} into custom Pixel class
 *  Returns null if malformed or out of range */
private fun parsePixel(message: String): Pixel? = try {
    val json = JSONObject(message)
    val x = json.getInt("x")
    val y = json.getInt("y")
    // Makes sure hex values are in standardized form, with # in front
    val hex = json.getString("color")
        .let{ if (it.startsWith("#")) it else "#$it" }
    // Validates coordinates
    if (x in 0 until GRID && y in 0 until GRID) {
        Pixel(x, y, Color(android.graphics.Color.parseColor(hex)))
    } else null
} catch (e: Exception) {
    null
}

@Composable
fun LiveScreen() {
    val grid = remember {
        mutableStateListOf<Color>().apply{ repeat(GRID * GRID){ add(BLANK_CELL) } }
    }
    var status by remember { mutableStateOf("Connecting...") }

    // Starts when the screen opens; leaving the screen disposes it and closes the socket
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        var lastPixelAt = 0L

        val options = IO.Options().apply{ transports = arrayOf("websocket") } // no polling
        val socket = IO.socket(BuildConfig.API_BASE_URL.trimEnd('/'), options)

        // Fires on the first connect and on every automatic reconnect
        socket.on(Socket.EVENT_CONNECT) {
            main.post {
                for (i in grid.indices) grid[i] = BLANK_CELL
                lastPixelAt = 0L
                status = "Pixel Art:"
            }
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            main.post { status = "Disconnected. Reconnecting..." }
        }
        socket.on("pixel") { args ->
            val message = args.firstOrNull() as? String ?: return@on
            main.post {
                val pixel = parsePixel(message) ?: return@post

                // A long silence means the previous image finished: a new one is starting
                val now = SystemClock.elapsedRealtime()
                if (now - lastPixelAt > NEW_IMAGE_GAP_MS) {
                    for (i in grid.indices) grid[i] = BLANK_CELL
                }
                lastPixelAt = now

                grid[pixel.y * GRID + pixel.x] = pixel.color
            }
        }
        socket.connect()

        onDispose {
            socket.off()
            socket.disconnect()
        }
    }

    // The visual grid and filling in grid rectangles as stream comes through
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(status)
        Spacer(Modifier.height(16.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Color.Black)) {
            val cell = size.width / GRID
            for (y in 0 until GRID) {
                for (x in 0 until GRID) {
                    drawRect(
                        color = grid[y * GRID + x],
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }
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