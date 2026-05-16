package id.infinia.porta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import id.infinia.porta.ui.screens.*
import id.infinia.porta.ui.theme.PortaRokidTheme
import id.infinia.porta.viewmodel.BridgeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PortaRokidTheme(darkTheme = true) {
                val viewModel: BridgeViewModel = viewModel()
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
}
