package id.infinia.porta.service.glasses

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * GlassesProvider that connects directly to the Rokid Glasses via Bluetooth Serial (SPP/RFCOMM).
 * Bypasses the Rokid CXR-L SDK. Requires the Porta Glasses HUD receiver app to be running on the glasses.
 */
class BluetoothSerialGlassesProvider(
    private val onSendChat: ((String) -> Unit)? = null,
    private val onStartVoice: (() -> Unit)? = null,
    private val onStopVoice: (() -> Unit)? = null,
    private val getLayoutSettings: (() -> Triple<String, String, Int>)? = null,
    private val onApproveAction: (() -> Unit)? = null,
    private val onRejectAction: (() -> Unit)? = null
) : GlassesProvider {

    companion object {
        private const val TAG = "BTSerialGlasses"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override val providerName = "Bluetooth Serial (Direct)"

    private val _state = MutableStateFlow(GlassesState.DISCONNECTED)
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _capabilities = MutableStateFlow(GlassesCapabilities())
    override val capabilities: StateFlow<GlassesCapabilities> = _capabilities.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectionJob: Job? = null

    private var appContext: Context? = null
    private var userWantsConnection = false
    private var reconnectJob: Job? = null

    private var _connectionLost = false
    val connectionLost: Boolean get() = _connectionLost

    override fun connect(context: Context) {
        appContext = context.applicationContext
        userWantsConnection = true
        startConnectionAttempt()
    }

    private fun startConnectionAttempt() {
        if (_state.value == GlassesState.CONNECTED || _state.value == GlassesState.SCENE_ACTIVE) return

        connectionJob?.cancel()
        reconnectJob?.cancel()

        _state.value = GlassesState.CONNECTING
        _errorMessage.value = null
        Log.i(TAG, "Starting Bluetooth Serial connection...")

        // Find paired Rokid device
        val context = appContext
        if (context == null) {
            Log.w(TAG, "appContext is null, returning")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _state.value = GlassesState.ERROR
            _errorMessage.value = "Bluetooth is not supported on this device."
            Log.w(TAG, "bluetoothAdapter is null, returning")
            return
        }

        if (!adapter.isEnabled) {
            _state.value = GlassesState.ERROR
            _errorMessage.value = "Bluetooth is turned off. Please turn it on and try again."
            Log.w(TAG, "bluetoothAdapter is disabled, returning")
            scheduleReconnect()
            return
        }

        // Check permission (on Android 12+, bluetooth permission must be granted at runtime)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                _state.value = GlassesState.ERROR
                _errorMessage.value = "Bluetooth connect permission not granted."
                Log.w(TAG, "BLUETOOTH_CONNECT permission NOT granted, returning")
                return
            }
        }


        // Find paired Rokid device
        Log.i(TAG, "Getting bonded devices...")
        val pairedDevices = try {
            val devices = adapter.bondedDevices
            Log.i(TAG, "Total bonded devices: ${devices?.size ?: 0}")
            devices?.forEach { device ->
                Log.i(TAG, "Bonded device: name='${device.name}' addr='${device.address}'")
            }
            devices
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting bonded devices", e)
            _state.value = GlassesState.ERROR
            _errorMessage.value = "Security Exception: Bluetooth connect permission missing."
            return
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting bonded devices", e)
            _state.value = GlassesState.ERROR
            _errorMessage.value = "Error: ${e.message}"
            return
        }

        if (pairedDevices == null || pairedDevices.isEmpty()) {
            _state.value = GlassesState.ERROR
            _errorMessage.value = "No paired Bluetooth devices found on this phone."
            Log.w(TAG, "Bonded devices list is empty or null")
            scheduleReconnect()
            return
        }

        val rokidDevice = pairedDevices.firstOrNull { device ->
            val name = device.name ?: ""
            name.contains("Rokid", ignoreCase = true) || name.contains("Glasses", ignoreCase = true)
        }

        if (rokidDevice == null) {
            _state.value = GlassesState.ERROR
            _errorMessage.value = buildString {
                appendLine("No paired Rokid glasses found.")
                appendLine()
                appendLine("Bonded devices checked: ${pairedDevices.size}")
                appendLine("Ensure device name contains 'Rokid' or 'Glasses'.")
            }
            Log.w(TAG, "No paired device found containing 'Rokid' or 'Glasses'")
            scheduleReconnect()
            return
        }



        Log.i(TAG, "Found paired Rokid device: ${rokidDevice.name} (${rokidDevice.address})")
        connectToDevice(rokidDevice)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        connectionJob = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    closeSocket() // Always close previous socket cleanup first
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                    outputStream = socket?.outputStream

                    val currentSocket = socket
                    if (currentSocket != null) {
                        listenForIncomingData(currentSocket)
                    }

                    withContext(Dispatchers.Main) {
                        _connectionLost = false
                        _state.value = GlassesState.CONNECTED
                        delay(200) // Brief delay
                        _capabilities.value = GlassesCapabilities(
                            canDisplay = true,
                            canCapturePhoto = false,
                            canStreamAudio = false,
                            canSendCommands = true
                        )
                        Log.i(TAG, "Bluetooth Serial socket connected successfully!")
                        
                        // Synchronize layout configuration
                        getLayoutSettings?.invoke()?.let { (align, fontSize, padding) ->
                            sendJsonCommand("update_layout", mapOf(
                                "alignment" to align,
                                "font_size" to fontSize,
                                "padding" to padding.toString()
                            ))
                        }

                        // Send welcome message
                        sendJsonCommand("display_text", mapOf(
                            "title" to "Porta AI",
                            "body" to "Connected via Bluetooth Serial! ✨\nReady for your questions."
                        ))

                        // Set SCENE_ACTIVE after sending the welcome message to let VM update screen if needed
                        _state.value = GlassesState.SCENE_ACTIVE
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Socket connection failed", e)
                    withContext(Dispatchers.Main) {
                        _state.value = GlassesState.ERROR
                        _errorMessage.value = "Failed to connect to ${device.name}. Retrying..."
                        closeSocket()
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!userWantsConnection) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000) // Retry connecting every 5 seconds
            Log.d(TAG, "Auto-reconnecting to glasses...")
            startConnectionAttempt()
        }
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        userWantsConnection = false
        connectionJob?.cancel()
        connectionJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        sendJsonCommand("clear", emptyMap())
        closeSocket()
        _state.value = GlassesState.DISCONNECTED
        _capabilities.value = GlassesCapabilities()
        _errorMessage.value = null
    }

    fun reconnect() {
        val context = appContext ?: return
        if (_state.value == GlassesState.CONNECTED || _state.value == GlassesState.SCENE_ACTIVE) return
        userWantsConnection = true
        _connectionLost = false
        startConnectionAttempt()
    }

    override fun displayText(title: String, body: String) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            Log.w(TAG, "displayText: connection not active")
            return
        }
        sendJsonCommand("display_text", mapOf(
            "title" to title,
            "body" to body
        ))
    }

    fun updateLayout(alignment: String, fontSize: String, padding: Int) {
        if (_state.value == GlassesState.SCENE_ACTIVE) {
            sendJsonCommand("update_layout", mapOf(
                "alignment" to alignment,
                "font_size" to fontSize,
                "padding" to padding.toString()
            ))
        }
    }

    fun sendPowerConfig(dimTimeout: Int, sleepTimeout: Int) {
        if (_state.value == GlassesState.SCENE_ACTIVE) {
            sendJsonCommand("set_power", mapOf(
                "screen_timeout" to dimTimeout.toString(),
                "screen_sleep" to sleepTimeout.toString()
            ))
        }
    }

    override fun clearDisplay() {
        if (_state.value == GlassesState.SCENE_ACTIVE) {
            sendJsonCommand("clear", emptyMap())
        }
    }

    fun updateToolbar(
        model: String? = null,
        steps: Int? = null,
        agentRunning: Boolean? = null,
        toolAction: String? = null,
        conversation: String? = null,
        subagents: List<Pair<String, Int>>? = null  // List of (role, count)
    ) {
        if (_state.value != GlassesState.SCENE_ACTIVE) return
        val json = JsonObject().apply {
            addProperty("action", "update_toolbar")
            model?.let { addProperty("model", it) }
            steps?.let { addProperty("steps", it) }
            agentRunning?.let { addProperty("agent_running", it) }
            toolAction?.let { addProperty("tool_action", it) }
            conversation?.let { addProperty("conversation", it) }
            subagents?.let { list ->
                val arr = com.google.gson.JsonArray()
                list.forEach { (role, count) ->
                    val sa = JsonObject().apply {
                        addProperty("role", role)
                        addProperty("count", count)
                    }
                    arr.add(sa)
                }
                add("subagents", arr)
            }
        }
        sendJsonObject(json)
    }

    override fun startAudioStream(callback: AudioStreamCallback) {
        // Direct serial doesn't support mic streaming without receiver-side audio coding.
        // Fall back to phone microphone.
        callback.onAudioError(
            "Audio capture is not supported in Direct Serial mode.\n" +
            "Using phone microphone instead."
        )
    }

    override fun stopAudioStream() {
        // No-op
    }

    override fun takePhoto(width: Int, height: Int, quality: Int, callback: PhotoCallback) {
        // Direct serial doesn't support camera capture without receiver-side JPEG transmission.
        // Fall back to phone camera.
        callback.onPhotoError(
            "Camera capture is not supported in Direct Serial mode.\n" +
            "Using phone camera instead."
        )
    }

    override fun destroy() {
        disconnect()
        scope.cancel()
    }

    // ── Helper ──

    private fun sendJsonObject(json: JsonObject) {
        val stream = outputStream ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val payload = json.toString() + "\n"
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
                Log.d(TAG, "Sent JSON: $payload")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send command", e)
                withContext(Dispatchers.Main) {
                    _connectionLost = true
                    _state.value = GlassesState.ERROR
                    _errorMessage.value = "Connection lost: ${e.message}"
                    closeSocket()
                    scheduleReconnect()
                }
            }
        }
    }

    private fun sendJsonCommand(action: String, data: Map<String, String>) {
        val json = JsonObject().apply {
            addProperty("action", action)
            data.forEach { (key, value) -> addProperty(key, value) }
        }
        sendJsonObject(json)
    }

    private fun closeSocket() {
        try {
            outputStream?.close()
        } catch (_: IOException) {}
        outputStream = null
        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null
    }

    private fun listenForIncomingData(socket: BluetoothSocket) {
        scope.launch(Dispatchers.IO) {
            try {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.inputStream))
                while (isActive) {
                    val line = reader.readLine() ?: break // Socket disconnected
                    Log.d(TAG, "Received from glasses: $line")
                    withContext(Dispatchers.Main) {
                        handleIncomingCommand(line)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error in read loop", e)
            } finally {
                withContext(Dispatchers.Main) {
                    if (userWantsConnection) {
                        Log.w(TAG, "Connection lost, scheduling reconnect...")
                        _state.value = GlassesState.ERROR
                        _errorMessage.value = "Connection lost. Reconnecting..."
                        closeSocket()
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun handleIncomingCommand(jsonStr: String) {
        try {
            val obj = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            val action = obj.get("action")?.asString ?: ""
            when (action) {
                "send_chat" -> {
                    val text = obj.get("text")?.asString ?: ""
                    if (text.isNotBlank()) {
                        onSendChat?.invoke(text)
                    }
                }
                "trigger_mic" -> {
                    onStartVoice?.invoke()
                }
                "stop_mic" -> {
                    onStopVoice?.invoke()
                }
                "approve_action" -> {
                    onApproveAction?.invoke()
                }
                "reject_action" -> {
                    onRejectAction?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming glasses command", e)
        }
    }
}
