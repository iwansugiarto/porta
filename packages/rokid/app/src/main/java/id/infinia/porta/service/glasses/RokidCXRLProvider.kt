package id.infinia.porta.service.glasses

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rokid CXR-L SDK provider for AR glasses connectivity.
 *
 * Uses CUSTOMVIEW session mode to push JSON layouts to glasses display
 * via the Rokid AI Companion App — no separate glasses APK needed.
 *
 * SDK reference: com.rokid.cxr:client-l:1.0.1
 * Docs: https://custom.rokid.com/prod/rokid_web/.../bb449f86cdd84164969f0e0b013bcfae.html
 *
 * API Flow:
 *   1. AuthorizationHelper.authorize() → redirects to Rokid AI app → returns token
 *   2. CXRLink.connect(token, SessionType.CUSTOMVIEW)
 *   3. customViewOpen(layoutJson) → renders on glasses display
 *   4. customViewUpdate(layoutJson) → updates content
 *   5. startAudioStream() → PCM 16kHz from glasses mic
 *   6. takePhoto(w, h, quality) → JPEG from glasses camera
 */
class RokidCXRLProvider : GlassesProvider {

    companion object {
        private const val TAG = "RokidCXRL"
    }

    override val providerName = "Rokid CXR-L"

    private val _state = MutableStateFlow(GlassesState.DISCONNECTED)
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _capabilities = MutableStateFlow(GlassesCapabilities())
    override val capabilities: StateFlow<GlassesCapabilities> = _capabilities.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // CXR-L SDK handles (initialized on connect)
    // private var cxrLink: CXRLink? = null
    private var isSceneOpen = false

    override fun connect(context: Context) {
        if (_state.value == GlassesState.CONNECTING || _state.value == GlassesState.CONNECTED) return

        _state.value = GlassesState.CONNECTING
        _errorMessage.value = null
        Log.i(TAG, "Starting CXR-L authorization flow...")

        // TODO: When SDK is available, implement:
        // 1. AuthorizationHelper.authorize(context) → launch Rokid AI app
        // 2. Receive token via onActivityResult or deeplink callback
        // 3. CXRLink.connect(token, SessionType.CUSTOMVIEW, listener)

        // For now, report that SDK is not yet configured
        scope.launch {
            delay(500)
            _state.value = GlassesState.ERROR
            _errorMessage.value = "CXR-L SDK not yet configured. Download SDK from ar.rokid.com and add sn_auth_file to project."
            Log.w(TAG, "SDK not configured — falling back. Use MockGlassesProvider for development.")
        }
    }

    override fun disconnect() {
        // TODO: cxrLink?.disconnect()
        isSceneOpen = false
        _state.value = GlassesState.DISCONNECTED
        _capabilities.value = GlassesCapabilities()
        _errorMessage.value = null
        Log.i(TAG, "Disconnected")
    }

    override fun displayText(title: String, body: String) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            Log.w(TAG, "displayText: scene not active")
            return
        }

        // Build the CustomView JSON layout per CXR-L API
        val layoutJson = buildDisplayLayout(title, body)

        if (!isSceneOpen) {
            // First display → open scene
            // TODO: cxrLink?.customViewOpen(layoutJson)
            isSceneOpen = true
            Log.i(TAG, "customViewOpen: $title")
        } else {
            // Update existing scene
            // TODO: cxrLink?.customViewUpdate(layoutJson)
            Log.i(TAG, "customViewUpdate: $title")
        }
    }

    override fun clearDisplay() {
        if (isSceneOpen) {
            // TODO: cxrLink?.customViewClose()
            isSceneOpen = false
            Log.i(TAG, "customViewClose")
        }
    }

    override fun startAudioStream(callback: AudioStreamCallback) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            callback.onAudioError("Scene not active")
            return
        }

        // TODO: cxrLink?.startAudioStream(object : AudioStreamListener {
        //     override fun onAudioData(pcm: ByteArray) = callback.onAudioData(pcm)
        //     override fun onError(msg: String) = callback.onAudioError(msg)
        //     override fun onStopped() = callback.onAudioStopped()
        // })
        Log.i(TAG, "startAudioStream (not yet implemented)")
        callback.onAudioError("Audio stream not yet implemented — SDK pending")
    }

    override fun stopAudioStream() {
        // TODO: cxrLink?.stopAudioStream()
        Log.i(TAG, "stopAudioStream")
    }

    override fun takePhoto(width: Int, height: Int, quality: Int, callback: PhotoCallback) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            callback.onPhotoError("Scene not active")
            return
        }

        // TODO: cxrLink?.takePhoto(width, height, quality, object : PhotoListener {
        //     override fun onCaptured(jpeg: ByteArray, w: Int, h: Int) = callback.onPhotoCaptured(jpeg, w, h)
        //     override fun onError(msg: String) = callback.onPhotoError(msg)
        // })
        Log.i(TAG, "takePhoto (not yet implemented)")
        callback.onPhotoError("Photo capture not yet implemented — SDK pending")
    }

    override fun destroy() {
        disconnect()
        scope.cancel()
        Log.i(TAG, "Destroyed")
    }

    // ── Internal ──

    /**
     * Build a CXR-L CustomView JSON layout for text display.
     * Format follows Rokid's JSON template specification.
     */
    private fun buildDisplayLayout(title: String, body: String): String {
        // Escape JSON special characters
        val safeTitle = title.replace("\"", "\\\"").replace("\n", "\\n")
        val safeBody = body.replace("\"", "\\\"").replace("\n", "\\n")

        return """
        {
            "type": "vertical",
            "children": [
                {
                    "type": "text",
                    "text": "$safeTitle",
                    "style": {
                        "fontSize": 28,
                        "fontWeight": "bold",
                        "color": "#6366F1",
                        "marginBottom": 16
                    }
                },
                {
                    "type": "text",
                    "text": "$safeBody",
                    "style": {
                        "fontSize": 22,
                        "color": "#F1F5F9",
                        "lineHeight": 1.5
                    }
                }
            ],
            "style": {
                "padding": 24,
                "backgroundColor": "#0F172A"
            }
        }
        """.trimIndent()
    }

    // CXR-L SDK connection listener (to be wired when SDK is available)
    // private val cxrLinkListener = object : CXRLinkListener {
    //     override fun onCXRLinkConnected() {
    //         _state.value = GlassesState.CONNECTED
    //         // Open CustomView scene
    //         cxrLink?.customViewOpen(buildDisplayLayout("Porta", "Connected to glasses"))
    //         _state.value = GlassesState.SCENE_ACTIVE
    //         _capabilities.value = GlassesCapabilities(
    //             canDisplay = true,
    //             canCapturePhoto = true,
    //             canStreamAudio = true,
    //             canSendCommands = true
    //         )
    //     }
    //     override fun onCXRLinkDisconnected(reason: Int) {
    //         _state.value = GlassesState.DISCONNECTED
    //         _capabilities.value = GlassesCapabilities()
    //     }
    //     override fun onCXRLinkError(code: Int, message: String) {
    //         _state.value = GlassesState.ERROR
    //         _errorMessage.value = "CXR Error $code: $message"
    //     }
    // }
}
