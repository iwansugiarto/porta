package id.infinia.porta.viewmodel

import id.infinia.porta.ui.UiUtils
import id.infinia.porta.ui.theme.ThemeMode

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import id.infinia.porta.data.OfflineMessageQueue
import id.infinia.porta.data.SettingsReader
import id.infinia.porta.data.PortaClient
import id.infinia.porta.data.ServerProfileManager
import id.infinia.porta.data.Project
import id.infinia.porta.data.ProjectStatus
import id.infinia.porta.service.NotificationService
import id.infinia.porta.service.PortaConnectionService
import id.infinia.porta.service.glasses.*
import id.infinia.porta.service.voice.TextToSpeechService
import id.infinia.porta.service.voice.VoiceInputService
import id.infinia.porta.shared.protocol.*
import id.infinia.porta.shared.protocol.stepsToMessages
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "porta_settings")

/**
 * Main ViewModel for the Porta Rokid Bridge app.
 *
 * Manages:
 * - Connection to Porta proxy
 * - Conversation list and selection
 * - Step streaming and display
 * - Voice input state
 * - Approval handling
 */
class BridgeViewModel(application: Application) : AndroidViewModel(application) {

    // ── Persistence keys ──
    private object PrefKeys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val USE_TLS = booleanPreferencesKey("use_tls")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val NOTIFY_SOUND = booleanPreferencesKey("notify_sound")
        val NOTIFY_VIBRATE = booleanPreferencesKey("notify_vibrate")
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val GLASSES_PROVIDER = stringPreferencesKey("glasses_provider")
        val GLASSES_AUTO_FORWARD = booleanPreferencesKey("glasses_auto_forward")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val PLANNER_TYPE = stringPreferencesKey("planner_type")
        val NOTIFY_APPROVAL_ENABLED = booleanPreferencesKey("notify_approval_enabled")
    }

    private val dataStore = application.settingsDataStore

    val portaClient = PortaClient(viewModelScope)

    // ── Offline queue ──
    val offlineQueue = OfflineMessageQueue(application)

    // ── Server profiles ──
    val serverProfiles = ServerProfileManager(application)

    // ── Projects ──
    private val projectsFile = java.io.File(application.filesDir, "projects.json")
    private val gson = com.google.gson.Gson()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _activeProjectId = MutableStateFlow<String?>("p-inprogress-1")
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    // ── Notification service ──

    val notificationService = NotificationService(application)
    private val notifiedSteps = mutableSetOf<String>()
    
    private val _isConversationScreenActive = MutableStateFlow(false)
    val isConversationScreenActive: StateFlow<Boolean> = _isConversationScreenActive.asStateFlow()

    fun setConversationScreenActive(active: Boolean) {
        _isConversationScreenActive.value = active
        if (active) {
            notificationService.dismissApproval()
        }
    }

    // ── Voice services ──

    val voiceInput = VoiceInputService(application)
    val tts = TextToSpeechService(application)

    // ── Glasses ──

    private var _glassesProvider: GlassesProvider = MockGlassesProvider()

    val glassesState: StateFlow<GlassesState> get() = _glassesProvider.state
    val glassesError: StateFlow<String?> get() = _glassesProvider.errorMessage
    val glassesCapabilities: StateFlow<GlassesCapabilities> get() = _glassesProvider.capabilities

    private val _glassesProviderName = MutableStateFlow("Mock (Development)")
    val glassesProviderName: StateFlow<String> = _glassesProviderName.asStateFlow()

    private val _autoForwardToGlasses = MutableStateFlow(false)
    val autoForwardToGlasses: StateFlow<Boolean> = _autoForwardToGlasses.asStateFlow()

    /** App theme: SYSTEM, LIGHT, or DARK. */
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Whether to auto-read responses aloud. */
    private val _ttsEnabled = MutableStateFlow(false)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    /** Whether to show system notification on task completion. */
    private val _notifyEnabled = MutableStateFlow(true)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    /** Whether to show system notification for approval requests. */
    private val _notifyApprovalEnabled = MutableStateFlow(true)
    val notifyApprovalEnabled: StateFlow<Boolean> = _notifyApprovalEnabled.asStateFlow()

    /** Whether to play sound with notification. */
    private val _notifySound = MutableStateFlow(true)
    val notifySound: StateFlow<Boolean> = _notifySound.asStateFlow()

    /** Whether to vibrate with notification. */
    private val _notifyVibrate = MutableStateFlow(true)
    val notifyVibrate: StateFlow<Boolean> = _notifyVibrate.asStateFlow()

    // ── Connection state ──

    val connectionState = portaClient.connectionState
    val agentRunning = portaClient.agentRunning
    val errors = portaClient.error

    // ── Configuration ──

    private val _host = MutableStateFlow("localhost")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(3170)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _authToken = MutableStateFlow<String?>(null)
    val authToken: StateFlow<String?> = _authToken.asStateFlow()

    private val _useTls = MutableStateFlow(false)
    val useTls: StateFlow<Boolean> = _useTls.asStateFlow()

    private val _autoConnect = MutableStateFlow(false)
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    // ── Workspaces ──

    data class WorkspaceOption(
        val uri: String,
        val name: String
    )

    private val _workspaces = MutableStateFlow<List<WorkspaceOption>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceOption>> = _workspaces.asStateFlow()

    // ── Language Server instances ──

    data class LSInstanceInfo(
        val pid: Int,
        val subclientType: String,
        val appDataDir: String,
        val workspaceId: String?
    )

    private val _lsInstances = MutableStateFlow<List<LSInstanceInfo>>(emptyList())
    val lsInstances: StateFlow<List<LSInstanceInfo>> = _lsInstances.asStateFlow()

    /** Source filter: "all", "hub", "ide" */
    private val _sourceFilter = MutableStateFlow("all")
    val sourceFilter: StateFlow<String> = _sourceFilter.asStateFlow()

    /** Target LS for new conversations: null = auto, "hub", "ide" */
    private val _selectedTarget = MutableStateFlow<String?>(null)
    val selectedTarget: StateFlow<String?> = _selectedTarget.asStateFlow()

    fun setSourceFilter(filter: String) {
        _sourceFilter.value = filter
    }

    fun setSelectedTarget(target: String?) {
        _selectedTarget.value = target
    }

    // ── Conversations ──

    private val _conversations = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val conversations: StateFlow<Map<String, JsonObject>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    // ── Pinned Conversations ──
    private val _pinnedConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedConversationIds: StateFlow<Set<String>> = _pinnedConversationIds.asStateFlow()

    fun togglePinConversation(id: String) {
        val current = _pinnedConversationIds.value
        val updated = if (current.contains(id)) current - id else current + id
        _pinnedConversationIds.value = updated
        getApplication<Application>().getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("pinned_conversations", updated)
            .apply()
    }

    // ── Workspace grouping (legacy — kept for workspace screen if needed) ──

    data class WorkspaceInfo(
        val name: String,
        val totalCount: Int,
        val activeCount: Int,
        val lastModified: String,
        val conversationIds: List<String>
    )

    /** Conversations grouped by workspace, sorted by activity. */
    val workspaceGroups: StateFlow<List<WorkspaceInfo>> = _conversations.map { convos ->
        val groups = mutableMapOf<String, MutableList<Pair<String, JsonObject>>>()
        for ((id, summary) in convos) {
            val wsName = UiUtils.extractWorkspaceName(summary)
            groups.getOrPut(wsName) { mutableListOf() }.add(id to summary)
        }
        groups.entries.map { (name, items) ->
            val active = items.count {
                it.second.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
            }
            val lastMod = items.maxOfOrNull {
                it.second.get("lastModifiedTime")?.asString ?: ""
            } ?: ""
            WorkspaceInfo(
                name = name,
                totalCount = items.size,
                activeCount = active,
                lastModified = lastMod,
                conversationIds = items.map { it.first }
            )
        }.sortedWith(
            compareByDescending<WorkspaceInfo> { it.activeCount > 0 }
                .thenByDescending { it.lastModified }
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Time-grouped conversation timeline ──

    data class TimeGroup(
        val label: String,
        val conversations: List<Pair<String, JsonObject>>
    )

    /** Conversations grouped by time (Today, Yesterday, This Week, Earlier), filtered. */
    val timeGroupedConversations: StateFlow<List<TimeGroup>> = combine(
        _conversations, _sourceFilter
    ) { convos, filter ->
        val filtered = convos.entries
            .filter { !UiUtils.isGhostConversation(it.value) && matchesSourceFilter(it.value, filter) }
            .sortedByDescending { it.value.get("lastModifiedTime")?.asString ?: "" }
            .map { it.key to it.value }

        val groups = linkedMapOf<String, MutableList<Pair<String, JsonObject>>>()
        for (entry in filtered) {
            val time = entry.second.get("lastModifiedTime")?.asString
            val group = UiUtils.timeGroup(time)
            groups.getOrPut(group) { mutableListOf() }.add(entry)
        }

        // Maintain stable order: Today → Yesterday → This Week → Earlier
        val order = listOf("Today", "Yesterday", "This Week", "Earlier")
        order.mapNotNull { label ->
            groups[label]?.let { TimeGroup(label, it) }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Visible conversation count (excluding ghosts, respecting source filter). */
    val visibleConversationCount: StateFlow<Int> = combine(
        _conversations, _sourceFilter
    ) { convos, filter ->
        convos.values.count { summary ->
            !UiUtils.isGhostConversation(summary) && matchesSourceFilter(summary, filter)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** Recent conversations (last 10, excluding ghosts, respecting source filter). */
    val recentConversations: StateFlow<List<Pair<String, JsonObject>>> = combine(
        _conversations, _sourceFilter
    ) { convos, filter ->
        convos.entries
            .filter { !UiUtils.isGhostConversation(it.value) && matchesSourceFilter(it.value, filter) }
            .sortedByDescending { it.value.get("lastModifiedTime")?.asString ?: "" }
            .take(10)
            .map { it.key to it.value }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Check if a conversation matches the current source filter. */
    private fun matchesSourceFilter(summary: JsonObject, filter: String): Boolean {
        if (filter == "all") return true
        val source = summary.get("_source")?.asString ?: return filter == "all"
        return source == filter
    }

    /** Child conversations (subagents) of the currently selected conversation. */
    data class RelatedConversation(
        val cascadeId: String,
        val summary: String,
        val stepCount: Int,
        val status: String
    )

    val childConversations: StateFlow<List<RelatedConversation>> = combine(
        _currentConversationId, _conversations
    ) { currentId, convos ->
        if (currentId == null) return@combine emptyList()
        val summary = convos[currentId] ?: return@combine emptyList()
        val children = summary.getAsJsonArray("childConversations") ?: return@combine emptyList()
        children.mapNotNull { child ->
            val obj = child.asJsonObject ?: return@mapNotNull null
            RelatedConversation(
                cascadeId = obj.get("cascadeId")?.asString ?: return@mapNotNull null,
                summary = obj.get("summary")?.asString ?: "Subagent",
                stepCount = obj.get("stepCount")?.asInt ?: 0,
                status = obj.get("status")?.asString ?: ""
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Parent conversation if this is a child/subagent conversation. */
    data class ParentConversation(val cascadeId: String, val summary: String)

    val parentConversation: StateFlow<ParentConversation?> = combine(
        _currentConversationId, _conversations
    ) { currentId, convos ->
        if (currentId == null) return@combine null
        val summary = convos[currentId] ?: return@combine null
        val parent = summary.getAsJsonObject("parentConversation") ?: return@combine null
        ParentConversation(
            cascadeId = parent.get("cascadeId")?.asString ?: return@combine null,
            summary = parent.get("summary")?.asString ?: "Parent"
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)


    // ── Steps (agent output) ──

    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()

    /** Raw JSON steps for the stepsToMessages transform. */
    private val _rawSteps = MutableStateFlow<List<JsonObject>>(emptyList())

    /** Chat messages derived from raw steps — the primary display data. */
    @OptIn(FlowPreview::class)
    val chatMessages: StateFlow<List<ChatMessage>> = _rawSteps
        .debounce(50) // Debounce rapid step updates during streaming
        .map { raw -> stepsToMessages(raw) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Latest agent text response for HUD display. */
    private val _latestResponse = MutableStateFlow("")
    val latestResponse: StateFlow<String> = _latestResponse.asStateFlow()

    /** Steps awaiting user approval. */
    val pendingApprovals: StateFlow<List<AgentStep>> = _steps.map { steps ->
        steps.filter { it.needsApproval }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Voice ──

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** Voice input language (BCP-47 code). Default: Indonesian. */
    private val _voiceLanguage = MutableStateFlow("id-ID")
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()

    // ── UI state ──

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // ── Model & Planner ──

    data class ModelConfig(
        val id: String,
        val label: String,
        val supportsImages: Boolean = false,
        val isRecommended: Boolean = false,
        val quotaRemaining: Float = 1f
    )

    private val _availableModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val availableModels: StateFlow<List<ModelConfig>> = _availableModels.asStateFlow()

    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()

    private val _defaultModel = MutableStateFlow<String?>(null)
    val defaultModel: StateFlow<String?> = _defaultModel.asStateFlow()

    /** Timestamp (millis) when models/quota were last refreshed. */
    private val _lastModelRefresh = MutableStateFlow(0L)
    val lastModelRefresh: StateFlow<Long> = _lastModelRefresh.asStateFlow()

    /** Planner mode: null = default, "fast" = no planning, "plan" = full planning */
    private val _plannerType = MutableStateFlow<String?>(null)
    val plannerType: StateFlow<String?> = _plannerType.asStateFlow()

    // ── Step tracking ──
    private var stepCount = 0
    /** Base offset for raw steps array (avoids padding empty entries). */
    private var rawStepsBaseOffset = 0

    /** Whether DataStore settings have been loaded (prevents race with UI connect). */
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    init {
        loadProjectsFromDisk()
        // Load saved pinned conversations
        val savedPinned = application.getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
            .getStringSet("pinned_conversations", emptySet()) ?: emptySet()
        _pinnedConversationIds.value = savedPinned

        // Load saved settings first
        viewModelScope.launch {
            dataStore.data.first().let { prefs ->
                // Load from DataStore with SharedPreferences fallback for connection settings
                val fallbackPrefs = application.getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
                _host.value = prefs[PrefKeys.HOST]
                    ?: fallbackPrefs.getString("host", null)
                    ?: "localhost"
                _port.value = prefs[PrefKeys.PORT]
                    ?: fallbackPrefs.getInt("port", 443)
                _authToken.value = prefs[PrefKeys.AUTH_TOKEN]
                    ?: fallbackPrefs.getString("auth_token", null)
                _useTls.value = prefs[PrefKeys.USE_TLS]
                    ?: fallbackPrefs.getBoolean("use_tls", true)
                _notifyEnabled.value = prefs[PrefKeys.NOTIFY_ENABLED] ?: true
                _notifyApprovalEnabled.value = prefs[PrefKeys.NOTIFY_APPROVAL_ENABLED] ?: true
                _notifySound.value = prefs[PrefKeys.NOTIFY_SOUND] ?: true
                _notifyVibrate.value = prefs[PrefKeys.NOTIFY_VIBRATE] ?: true
                _autoForwardToGlasses.value = prefs[PrefKeys.GLASSES_AUTO_FORWARD] ?: false
                _autoConnect.value = prefs[PrefKeys.AUTO_CONNECT] ?: false
                _voiceLanguage.value = prefs[PrefKeys.VOICE_LANGUAGE] ?: "id-ID"
                _themeMode.value = try {
                    ThemeMode.valueOf(prefs[PrefKeys.THEME_MODE] ?: "SYSTEM")
                } catch (_: Exception) { ThemeMode.SYSTEM }
                _selectedModel.value = prefs[PrefKeys.SELECTED_MODEL]
                _plannerType.value = prefs[PrefKeys.PLANNER_TYPE]
                // Restore glasses provider
                val savedProvider = prefs[PrefKeys.GLASSES_PROVIDER] ?: "mock"
                setGlassesProvider(savedProvider, persist = false)
                // Apply saved config to client
                portaClient.configure(_host.value, _port.value, _authToken.value, _useTls.value)

                // Sync settings to shared JSON for background components
                syncSharedSettings()

                // Mark settings as loaded BEFORE auto-connect
                _settingsLoaded.value = true
                Log.d("BridgeVM", "Settings loaded: host=${_host.value} port=${_port.value} tls=${_useTls.value}")

                // Auto-connect if enabled and credentials are configured
                if (_autoConnect.value && !_authToken.value.isNullOrBlank()) {
                    connect()
                }
            }
        }

        // Quick reply handler from notification
        NotificationService.setQuickReplyListener { cascadeId, replyText ->
            viewModelScope.launch {
                try {
                    portaClient.sendMessage(cascadeId, replyText, _selectedModel.value, _plannerType.value)
                } catch (_: Exception) { }
            }
        }

        // Listen to incoming WebSocket messages
        viewModelScope.launch {
            portaClient.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Update status message based on connection state + manage foreground service
        viewModelScope.launch {
            connectionState.collect { state ->
                _statusMessage.value = when (state) {
                    ConnectionState.DISCONNECTED -> {
                        PortaConnectionService.stop(application)
                        "Not connected"
                    }
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.CONNECTED -> {
                        // Start foreground service to keep WS alive in background
                        PortaConnectionService.start(application)
                        // Drain offline queue on reconnect
                        drainOfflineQueue()
                        // Fetch workspaces for the picker
                        loadWorkspaces()
                        "Connected"
                    }
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                    ConnectionState.ERROR -> {
                        PortaConnectionService.stop(application)
                        "Connection error"
                    }
                }
            }
        }

        // Voice input → auto-send to conversation
        viewModelScope.launch {
            voiceInput.finalResult.collect { text ->
                if (text.isNotBlank()) {
                    sendMessage(text)
                }
            }
        }

        // Sync voice listening state
        viewModelScope.launch {
            voiceInput.isListening.collect { listening ->
                _isListening.value = listening
            }
        }
    }

    // ── Configuration ──

    /** Write current settings to shared JSON file for background components */
    private fun syncSharedSettings() {
        val ctx: Context = getApplication()
        SettingsReader.writeSettings(ctx, mapOf(
            "host" to _host.value,
            "port" to _port.value,
            "auth_token" to _authToken.value,
            "use_tls" to _useTls.value,
            "auto_connect" to _autoConnect.value,
            "notify_enabled" to _notifyEnabled.value,
            "notify_sound" to _notifySound.value,
            "notify_vibrate" to _notifyVibrate.value
        ))
        // Also write to SharedPreferences as fallback for Android Auto
        ctx.getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("host", _host.value)
            .putInt("port", _port.value)
            .putString("auth_token", _authToken.value)
            .putBoolean("use_tls", _useTls.value)
            .apply()
    }

    fun updateConfig(host: String, port: Int, authToken: String?, useTls: Boolean = true) {
        _host.value = host
        _port.value = port
        _authToken.value = authToken
        _useTls.value = useTls
        portaClient.configure(host, port, authToken, useTls)

        // Persist to DataStore
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefKeys.HOST] = host
                prefs[PrefKeys.PORT] = port
                if (authToken != null) {
                    prefs[PrefKeys.AUTH_TOKEN] = authToken
                } else {
                    prefs.remove(PrefKeys.AUTH_TOKEN)
                }
                prefs[PrefKeys.USE_TLS] = useTls
            }
            // Keep SharedPreferences fallback in sync
            getApplication<Application>().getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE).edit()
                .putString("host", host)
                .putInt("port", port)
                .apply {
                    if (authToken != null) putString("auth_token", authToken) else remove("auth_token")
                }
                .putBoolean("use_tls", useTls)
                .apply()
            // Sync to shared JSON for background components
            syncSharedSettings()
        }
    }

    // ── Connection ──

    /**
     * Switch to a saved server profile.
     * Disconnects current session, updates config, and reconnects.
     */
    fun switchToProfile(profileId: String) {
        val profile = serverProfiles.profiles.value.find { it.id == profileId } ?: return
        disconnect()
        serverProfiles.setActive(profileId)
        updateConfig(profile.host, profile.port, profile.authToken, profile.useTls)
        connect()
    }

    fun connect() {
        viewModelScope.launch {
            // Wait for settings to be loaded from DataStore first
            _settingsLoaded.first { it }
            portaClient.configure(_host.value, _port.value, _authToken.value, _useTls.value)
            loadConversations(markConnected = true)

            // Re-fetch after delay to pick up warm-up results from proxy.
            // Disk-only conversations initially return UUID placeholder titles;
            // after the proxy warm-up loads them into the LS (a few seconds),
            // proper titles and metadata become available.
            delay(10_000)
            loadConversations()
        }
    }

    fun disconnect() {
        portaClient.disconnectWebSocket()
        _steps.value = emptyList()
        _rawSteps.value = emptyList()
        _latestResponse.value = ""
        _currentConversationId.value = null
        stepCount = 0
        rawStepsBaseOffset = 0
        PortaConnectionService.stop(getApplication())
    }

    // ── Workspaces ──

    fun loadWorkspaces() {
        viewModelScope.launch {
            try {
                val result = portaClient.fetchWorkspaces()
                val infos = result.getAsJsonArray("workspaceInfos")
                val options = infos?.mapNotNull { el ->
                    val obj = el.asJsonObject
                    val uri = obj.get("workspaceUri")?.asString ?: return@mapNotNull null
                    val name = uri.removePrefix("file://").substringAfterLast("/")
                    WorkspaceOption(uri = uri, name = name)
                } ?: emptyList()
                _workspaces.value = options
                Log.d("BridgeVM", "Loaded ${options.size} workspaces")
            } catch (e: Exception) {
                Log.w("BridgeVM", "Failed to load workspaces: ${e.message}")
            }
        }
    }

    // ── Conversations ──

    fun loadConversations(markConnected: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            Log.d("BridgeVM", "loadConversations: host=${_host.value} port=${_port.value} tls=${_useTls.value} token=${_authToken.value?.take(10)}...")
            try {
                val convos = portaClient.fetchConversations()
                _conversations.value = convos
                _statusMessage.value = "Loaded ${convos.size} conversations"
                Log.d("BridgeVM", "loadConversations: success, ${convos.size} conversations")
                // Mark API as reachable so HomeScreen shows connected indicator
                if (markConnected || convos.isNotEmpty()) {
                    portaClient.markApiReachable()
                }
                // Also fetch available LS instances for filter/target UI
                try {
                    val instances = portaClient.fetchLSInstances()
                    _lsInstances.value = instances.mapNotNull { json ->
                        val pid = json.get("pid")?.asInt ?: return@mapNotNull null
                        val subclient = json.get("subclientType")?.asString ?: "unknown"
                        val appData = json.get("appDataDir")?.asString ?: "unknown"
                        val wsId = json.get("workspaceId")?.asString
                        LSInstanceInfo(pid, subclient, appData, wsId)
                    }
                } catch (_: Exception) {
                    // Non-critical — keep old instances
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed: ${e.message}"
                Log.e("BridgeVM", "loadConversations FAILED", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectConversation(cascadeId: String) {
        // Disconnect old WS first to prevent stale steps bleeding through
        portaClient.disconnectWebSocket()
        portaClient.resetRunningState()

        // Ensure client is configured (e.g. when called before connect())
        portaClient.configure(_host.value, _port.value, _authToken.value, _useTls.value)

        // Clear all conversation-specific state
        _currentConversationId.value = cascadeId
        _steps.value = emptyList()
        _rawSteps.value = emptyList()
        _latestResponse.value = ""
        stepCount = 0
        rawStepsBaseOffset = 0
        
        // Clear notified steps and dismiss persistent approval notification
        notifiedSteps.clear()
        notificationService.dismissApproval()

        // Connect to new conversation
        portaClient.connectWebSocket(cascadeId)
    }

    fun createNewConversation(workspaceUri: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val target = _selectedTarget.value
                val cascadeId = portaClient.createConversation(
                    workspaceUri = workspaceUri,
                    targetSubclientType = target
                )
                loadConversations()
                selectConversation(cascadeId)
            } catch (e: Exception) {
                _statusMessage.value = "Failed to create: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Messaging ──

    fun sendMessage(text: String, model: String? = null, media: List<Map<String, String>>? = null) {
        val cascadeId = _currentConversationId.value ?: return
        val activeModel = model ?: _selectedModel.value
        val activePlanner = _plannerType.value

        // If disconnected, queue for later
        if (connectionState.value != ConnectionState.CONNECTED) {
            viewModelScope.launch {
                offlineQueue.enqueue(
                    OfflineMessageQueue.PendingMessage(
                        cascadeId = cascadeId,
                        text = text,
                        model = activeModel,
                        planner = activePlanner,
                        media = media
                    )
                )
                _statusMessage.value = "Queued offline (${offlineQueue.size} pending)"
            }
            return
        }

        viewModelScope.launch {
            try {
                _statusMessage.value = "Sending..."
                portaClient.sendMessage(cascadeId, text, activeModel, activePlanner, media)
                _statusMessage.value = "Message sent"
            } catch (e: Exception) {
                // Queue on send failure too
                offlineQueue.enqueue(
                    OfflineMessageQueue.PendingMessage(
                        cascadeId = cascadeId,
                        text = text,
                        model = activeModel,
                        planner = activePlanner,
                        media = media
                    )
                )
                _statusMessage.value = "Queued (send failed): ${e.message}"
            }
        }
    }

    // ── Offline queue drain ──

    private fun drainOfflineQueue() {
        if (offlineQueue.size == 0) return
        viewModelScope.launch {
            val pending = offlineQueue.drainAll()
            _statusMessage.value = "Sending ${pending.size} queued message(s)..."
            for (msg in pending) {
                try {
                    portaClient.sendMessage(
                        msg.cascadeId, msg.text, msg.model, msg.planner, msg.media
                    )
                    offlineQueue.dequeue(msg.id)
                } catch (e: Exception) {
                    _statusMessage.value = "Queue drain failed: ${e.message}"
                    break // Stop draining on first failure; will retry on next reconnect
                }
            }
            if (offlineQueue.size == 0) {
                _statusMessage.value = "Connected — all queued messages sent"
            }
        }
    }

    // ── Stop / Delete ──

    fun stopGeneration() {
        val cascadeId = _currentConversationId.value ?: return
        viewModelScope.launch {
            try {
                portaClient.stopConversation(cascadeId)
                _statusMessage.value = "Stopped"
            } catch (e: Exception) {
                _statusMessage.value = "Stop failed: ${e.message}"
            }
        }
    }

    fun deleteConversation(cascadeId: String) {
        viewModelScope.launch {
            try {
                portaClient.deleteConversation(cascadeId)
                // If deleting the current conversation, clear it
                if (_currentConversationId.value == cascadeId) {
                    _currentConversationId.value = null
                    _steps.value = emptyList()
                    _rawSteps.value = emptyList()
                }
                loadConversations()
                _statusMessage.value = "Deleted"
            } catch (e: Exception) {
                _statusMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    // ── Models ──

    fun loadModels() {
        viewModelScope.launch {
            try {
                val result = portaClient.fetchModels()
                val configs = result.getAsJsonArray("clientModelConfigs")
                val models = configs?.map { el ->
                    val obj = el.asJsonObject
                    ModelConfig(
                        id = obj.getAsJsonObject("modelOrAlias")?.get("model")?.asString ?: "",
                        label = obj.get("label")?.asString ?: "",
                        supportsImages = obj.get("supportsImages")?.asBoolean ?: false,
                        isRecommended = obj.get("isRecommended")?.asBoolean ?: false,
                        quotaRemaining = obj.getAsJsonObject("quotaInfo")
                            ?.get("remainingFraction")?.asFloat ?: 1f
                    )
                } ?: emptyList()
                _availableModels.value = models
                _lastModelRefresh.value = System.currentTimeMillis()

                val defaultOverride = result.getAsJsonObject("defaultOverrideModelConfig")
                    ?.getAsJsonObject("modelOrAlias")
                    ?.get("model")?.asString
                _defaultModel.value = defaultOverride
            } catch (e: Exception) {
                // Silently fail — models list is optional
            }
        }
    }

    fun selectModel(modelId: String?) {
        _selectedModel.value = modelId
        viewModelScope.launch {
            dataStore.edit {
                if (modelId != null) it[PrefKeys.SELECTED_MODEL] = modelId
                else it.remove(PrefKeys.SELECTED_MODEL)
            }
        }
    }

    fun setPlannerType(type: String?) {
        _plannerType.value = type
        viewModelScope.launch {
            dataStore.edit {
                if (type != null) it[PrefKeys.PLANNER_TYPE] = type
                else it.remove(PrefKeys.PLANNER_TYPE)
            }
        }
    }

    // ── Revert ──

    fun revertToStep(stepIndex: Int) {
        val cascadeId = _currentConversationId.value ?: return
        viewModelScope.launch {
            try {
                _statusMessage.value = "Reverting..."
                portaClient.revertToStep(cascadeId, stepIndex, _selectedModel.value)
                _statusMessage.value = "Reverted to step $stepIndex"
            } catch (e: Exception) {
                _statusMessage.value = "Revert failed: ${e.message}"
            }
        }
    }

    // ── Approvals ──

    fun approveStep(step: AgentStep) {
        val cascadeId = _currentConversationId.value ?: return
        val info = step.approvalInfo ?: return

        viewModelScope.launch {
            try {
                when (info.type) {
                    ApprovalType.COMMAND -> {
                        portaClient.handleCommandAction(
                            cascadeId, info.trajectoryId, info.stepIndex, true
                        )
                    }
                    ApprovalType.PERMISSION -> {
                        portaClient.handleFilePermission(
                            cascadeId, info.trajectoryId, info.stepIndex, true
                        )
                    }
                    ApprovalType.QUESTION -> {
                        portaClient.handleCommandAction(
                            cascadeId, info.trajectoryId, info.stepIndex, true
                        )
                    }
                    ApprovalType.OTHER -> {
                        portaClient.handleCommandAction(
                            cascadeId, info.trajectoryId, info.stepIndex, true
                        )
                    }
                }
                _statusMessage.value = "Approved"
            } catch (e: Exception) {
                _statusMessage.value = "Approval failed: ${e.message}"
            }
        }
    }

    fun rejectStep(step: AgentStep) {
        val cascadeId = _currentConversationId.value ?: return
        val info = step.approvalInfo ?: return

        viewModelScope.launch {
            try {
                when (info.type) {
                    ApprovalType.COMMAND -> {
                        portaClient.handleCommandAction(
                            cascadeId, info.trajectoryId, info.stepIndex, false
                        )
                    }
                    ApprovalType.PERMISSION -> {
                        portaClient.handleFilePermission(
                            cascadeId, info.trajectoryId, info.stepIndex, false
                        )
                    }
                    ApprovalType.QUESTION -> {
                        portaClient.handleCommandAction(
                            cascadeId, info.trajectoryId, info.stepIndex, false
                        )
                    }
                    ApprovalType.OTHER -> {
                        portaClient.handleCommandAction(
                            cascadeId, info.trajectoryId, info.stepIndex, false
                        )
                    }
                }
                _statusMessage.value = "Rejected"
            } catch (e: Exception) {
                _statusMessage.value = "Rejection failed: ${e.message}"
            }
        }
    }

    // ── Trajectory-based approvals (used by MessageBubble cards) ──

    fun approveCommandByTrajectory(trajectoryId: String, stepIndex: Int) {
        val cascadeId = _currentConversationId.value ?: return
        viewModelScope.launch {
            try {
                portaClient.handleCommandAction(cascadeId, trajectoryId, stepIndex, true)
                notificationService.dismissApproval()
                _statusMessage.value = "Approved"
            } catch (e: Exception) {
                _statusMessage.value = "Approval failed: ${e.message}"
            }
        }
    }

    fun rejectCommandByTrajectory(trajectoryId: String, stepIndex: Int) {
        val cascadeId = _currentConversationId.value ?: return
        viewModelScope.launch {
            try {
                portaClient.handleCommandAction(cascadeId, trajectoryId, stepIndex, false)
                notificationService.dismissApproval()
                _statusMessage.value = "Rejected"
            } catch (e: Exception) {
                _statusMessage.value = "Rejection failed: ${e.message}"
            }
        }
    }

    fun handleFilePermissionByTrajectory(
        trajectoryId: String, stepIndex: Int, allow: Boolean, scope: Int
    ) {
        val cascadeId = _currentConversationId.value ?: return
        viewModelScope.launch {
            try {
                portaClient.handleFilePermission(
                    cascadeId, trajectoryId, stepIndex, allow,
                    scope = if (allow) scope else 0
                )
                _statusMessage.value = if (allow) "Allowed" else "Denied"
                notificationService.dismissApproval()
            } catch (e: Exception) {
                _statusMessage.value = "Permission failed: ${e.message}"
            }
        }
    }

    fun answerQuestionByTrajectory(
        trajectoryId: String,
        stepIndex: Int,
        selectedOptions: List<Int>,
        writeInText: String? = null
    ) {
        val cascadeId = _currentConversationId.value ?: return
        viewModelScope.launch {
            try {
                portaClient.answerQuestion(
                    cascadeId, trajectoryId, stepIndex, selectedOptions, writeInText
                )
                notificationService.dismissApproval()
                _statusMessage.value = "Answer submitted"
            } catch (e: Exception) {
                _statusMessage.value = "Answer failed: ${e.message}"
            }
        }
    }

    // ── Voice ──

    fun startVoiceInput() {
        voiceInput.startListening(_voiceLanguage.value)
    }

    /** Available voice language options. */
    val voiceLanguageOptions = listOf(
        "id-ID" to "Indonesia",
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "jv-ID" to "Jawa",
        "su-ID" to "Sunda",
        "zh-CN" to "中文 (Mandarin)",
        "ja-JP" to "日本語",
        "ko-KR" to "한국어",
    )

    fun setVoiceLanguage(code: String) {
        _voiceLanguage.value = code
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.VOICE_LANGUAGE] = code }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.THEME_MODE] = mode.name }
        }
    }

    fun stopVoiceInput() {
        voiceInput.stopListening()
    }

    fun cancelVoiceInput() {
        voiceInput.cancel()
    }

    fun toggleTts() {
        _ttsEnabled.value = !_ttsEnabled.value
        if (!_ttsEnabled.value) tts.stop()
    }

    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    // ── Notification settings ──

    fun setNotifyEnabled(enabled: Boolean) {
        _notifyEnabled.value = enabled
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.NOTIFY_ENABLED] = enabled }
        }
    }

    fun setNotifyApprovalEnabled(enabled: Boolean) {
        _notifyApprovalEnabled.value = enabled
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.NOTIFY_APPROVAL_ENABLED] = enabled }
        }
    }

    fun setNotifySound(enabled: Boolean) {
        _notifySound.value = enabled
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.NOTIFY_SOUND] = enabled }
        }
    }

    fun setNotifyVibrate(enabled: Boolean) {
        _notifyVibrate.value = enabled
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.NOTIFY_VIBRATE] = enabled }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        _autoConnect.value = enabled
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.AUTO_CONNECT] = enabled }
        }
    }

    // ── Glasses ──

    fun connectGlasses(context: Context) {
        _glassesProvider.connect(context)
    }

    fun disconnectGlasses() {
        _glassesProvider.disconnect()
    }

    fun setGlassesProvider(providerId: String, persist: Boolean = true) {
        _glassesProvider.destroy()
        _glassesProvider = when (providerId) {
            "rokid_cxrl" -> RokidCXRLProvider()
            else -> MockGlassesProvider()
        }
        _glassesProviderName.value = _glassesProvider.providerName
        if (persist) {
            viewModelScope.launch {
                dataStore.edit { it[PrefKeys.GLASSES_PROVIDER] = providerId }
            }
        }
    }

    fun setAutoForwardToGlasses(enabled: Boolean) {
        _autoForwardToGlasses.value = enabled
        viewModelScope.launch {
            dataStore.edit { it[PrefKeys.GLASSES_AUTO_FORWARD] = enabled }
        }
    }

    fun testGlassesDisplay() {
        _glassesProvider.displayText(
            "Porta Test",
            "If you can see this on your AR glasses, the connection is working! 🎉"
        )
    }

    fun testGlassesPhoto() {
        _glassesProvider.takePhoto(callback = object : PhotoCallback {
            override fun onPhotoCaptured(jpegData: ByteArray, width: Int, height: Int) {
                _statusMessage.value = "📷 Photo captured (${width}x${height}, ${jpegData.size} bytes)"
            }
            override fun onPhotoError(error: String) {
                _statusMessage.value = "📷 Photo error: $error"
            }
        })
    }

    private fun forwardToGlasses(text: String) {
        if (_autoForwardToGlasses.value && _glassesProvider.state.value == GlassesState.SCENE_ACTIVE) {
            _glassesProvider.displayText("Porta", text)
        }
    }

    // ── WebSocket message handling ──

    private fun handleIncomingMessage(message: PortaMessage) {
        when (message) {
            is PortaMessage.Ready -> {
                stepCount = message.stepCount
                _statusMessage.value = "Ready (${message.stepCount} steps)"
                Log.d("BridgeVM", "WS Ready: stepCount=${message.stepCount} convo=${_currentConversationId.value?.take(8)}")

                // Fetch conversation history — limit to last 200 steps for large conversations
                // to avoid huge payloads (e.g. 25MB for 2149 steps)
                if (message.stepCount > 0) {
                    val maxHistorySteps = 200
                    val fromOffset = if (message.stepCount > maxHistorySteps) {
                        message.stepCount - maxHistorySteps
                    } else {
                        0
                    }
                    Log.d("BridgeVM", "Requesting history via syncOffset($fromOffset) (total=${message.stepCount})")
                    portaClient.syncOffset(fromOffset)

                    // Retry if steps don't arrive within 5s (large payloads can take 3+ seconds)
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(5000)
                        if (_steps.value.isEmpty() && stepCount > 0) {
                            Log.d("BridgeVM", "Steps not received after Ready, retrying syncOffset($fromOffset)...")
                            portaClient.syncOffset(fromOffset)
                        }
                    }
                } else {
                    Log.d("BridgeVM", "New conversation (0 steps), no history to fetch")
                }
            }

            is PortaMessage.Steps -> {
                // Guard: discard stale packets from a previous conversation
                val expectedId = _currentConversationId.value
                val incomingId = portaClient.currentCascadeId
                if (expectedId != null && incomingId != null && expectedId != incomingId) {
                    return // Stale packet from old conversation
                }

                val newSteps = message.steps.mapIndexed { i, json ->
                    AgentStep.fromJson(message.offset + i, json)
                }

                // Merge AgentStep objects
                val currentSteps = _steps.value.toMutableList()
                for (step in newSteps) {
                    val existingIndex = currentSteps.indexOfFirst { it.index == step.index }
                    if (existingIndex >= 0) {
                        currentSteps[existingIndex] = step
                    } else {
                        currentSteps.add(step)
                    }
                }
                currentSteps.sortBy { it.index }
                _steps.value = currentSteps

                // Merge raw JSON steps for chat messages
                // Use relative indexing to avoid padding thousands of empty entries
                val currentRaw = _rawSteps.value.toMutableList()
                if (currentRaw.isEmpty() && message.offset > 0) {
                    // First batch with non-zero offset — store directly
                    rawStepsBaseOffset = message.offset
                    currentRaw.addAll(message.steps)
                } else {
                    for ((i, json) in message.steps.withIndex()) {
                        val idx = message.offset + i - rawStepsBaseOffset
                        if (idx < 0) continue // Skip steps before our base
                        // Pad if needed (only small gaps)
                        while (currentRaw.size <= idx) currentRaw.add(JsonObject())
                        currentRaw[idx] = json
                    }
                }
                _rawSteps.value = currentRaw
                Log.d("BridgeVM", "Steps merged: rawSteps=${currentRaw.size} baseOffset=$rawStepsBaseOffset offset=${message.offset} incoming=${message.steps.size}")

                // Update latest text response for HUD
                val latestText = currentSteps
                    .filter { it.plannerText != null }
                    .lastOrNull()
                    ?.plannerText
                if (latestText != null) {
                    _latestResponse.value = latestText
                    forwardToGlasses(latestText)
                }

                // Update step count
                val newEnd = message.offset + message.steps.size
                if (newEnd > stepCount) {
                    stepCount = newEnd
                }

                // Check for new steps needing approval → fire notification
                if (currentSteps.none { it.needsApproval }) {
                    notificationService.dismissApproval()
                }

                if (_notifyApprovalEnabled.value) {
                    val isChatActive = _isConversationScreenActive.value
                    val suppressNotification = isChatActive
                    val cascadeId = _currentConversationId.value ?: ""

                    val pendingSteps = currentSteps.filter { it.needsApproval }
                    if (pendingSteps.isNotEmpty() && !suppressNotification) {
                        val stepToNotify = pendingSteps.firstOrNull { step ->
                            val stepKey = "$cascadeId:${step.index}"
                            !notifiedSteps.contains(stepKey)
                        }

                        if (stepToNotify != null) {
                            val stepKey = "$cascadeId:${stepToNotify.index}"
                            notifiedSteps.add(stepKey)

                            val description = stepToNotify.toolAction
                                ?: stepToNotify.toolSummary
                                ?: stepToNotify.approvalInfo?.let {
                                    when (it.type) {
                                        ApprovalType.COMMAND -> "Command: ${stepToNotify.commandInfo?.commandLine ?: "execute command"}"
                                        ApprovalType.PERMISSION -> "File permission request"
                                        ApprovalType.QUESTION -> "Agent has a question"
                                        ApprovalType.OTHER -> "Action requires approval"
                                    }
                                }
                                ?: "Agent needs your approval to proceed"

                            // Look up conversation title for richer notification
                            val convoSummary = _conversations.value[cascadeId]
                            val convoTitle = if (convoSummary != null) {
                                UiUtils.displayTitle(convoSummary)
                            } else {
                                "Conversation"
                            }
                            val notifTitle = "⚠️ $convoTitle"

                            // Per-conversation notification ID so multiple conversations get separate notifications
                            val perConvoNotifId = (cascadeId.hashCode() and 0x7FFFFFFF) % 50000 + 2000

                            notificationService.showApprovalNeeded(
                                title = notifTitle,
                                description = description,
                                cascadeId = cascadeId,
                                playSound = _notifySound.value,
                                vibrate = _notifyVibrate.value,
                                notificationId = perConvoNotifId
                            )
                        }
                    }
                }
            }

            is PortaMessage.Status -> {
                val wasRunning = _statusMessage.value == "Agent running..."
                _statusMessage.value = if (message.running) "Agent running..." else "Agent idle"

                // Agent just finished a task
                if (wasRunning && !message.running) {
                    // TTS
                    if (_ttsEnabled.value) {
                        val lastText = _latestResponse.value
                        if (lastText.isNotBlank()) {
                            tts.speak(lastText.take(500))
                        }
                    }

                    // System notification
                    if (_notifyEnabled.value) {
                        val summary = _latestResponse.value.take(200).ifBlank { "Task finished" }
                        val cascadeId = _currentConversationId.value ?: ""
                        notificationService.showTaskComplete(
                            title = "✅ Task Complete",
                            summary = summary,
                            cascadeId = cascadeId,
                            playSound = _notifySound.value,
                            vibrate = _notifyVibrate.value
                        )
                    }
                }
            }

            else -> {}
        }
    }

    private fun generateMockProjects(): List<Project> {
        val now = System.currentTimeMillis()
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        val initial = listOf(
            Project(
                id = "p-blocked-1",
                title = "Analyzing Odoo 8 Perpetual Inventory Logic & Verification",
                status = ProjectStatus.BLOCKED,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 2))
            ),
            Project(
                id = "p-inprogress-1",
                title = "Fixing Porta Android Auto Callback & Media Session Synchronization",
                status = ProjectStatus.IN_PROGRESS,
                lastUpdated = formatter.format(java.util.Date(now))
            ),
            Project(
                id = "p-inprogress-2",
                title = "Integrating Porta With Rokid Glass CXR-L Bluetooth Stack",
                status = ProjectStatus.IN_PROGRESS,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 4))
            ),
            Project(
                id = "p-idle-1",
                title = "Investigating Fail2ban Blocklist Synchronization & Logs",
                status = ProjectStatus.IDLE,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 5)),
                hasIndicator = true
            ),
            Project(
                id = "p-idle-2",
                title = "Updating Local Repository Packages & Locking Dependencies",
                status = ProjectStatus.IDLE,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 18)),
                timeBadge = "18h"
            ),
            Project(
                id = "p-idle-3",
                title = "Running The Web Dashboard Profiler & Checking Memory Leak",
                status = ProjectStatus.IDLE,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 6)),
                hasIndicator = true
            ),
            Project(
                id = "p-idle-4",
                title = "Tracing Peterongan Musical Notation Parser & Engine Logs",
                status = ProjectStatus.IDLE,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 22)),
                timeBadge = "22h"
            ),
            Project(
                id = "p-idle-5",
                title = "Diagnosing Infinia Server Latency Spikes Under Load",
                status = ProjectStatus.IDLE,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 48)),
                timeBadge = "2d"
            ),
            Project(
                id = "p-idle-6",
                title = "Displaying Last Login Odoo System Log Audit Trail",
                status = ProjectStatus.IDLE,
                lastUpdated = formatter.format(java.util.Date(now - 3600000 * 48)),
                timeBadge = "2d"
            )
        )

        val templates = listOf(
            "Refactoring Auth Middleware & Token Rotation",
            "Optimizing Largest Contentful Paint (LCP) performance",
            "Translating Web Extension UI & Popup Content",
            "Implementing SQLite Shard Backups & Maintenance Schedule",
            "Upgrading TailwindCSS to Version 4 & Linting Classes",
            "Setting up Firebase Crashlytics on Android Debug APK",
            "Auditing Ensembl Database Variant Consequences Fetcher",
            "Profiling PWA Service Worker Cache Expiration Logic",
            "Resolving Dart Tooling Daemon WS Port Collisions",
            "Debugging Voice Input Noise Reduction Thresholds",
            "Reviewing Firebase Data Connect PostgreSQL Relations",
            "Testing WebUSB Connectivity with Rokid AR Glasses",
            "Improving HSL Adaptive Colors contrast for Accessibility",
            "Benchmarking MMseqs2 Sequence Similarity Searches",
            "Updating CLI build scripts with UV Package Manager",
            "Investigating memory leaks inside local Proxy connections",
            "Generating OpenAPI schema specifications from Express routes",
            "Formatting CSS layout systems for Ultra-Wide displays",
            "Pre-indexing Cloud Firestore databases with Composite Indexes",
            "Drafting App Store description and Fastlane automated metadata"
        )

        val list = initial.toMutableList()
        for (i in 0 until 91) {
            val template = templates[i % templates.size]
            val index = i + 7
            val daysAgo = (index / 3) + 2
            list.add(
                Project(
                    id = "p-idle-$index",
                    title = "$template (Sprint #${(index + 9) / 10})",
                    status = ProjectStatus.IDLE,
                    lastUpdated = formatter.format(java.util.Date(now - 3600000L * 24 * daysAgo)),
                    timeBadge = "${daysAgo}d"
                )
            )
        }
        return list
    }

    private fun loadProjectsFromDisk() {
        try {
            if (projectsFile.exists()) {
                val json = projectsFile.readText()
                val type = object : com.google.gson.reflect.TypeToken<List<Project>>() {}.type
                val loadedList: List<Project> = gson.fromJson(json, type) ?: emptyList()
                _projects.value = loadedList
            } else {
                val defaultList = generateMockProjects()
                _projects.value = defaultList
                saveProjectsToDisk()
            }
        } catch (e: Exception) {
            _projects.value = generateMockProjects()
        }

        // Restore active project
        val sharedPrefs = getApplication<Application>().getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
        _activeProjectId.value = sharedPrefs.getString("active_project_id", "p-inprogress-1") ?: "p-inprogress-1"
    }

    private fun saveProjectsToDisk() {
        try {
            projectsFile.writeText(gson.toJson(_projects.value))
        } catch (_: Exception) {}
    }

    fun setActiveProjectId(id: String?) {
        _activeProjectId.value = id
        getApplication<Application>().getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("active_project_id", id)
            .apply()
    }

    fun createProject(title: String, status: ProjectStatus): Project {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val newProj = Project(
            id = "p-custom-${System.currentTimeMillis()}",
            title = title,
            status = status,
            lastUpdated = formatter.format(java.util.Date()),
            hasIndicator = status == ProjectStatus.IDLE
        )
        _projects.value = listOf(newProj) + _projects.value
        saveProjectsToDisk()
        return newProj
    }

    fun updateProjectStatus(id: String, status: ProjectStatus) {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        _projects.value = _projects.value.map {
            if (it.id == id) {
                it.copy(status = status, lastUpdated = formatter.format(java.util.Date()))
            } else it
        }
        saveProjectsToDisk()
    }

    fun updateProjectTitle(id: String, title: String) {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        _projects.value = _projects.value.map {
            if (it.id == id) {
                it.copy(title = title, lastUpdated = formatter.format(java.util.Date()))
            } else it
        }
        saveProjectsToDisk()
    }

    fun deleteProject(id: String) {
        _projects.value = _projects.value.filter { it.id != id }
        saveProjectsToDisk()
        if (_activeProjectId.value == id) {
            setActiveProjectId(null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceInput.destroy()
        tts.destroy()
        notificationService.destroy()
        _glassesProvider.destroy()
        portaClient.destroy()
        PortaConnectionService.stop(getApplication())
    }
}
