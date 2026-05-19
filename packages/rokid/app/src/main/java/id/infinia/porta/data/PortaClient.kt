package id.infinia.porta.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import id.infinia.porta.shared.protocol.ConnectionState
import android.util.Log
import id.infinia.porta.shared.protocol.PortaMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for connecting to the Porta proxy.
 *
 * Implements the Porta WebSocket protocol:
 * - Connects to `/api/conversations/{cascadeId}/ws`
 * - Receives `ready`, `steps`, `status` messages
 * - Sends `sync` and `refresh` messages
 * - Handles auto-reconnection with exponential backoff
 *
 * This is the primary bridge between the Android app and the
 * Antigravity Language Server (via Porta proxy).
 */
class PortaClient(
    private val scope: CoroutineScope
) {
    private val gson = Gson()

    // ── Connection config ──

    private var host: String = "localhost"
    private var port: Int = 3170
    private var authToken: String? = null
    private var useTls: Boolean = false

    // ── OkHttp ──

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── State flows ──

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _agentRunning = MutableStateFlow(false)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<PortaMessage>(
        replay = 0,
        extraBufferCapacity = 100
    )
    val incomingMessages: SharedFlow<PortaMessage> = _incomingMessages.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val error: SharedFlow<String> = _error.asSharedFlow()

    // ── Internal state ──

    private var webSocket: WebSocket? = null
    private var _currentCascadeId: String? = null
    val currentCascadeId: String? get() = _currentCascadeId

    /** Reset the agent running state (used when switching conversations). */
    fun resetRunningState() {
        _agentRunning.value = false
    }
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 10_000L // 10 seconds max
    private val maxReconnectAttempts = 20

    // ── Configuration ──

    fun configure(host: String, port: Int, authToken: String? = null, useTls: Boolean = false) {
        this.host = host
        this.port = port
        this.authToken = authToken
        this.useTls = useTls
    }

    // ── REST API calls ──

    /**
     * Fetch the list of conversations from the Porta proxy.
     *
     * GET /api/conversations
     * Returns: { trajectorySummaries: { [cascadeId]: summary } }
     */
    suspend fun fetchConversations(): Map<String, JsonObject> = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations")
        val request = Request.Builder()
            .url(url)
            .apply { addAuthHeader(this) }
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to fetch conversations: ${resp.code}")
            }

            val body = resp.body?.string() ?: "{}"
            val json = gson.fromJson(body, JsonObject::class.java)
            val summaries = json.getAsJsonObject("trajectorySummaries") ?: JsonObject()

            summaries.entrySet().associate { (key, value) ->
                key to value.asJsonObject
            }
        }
    }

    /**
     * Fetch available workspaces from the Porta proxy.
     *
     * GET /api/workspaces
     */
    suspend fun fetchWorkspaces(): JsonObject = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/workspaces")
        val request = Request.Builder()
            .url(url)
            .apply { addAuthHeader(this) }
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to fetch workspaces: ${resp.code}")
            }

            val body = resp.body?.string() ?: "{}"
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    /**
     * Send a message to a conversation.
     *
     * POST /api/conversations/{id}/messages
     */
    suspend fun sendMessage(
        cascadeId: String,
        text: String,
        model: String? = null,
        plannerType: String? = null,
        media: List<Map<String, String>>? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations/$cascadeId/messages")

        val payload = JsonObject().apply {
            add("items", gson.toJsonTree(listOf(
                mapOf("text" to text)
            )))
            model?.let { addProperty("model", it) }
            plannerType?.let { addProperty("plannerType", it) }
            if (!media.isNullOrEmpty()) {
                add("media", gson.toJsonTree(media))
            }
            addProperty("fileAccessGranted", true)
        }

        val requestBody = payload.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to send message: ${resp.code}")
            }

            val body = resp.body?.string() ?: "{}"
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    /**
     * Create a new conversation.
     *
     * POST /api/conversations
     */
    suspend fun createConversation(
        workspaceUri: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations")

        val payload = JsonObject().apply {
            workspaceUri?.let {
                addProperty("workspaceFolderAbsoluteUri", it)
            }
            addProperty("fileAccessGranted", true)
        }

        val requestBody = payload.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to create conversation: ${resp.code}")
            }

            val body = resp.body?.string() ?: "{}"
            val json = gson.fromJson(body, JsonObject::class.java)
            json.get("cascadeId")?.asString
                ?: throw PortaApiException("No cascadeId in response")
        }
    }

    /**
     * Approve or reject a command action.
     *
     * POST /api/conversations/{id}/command-action
     */
    suspend fun handleCommandAction(
        cascadeId: String,
        trajectoryId: String,
        stepIndex: Int,
        approved: Boolean
    ) = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations/$cascadeId/command-action")

        val payload = JsonObject().apply {
            addProperty("trajectoryId", trajectoryId)
            addProperty("stepIndex", stepIndex)
            addProperty("approved", approved)
        }

        val requestBody = payload.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to handle command action: ${resp.code}")
            }
        }
    }

    /**
     * Handle a file permission request.
     *
     * POST /api/conversations/{id}/file-permission
     */
    suspend fun handleFilePermission(
        cascadeId: String,
        trajectoryId: String,
        stepIndex: Int,
        allow: Boolean,
        scope: Int = 2 // CONVERSATION scope
    ) = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations/$cascadeId/file-permission")

        val payload = JsonObject().apply {
            addProperty("trajectoryId", trajectoryId)
            addProperty("stepIndex", stepIndex)
            addProperty("allow", allow)
            addProperty("scope", scope)
        }

        val requestBody = payload.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to handle file permission: ${resp.code}")
            }
        }
    }

    /**
     * Stop a running conversation.
     *
     * POST /api/conversations/{id}/stop
     */
    suspend fun stopConversation(cascadeId: String) = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations/$cascadeId/stop")

        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to stop conversation: ${resp.code}")
            }
        }
    }

    /**
     * Delete a conversation.
     *
     * DELETE /api/conversations/{id}
     */
    suspend fun deleteConversation(cascadeId: String) = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations/$cascadeId")

        val request = Request.Builder()
            .url(url)
            .delete()
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to delete conversation: ${resp.code}")
            }
        }
    }

    /**
     * Fetch available models.
     *
     * GET /api/models
     */
    suspend fun fetchModels(): JsonObject = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/models")

        val request = Request.Builder()
            .url(url)
            .get()
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to fetch models: ${resp.code}")
            }

            val body = resp.body?.string() ?: "{}"
            gson.fromJson(body, JsonObject::class.java)
        }
    }

    /**
     * Revert conversation to a specific step.
     *
     * POST /api/conversations/{id}/revert
     */
    suspend fun revertToStep(
        cascadeId: String,
        stepIndex: Int,
        model: String? = null
    ) = withContext(Dispatchers.IO) {
        val url = buildHttpUrl("/api/conversations/$cascadeId/revert")

        val payload = JsonObject().apply {
            addProperty("stepIndex", stepIndex)
            model?.let { addProperty("model", it) }
        }

        val requestBody = payload.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply { addAuthHeader(this) }
            .addHeader("X-Porta-Request", "1")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw PortaApiException("Failed to revert: ${resp.code}")
            }
        }
    }

    // ── WebSocket connection ──

    /**
     * Connect to a conversation's WebSocket stream.
     */
    fun connectWebSocket(cascadeId: String) {
        disconnectWebSocket()
        _currentCascadeId = cascadeId
        reconnectAttempt = 0
        doConnect(cascadeId)
    }

    /**
     * Disconnect the WebSocket.
     */
    fun disconnectWebSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _currentCascadeId = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _agentRunning.value = false
    }

    /**
     * Send a sync message to update the step offset cursor.
     * Uses raw JSON to avoid Gson sealed-class serialization issues.
     */
    fun syncOffset(fromOffset: Int) {
        val json = """{"type":"sync","fromOffset":$fromOffset}"""
        Log.d("PortaClient", "Sending sync: $json")
        sendWsMessage(json)
    }

    /**
     * Send a refresh message to re-fetch all steps from the beginning.
     * Uses raw JSON to avoid Gson sealed-class serialization issues.
     */
    fun refresh() {
        val json = """{"type":"refresh"}"""
        Log.d("PortaClient", "Sending refresh: $json")
        sendWsMessage(json)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        disconnectWebSocket()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ── Internal ──

    private fun doConnect(cascadeId: String) {
        _connectionState.value = if (reconnectAttempt > 0)
            ConnectionState.RECONNECTING
        else
            ConnectionState.CONNECTING

        val scheme = if (useTls) "wss" else "ws"
        val url = "$scheme://$host:$port/api/conversations/$cascadeId/ws"

        val requestBuilder = Request.Builder().url(url)

        // Auth: use Authorization header for WebSocket
        authToken?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        val request = requestBuilder.build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempt = 0
                Log.d("PortaClient", "WebSocket connected to $cascadeId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = PortaMessage.parse(text)
                if (message != null) {
                    // Update running state from status messages
                    if (message is PortaMessage.Status) {
                        _agentRunning.value = message.running
                    }
                    // Emit to subscribers
                    scope.launch {
                        _incomingMessages.emit(message)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("PortaClient", "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("PortaClient", "WebSocket failure: ${t.message}")
                scope.launch {
                    _error.emit("Connection failed: ${t.message}")
                }
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        webSocket = null
        _agentRunning.value = false

        val cascadeId = _currentCascadeId ?: return

        // Give up after max attempts — set ERROR state
        if (reconnectAttempt >= maxReconnectAttempts) {
            Log.w("PortaClient", "Max reconnect attempts ($maxReconnectAttempts) reached, giving up")
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.launch {
                _error.emit("Connection lost after $maxReconnectAttempts attempts. Tap to reconnect.")
            }
            return
        }

        // Schedule reconnect with fast exponential backoff
        reconnectAttempt++
        val delay = minOf(
            500L * (1L shl minOf(reconnectAttempt, 4)),
            maxReconnectDelay
        )
        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            if (isActive && _currentCascadeId == cascadeId) {
                Log.d("PortaClient", "Reconnecting (attempt $reconnectAttempt/$maxReconnectAttempts)...")
                doConnect(cascadeId)
            }
        }
    }

    private fun sendWsMessage(json: String) {
        webSocket?.send(json) ?: run {
            scope.launch {
                _error.emit("Cannot send: WebSocket not connected")
            }
        }
    }

    private fun buildHttpUrl(path: String): String {
        val scheme = if (useTls) "https" else "http"
        return "$scheme://$host:$port$path"
    }

    private fun addAuthHeader(builder: Request.Builder) {
        authToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
    }
}

class PortaApiException(message: String) : Exception(message)
