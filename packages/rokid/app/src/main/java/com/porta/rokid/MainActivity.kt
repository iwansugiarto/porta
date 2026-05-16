package com.porta.rokid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.porta.rokid.ui.screens.*
import com.porta.rokid.ui.theme.PortaRokidTheme
import com.porta.rokid.viewmodel.BridgeViewModel

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
                        onBack = { screen = "chat" }
                    )
                    "conversations" -> ConversationsScreen(
                        viewModel = viewModel,
                        onBack = { screen = "chat" },
                        onSelectConversation = { id ->
                            viewModel.selectConversation(id)
                            screen = "chat"
                        }
                    )
                }
            }
        }
    }
}
