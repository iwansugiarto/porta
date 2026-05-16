package id.infinia.porta.service.glasses

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mock glasses provider for development and testing without hardware.
 * Simulates connection, display, audio, and photo operations with delays.
 */
class MockGlassesProvider : GlassesProvider {

    companion object {
        private const val TAG = "MockGlasses"
    }

    override val providerName = "Mock (Development)"

    private val _state = MutableStateFlow(GlassesState.DISCONNECTED)
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _capabilities = MutableStateFlow(GlassesCapabilities())
    override val capabilities: StateFlow<GlassesCapabilities> = _capabilities.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var audioJob: Job? = null

    // Track displayed content for debugging
    private var lastDisplayTitle: String? = null
    private var lastDisplayBody: String? = null

    override fun connect(context: Context) {
        if (_state.value == GlassesState.CONNECTING || _state.value == GlassesState.CONNECTED) return

        _state.value = GlassesState.CONNECTING
        _errorMessage.value = null
        Log.i(TAG, "Simulating connection...")

        scope.launch {
            delay(1500) // Simulate connection time
            _state.value = GlassesState.CONNECTED
            Log.i(TAG, "Connected (mock)")

            delay(500) // Simulate scene setup
            _state.value = GlassesState.SCENE_ACTIVE
            _capabilities.value = GlassesCapabilities(
                canDisplay = true,
                canCapturePhoto = true,
                canStreamAudio = true,
                canSendCommands = false
            )
            Log.i(TAG, "Scene active — all capabilities available")
        }
    }

    override fun disconnect() {
        audioJob?.cancel()
        _state.value = GlassesState.DISCONNECTED
        _capabilities.value = GlassesCapabilities()
        _errorMessage.value = null
        lastDisplayTitle = null
        lastDisplayBody = null
        Log.i(TAG, "Disconnected (mock)")
    }

    override fun displayText(title: String, body: String) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            Log.w(TAG, "displayText called but scene not active (state=${_state.value})")
            return
        }
        lastDisplayTitle = title
        lastDisplayBody = body
        val preview = if (body.length > 80) body.take(80) + "…" else body
        Log.i(TAG, "📺 Display → [$title] $preview")
    }

    override fun clearDisplay() {
        lastDisplayTitle = null
        lastDisplayBody = null
        Log.i(TAG, "📺 Display cleared")
    }

    override fun startAudioStream(callback: AudioStreamCallback) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            callback.onAudioError("Scene not active")
            return
        }

        Log.i(TAG, "🎤 Audio stream started (mock — generating silence)")
        audioJob = scope.launch {
            // Generate 100ms chunks of silence at 16kHz 16-bit mono
            val chunkSize = 16000 * 2 / 10  // 100ms
            val silence = ByteArray(chunkSize)
            repeat(100) { // 10 seconds of mock audio
                if (!isActive) return@launch
                delay(100)
                callback.onAudioData(silence)
            }
            callback.onAudioStopped()
        }
    }

    override fun stopAudioStream() {
        audioJob?.cancel()
        audioJob = null
        Log.i(TAG, "🎤 Audio stream stopped")
    }

    override fun takePhoto(width: Int, height: Int, quality: Int, callback: PhotoCallback) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            callback.onPhotoError("Scene not active")
            return
        }

        Log.i(TAG, "📷 Photo capture requested (${width}x${height} q=$quality)")
        scope.launch {
            delay(500) // Simulate capture delay
            // Return a tiny 1x1 JPEG as placeholder
            val minimalJpeg = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte()
            )
            callback.onPhotoCaptured(minimalJpeg, 1, 1)
            Log.i(TAG, "📷 Photo captured (mock placeholder)")
        }
    }

    override fun destroy() {
        scope.cancel()
        _state.value = GlassesState.DISCONNECTED
        Log.i(TAG, "Destroyed")
    }
}
