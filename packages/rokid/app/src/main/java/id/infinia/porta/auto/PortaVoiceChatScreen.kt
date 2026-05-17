package id.infinia.porta.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Voice-first chat screen for Android Auto.
 *
 * Allows the user to:
 * - Input a message via the car's voice input system
 * - Send it to Porta and see the response
 * - Continue the conversation or go back
 *
 * Designed for safe, eyes-free interaction while driving.
 */
class PortaVoiceChatScreen(
    carContext: CarContext,
    private val config: PortaMainCarScreen.ConnectionConfig?
) : Screen(carContext) {

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // Allow longer for AI responses
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentState: ChatState = ChatState.READY
    private var lastUserMessage: String? = null
    private var lastAssistantResponse: String? = null
    private var errorMessage: String? = null

    enum class ChatState {
        READY,        // Waiting for voice input
        SENDING,      // Sending message to server
        RESPONSE,     // Showing the response
        ERROR         // Something went wrong
    }

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        return when (currentState) {
            ChatState.READY -> buildReadyTemplate()
            ChatState.SENDING -> buildSendingTemplate()
            ChatState.RESPONSE -> buildResponseTemplate()
            ChatState.ERROR -> buildErrorTemplate()
        }
    }

    private fun buildReadyTemplate(): Template {
        return MessageTemplate.Builder("Tap 'Speak' to start a new conversation with Porta AI.")
            .setTitle("New Chat")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Speak")
                    .setOnClickListener {
                        // Use the car's built-in voice input
                        carContext.startCarApp(
                            android.content.Intent(CarContext.ACTION_NAVIGATE)
                        )
                    }
                    .build()
            )
            .build()
    }

    private fun buildSendingTemplate(): Template {
        return MessageTemplate.Builder("Sending: \"${lastUserMessage}\"")
            .setTitle("Porta")
            .setLoading(true)
            .build()
    }

    private fun buildResponseTemplate(): Template {
        val responseText = lastAssistantResponse?.take(400) ?: "No response received."

        return LongMessageTemplate.Builder(responseText)
            .setTitle("Porta Response")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Done")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
    }

    private fun buildErrorTemplate(): Template {
        return MessageTemplate.Builder(errorMessage ?: "Unknown error")
            .setTitle("Error")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener {
                        currentState = ChatState.READY
                        errorMessage = null
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    /**
     * Send a message to Porta via the HTTP API.
     * Called after voice input is processed.
     */
    fun sendMessage(text: String) {
        if (config == null) {
            currentState = ChatState.ERROR
            errorMessage = "Not configured"
            invalidate()
            return
        }

        lastUserMessage = text
        currentState = ChatState.SENDING
        invalidate()

        scope.launch {
            try {
                val scheme = if (config.useTls) "https" else "http"
                val url = "$scheme://${config.host}:${config.port}/api/conversations"

                // Create new conversation with the message
                val payload = JsonObject().apply {
                    addProperty("message", text)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .apply {
                        if (!config.authToken.isNullOrBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                    }
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = gson.fromJson(body, JsonObject::class.java)
                    lastAssistantResponse = json.get("response")?.asString
                        ?: json.get("text")?.asString
                        ?: "Message sent. Check your phone for the full response."
                    currentState = ChatState.RESPONSE
                } else {
                    currentState = ChatState.ERROR
                    errorMessage = "Server error: ${response.code}"
                }
            } catch (e: Exception) {
                currentState = ChatState.ERROR
                errorMessage = "Failed: ${e.message}"
            }
            invalidate()
        }
    }

}
