package id.infinia.porta.glasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
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
    private val layoutAlignment = mutableStateOf("top") // "top", "center", "bottom"
    private val fontSizeScale = mutableStateOf("medium") // "small", "medium", "large"
    private val hudPadding = mutableStateOf(24) // padding in dp

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
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0A0A0F),
                    surface = Color(0xFF16161F),
                    primary = Color(0xFF818CF8),
                    onBackground = Color(0xFFF1F5F9)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HUDContent()
                }
            }
        }

        checkPermissionsAndStart()
    }

    @Composable
    fun HUDContent() {
        val state by serverState
        val title by titleText
        val body by bodyText
        val status by statusLabel
        var localListening by remember { mutableStateOf(false) }

        val align = layoutAlignment.value
        val sizeScale = fontSizeScale.value
        val paddingVal = hudPadding.value.dp

        val titleSize = when (sizeScale) {
            "small" -> 18.sp
            "large" -> 32.sp
            else -> 26.sp
        }

        val bodySize = when (sizeScale) {
            "small" -> 16.sp
            "large" -> 28.sp
            else -> 22.sp
        }

        val bodyLineHeight = when (sizeScale) {
            "small" -> 24.sp
            "large" -> 40.sp
            else -> 32.sp
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
            // Status bar (Top)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = status,
                    fontSize = 14.sp,
                    color = when (state) {
                        ServerState.CONNECTED -> Color(0xFF34D399) // Success Green
                        ServerState.WAITING -> Color(0xFFFBBF24)   // Warning Orange
                        ServerState.THINKING -> Color(0xFFFBBF24)
                        ServerState.ERROR -> Color(0xFFF87171)      // Error Red
                        ServerState.IDLE -> Color(0xFF64748B)       // Slate
                    },
                    fontWeight = FontWeight.Medium
                )
                if (state == ServerState.THINKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            // Divider
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color(0xFF1E293B)
            )

            // Content Area (Scrollable with custom Alignment)
            Box(
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(
                            when (align) {
                                "center" -> Alignment.Center
                                "bottom" -> Alignment.BottomStart
                                else -> Alignment.TopStart
                            }
                        )
                        .verticalScroll(rememberScrollState())
                ) {
                    if (state == ServerState.THINKING) {
                        Text(
                            text = "⏳ Thinking...",
                            fontSize = bodySize,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24),
                            fontStyle = FontStyle.Italic
                        )
                    } else {
                        if (title.isNotBlank()) {
                            Text(
                                text = title,
                                fontSize = titleSize,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF818CF8),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Text(
                            text = body,
                            fontSize = bodySize,
                            color = Color(0xFFF1F5F9),
                            lineHeight = bodyLineHeight
                        )
                    }
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
        bodyText.value = "Waiting for connection from Porta phone app..."
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
                // Restart listening
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
                    serverState.value = ServerState.CONNECTED
                    titleText.value = obj.get("title")?.asString ?: "Porta AI"
                    bodyText.value = obj.get("body")?.asString ?: ""
                    
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
                    serverState.value = ServerState.THINKING
                }
                "clear" -> {
                    serverState.value = ServerState.CONNECTED
                    titleText.value = "Porta AI"
                    bodyText.value = "Cleared. Waiting for message..."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command: $jsonStr", e)
        }
    }

    private fun closeConnection() {
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
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            event?.startTracking()
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
        closeConnection()
        scope.cancel()
    }
}
