package id.infinia.porta.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * Safe, read-only accessor for shared settings.
 *
 * Background components (widget, poll worker, Android Auto) read from
 * a simple JSON file that the main ViewModel writes on every settings change.
 * This avoids DataStore singleton conflicts entirely.
 */
object SettingsReader {

    private const val SETTINGS_FILE = "porta_shared_settings.json"
    private val gson = Gson()

    data class ConnectionConfig(
        val host: String,
        val port: Int,
        val authToken: String?,
        val useTls: Boolean
    )

    /**
     * Read connection config from the shared JSON settings file.
     * Returns null if not configured or on any error.
     */
    fun readConnectionConfig(context: Context): ConnectionConfig? {
        return try {
            val json = readJson(context) ?: return null
            val host = json.get("host")?.asString ?: return null
            val port = json.get("port")?.asInt ?: 3170
            val token = json.get("auth_token")?.asString
            val useTls = json.get("use_tls")?.asBoolean ?: false

            ConnectionConfig(host, port, token, useTls)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read a boolean preference by key.
     */
    fun readBoolean(context: Context, key: String, default: Boolean = false): Boolean {
        return try {
            val json = readJson(context) ?: return default
            if (json.has(key)) json.get(key).asBoolean else default
        } catch (_: Exception) {
            default
        }
    }

    /**
     * Write current settings to the shared JSON file.
     * Called by the ViewModel whenever settings change.
     */
    fun writeSettings(context: Context, settings: Map<String, Any?>) {
        try {
            val json = JsonObject()
            for ((key, value) in settings) {
                when (value) {
                    is String -> json.addProperty(key, value)
                    is Int -> json.addProperty(key, value)
                    is Boolean -> json.addProperty(key, value)
                    is Long -> json.addProperty(key, value)
                    is Float -> json.addProperty(key, value)
                    is Double -> json.addProperty(key, value)
                    null -> {} // skip nulls
                }
            }
            File(context.filesDir, SETTINGS_FILE).writeText(gson.toJson(json))
        } catch (_: Exception) {
            // Best-effort write
        }
    }

    private fun readJson(context: Context): JsonObject? {
        val file = File(context.filesDir, SETTINGS_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            gson.fromJson(file.readText(), JsonObject::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
