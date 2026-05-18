package id.infinia.porta.viewmodel

import id.infinia.porta.ui.theme.ThemeMode

import android.app.Application
import android.content.Context
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
import id.infinia.porta.service.NotificationService
import id.infinia.porta.service.glasses.*
import id.infinia.porta.service.voice.TextToSpeechService
import id.infinia.porta.service.voice.VoiceInputService
import id.infinia.porta.shared.protocol.*
import id.infinia.porta.shared.protocol.stepsToMessages
import kotlinx.coroutines.flow.*
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
    }

    private val dataStore = application.settingsDataStore

    val portaClient = PortaClient(viewModelScope)

    // ── Offline queue ──
    val offlineQueue = OfflineMessageQueue(application)

    // ── Server profiles ──
    val serverProfiles = ServerProfileManager(application)

    // ── Notification service ──

    val notificationService = NotificationService(application)

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

    // ── Conversations ──

    private val _conversations = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val conversations: StateFlow<Map<String, JsonObject>> = _conversations.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    // ── Workspace grouping ──

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
            val wsName = extractWorkspaceName(summary)
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

    /** Recent conversations across all workspaces (last 10). */
    val recentConversations: StateFlow<List<Pair<String, JsonObject>>> = _conversations.map { convos ->
        convos.entries
            .sortedByDescending { it.value.get("lastModifiedTime")?.asString ?: "" }
            .take(10)
            .map { it.key to it.value }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Extract workspace display name from conversation summary JSON. */
    private fun extractWorkspaceName(summary: JsonObject): String {
        val workspaces = summary.getAsJsonArray("workspaces")
        if (workspaces == null || workspaces.size() == 0) return "Others"
        val ws = workspaces[0].asJsonObject
        val repo = ws.getAsJsonObject("repository")?.get("computedName")?.asString
        if (repo != null) return repo.substringAfterLast("/")
        val uri = ws.get("workspaceFolderAbsoluteUri")?.asString
        if (uri != null) return uri.substringAfterLast("/")
        return "Others"
    }

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

    /** Planner mode: null = default, "fast" = no planning, "plan" = full planning */
    private val _plannerType = MutableStateFlow<String?>(null)
    val plannerType: StateFlow<String?> = _plannerType.asStateFlow()

    // ── Step tracking ──
    private var stepCount = 0

    init {
        // Load saved settings first
        viewModelScope.launch {
            dataStore.data.first().let { prefs ->
                _host.value = prefs[PrefKeys.HOST] ?: "localhost"
                _port.value = prefs[PrefKeys.PORT] ?: 443
                _authToken.value = prefs[PrefKeys.AUTH_TOKEN]
                _useTls.value = prefs[PrefKeys.USE_TLS] ?: true
                _notifyEnabled.value = prefs[PrefKeys.NOTIFY_ENABLED] ?: true
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

        // Update status message based on connection state
        viewModelScope.launch {
            connectionState.collect { state ->
                _statusMessage.value = when (state) {
                    ConnectionState.DISCONNECTED -> "Not connected"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.CONNECTED -> {
                        // Drain offline queue on reconnect
                        drainOfflineQueue()
                        "Connected"
                    }
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                    ConnectionState.ERROR -> "Connection error"
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
        portaClient.configure(_host.value, _port.value, _authToken.value, _useTls.value)
        loadConversations()
    }

    fun disconnect() {
        portaClient.disconnectWebSocket()
        _steps.value = emptyList()
        _latestResponse.value = ""
        _currentConversationId.value = null
    }

    // ── Conversations ──

    fun loadConversations() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val convos = portaClient.fetchConversations()
                _conversations.value = convos
                _statusMessage.value = "Loaded ${convos.size} conversations"
            } catch (e: Exception) {
                _statusMessage.value = "Failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectConversation(cascadeId: String) {
        _currentConversationId.value = cascadeId
        _steps.value = emptyList()
        _rawSteps.value = emptyList()
        _latestResponse.value = ""
        stepCount = 0
        portaClient.connectWebSocket(cascadeId)
    }

    fun createNewConversation(workspaceUri: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val cascadeId = portaClient.createConversation(workspaceUri)
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

                // Fetch full conversation history from step 0
                if (message.stepCount > 0) {
                    portaClient.syncOffset(0)

                    // Retry if steps don't arrive within 1.5s
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1500)
                        if (_steps.value.isEmpty() && stepCount > 0) {
                            println("[BridgeVM] Steps not received after Ready, retrying sync(0)...")
                            portaClient.syncOffset(0)
                        }
                    }
                }
            }

            is PortaMessage.Steps -> {
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
                val currentRaw = _rawSteps.value.toMutableList()
                for ((i, json) in message.steps.withIndex()) {
                    val idx = message.offset + i
                    // Pad if needed
                    while (currentRaw.size <= idx) currentRaw.add(JsonObject())
                    currentRaw[idx] = json
                }
                _rawSteps.value = currentRaw

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
                if (_notifyEnabled.value) {
                    val pendingSteps = newSteps.filter { it.needsApproval }
                    if (pendingSteps.isNotEmpty()) {
                        val step = pendingSteps.last()
                        val description = step.toolAction
                            ?: step.toolSummary
                            ?: step.approvalInfo?.let {
                                when (it.type) {
                                    ApprovalType.COMMAND -> "Command: ${step.commandInfo?.commandLine ?: "execute command"}"
                                    ApprovalType.PERMISSION -> "File permission request"
                                    ApprovalType.OTHER -> "Action requires approval"
                                }
                            }
                            ?: "Agent needs your approval to proceed"
                        val cascadeId = _currentConversationId.value ?: ""
                        notificationService.showApprovalNeeded(
                            title = "⚠️ Approval Needed",
                            description = description,
                            cascadeId = cascadeId,
                            playSound = _notifySound.value,
                            vibrate = _notifyVibrate.value
                        )
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

    override fun onCleared() {
        super.onCleared()
        voiceInput.destroy()
        tts.destroy()
        notificationService.destroy()
        _glassesProvider.destroy()
        portaClient.destroy()
    }
}
