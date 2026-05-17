package id.infinia.porta.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import id.infinia.porta.data.SettingsReader
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Main screen shown when the user opens Porta in Android Auto.
 *
 * Displays:
 * - Recent conversations as a list
 * - Running conversation status
 * - Quick action to start a new voice conversation
 */
class PortaMainCarScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "PortaAuto"
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var conversations: List<ConvoSummary> = emptyList()
    private var isLoading = true
    private var errorMessage: String? = null
    private var connectionConfig: ConnectionConfig? = null

    data class ConvoSummary(
        val id: String,
        val title: String,
        val status: String,
        val stepCount: Int,
        val lastModified: String
    )

    data class ConnectionConfig(
        val host: String,
        val port: Int,
        val authToken: String?,
        val useTls: Boolean
    )

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                scope.cancel()
            }
        })
        loadData()
    }

    private fun loadData() {
        scope.launch {
            try {
                Log.i(TAG, "Loading data...")
                val config = readConnectionConfig()
                if (config == null) {
                    Log.w(TAG, "No connection config found")
                    errorMessage = "Not configured.\nOpen Porta on your phone first, then retry."
                    isLoading = false
                    invalidate()
                    return@launch
                }
                connectionConfig = config
                Log.i(TAG, "Config loaded: ${config.host}:${config.port}")

                val scheme = if (config.useTls) "https" else "http"
                val url = "$scheme://${config.host}:${config.port}/api/conversations"

                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (!config.authToken.isNullOrBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                    }
                    .build()

                Log.i(TAG, "Fetching conversations from $url")
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Server error: ${response.code}")
                    errorMessage = "Server error: ${response.code}"
                    isLoading = false
                    invalidate()
                    return@launch
                }

                val body = response.body?.string() ?: "{}"
                val json = try {
                    gson.fromJson(body, JsonObject::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse response: ${e.message}")
                    errorMessage = "Invalid server response"
                    isLoading = false
                    invalidate()
                    return@launch
                }

                val summaries = json.getAsJsonObject("trajectorySummaries") ?: JsonObject()

                conversations = summaries.entrySet()
                    .mapNotNull { (id, value) ->
                        try {
                            val obj = value.asJsonObject
                            ConvoSummary(
                                id = id,
                                title = obj.get("summary")?.asString ?: id.take(12),
                                status = obj.get("status")?.asString ?: "unknown",
                                stepCount = obj.get("stepCount")?.asInt ?: 0,
                                lastModified = obj.get("lastModifiedTime")?.asString ?: ""
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping conversation $id: ${e.message}")
                            null
                        }
                    }
                    .sortedByDescending { it.lastModified }
                    .take(10) // Android Auto list limits

                Log.i(TAG, "Loaded ${conversations.size} conversations")
                isLoading = false
                invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                errorMessage = "Connection failed:\n${e.message?.take(100)}"
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Loading state
        if (isLoading) {
            return MessageTemplate.Builder("Connecting to Porta...")
                .setTitle("Porta")
                .setLoading(true)
                .build()
        }

        // Error state
        if (errorMessage != null) {
            return MessageTemplate.Builder(errorMessage!!)
                .setTitle("Porta")
                .addAction(
                    Action.Builder()
                        .setTitle("Retry")
                        .setOnClickListener {
                            isLoading = true
                            errorMessage = null
                            invalidate()
                            loadData()
                        }
                        .build()
                )
                .build()
        }

        // Empty state
        if (conversations.isEmpty()) {
            return MessageTemplate.Builder("No conversations yet.\nStart one with voice!")
                .setTitle("Porta")
                .addAction(
                    Action.Builder()
                        .setTitle("New Chat")
                        .setOnClickListener {
                            screenManager.push(PortaVoiceChatScreen(carContext, connectionConfig))
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Refresh")
                        .setOnClickListener {
                            isLoading = true
                            invalidate()
                            loadData()
                        }
                        .build()
                )
                .build()
        }

        // Conversation list
        val listBuilder = ItemList.Builder()

        for (convo in conversations) {
            val isRunning = convo.status == "CASCADE_RUN_STATUS_RUNNING"
            val statusText = if (isRunning) "⚡ Running" else "✓ ${convo.stepCount} steps"

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(convo.title)
                    .addText(statusText)
                    .setOnClickListener {
                        screenManager.push(
                            PortaConvoDetailScreen(carContext, convo, connectionConfig)
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Porta")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("New Chat")
                    .setOnClickListener {
                        screenManager.push(PortaVoiceChatScreen(carContext, connectionConfig))
                    }
                    .build()
            )
            .build()
    }

    private fun readConnectionConfig(): ConnectionConfig? {
        return try {
            SettingsReader.readConnectionConfig(carContext)?.let { config ->
                Log.i(TAG, "Settings read OK: host=${config.host}")
                ConnectionConfig(config.host, config.port, config.authToken, config.useTls)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read settings: ${e.message}", e)
            null
        }
    }
}
