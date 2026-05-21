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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import id.infinia.porta.ui.screens.*
import id.infinia.porta.ui.theme.PortaRokidTheme
import id.infinia.porta.viewmodel.BridgeViewModel
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.infinia.porta.ui.components.ProjectsDrawerContent
import kotlinx.coroutines.launch

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

    /** Pending notification cascade ID — consumed by LaunchedEffect in Compose */
    private val _pendingCascadeId = mutableStateOf<String?>(null)

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

        // Handle notification tap → open specific conversation (cold start)
        _pendingCascadeId.value = intent?.getStringExtra(
            id.infinia.porta.service.NotificationService.EXTRA_CASCADE_ID
        )

        setContent {
            val viewModel: BridgeViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val sharedContent by _sharedContent
            val pendingCascadeId by _pendingCascadeId

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
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                // Start on chat screen if launched from notification with cascade ID
                var screen by remember {
                    mutableStateOf(if (pendingCascadeId != null) "chat" else "home")
                }
                var workspaceFilter by remember { mutableStateOf<String?>(null) }

                // Navigate to the notified conversation (cold + warm start)
                LaunchedEffect(pendingCascadeId) {
                    val cascadeId = pendingCascadeId
                    if (cascadeId != null) {
                        viewModel.selectConversation(cascadeId)
                        screen = "chat"
                        _pendingCascadeId.value = null  // consume
                    }
                }

                // Keep viewModel's active conversation screen state in sync
                LaunchedEffect(screen) {
                    viewModel.setConversationScreenActive(screen == "chat")
                }

                val onSelectProject = remember(viewModel) {
                    { project: id.infinia.porta.data.Project ->
                        viewModel.setActiveProjectId(project.id)
                        
                        // Locate matching conversation
                        val conversationsMap = viewModel.conversations.value
                        val exactMatchEntry = conversationsMap.entries.find { entry ->
                            val summary = entry.value
                            val summaryTitle = summary.get("summary")?.asString
                            summaryTitle?.equals(project.title, ignoreCase = true) == true
                        }
                        
                        if (exactMatchEntry != null) {
                            viewModel.selectConversation(exactMatchEntry.key)
                            screen = "chat"
                            scope.launch { drawerState.close() }
                        } else {
                            scope.launch {
                                try {
                                    val result = viewModel.portaClient.fetchWorkspaces()
                                    val workspaceInfos = result.getAsJsonArray("workspaceInfos")
                                    var matchedUri: String? = null
                                    if (workspaceInfos != null) {
                                        var bestScore = 0
                                        for (element in workspaceInfos) {
                                            val obj = element.asJsonObject
                                            val uri = obj.get("workspaceUri")?.asString ?: continue
                                            val cleanUri = uri.replace("file://", "")
                                            val folderName = cleanUri.split("/").lastOrNull() ?: ""
                                            val titleLower = project.title.lowercase()
                                            val folderLower = folderName.lowercase()
                                            var score = 0
                                            if (folderLower == titleLower) {
                                                score = 100
                                            } else if (titleLower.contains(folderLower) && folderLower.isNotEmpty()) {
                                                score = folderLower.length
                                            } else if (folderLower.contains(titleLower) && titleLower.isNotEmpty()) {
                                                score = titleLower.length
                                            }
                                            if (score > bestScore) {
                                                bestScore = score
                                                matchedUri = uri
                                            }
                                        }
                                    }
                                    viewModel.createNewConversation(matchedUri)
                                    screen = "chat"
                                } catch (e: Exception) {
                                    viewModel.createNewConversation(null)
                                    screen = "chat"
                                } finally {
                                    drawerState.close()
                                }
                            }
                        }
                        Unit
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = screen == "home" || screen == "conversations" || screen == "chat",
                    drawerContent = {
                        ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                            ProjectsDrawerContent(
                                viewModel = viewModel,
                                activeScreen = screen,
                                onSelectProject = onSelectProject,
                                onSelectConversation = { id ->
                                    viewModel.selectConversation(id)
                                    screen = "chat"
                                    scope.launch { drawerState.close() }
                                },
                                onNewConversation = {
                                    viewModel.createNewConversation()
                                    screen = "chat"
                                    scope.launch { drawerState.close() }
                                },
                                onOpenSettings = {
                                    screen = "settings"
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                ) {
                    when (screen) {
                        "home" -> {
                            var backPressedOnce by remember { mutableStateOf(false) }
                            BackHandler {
                                if (backPressedOnce) {
                                    finish()
                                } else {
                                    backPressedOnce = true
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Press back again to exit",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    // Reset after 2 seconds
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                        { backPressedOnce = false }, 2000
                                    )
                                }
                            }
                            HomeScreen(
                                viewModel = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onNavigateToSettings = { screen = "settings" },
                                onNavigateToWorkspace = { wsName ->
                                    workspaceFilter = wsName
                                    screen = "conversations"
                                },
                                onSelectConversation = { id ->
                                    viewModel.selectConversation(id)
                                    screen = "chat"
                                },
                                onNewConversation = {
                                    screen = "chat"
                                }
                            )
                        }
                        "chat" -> {
                            BackHandler {
                                viewModel.loadConversations()
                                screen = "home"
                            }
                            ChatScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = { screen = "settings" },
                                onNavigateToConversations = {
                                    viewModel.loadConversations()
                                    screen = "home"
                                },
                                sharedContent = sharedContent,
                                onSharedContentConsumed = { _sharedContent.value = null }
                            )
                        }
                        "settings" -> {
                            BackHandler { screen = "home" }
                            SettingsScreen(
                                viewModel = viewModel,
                                onBack = { screen = "home" },
                                onNavigateToAbout = { screen = "about" },
                                onNavigateToGlasses = { screen = "glasses" }
                            )
                        }
                        "conversations" -> {
                            BackHandler {
                                workspaceFilter = null
                                screen = "home"
                            }
                            ConversationsScreen(
                                viewModel = viewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onBack = {
                                    workspaceFilter = null
                                    screen = "home"
                                },
                                onSelectConversation = { id ->
                                    viewModel.selectConversation(id)
                                    screen = "chat"
                                },
                                onSelectProject = onSelectProject,
                                workspaceFilter = workspaceFilter
                            )
                        }
                        "about" -> {
                            BackHandler { screen = "settings" }
                            AboutScreen(
                                viewModel = viewModel,
                                onBack = { screen = "settings" }
                            )
                        }
                        "glasses" -> {
                            BackHandler { screen = "settings" }
                            GlassesScreen(
                                viewModel = viewModel,
                                onBack = { screen = "settings" }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // Handle warm-start notification taps
        intent.getStringExtra(
            id.infinia.porta.service.NotificationService.EXTRA_CASCADE_ID
        )?.let { _pendingCascadeId.value = it }
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
