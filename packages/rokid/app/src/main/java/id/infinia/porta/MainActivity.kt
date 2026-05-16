package id.infinia.porta

import android.Manifest
import android.content.pm.PackageManager
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

class MainActivity : ComponentActivity() {

    // Runtime permission request for Android 13+ notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — we handle gracefully either way */ }

    // Runtime permission request for microphone (voice input)
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — VoiceInputService checks availability */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        requestAudioPermissionIfNeeded()

        setContent {
            val viewModel: BridgeViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()

            PortaRokidTheme(themeMode = themeMode) {
                var screen by remember { mutableStateOf("chat") }

                when (screen) {
                    "chat" -> ChatScreen(
                        viewModel = viewModel,
                        onNavigateToSettings = { screen = "settings" },
                        onNavigateToConversations = { screen = "conversations" }
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
}
