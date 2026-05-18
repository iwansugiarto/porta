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
 * - "Recent" item — last 5 conversations across all workspaces
 * - Workspace tiles with active/total conversation counts
 * - Tap workspace → filtered conversation list
 */
class PortaMainCarScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "PortaAuto"
        // Android Auto list template typically supports 6 items max
        private const val MAX_LIST_ITEMS = 6
        private const val MAX_RECENT = 5
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var allConversations: List<ConvoSummary> = emptyList()
    private var workspaces: List<WorkspaceGroup> = emptyList()
    private var isLoading = true
    private var errorMessage: String? = null
    private var connectionConfig: ConnectionConfig? = null

    data class ConvoSummary(
        val id: String,
        val title: String,
        val status: String,
        val stepCount: Int,
        val lastModified: String,
        val workspaceName: String
    )

    data class WorkspaceGroup(
        val name: String,
        val activeCount: Int,
        val totalCount: Int,
        val conversations: List<ConvoSummary>
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

    private fun extractWorkspaceName(summary: JsonObject): String {
        val workspacesArr = summary.getAsJsonArray("workspaces")
        if (workspacesArr == null || workspacesArr.size() == 0) return "Others"
        val ws = workspacesArr[0].asJsonObject
        val repo = ws.getAsJsonObject("repository")?.get("computedName")?.asString
        if (repo != null) return repo.substringAfterLast("/")
        val uri = ws.get("workspaceFolderAbsoluteUri")?.asString
        if (uri != null) return uri.substringAfterLast("/")
        return "Others"
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

                allConversations = summaries.entrySet()
                    .mapNotNull { (id, value) ->
                        try {
                            val obj = value.asJsonObject
                            ConvoSummary(
                                id = id,
                                title = CarTextUtils.sanitize(
                                    obj.get("summary")?.asString ?: id.take(12),
                                    120
                                ),
                                status = obj.get("status")?.asString ?: "unknown",
                                stepCount = obj.get("stepCount")?.asInt ?: 0,
                                lastModified = obj.get("lastModifiedTime")?.asString ?: "",
                                workspaceName = extractWorkspaceName(obj)
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping conversation $id: ${e.message}")
                            null
                        }
                    }
                    .sortedByDescending { it.lastModified }

                // Group by workspace
                workspaces = allConversations
                    .groupBy { it.workspaceName }
                    .map { (name, convos) ->
                        WorkspaceGroup(
                            name = name,
                            activeCount = convos.count { it.status == "CASCADE_RUN_STATUS_RUNNING" },
                            totalCount = convos.size,
                            conversations = convos
                        )
                    }
                    .sortedWith(
                        compareByDescending<WorkspaceGroup> { it.activeCount > 0 }
                            .thenByDescending { it.conversations.maxOfOrNull { c -> c.lastModified } ?: "" }
                    )

                Log.i(TAG, "Loaded ${allConversations.size} conversations in ${workspaces.size} workspaces")
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
        return try {
            buildTemplate()
        } catch (e: Exception) {
            Log.e(TAG, "onGetTemplate crashed", e)
            MessageTemplate.Builder("Something went wrong.\n${e.message?.take(80)}")
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
    }

    private fun buildTemplate(): Template {
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
        if (allConversations.isEmpty()) {
            return MessageTemplate.Builder("No conversations yet.\nStart one from your phone.")
                .setTitle("Porta")
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

        // Workspace home list
        val listBuilder = ItemList.Builder()

        // 1. "Recent" item — always first
        val recentConvos = allConversations.take(MAX_RECENT)
        val recentActive = recentConvos.count { it.status == "CASCADE_RUN_STATUS_RUNNING" }
        val recentSubtext = if (recentActive > 0) {
            "$recentActive active · ${recentConvos.size} recent"
        } else {
            "${recentConvos.size} recent conversations"
        }

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Recent")
                .addText(CarTextUtils.sanitize(recentSubtext))
                .setOnClickListener {
                    screenManager.push(
                        PortaWorkspaceConvosScreen(
                            carContext, "Recent", recentConvos, connectionConfig
                        )
                    )
                }
                .build()
        )

        // 2. Workspace items (remaining slots, max MAX_LIST_ITEMS - 1)
        val maxWsItems = MAX_LIST_ITEMS - 1
        for (ws in workspaces.take(maxWsItems)) {
            val subtitle = if (ws.activeCount > 0) {
                "${ws.activeCount} active · ${ws.totalCount} total"
            } else {
                "${ws.totalCount} conversations"
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(CarTextUtils.sanitize(ws.name, 60))
                    .addText(CarTextUtils.sanitize(subtitle))
                    .setOnClickListener {
                        screenManager.push(
                            PortaWorkspaceConvosScreen(
                                carContext, ws.name, ws.conversations, connectionConfig
                            )
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Porta")
            .setHeaderAction(Action.APP_ICON)
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
