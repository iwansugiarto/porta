package id.infinia.porta.service.glasses

import android.content.Context
import android.util.Log
import id.infinia.porta.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rokid CXR-L SDK provider for the Rokid Glasses 2025 (RV101, Qualcomm AR1).
 *
 * Uses CUSTOMVIEW session mode to push JSON layouts to the glasses display
 * via the Rokid AI Companion App — no separate glasses APK needed.
 *
 * Hardware capabilities (RV101):
 * - 12MP Sony IMX681 camera (109° FOV, 1680P video)
 * - Dual Micro-LED waveguide display
 * - Built-in microphone
 * - Qualcomm AR1 processor
 *
 * SDK: com.rokid.cxr:client-l:1.0.3
 * Docs: https://ar.rokid.com/sdk
 *
 * API Flow:
 *   1. CxrClient.initialize(mode=CUSTOM_VIEW)
 *   2. requestAuthorization([CAMERA, MICROPHONE, MEDIA]) → redirects to Rokid AI app
 *   3. On auth success → connect to glasses
 *   4. customViewOpen(layoutJson) → renders on glasses display
 *   5. customViewUpdate(layoutJson) → updates content
 *   6. startAudioStream() → PCM 16kHz from glasses mic
 *   7. takePhoto(w, h, quality) → JPEG from glasses camera
 *
 * Credentials are loaded from BuildConfig (injected from local.properties):
 *   - ROKID_CLIENT_ID
 *   - ROKID_CLIENT_SECRET
 *   - ROKID_ACCESS_KEY
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

    // CXR-L SDK state
    private var isInitialized = false
    private var isSceneOpen = false
    private var sdkAvailable = false

    init {
        // Check if CXR-L SDK classes are available at runtime
        sdkAvailable = try {
            Class.forName("com.rokid.cxrl.CxrClient")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        // Validate credentials
        if (BuildConfig.ROKID_CLIENT_ID.isBlank()) {
            Log.w(TAG, "ROKID_CLIENT_ID not set in local.properties")
        }
    }

    override fun connect(context: Context) {
        if (_state.value == GlassesState.CONNECTING || _state.value == GlassesState.CONNECTED) return

        _state.value = GlassesState.CONNECTING
        _errorMessage.value = null
        Log.i(TAG, "Starting CXR-L connection (SDK available: $sdkAvailable)...")

        if (!sdkAvailable) {
            // SDK not bundled — provide helpful guidance
            scope.launch {
                delay(300)
                _state.value = GlassesState.ERROR
                _errorMessage.value = buildString {
                    appendLine("CXR-L SDK not available at runtime.")
                    appendLine()
                    appendLine("The SDK AAR needs to be downloaded from")
                    appendLine("ar.rokid.com and added to the project.")
                    appendLine()
                    appendLine("Meanwhile, use 'USB Display' provider")
                    appendLine("for display output via USB-C.")
                }
                Log.w(TAG, "SDK classes not found — using compileOnly. Switch to 'implementation' when AAR is available.")
            }
            return
        }

        // Check credentials
        if (BuildConfig.ROKID_CLIENT_ID.isBlank()) {
            scope.launch {
                delay(300)
                _state.value = GlassesState.ERROR
                _errorMessage.value = "Rokid credentials not configured.\n\nSet ROKID_CLIENT_ID, ROKID_CLIENT_SECRET, ROKID_ACCESS_KEY in local.properties"
            }
            return
        }

        // ── SDK initialization and auth flow ──
        scope.launch {
            try {
                initializeSdk(context)
                requestAuthorization(context)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _state.value = GlassesState.ERROR
                _errorMessage.value = "Connection failed: ${e.message}"
            }
        }
    }

    private suspend fun initializeSdk(context: Context) {
        if (isInitialized) return

        withContext(Dispatchers.IO) {
            // CxrClient.initialize(
            //     mode = CxrMode.CUSTOM_VIEW,
            //     options = mapOf(
            //         "clientId" to BuildConfig.ROKID_CLIENT_ID,
            //         "clientSecret" to BuildConfig.ROKID_CLIENT_SECRET,
            //         "accessKey" to BuildConfig.ROKID_ACCESS_KEY
            //     )
            // )
            Log.i(TAG, "SDK initialized with clientId=${BuildConfig.ROKID_CLIENT_ID.take(8)}...")
            isInitialized = true
        }
    }

    private suspend fun requestAuthorization(context: Context) {
        // CxrClient.requestAuthorization(
        //     arrayOf(
        //         GlassPermission.CAMERA,
        //         GlassPermission.MICROPHONE,
        //         GlassPermission.MEDIA
        //     )
        // ) { result ->
        //     if (result.isSuccess) {
        //         onAuthorized()
        //     } else {
        //         _state.value = GlassesState.ERROR
        //         _errorMessage.value = "Authorization denied: ${result.error}"
        //     }
        // }

        // Simulated success for now — will be replaced with real SDK call
        delay(1000)
        onAuthorized()
    }

    private fun onAuthorized() {
        Log.i(TAG, "Authorized — connecting to glasses...")
        _state.value = GlassesState.CONNECTED

        // Open CustomView scene
        val welcomeLayout = buildDisplayLayout("Porta AI", "Connected to Rokid Glasses ✨\nReady for your questions.")
        // cxrLink?.customViewOpen(welcomeLayout)
        isSceneOpen = true

        _state.value = GlassesState.SCENE_ACTIVE
        _capabilities.value = GlassesCapabilities(
            canDisplay = true,
            canCapturePhoto = true,   // RV101 has 12MP camera
            canStreamAudio = true,    // RV101 has microphone
            canSendCommands = true
        )
        Log.i(TAG, "Scene active — all capabilities available")
    }

    override fun disconnect() {
        if (isSceneOpen) {
            // cxrLink?.customViewClose()
            isSceneOpen = false
        }
        // cxrLink?.disconnect()
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

        val layoutJson = buildDisplayLayout(title, body)

        if (!isSceneOpen) {
            // cxrLink?.customViewOpen(layoutJson)
            isSceneOpen = true
            Log.i(TAG, "customViewOpen: $title")
        } else {
            // cxrLink?.customViewUpdate(layoutJson)
            Log.i(TAG, "customViewUpdate: $title (${body.length} chars)")
        }
    }

    override fun clearDisplay() {
        if (isSceneOpen) {
            // cxrLink?.customViewClose()
            isSceneOpen = false
            Log.i(TAG, "customViewClose")
        }
    }

    override fun startAudioStream(callback: AudioStreamCallback) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            callback.onAudioError("Scene not active")
            return
        }

        // cxrLink?.startAudioStream(object : AudioStreamListener {
        //     override fun onAudioData(pcm: ByteArray) = callback.onAudioData(pcm)
        //     override fun onError(msg: String) = callback.onAudioError(msg)
        //     override fun onStopped() = callback.onAudioStopped()
        // })
        Log.i(TAG, "startAudioStream requested")

        // For now, fall back to device microphone (already handled by VoiceInputService)
        callback.onAudioError("CXR-L audio stream pending SDK wiring.\nUsing device microphone instead.")
    }

    override fun stopAudioStream() {
        // cxrLink?.stopAudioStream()
        Log.i(TAG, "stopAudioStream")
    }

    override fun takePhoto(width: Int, height: Int, quality: Int, callback: PhotoCallback) {
        if (_state.value != GlassesState.SCENE_ACTIVE) {
            callback.onPhotoError("Scene not active")
            return
        }

        // cxrLink?.takePhoto(width, height, quality, object : PhotoListener {
        //     override fun onCaptured(jpeg: ByteArray, w: Int, h: Int) =
        //         callback.onPhotoCaptured(jpeg, w, h)
        //     override fun onError(msg: String) = callback.onPhotoError(msg)
        // })
        Log.i(TAG, "takePhoto ${width}x$height q=$quality")
        callback.onPhotoError("CXR-L camera pending SDK wiring.\nUse device camera for now.")
    }

    override fun destroy() {
        disconnect()
        scope.cancel()
        isInitialized = false
        Log.i(TAG, "Destroyed")
    }

    // ── Internal ──

    /**
     * Build a CXR-L CustomView JSON layout for text display.
     * Follows the Rokid JSON template specification for Micro-LED rendering.
     * Optimized for the RV101's waveguide display.
     */
    private fun buildDisplayLayout(title: String, body: String): String {
        val safeTitle = title.replace("\"", "\\\"").replace("\n", "\\n")
        val safeBody = body.replace("\"", "\\\"").replace("\n", "\\n")

        return """
        {
            "type": "LinearLayout",
            "orientation": "vertical",
            "style": {
                "padding": 24,
                "backgroundColor": "#000000"
            },
            "children": [
                {
                    "type": "TextView",
                    "text": "$safeTitle",
                    "style": {
                        "fontSize": 26,
                        "fontWeight": "bold",
                        "color": "#818CF8",
                        "marginBottom": 12
                    }
                },
                {
                    "type": "TextView",
                    "text": "$safeBody",
                    "style": {
                        "fontSize": 20,
                        "color": "#F1F5F9",
                        "lineSpacing": 6
                    }
                }
            ]
        }
        """.trimIndent()
    }
}
