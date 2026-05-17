package id.infinia.porta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import id.infinia.porta.ui.screens.*
import id.infinia.porta.ui.theme.PortaRokidTheme
import id.infinia.porta.viewmodel.BridgeViewModel

/**
 * Shared content from external apps (via Android share sheet).
 */
data class SharedContent(
    val text: String? = null,
    val imageUris: List<Uri> = emptyList()
)

class MainActivity : ComponentActivity() {

    // Runtime permission request for Android 13+ notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — we handle gracefully either way */ }

    // Runtime permission request for microphone (voice input)
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — VoiceInputService checks availability */ }

    /** Shared content state — consumed by ChatScreen */
    private val _sharedContent = mutableStateOf<SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        requestAudioPermissionIfNeeded()
        setupAppShortcuts()
        id.infinia.porta.service.ConversationPollWorker.enqueue(this)

        // Handle share intent on cold start
        handleShareIntent(intent)

        // Handle shortcut action
        val shortcutAction = intent?.getStringExtra("shortcut_action")

        setContent {
            val viewModel: BridgeViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val sharedContent by _sharedContent

            // Handle shortcut: auto-create new conversation
            LaunchedEffect(shortcutAction) {
                when (shortcutAction) {
                    "new_conversation" -> viewModel.createNewConversation()
                    "voice_mode" -> {
                        viewModel.createNewConversation()
                        viewModel.startVoiceInput()
                    }
                }
            }

            PortaRokidTheme(themeMode = themeMode) {
                var screen by remember { mutableStateOf("chat") }

                when (screen) {
                    "chat" -> ChatScreen(
                        viewModel = viewModel,
                        onNavigateToSettings = { screen = "settings" },
                        onNavigateToConversations = { screen = "conversations" },
                        sharedContent = sharedContent,
                        onSharedContentConsumed = { _sharedContent.value = null }
                    )
                    "settings" -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { screen = "chat" },
                        onNavigateToAbout = { screen = "about" },
                        onNavigateToGlasses = { screen = "glasses" }
                    )
                    "conversations" -> ConversationsScreen(
                        viewModel = viewModel,
                        onBack = { screen = "chat" },
                        onSelectConversation = { id ->
                            viewModel.selectConversation(id)
                            screen = "chat"
                        }
                    )
                    "about" -> AboutScreen(
                        viewModel = viewModel,
                        onBack = { screen = "settings" }
                    )
                    "glasses" -> GlassesScreen(
                        viewModel = viewModel,
                        onBack = { screen = "settings" }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Parse ACTION_SEND / ACTION_SEND_MULTIPLE intents and expose
     * the shared content for ChatScreen to consume.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type ?: return
                if (type.startsWith("text/")) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        _sharedContent.value = SharedContent(text = text)
                    }
                } else if (type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        _sharedContent.value = SharedContent(imageUris = listOf(uri))
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    _sharedContent.value = SharedContent(imageUris = uris)
                }
            }
        }
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * Without this, all notifications are silently blocked.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Request RECORD_AUDIO permission for voice input (speech-to-text).
     * Required for hands-free usage and Android Auto integration.
     */
    private fun requestAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Register dynamic app shortcuts — visible on long-press of the app icon.
     * Available on Android 7.1+ (API 25+).
     */
    private fun setupAppShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return

        val newConvo = ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel("New Chat")
            .setLongLabel("Start a new conversation")
            .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_add))
            .setIntent(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("shortcut_action", "new_conversation")
                }
            )
            .build()

        val voiceMode = ShortcutInfo.Builder(this, "voice_mode")
            .setShortLabel("Voice Chat")
            .setLongLabel("Start voice conversation")
            .setIcon(Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now))
            .setIntent(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("shortcut_action", "voice_mode")
                }
            )
            .build()

        shortcutManager.dynamicShortcuts = listOf(newConvo, voiceMode)
    }
}
