package id.infinia.porta.service.glasses

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Connection state for AR glasses.
 */
enum class GlassesState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SCENE_ACTIVE,   // CustomView or CustomApp scene is open
    ERROR
}

/**
 * Capabilities available once a glasses session is active.
 */
data class GlassesCapabilities(
    val canDisplay: Boolean = false,
    val canCapturePhoto: Boolean = false,
    val canStreamAudio: Boolean = false,
    val canSendCommands: Boolean = false
)

/**
 * Callback for receiving audio PCM data from glasses microphone.
 */
interface AudioStreamCallback {
    /** 16kHz, 16-bit mono PCM data */
    fun onAudioData(pcmData: ByteArray)
    fun onAudioError(error: String)
    fun onAudioStopped()
}

/**
 * Callback for photo capture from glasses camera.
 */
interface PhotoCallback {
    fun onPhotoCaptured(jpegData: ByteArray, width: Int, height: Int)
    fun onPhotoError(error: String)
}

/**
 * Vendor-agnostic interface for AR glasses connectivity.
 *
 * Implementations:
 * - [MockGlassesProvider] — for development/testing without hardware
 * - [RokidCXRLProvider] — Rokid CXR-L SDK integration
 *
 * Future vendors can add their own implementations (Xreal, Viture, etc.)
 */
interface GlassesProvider {

    /** Human-readable name of this provider (e.g. "Rokid CXR-L", "Mock") */
    val providerName: String

    /** Current connection state */
    val state: StateFlow<GlassesState>

    /** Error message if state == ERROR */
    val errorMessage: StateFlow<String?>

    /** Available capabilities (updated when scene becomes active) */
    val capabilities: StateFlow<GlassesCapabilities>

    // ── Connection ──

    /** Initiate connection to glasses. May launch an external auth flow. */
    fun connect(context: Context)

    /** Disconnect from glasses and release resources. */
    fun disconnect()

    // ── Display ──

    /**
     * Display text content on the glasses.
     * @param title  Short title line
     * @param body   Main content text
     */
    fun displayText(title: String, body: String)

    /**
     * Clear the glasses display.
     */
    fun clearDisplay()

    // ── Audio ──

    /**
     * Start streaming audio from the glasses microphone.
     * Audio arrives as 16kHz 16-bit mono PCM via the callback.
     */
    fun startAudioStream(callback: AudioStreamCallback)

    /** Stop the audio stream. */
    fun stopAudioStream()

    // ── Photo ──

    /**
     * Capture a photo from the glasses camera.
     * @param width    Desired width (hardware may adjust)
     * @param height   Desired height
     * @param quality  JPEG quality 1-100
     */
    fun takePhoto(width: Int = 1920, height: Int = 1080, quality: Int = 85, callback: PhotoCallback)

    // ── Lifecycle ──

    /** Release all resources. Called when the provider is no longer needed. */
    fun destroy()
}
