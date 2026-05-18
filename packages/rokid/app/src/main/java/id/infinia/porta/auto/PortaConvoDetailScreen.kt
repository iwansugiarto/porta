package id.infinia.porta.auto

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shows details of a specific conversation on Android Auto.
 *
 * Features:
 * - Recent chat messages (user + assistant)
 * - Auto-refresh every 5s while conversation is running
 * - Read aloud latest response via TTS
 *
 * Safety: All text is sanitized to remove emoji and special chars
 * that can crash certain head units.
 */
class PortaConvoDetailScreen(
    carContext: CarContext,
    private val convo: PortaMainCarScreen.ConvoSummary,
    private val config: PortaMainCarScreen.ConnectionConfig?
) : Screen(carContext) {

    companion object {
        private const val TAG = "PortaAutoDetail"
        // PaneTemplate allows max 4 rows total.
        // 1 for status row + 2 for messages = 3 (safe under limit)
        private const val MAX_DISPLAY_MESSAGES = 2
        private const val MAX_TEXT_LENGTH = 180
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var chatMessages: List<ChatMessage> = emptyList()
    private var isLoading = true
    private var isRunning = false
    private var autoRefreshJob: Job? = null

    // TTS engine
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    data class ChatMessage(
        val role: String,  // "user" or "assistant"
        val text: String
    )

    init {
        // Initialize TTS
        tts = TextToSpeech(carContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale("id", "ID")
        }

        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                autoRefreshJob?.cancel()
                scope.cancel()
                tts?.stop()
                tts?.shutdown()
            }
        })
        loadSteps()
    }

    private fun loadSteps() {
        if (config == null) {
            isLoading = false
            invalidate()
            return
        }

        scope.launch {
            try {
                val scheme = if (config.useTls) "https" else "http"
                val url = "$scheme://${config.host}:${config.port}/api/conversations/${convo.id}/steps"

                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (!config.authToken.isNullOrBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                    }
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val steps = gson.fromJson(body, JsonArray::class.java)

                    // Extract user and assistant text messages
                    val messages = mutableListOf<ChatMessage>()
                    for (i in 0 until steps.size()) {
                        try {
                            val step = steps[i].asJsonObject
                            val type = step.get("type")?.asString ?: continue

                            when (type) {
                                "user_message" -> {
                                    val text = step.get("text")?.asString
                                        ?: step.get("content")?.asString
                                    if (!text.isNullOrBlank()) {
                                        messages.add(ChatMessage("user", text))
                                    }
                                }
                                "text", "response" -> {
                                    val text = step.get("text")?.asString
                                        ?: step.get("content")?.asString
                                    if (!text.isNullOrBlank()) {
                                        messages.add(ChatMessage("assistant", text))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping step $i: ${e.message}")
                        }
                    }

                    // Keep only the last messages for Auto display
                    chatMessages = messages.takeLast(MAX_DISPLAY_MESSAGES)

                    // Check if still running
                    isRunning = convo.status == "CASCADE_RUN_STATUS_RUNNING"

                } else {
                    chatMessages = listOf(
                        ChatMessage("assistant", "Could not load steps (${response.code})")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load steps: ${e.message}", e)
                chatMessages = listOf(
                    ChatMessage("assistant", "Failed to load: ${e.message?.take(80)}")
                )
            } finally {
                isLoading = false
                invalidate()

                // Start auto-refresh if running
                if (isRunning) {
                    startAutoRefresh()
                }
            }
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = scope.launch {
            while (isActive && isRunning) {
                delay(5000) // Refresh every 5 seconds
                try {
                    loadStepsQuiet()
                } catch (_: Exception) {}
            }
        }
    }

    /** Silent reload without showing loading spinner */
    private suspend fun loadStepsQuiet() {
        if (config == null) return
        try {
            val scheme = if (config.useTls) "https" else "http"
            val url = "$scheme://${config.host}:${config.port}/api/conversations/${convo.id}/steps"

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (!config.authToken.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer ${config.authToken}")
                    }
                }
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                val steps = gson.fromJson(body, JsonArray::class.java)

                val messages = mutableListOf<ChatMessage>()
                for (i in 0 until steps.size()) {
                    try {
                        val step = steps[i].asJsonObject
                        val type = step.get("type")?.asString ?: continue
                        when (type) {
                            "user_message" -> {
                                val text = step.get("text")?.asString
                                    ?: step.get("content")?.asString
                                if (!text.isNullOrBlank()) {
                                    messages.add(ChatMessage("user", text))
                                }
                            }
                            "text", "response" -> {
                                val text = step.get("text")?.asString
                                    ?: step.get("content")?.asString
                                if (!text.isNullOrBlank()) {
                                    messages.add(ChatMessage("assistant", text))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val newMessages = messages.takeLast(MAX_DISPLAY_MESSAGES)
                if (newMessages != chatMessages) {
                    chatMessages = newMessages
                    invalidate()
                }
            }
        } catch (_: Exception) {}
    }

    private fun speakLatestResponse() {
        if (!ttsReady || tts == null) {
            CarToast.makeText(carContext, "TTS not available", CarToast.LENGTH_SHORT).show()
            return
        }

        val lastAssistant = chatMessages.lastOrNull { it.role == "assistant" }
        if (lastAssistant == null) {
            CarToast.makeText(carContext, "No response to read", CarToast.LENGTH_SHORT).show()
            return
        }

        // Truncate for TTS (keep it reasonable for driving)
        val textToSpeak = CarTextUtils.sanitize(lastAssistant.text, 300)
        tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "porta_auto_tts")
        CarToast.makeText(carContext, "Reading aloud...", CarToast.LENGTH_SHORT).show()
    }

    override fun onGetTemplate(): Template {
        return try {
            buildTemplate()
        } catch (e: Exception) {
            Log.e(TAG, "onGetTemplate crashed", e)
            MessageTemplate.Builder("Something went wrong.\n${e.message?.take(80)}")
                .setTitle("Porta")
                .setHeaderAction(Action.BACK)
                .build()
        }
    }

    private fun buildTemplate(): Template {
        val sanitizedTitle = CarTextUtils.sanitize(convo.title, 80)

        if (isLoading) {
            return MessageTemplate.Builder("Loading conversation...")
                .setTitle(sanitizedTitle)
                .setLoading(true)
                .build()
        }

        if (chatMessages.isEmpty()) {
            return MessageTemplate.Builder("No messages in this conversation yet.")
                .setTitle(sanitizedTitle)
                .setHeaderAction(Action.BACK)
                .build()
        }

        val paneBuilder = Pane.Builder()

        // Status row (1 of max 3 rows)
        val statusText = if (isRunning) "Active - Running" else "Completed"
        val stepsInfo = "${convo.stepCount} steps" +
            if (isRunning) " - Auto-refreshing" else ""
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(CarTextUtils.sanitize(statusText))
                .addText(CarTextUtils.sanitize(stepsInfo))
                .build()
        )

        // Show recent chat messages (max 2 to stay within PaneTemplate 4-row limit)
        // Total: 1 status + 2 messages = 3 rows (safely under 4)
        val displayMessages = chatMessages.takeLast(MAX_DISPLAY_MESSAGES)
        for (msg in displayMessages) {
            val prefix = if (msg.role == "user") "You" else "Porta"
            val truncatedText = CarTextUtils.sanitize(msg.text, MAX_TEXT_LENGTH)
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(prefix)
                    .addText(truncatedText)
                    .build()
            )
        }

        // Actions (max 2 actions on PaneTemplate)
        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Read Aloud")
                .setOnClickListener { speakLatestResponse() }
                .build()
        )

        if (isRunning) {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener {
                        isLoading = true
                        invalidate()
                        loadSteps()
                    }
                    .build()
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(sanitizedTitle)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
