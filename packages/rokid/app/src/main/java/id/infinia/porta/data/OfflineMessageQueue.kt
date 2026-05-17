package id.infinia.porta.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Offline message queue that persists unsent messages to disk.
 *
 * When the user sends a message while disconnected, it's queued here.
 * When connectivity resumes, the ViewModel drains the queue and sends
 * all pending messages in order.
 *
 * Storage: JSON file in app-internal files directory.
 */
class OfflineMessageQueue(context: Context) {

    data class PendingMessage(
        val id: String = "${System.currentTimeMillis()}-${System.nanoTime()}",
        val cascadeId: String,
        val text: String,
        val model: String? = null,
        val planner: String? = null,
        val media: List<Map<String, String>>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val gson = Gson()
    private val queueFile = File(context.filesDir, "offline_queue.json")
    private val mutex = Mutex()

    private val _pendingMessages = MutableStateFlow<List<PendingMessage>>(emptyList())
    val pendingMessages: StateFlow<List<PendingMessage>> = _pendingMessages.asStateFlow()

    init {
        loadFromDisk()
    }

    /**
     * Enqueue a message for later sending.
     */
    suspend fun enqueue(message: PendingMessage) {
        mutex.withLock {
            val updated = _pendingMessages.value + message
            _pendingMessages.value = updated
            saveToDisk(updated)
        }
    }

    /**
     * Remove a successfully sent message from the queue.
     */
    suspend fun dequeue(messageId: String) {
        mutex.withLock {
            val updated = _pendingMessages.value.filter { it.id != messageId }
            _pendingMessages.value = updated
            saveToDisk(updated)
        }
    }

    /**
     * Get all pending messages for a specific conversation.
     */
    fun getForConversation(cascadeId: String): List<PendingMessage> {
        return _pendingMessages.value.filter { it.cascadeId == cascadeId }
    }

    /**
     * Drain all pending messages (for sending on reconnect).
     * Returns messages in chronological order.
     */
    suspend fun drainAll(): List<PendingMessage> {
        mutex.withLock {
            val messages = _pendingMessages.value.sortedBy { it.timestamp }
            return messages
        }
    }

    /**
     * Clear all queued messages.
     */
    suspend fun clear() {
        mutex.withLock {
            _pendingMessages.value = emptyList()
            saveToDisk(emptyList())
        }
    }

    val size: Int get() = _pendingMessages.value.size

    private fun loadFromDisk() {
        try {
            if (queueFile.exists()) {
                val json = queueFile.readText()
                val type = object : TypeToken<List<PendingMessage>>() {}.type
                val messages: List<PendingMessage> = gson.fromJson(json, type) ?: emptyList()
                _pendingMessages.value = messages
            }
        } catch (_: Exception) {
            _pendingMessages.value = emptyList()
        }
    }

    private fun saveToDisk(messages: List<PendingMessage>) {
        try {
            queueFile.writeText(gson.toJson(messages))
        } catch (_: Exception) {
            // Silent fail — queue is best-effort
        }
    }
}
