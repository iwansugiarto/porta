package id.infinia.porta.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * Safe, read-only accessor for shared settings.
 *
 * Background components (widget, poll worker, Android Auto) read from
 * a simple JSON file that the main ViewModel writes on every settings change.
 * Falls back to SharedPreferences if JSON file doesn't exist yet.
 */
object SettingsReader {

    private const val TAG = "SettingsReader"
    private const val SETTINGS_FILE = "porta_shared_settings.json"
    private const val FALLBACK_PREFS = "porta_fallback_prefs"
    private val gson = Gson()

    data class ConnectionConfig(
        val host: String,
        val port: Int,
        val authToken: String?,
        val useTls: Boolean
    )

    /**
     * Read connection config. Tries JSON file first, then SharedPreferences fallback.
     * Returns null if not configured or on any error.
     */
    fun readConnectionConfig(context: Context): ConnectionConfig? {
        return try {
            // Primary: read from JSON file
            val json = readJson(context)
            if (json != null) {
                val host = json.get("host")?.asString
                if (!host.isNullOrBlank()) {
                    val port = json.get("port")?.asInt ?: 3170
                    val token = json.get("auth_token")?.asString
                    val useTls = json.get("use_tls")?.asBoolean ?: false
                    Log.i(TAG, "Config from JSON: $host:$port")
                    return ConnectionConfig(host, port, token, useTls)
                }
            }

            // Fallback: SharedPreferences
            Log.i(TAG, "JSON not available, trying SharedPreferences fallback")
            readFromSharedPreferences(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config: ${e.message}")
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
            val file = File(context.filesDir, SETTINGS_FILE)
            file.writeText(gson.toJson(json))
            Log.i(TAG, "Settings written to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write settings: ${e.message}")
        }
    }

    private fun readJson(context: Context): JsonObject? {
        return try {
            val file = File(context.filesDir, SETTINGS_FILE)
            if (!file.exists() || file.length() == 0L) return null
            gson.fromJson(file.readText(), JsonObject::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "JSON read error: ${e.message}")
            null
        }
    }

    /**
     * Fallback: Read from SharedPreferences written by syncSharedSettings().
     */
    private fun readFromSharedPreferences(context: Context): ConnectionConfig? {
        return try {
            val prefs = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
            val host = prefs.getString("host", null)
            if (host.isNullOrBlank()) {
                Log.i(TAG, "No fallback prefs found")
                return null
            }
            Log.i(TAG, "Config from SharedPrefs: $host")
            ConnectionConfig(
                host = host,
                port = prefs.getInt("port", 3170),
                authToken = prefs.getString("auth_token", null),
                useTls = prefs.getBoolean("use_tls", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "SharedPrefs fallback failed: ${e.message}")
            null
        }
    }
}
