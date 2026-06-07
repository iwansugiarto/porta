package id.infinia.porta.service.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.Display
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GlassesProvider for USB-connected Rokid glasses using Android's Presentation API.
 *
 * Works with all Rokid models that support USB-C display output (DP Alt Mode):
 * - Rokid Glasses 2025 (RV101, AR1) — primary target
 * - Rokid Max / Max 2 — display-only
 * - Rokid Air — display-only
 *
 * Detection:
 *   1. USB device attach/detach via BroadcastReceiver (Vendor ID 0x04D2)
 *   2. External display discovery via DisplayManager
 *   3. GlassesPresentation rendered on the external display
 *
 * For the 2025 AI Glasses (RV101), camera and mic are accessed through
 * the CXR-L SDK (see [RokidCXRLProvider]). This provider handles
 * the display output channel which works without any SDK.
 */
class UsbGlassesProvider : GlassesProvider {

    companion object {
        private const val TAG = "UsbGlasses"

        /** Rokid Inc. USB Vendor ID */
        const val ROKID_VENDOR_ID = 0x04D2  // 1234 decimal

        /** Known Rokid Product IDs */
        private val ROKID_PRODUCT_IDS = setOf(
            0x2002, // Common glasses PID
            0x2003, // Alt PID
            0x0001, // Some firmware versions
        )
    }

    override val providerName = "USB Display"

    private val _state = MutableStateFlow(GlassesState.DISCONNECTED)
    override val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _capabilities = MutableStateFlow(GlassesCapabilities())
    override val capabilities: StateFlow<GlassesCapabilities> = _capabilities.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var presentation: GlassesPresentation? = null
    private var context: Context? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Info about detected Rokid USB device */
    data class DetectedDevice(
        val name: String,
        val vendorId: Int,
        val productId: Int
    )

    private val _detectedDevice = MutableStateFlow<DetectedDevice?>(null)
    val detectedDevice: StateFlow<DetectedDevice?> = _detectedDevice.asStateFlow()

    override fun connect(context: Context) {
        if (_state.value == GlassesState.CONNECTING || _state.value == GlassesState.CONNECTED) return

        this.context = context
        _state.value = GlassesState.CONNECTING
        _errorMessage.value = null
        Log.i(TAG, "Scanning for Rokid glasses...")

        // Register USB attach/detach receiver
        registerUsbReceiver(context)

        // Register display listener for external displays
        registerDisplayListener(context)

        // Check if already connected
        scope.launch {
            // Small delay to let DisplayManager settle
            delay(300)

            val rokidUsb = findRokidUsbDevice(context)
            if (rokidUsb != null) {
                _detectedDevice.value = DetectedDevice(
                    name = rokidUsb.productName ?: "Rokid Glasses",
                    vendorId = rokidUsb.vendorId,
                    productId = rokidUsb.productId
                )
                Log.i(TAG, "Found Rokid USB: ${rokidUsb.productName} " +
                        "(VID=0x${rokidUsb.vendorId.toString(16)}, " +
                        "PID=0x${rokidUsb.productId.toString(16)})")
            }

            // Try to find and use external display
            val externalDisplay = findExternalDisplay(context)
            if (externalDisplay != null) {
                showPresentation(context, externalDisplay)
            } else if (rokidUsb != null) {
                // USB device detected but no external display yet
                // (DP Alt Mode may take a moment to initialize)
                Log.i(TAG, "Rokid USB detected but no display yet — waiting...")
                _state.value = GlassesState.CONNECTED
                _capabilities.value = GlassesCapabilities(
                    canDisplay = false, // will update when display appears
                    canCapturePhoto = false,
                    canStreamAudio = false,
                    canSendCommands = true
                )

                // Retry display detection
                retryDisplayDetection(context)
            } else {
                _state.value = GlassesState.ERROR
                _errorMessage.value = "No Rokid glasses detected.\n\n" +
                        "• Check USB-C cable connection\n" +
                        "• Ensure glasses are powered on\n" +
                        "• Try unplugging and re-plugging"
                Log.w(TAG, "No Rokid device or external display found")
            }
        }
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        dismissPresentation()
        unregisterReceivers()
        _state.value = GlassesState.DISCONNECTED
        _capabilities.value = GlassesCapabilities()
        _errorMessage.value = null
        _detectedDevice.value = null
        context = null
    }

    override fun displayText(title: String, body: String) {
        val pres = presentation
        if (pres == null) {
            Log.w(TAG, "displayText: no presentation active")
            return
        }
        pres.updateContent(title, body)
        Log.d(TAG, "displayText: title='${title.take(30)}' body=${body.length}chars")
    }

    override fun clearDisplay() {
        presentation?.clear()
        Log.d(TAG, "clearDisplay")
    }

    override fun startAudioStream(callback: AudioStreamCallback) {
        // USB display mode doesn't directly support audio streaming.
        // For RV101 (2025 glasses with mic), use CXR-L SDK instead.
        callback.onAudioError(
            "Audio capture requires CXR-L SDK.\n" +
            "Use 'Rokid CXR-L' provider for mic access."
        )
    }

    override fun stopAudioStream() {
        // No-op for USB display
    }

    override fun takePhoto(width: Int, height: Int, quality: Int, callback: PhotoCallback) {
        // USB display mode doesn't directly support camera.
        // For RV101 (2025 glasses with camera), use CXR-L SDK instead.
        callback.onPhotoError(
            "Camera capture requires CXR-L SDK.\n" +
            "Use 'Rokid CXR-L' provider for camera access."
        )
    }

    override fun destroy() {
        disconnect()
        scope.cancel()
        Log.i(TAG, "Destroyed")
    }

    // ── Display management ──

    private fun showPresentation(context: Context, display: Display) {
        try {
            dismissPresentation() // Clean up any existing

            val pres = GlassesPresentation(context, display)
            pres.show()
            presentation = pres

            _state.value = GlassesState.SCENE_ACTIVE
            _capabilities.value = GlassesCapabilities(
                canDisplay = true,
                canCapturePhoto = false,  // needs CXR-L for RV101 camera
                canStreamAudio = false,   // needs CXR-L for RV101 mic
                canSendCommands = true
            )

            val deviceName = _detectedDevice.value?.name ?: "Rokid Glasses"
            pres.updateStatus("● $deviceName — Connected")

            Log.i(TAG, "Presentation active on display: ${display.name} (${display.displayId})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show presentation", e)
            _state.value = GlassesState.ERROR
            _errorMessage.value = "Failed to show on glasses display: ${e.message}"
        }
    }

    private fun dismissPresentation() {
        try {
            presentation?.dismiss()
        } catch (_: Exception) {
            // May throw if window already gone
        }
        presentation = null
    }

    /**
     * Show "thinking" state on the glasses display.
     */
    fun showThinking() {
        presentation?.showThinking()
    }

    // ── USB detection ──

    private fun findRokidUsbDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return null

        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == ROKID_VENDOR_ID
        }
    }

    /**
     * Check if ANY Rokid USB device is connected (static utility).
     */
    fun isRokidUsbConnected(context: Context): Boolean {
        return findRokidUsbDevice(context) != null
    }

    // ── External display detection ──

    private fun findExternalDisplay(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null

        // Look for non-default displays (category PRESENTATION preferred)
        val presentations = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (presentations.isNotEmpty()) {
            Log.d(TAG, "Found ${presentations.size} presentation display(s)")
            return presentations[0]
        }

        // Fallback: any display that isn't the default
        val all = displayManager.displays
        return all.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun retryDisplayDetection(context: Context) {
        scope.launch {
            // Retry a few times with increasing delays
            val retryDelays = listOf(1000L, 2000L, 3000L, 5000L)
            for (delay in retryDelays) {
                delay(delay)
                if (_state.value == GlassesState.DISCONNECTED) return@launch

                val display = findExternalDisplay(context)
                if (display != null) {
                    Log.i(TAG, "Display appeared after retry!")
                    showPresentation(context, display)
                    return@launch
                }
            }

            // If still no display, update state
            if (_state.value == GlassesState.CONNECTED) {
                Log.w(TAG, "Rokid USB connected but no display output. " +
                        "Phone may not support DP Alt Mode, or glasses may be in charge-only mode.")
                _capabilities.value = _capabilities.value.copy(canDisplay = false)
                _errorMessage.value = "Rokid detected via USB but no display output.\n\n" +
                        "• Your phone may not support DisplayPort Alt Mode\n" +
                        "• Try the CXR-L provider for full features"
            }
        }
    }

    // ── Broadcast receivers ──

    private fun registerUsbReceiver(context: Context) {
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (device != null && device.vendorId == ROKID_VENDOR_ID) {
                            Log.i(TAG, "Rokid USB attached: ${device.productName}")
                            _detectedDevice.value = DetectedDevice(
                                name = device.productName ?: "Rokid Glasses",
                                vendorId = device.vendorId,
                                productId = device.productId
                            )
                            // Re-check for display
                            scope.launch {
                                delay(500)
                                val display = findExternalDisplay(ctx)
                                if (display != null) {
                                    showPresentation(ctx, display)
                                }
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (device != null && device.vendorId == ROKID_VENDOR_ID) {
                            Log.i(TAG, "Rokid USB detached")
                            _detectedDevice.value = null
                            dismissPresentation()
                            _state.value = GlassesState.DISCONNECTED
                            _capabilities.value = GlassesCapabilities()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    private fun registerDisplayListener(context: Context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.d(TAG, "Display added: $displayId")
                // A new display appeared — check if it's our glasses
                if (_state.value == GlassesState.CONNECTED && presentation == null) {
                    val display = displayManager.getDisplay(displayId)
                    if (display != null && display.displayId != Display.DEFAULT_DISPLAY) {
                        scope.launch {
                            context.let { ctx -> showPresentation(ctx, display) }
                        }
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "Display removed: $displayId")
                if (presentation != null) {
                    dismissPresentation()
                    if (_detectedDevice.value != null) {
                        // USB still connected, just lost display
                        _state.value = GlassesState.CONNECTED
                        _capabilities.value = _capabilities.value.copy(canDisplay = false)
                    } else {
                        _state.value = GlassesState.DISCONNECTED
                        _capabilities.value = GlassesCapabilities()
                    }
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                // Display config changed (resolution, refresh rate, etc.)
                Log.d(TAG, "Display changed: $displayId")
            }
        }

        displayManager.registerDisplayListener(displayListener, null)
    }

    private fun unregisterReceivers() {
        try {
            usbReceiver?.let { context?.unregisterReceiver(it) }
        } catch (_: Exception) {}
        usbReceiver = null

        try {
            displayListener?.let {
                val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                dm?.unregisterDisplayListener(it)
            }
        } catch (_: Exception) {}
        displayListener = null
    }
}
