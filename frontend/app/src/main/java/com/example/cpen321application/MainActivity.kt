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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
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
private enum class TimerPhase { IDLE, RUNNING, LOADING, DONE }

private data class TimerOutcome(val basis: String, val symbol: String, val gain: Double, val percent: Double)

/** Sends the timer's start/end (epoch ms) to the backend, which works out the stock gain/loss */
private fun fetchTimerOutcome(startMs: Long, endMs: Long): TimerOutcome {
    val json = getJson("/api/timer-result?start=$startMs&end=$endMs")
    return TimerOutcome(
        json.getString("basis"),
        json.getString("symbol"),
        json.getDouble("gain"),
        json.getDouble("percent")
    )
}

private val PICKER_ROW_HEIGHT = 44.dp

/** One scrollable column of two-digit numbers 00..(count-1)
 *  Shows three rows; the middle one (inside the highlight band) is the selected value. */
@Composable
private fun NumberPicker(
    label: String,
    count: Int,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowPx = with(LocalDensity.current) { PICKER_ROW_HEIGHT.toPx() }
    // The list is [blank, 0, 1, ..., count-1, blank], so with 3 visible rows the value in the
    // middle row is simply the index of the first (top) visible item.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value)

    // Whenever scrolling stops, snap to the nearest row and report it
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .filter { !it }
            .collect {
                val nearest = state.firstVisibleItemIndex +
                    if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
                val row = nearest.coerceIn(0, count - 1)
                onValueChange(row)
                try {
                    state.animateScrollToItem(row)
                } catch (e: CancellationException) {
                    ensureActive() // only swallow it if the user grabbed the list mid-snap
                }
            }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(
            modifier = Modifier.height(PICKER_ROW_HEIGHT * 3),
            contentAlignment = Alignment.Center
        ) {
            // Highlight band behind the selected (middle) row
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PICKER_ROW_HEIGHT)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            )
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
                item { Spacer(Modifier.height(PICKER_ROW_HEIGHT)) }
                items(count) { i ->
                    Box(
                        Modifier.fillMaxWidth().height(PICKER_ROW_HEIGHT),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("%02d".format(i), style = MaterialTheme.typography.headlineSmall)
                    }
                }
                item { Spacer(Modifier.height(PICKER_ROW_HEIGHT)) }
            }
        }
    }
}

@Composable
fun TimerScreen() {
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(0) }
    var seconds by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf(TimerPhase.IDLE) }
    var startedAt by remember { mutableStateOf(0L) } // epoch ms, recorded when Start is pressed
    var durationMs by remember { mutableStateOf(0L) }
    var remainingMs by remember { mutableStateOf(0L) }
    var outcome by remember { mutableStateOf<TimerOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val inputMs = ((hours * 60L + minutes) * 60 + seconds) * 1_000

    // Runs once per Start press (startedAt changes). Only the frontend tracks the countdown;
    // the backend is contacted once, at the end, with the two recorded timestamps.
    LaunchedEffect(startedAt) {
        if (phase != TimerPhase.RUNNING) return@LaunchedEffect
        val target = startedAt + durationMs

        // Recompute from the wall clock every tick so a delayed tick can't make the timer drift
        while (true) {
            remainingMs = target - System.currentTimeMillis()
            if (remainingMs <= 0) break
            delay(200)
        }

        val endedAt = System.currentTimeMillis()
        phase = TimerPhase.LOADING
        try {
            outcome = withContext(Dispatchers.IO) { fetchTimerOutcome(startedAt, endedAt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't load stock results: ${e.message}"
        }
        phase = TimerPhase.DONE
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (phase) {
            TimerPhase.IDLE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker("hours", 24, hours, { hours = it }, Modifier.weight(1f))
                    NumberPicker("min", 60, minutes, { minutes = it }, Modifier.weight(1f))
                    NumberPicker("sec", 60, seconds, { seconds = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = inputMs > 0,
                    onClick = {
                        durationMs = inputMs
                        remainingMs = inputMs
                        outcome = null
                        error = null
                        startedAt = System.currentTimeMillis()
                        phase = TimerPhase.RUNNING
                    }
                ) { Text("Start timer") }
            }

            TimerPhase.RUNNING -> {
                val totalSec = (remainingMs + 999) / 1000 // round up so it never shows 00:00 early
                val h = totalSec / 3600
                val m = totalSec % 3600 / 60
                val s = totalSec % 60
                Text(
                    if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                    style = MaterialTheme.typography.displayLarge
                )
            }

            TimerPhase.LOADING -> Text("Time's up! Checking what \$1M would have done...")

            TimerPhase.DONE -> {
                val result = outcome
                if (result == null) {
                    Text(error ?: "Something went wrong")
                } else {
                    Text("Time's up! If you had put \$1M into ${result.symbol}:")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${"%+,.2f".format(result.gain)} USD (${"%+.2f".format(result.percent)}%)",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (result.gain >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(result.basis)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { phase = TimerPhase.IDLE }) { Text("New timer") }
            }
        }
    }
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