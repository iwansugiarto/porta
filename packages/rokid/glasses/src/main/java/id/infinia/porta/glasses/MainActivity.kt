package id.infinia.porta.glasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ServerState {
    IDLE,
    WAITING,
    CONNECTED,
    THINKING,
    ERROR
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PortaGlassesHUD"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val NAME = "PortaGlassesServer"
        private const val MAX_HISTORY = 20
        private const val DOUBLE_TAP_THRESHOLD_MS = 300L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI States
    private val serverState = mutableStateOf(ServerState.IDLE)
    private val titleText = mutableStateOf("Porta AI")
    private val bodyText = mutableStateOf("Ready. Waiting for connection from phone app...")
    private val statusLabel = mutableStateOf("Status: Offline")

    // Layout configuration controlled from companion app
    private val layoutAlignment = mutableStateOf("bottom") // "top", "center", "bottom"
    private val fontSizeScale = mutableStateOf("medium") // "small", "medium", "large"
    private val hudPadding = mutableStateOf(24) // padding in dp

    // Power management
    private var screenTimeoutSeconds = 30L  // seconds before dim (configurable from phone)
    private var screenSleepSeconds = 120L   // seconds before sleep (configurable from phone)
    private var idleTimerJob: Job? = null
    private val screenDimmed = mutableStateOf(false)
    private val screenAsleep = mutableStateOf(false)

    // Gesture scroll states
    private val isApprovalActive = mutableStateOf(false)
    private var composeScrollState: ScrollState? = null

    // Bottom toolbar states (updated by phone app via "update_toolbar")
    private val toolbarModel = mutableStateOf("")
    private val toolbarSteps = mutableStateOf(0)
    private val toolbarAgentRunning = mutableStateOf(false)
    private val toolbarToolAction = mutableStateOf<String?>(null)
    private val toolbarConversation = mutableStateOf("")
    data class SubagentEntry(val role: String, val count: Int)
    private val toolbarSubagents = mutableStateOf<List<SubagentEntry>>(emptyList())

    // Battery level
    private val batteryLevel = mutableStateOf(100)
    private var batteryReceiver: BroadcastReceiver? = null

    // Auto-reconnect
    private val reconnectAttempt = mutableStateOf(0)
    private val isReconnecting = mutableStateOf(false)

    // Message history
    private val messageHistory = mutableStateListOf<String>()
    private val historyIndex = mutableStateOf(-1) // -1 = live (latest)
    private val browsingHistory = mutableStateOf(false)

    // Double-tap detection
    private var lastEnterTapTime = 0L

    // Permission launcher for Android 12+
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            startBluetoothServer()
        } else {
            serverState.value = ServerState.ERROR
            bodyText.value = "Bluetooth permissions denied.\nCannot start SPP Server."
            statusLabel.value = "Permission Error"
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreen()
        
        // Clear translucent flags and force solid black window, status bar, and navigation bar
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Register battery broadcast receiver
        registerBatteryReceiver()
        
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color.Black,
                        surface = Color.Black,
                        primary = Color(0xFF818CF8),
                        onBackground = Color(0xFFF1F5F9)
                    )
                ) {
                    HUDContent()
                }
            }
        }

        // Hide system UI (status bar, navigation bar) safely after view attach
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }

        checkPermissionsAndStart()
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    batteryLevel.value = (level * 100) / scale
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun wakeScreen() {
        // Restore full brightness
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        screenDimmed.value = false
        screenAsleep.value = false

        // Keep screen on while app is in foreground
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Turn screen on (for lock screen/timed-out screen)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }

        // Force screen wake using PowerManager
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "PortaGlassesHUD::Wake"
                )
                wl.acquire(1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }

        // Reset idle timer
        resetIdleTimer()
    }

    private fun resetIdleTimer() {
        idleTimerJob?.cancel()
        idleTimerJob = scope.launch {
            // Phase 1: Wait, then dim
            delay(screenTimeoutSeconds * 1000)
            dimScreen()

            // Phase 2: Wait more, then sleep
            delay((screenSleepSeconds - screenTimeoutSeconds) * 1000)
            sleepScreen()
        }
    }

    private fun dimScreen() {
        Log.d(TAG, "Power: Dimming screen")
        screenDimmed.value = true
        val lp = window.attributes
        lp.screenBrightness = 0.01f  // Very dim but still visible
        window.attributes = lp
    }

    private fun sleepScreen() {
        Log.d(TAG, "Power: Sleeping screen")
        screenAsleep.value = true
        val lp = window.attributes
        lp.screenBrightness = 0.0f  // Screen off
        window.attributes = lp
        // Allow system to turn off screen
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Composable
    fun AmbientClockContent() {
        // Live clock for ambient mode
        var currentTime by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                currentTime = sdf.format(Date())
                delay(30_000)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = currentTime,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
                color = Color(0xFF334155) // Very dim color
            )
        }
    }

    @Composable
    fun HUDContent() {
        val isDimmed by screenDimmed
        val isAsleep by screenAsleep

        // Ambient clock mode: show minimal clock when dimmed
        if (isAsleep) {
            // Black screen — show nothing
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
            return
        }

        if (isDimmed) {
            AmbientClockContent()
            return
        }

        // Normal HUD content
        HUDMainContent()
    }

    @Composable
    fun HUDMainContent() {
        val state by serverState
        val title by titleText
        val body by bodyText
        val status by statusLabel
        var localListening by remember { mutableStateOf(false) }
        val isBrowsingHistory by browsingHistory
        val currentHistoryIndex by historyIndex

        val scrollState = rememberScrollState()
        composeScrollState = scrollState

        // Determine displayed text: live or from history
        val displayedBody = if (isBrowsingHistory && currentHistoryIndex >= 0 && currentHistoryIndex < messageHistory.size) {
            messageHistory[currentHistoryIndex]
        } else {
            body
        }

        LaunchedEffect(displayedBody) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        val align = layoutAlignment.value
        val sizeScale = fontSizeScale.value
        val paddingVal = hudPadding.value.dp

        val titleSize = when (sizeScale) {
            "tiny" -> 10.sp
            "xsmall" -> 12.sp
            "small" -> 14.sp
            "large" -> 26.sp
            else -> 18.sp
        }

        val bodySize = when (sizeScale) {
            "tiny" -> 8.sp
            "xsmall" -> 10.sp
            "small" -> 12.sp
            "large" -> 22.sp
            else -> 16.sp
        }

        val bodyLineHeight = when (sizeScale) {
            "tiny" -> 11.sp
            "xsmall" -> 14.sp
            "small" -> 18.sp
            "large" -> 32.sp
            else -> 24.sp
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingVal)
                .clickable {
                    if (state == ServerState.CONNECTED || state == ServerState.THINKING) {
                        localListening = !localListening
                        if (localListening) {
                            sendJsonToPhone("trigger_mic", emptyMap())
                            statusLabel.value = "Status: Listening (Mic Active)"
                        } else {
                            sendJsonToPhone("stop_mic", emptyMap())
                            statusLabel.value = "Status: Connected"
                        }
                    }
                }
        ) {
            // Content area — weight(1f) fills all space above toolbar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = when (align) {
                    "center" -> Alignment.Center
                    "top" -> Alignment.TopStart
                    else -> Alignment.BottomStart
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 20.dp) // Reserve space for steps overlay
                        .verticalScroll(scrollState)
                ) {
                    // History indicator
                    if (isBrowsingHistory) {
                        Text(
                            text = "📜 History (${currentHistoryIndex + 1}/${messageHistory.size})",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFBBF24),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    // Reconnecting indicator
                    val reconnecting by isReconnecting
                    val reconnectNum by reconnectAttempt
                    if (reconnecting && reconnectNum > 0) {
                        Text(
                            text = "Reconnecting... (attempt $reconnectNum)",
                            fontSize = bodySize,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFBBF24),
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (state == ServerState.THINKING) {
                        Text(
                            text = "⏳ Thinking...",
                            fontSize = bodySize,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24),
                            fontStyle = FontStyle.Italic
                        )
                    } else {
                        if (title.isNotBlank()) {
                            Text(
                                text = title,
                                fontSize = titleSize,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF818CF8),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Text(
                            text = displayedBody,
                            fontSize = bodySize,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFF1F5F9),
                            lineHeight = bodyLineHeight
                        )
                    }
                }

                // Steps overlay — vertical text on right edge
                val steps by toolbarSteps
                if (steps > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .graphicsLayer { rotationZ = 90f }
                    ) {
                        Text(
                            text = "$steps",
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light,
                            color = Color(0xFF334155),
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            // Toolbar — single child, always at bottom of Column
            ToolbarRow(state)
        }
    }

    @Composable
    fun ToolbarRow(state: ServerState) {
        val model by toolbarModel
        val steps by toolbarSteps
        val running by toolbarAgentRunning
        val toolAction by toolbarToolAction
        val subagents by toolbarSubagents
        val battery by batteryLevel

        // Live clock - updates every 30 seconds
        var currentTime by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                currentTime = sdf.format(Date())
                delay(30_000)
            }
        }

        // Pulse animation for connection dot
        val infiniteTransition = rememberInfiniteTransition(label = "dotPulse")
        val dotAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )

        // Battery display
        val batteryIcon = when {
            battery > 50 -> "🔋"
            battery <= 20 -> "🪫"
            else -> ""
        }
        val batteryDisplay = if (batteryIcon.isNotEmpty()) "$batteryIcon$battery%" else "$battery%"

        // Single wrapper Column — ensures toolbar is one solid block
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = Color(0xFF334155))

            // Subagent panel (shown when subagents are active)
            if (subagents.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔄",
                        fontSize = 10.sp
                    )
                    subagents.forEach { sa ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF34D399).copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            val label = if (sa.count > 1) "${sa.role} ×${sa.count}" else sa.role
                            Text(
                                text = label,
                                fontSize = 9.sp,
                                color = Color(0xFF34D399),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Main status row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: connection dot + clock + agent status
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotColor = when (state) {
                        ServerState.CONNECTED -> Color(0xFF34D399)
                        ServerState.THINKING -> Color(0xFFFBBF24)
                        ServerState.WAITING -> Color(0xFFFBBF24)
                        ServerState.ERROR -> Color(0xFFF87171)
                        ServerState.IDLE -> Color(0xFF64748B)
                    }
                    // Apply pulse animation only when connected or thinking
                    val effectiveAlpha = if (state == ServerState.CONNECTED || state == ServerState.THINKING) {
                        dotAlpha
                    } else {
                        1.0f
                    }
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(dotColor.copy(alpha = effectiveAlpha))
                    )
                    Text(
                        text = currentTime,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    if (running) {
                        val displayAction = toolAction ?: "Working"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF818CF8).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "⚡ $displayAction",
                                fontSize = 10.sp,
                                color = Color(0xFF818CF8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Right: model + steps + battery
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (model.isNotBlank()) {
                        Text(
                            text = model,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (steps > 0) {
                        Text(
                            text = "$steps steps",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                    Text(
                        text = batteryDisplay,
                        fontSize = 10.sp,
                        color = if (battery <= 20) Color(0xFFF87171) else Color(0xFF64748B)
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (bluetoothAdapter == null) {
            serverState.value = ServerState.ERROR
            bodyText.value = "Bluetooth is not supported on this device."
            statusLabel.value = "Bluetooth Unsupported"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasAdvertise = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            
            if (hasConnect && hasAdvertise) {
                startBluetoothServer()
            } else {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    )
                )
            }
        } else {
            startBluetoothServer()
        }
    }

    private fun startBluetoothServer() {
        if (serverState.value == ServerState.WAITING || serverState.value == ServerState.CONNECTED) return
        
        Log.i(TAG, "Starting Bluetooth SPP Server...")
        serverState.value = ServerState.WAITING
        if (!isReconnecting.value) {
            bodyText.value = "Waiting for connection from Porta phone app..."
        }
        statusLabel.value = "Status: Listening (SPP)"

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(NAME, SPP_UUID)
                    while (serverState.value == ServerState.WAITING) {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            handleClientConnection(socket)
                            break
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Server socket accept failed", e)
                    withContext(Dispatchers.Main) {
                        serverState.value = ServerState.ERROR
                        bodyText.value = "Server start failed: ${e.message}"
                        statusLabel.value = "Status: Error"
                    }
                }
            }
        }
    }

    private suspend fun handleClientConnection(socket: BluetoothSocket) {
        activeSocket = socket
        withContext(Dispatchers.Main) {
            serverState.value = ServerState.CONNECTED
            titleText.value = "Porta AI"
            bodyText.value = "Connected! Ready to stream responses."
            statusLabel.value = "Status: Connected"
            // Reset reconnect counter on successful connection
            reconnectAttempt.value = 0
            isReconnecting.value = false
            Log.i(TAG, "Client connected: ${socket.remoteDevice.name ?: "Unknown"}")
        }

        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (serverState.value == ServerState.CONNECTED || serverState.value == ServerState.THINKING) {
                    val line = reader.readLine() ?: break // Connection closed
                    Log.d(TAG, "Received message: $line")
                    
                    withContext(Dispatchers.Main) {
                        parseAndExecuteCommand(line)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Socket read error", e)
            } finally {
                closeConnection()
                // Auto-reconnect with exponential backoff
                withContext(Dispatchers.Main) {
                    isReconnecting.value = true
                    reconnectAttempt.value++
                    val attempt = reconnectAttempt.value
                    val delayMs = (1000L * (1L shl (attempt - 1).coerceAtMost(5)))
                        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                    Log.i(TAG, "Reconnecting attempt $attempt after ${delayMs}ms")
                    bodyText.value = "Reconnecting... (attempt $attempt)"
                }
                delay(
                    (1000L * (1L shl (reconnectAttempt.value - 1).coerceAtMost(5)))
                        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                )
                withContext(Dispatchers.Main) {
                    startBluetoothServer()
                }
            }
        }
    }

    private fun parseAndExecuteCommand(jsonStr: String) {
        try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val action = obj.get("action")?.asString ?: "display_text"
            
            when (action) {
                "display_text" -> {
                    runOnUiThread { wakeScreen() }
                    serverState.value = ServerState.CONNECTED
                    val title = obj.get("title")?.asString ?: "Porta AI"
                    val newBody = obj.get("body")?.asString ?: ""

                    // Append to message history before updating bodyText
                    if (newBody.isNotBlank()) {
                        messageHistory.add(newBody)
                        if (messageHistory.size > MAX_HISTORY) {
                            messageHistory.removeAt(0)
                        }
                    }

                    // Jump to latest (exit history browsing)
                    browsingHistory.value = false
                    historyIndex.value = -1

                    titleText.value = title
                    bodyText.value = newBody
                    
                    // Approval if title starts with standard emoji
                    isApprovalActive.value = title.startsWith("⚠️") || title.startsWith("❓")
                    
                    // Apply optional layout overrides sent in display_text
                    obj.get("alignment")?.asString?.let { layoutAlignment.value = it }
                    obj.get("font_size")?.asString?.let { fontSizeScale.value = it }
                    obj.get("padding")?.asInt?.let { hudPadding.value = it }
                }
                "update_layout" -> {
                    obj.get("alignment")?.asString?.let { layoutAlignment.value = it }
                    obj.get("font_size")?.asString?.let { fontSizeScale.value = it }
                    obj.get("padding")?.asInt?.let { hudPadding.value = it }
                }
                "show_thinking" -> {
                    runOnUiThread { wakeScreen() }
                    serverState.value = ServerState.THINKING
                    isApprovalActive.value = false
                    toolbarAgentRunning.value = true
                }
                "clear" -> {
                    serverState.value = ServerState.CONNECTED
                    titleText.value = "Porta AI"
                    bodyText.value = "Cleared. Waiting for message..."
                    isApprovalActive.value = false
                    toolbarAgentRunning.value = false
                    toolbarToolAction.value = null
                    // Don't wake on clear — let screen dim naturally
                }
                "set_power" -> {
                    obj.get("screen_timeout")?.asLong?.let { screenTimeoutSeconds = it.coerceIn(10, 300) }
                    obj.get("screen_sleep")?.asLong?.let { screenSleepSeconds = it.coerceIn(30, 600) }
                    Log.d(TAG, "Power config: dim=${screenTimeoutSeconds}s sleep=${screenSleepSeconds}s")
                    resetIdleTimer()
                }
                "update_toolbar" -> {
                    obj.get("model")?.asString?.let { toolbarModel.value = it }
                    obj.get("steps")?.asInt?.let { toolbarSteps.value = it }
                    obj.get("agent_running")?.asBoolean?.let { toolbarAgentRunning.value = it }
                    obj.get("tool_action")?.asString?.let { toolbarToolAction.value = it }
                    obj.get("conversation")?.asString?.let { toolbarConversation.value = it }
                    val saArr = obj.getAsJsonArray("subagents")
                    toolbarSubagents.value = if (saArr != null && saArr.size() > 0) {
                        saArr.mapNotNull { el ->
                            val saObj = el.asJsonObject ?: return@mapNotNull null
                            SubagentEntry(
                                role = saObj.get("role")?.asString ?: "Agent",
                                count = saObj.get("count")?.asInt ?: 1
                            )
                        }
                    } else emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command: $jsonStr", e)
        }
    }

    private fun closeConnection() {
        serverState.value = ServerState.IDLE
        try {
            activeSocket?.close()
        } catch (_: IOException) {}
        activeSocket = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any gesture wakes from dimmed/sleep state
        if (screenDimmed.value || screenAsleep.value) {
            wakeScreen()
            return true  // Consume gesture as wake trigger
        }
        // Normal interaction also resets idle timer
        resetIdleTimer()

        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            // Double-tap detection
            val now = System.currentTimeMillis()
            if (now - lastEnterTapTime < DOUBLE_TAP_THRESHOLD_MS) {
                // Double-tap detected — trigger mic
                Log.d(TAG, "Double-tap detected - triggering voice input on phone")
                sendJsonToPhone("trigger_mic", emptyMap())
                statusLabel.value = "Status: Listening (Mic Active)"
                lastEnterTapTime = 0L
                return true
            }
            lastEnterTapTime = now

            event?.startTracking()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (isApprovalActive.value) {
                Log.d(TAG, "Swipe forward/down detected - sending approve_action")
                sendJsonToPhone("approve_action", emptyMap())
            } else if (browsingHistory.value) {
                // Swipe down = forward in history (toward latest)
                val idx = historyIndex.value
                if (idx < messageHistory.size - 1) {
                    historyIndex.value = idx + 1
                } else {
                    // Exit history browsing, back to live
                    browsingHistory.value = false
                    historyIndex.value = -1
                }
            } else {
                Log.d(TAG, "Swipe forward/down detected - scrolling down")
                composeScrollState?.let { state ->
                    scope.launch {
                        state.animateScrollTo((state.value + 150).coerceAtMost(state.maxValue))
                    }
                }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (isApprovalActive.value) {
                Log.d(TAG, "Swipe backward/up detected - sending reject_action")
                sendJsonToPhone("reject_action", emptyMap())
            } else if (messageHistory.isNotEmpty()) {
                // Swipe up = back in history
                if (!browsingHistory.value) {
                    // Enter history browsing mode at the latest item
                    browsingHistory.value = true
                    historyIndex.value = messageHistory.size - 1
                } else {
                    val idx = historyIndex.value
                    if (idx > 0) {
                        historyIndex.value = idx - 1
                    }
                }
            } else {
                Log.d(TAG, "Swipe backward/up detected - scrolling up")
                composeScrollState?.let { state ->
                    scope.launch {
                        state.animateScrollTo((state.value - 150).coerceAtLeast(0))
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            Log.d(TAG, "Key long press detected - triggering voice input on phone")
            sendJsonToPhone("trigger_mic", emptyMap())
            statusLabel.value = "Status: Listening (Mic Active)"
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event != null && event.isTracking && !event.isCanceled) {
                Log.d(TAG, "Key click detected - stopping voice input")
                sendJsonToPhone("stop_mic", emptyMap())
                statusLabel.value = "Status: Connected"
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun sendJsonToPhone(action: String, data: Map<String, String>) {
        val socket = activeSocket ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val stream = socket.outputStream ?: return@launch
                val json = JsonObject().apply {
                    addProperty("action", action)
                    data.forEach { (k, v) -> addProperty(k, v) }
                }
                val payload = json.toString() + "\n"
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
                Log.d(TAG, "Sent back to phone: $payload")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send data back to phone", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister battery receiver
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
        closeConnection()
        scope.cancel()
    }
}
