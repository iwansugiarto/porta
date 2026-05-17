package id.infinia.porta.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shows details of a specific conversation on Android Auto.
 *
 * Displays:
 * - Conversation summary / title
 * - Current status (running/completed)
 * - Step count
 * - Actions: View latest response, go back
 */
class PortaConvoDetailScreen(
    carContext: CarContext,
    private val convo: PortaMainCarScreen.ConvoSummary,
    private val config: PortaMainCarScreen.ConnectionConfig?
) : Screen(carContext) {

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var latestResponse: String? = null
    private var isLoading = true

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                scope.cancel()
            }
        })
        loadLatestStep()
    }

    private fun loadLatestStep() {
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
                    val steps = gson.fromJson(body, com.google.gson.JsonArray::class.java)

                    // Find the last assistant text response
                    for (i in steps.size() - 1 downTo 0) {
                        val step = steps[i].asJsonObject
                        val type = step.get("type")?.asString
                        if (type == "text" || type == "response") {
                            val text = step.get("text")?.asString
                                ?: step.get("content")?.asString
                            if (!text.isNullOrBlank()) {
                                latestResponse = text.take(500) // Truncate for Auto
                                break
                            }
                        }
                    }

                    if (latestResponse == null) {
                        latestResponse = "No text response available yet."
                    }
                } else {
                    latestResponse = "Could not load steps (${response.code})"
                }
            } catch (e: Exception) {
                latestResponse = "Failed to load: ${e.message}"
            } finally {
                isLoading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val isRunning = convo.status == "CASCADE_RUN_STATUS_RUNNING"
        val statusEmoji = if (isRunning) "⚡" else "✓"
        val statusText = if (isRunning) "Running" else "Completed"

        if (isLoading) {
            return MessageTemplate.Builder("Loading conversation...")
                .setTitle(convo.title)
                .setLoading(true)
                .build()
        }

        val paneBuilder = Pane.Builder()

        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Status")
                .addText("$statusEmoji $statusText • ${convo.stepCount} steps")
                .build()
        )

        if (latestResponse != null) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Latest Response")
                    .addText(latestResponse!!)
                    .build()
            )
        }

        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Back")
                .setOnClickListener { screenManager.pop() }
                .build()
        )

        if (isRunning) {
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener {
                        isLoading = true
                        invalidate()
                        loadLatestStep()
                    }
                    .build()
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(convo.title)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
