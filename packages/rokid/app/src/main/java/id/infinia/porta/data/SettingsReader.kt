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
 * This avoids DataStore singleton conflicts entirely.
 *
 * Falls back to reading SharedPreferences XML if JSON file doesn't exist yet
 * (e.g. first launch after update before opening main app).
 */
object SettingsReader {

    private const val TAG = "SettingsReader"
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
     * Falls back to SharedPreferences XML if JSON file doesn't exist.
     * Returns null if not configured or on any error.
     */
    fun readConnectionConfig(context: Context): ConnectionConfig? {
        return try {
            // Primary: read from JSON file
            val json = readJson(context)
            if (json != null) {
                val host = json.get("host")?.asString
                if (host.isNullOrBlank()) {
                    Log.w(TAG, "JSON exists but host is blank, trying fallback")
                    return readFromPreferencesXml(context)
                }
                val port = json.get("port")?.asInt ?: 3170
                val token = json.get("auth_token")?.asString
                val useTls = json.get("use_tls")?.asBoolean ?: false
                Log.i(TAG, "Read from JSON OK: $host:$port")
                return ConnectionConfig(host, port, token, useTls)
            }

            // Fallback: try to read DataStore Preferences XML
            Log.w(TAG, "JSON file not found, trying preferences XML fallback")
            readFromPreferencesXml(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config: ${e.message}", e)
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
        val file = File(context.filesDir, SETTINGS_FILE)
        Log.d(TAG, "Looking for JSON at: ${file.absolutePath} exists=${file.exists()}")
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val text = file.readText()
            Log.d(TAG, "JSON content: ${text.take(200)}")
            gson.fromJson(text, JsonObject::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}")
            null
        }
    }

    /**
     * Fallback: Read from DataStore's Preferences XML file.
     * DataStore stores Preferences as an XML file in datastore/ directory.
     * This allows Android Auto to work even before the user opens the app
     * after updating (which would trigger JSON file creation).
     */
    private fun readFromPreferencesXml(context: Context): ConnectionConfig? {
        try {
            // DataStore Preferences are stored as preferences_pb in datastore/
            // But we can also check SharedPreferences directly
            val prefsFile = File(context.filesDir, "../shared_prefs/porta_settings.xml")
            if (!prefsFile.exists()) {
                // Also try DataStore preferences pb file
                val datastoreDir = File(context.filesDir, "../datastore")
                Log.d(TAG, "SharedPrefs not found, datastore dir exists=${datastoreDir.exists()}")
                if (datastoreDir.exists()) {
                    Log.d(TAG, "Datastore files: ${datastoreDir.listFiles()?.map { it.name }}")
                }

                // Try using Android SharedPreferences API as last resort
                val prefs = context.getSharedPreferences("porta_fallback_prefs", Context.MODE_PRIVATE)
                val host = prefs.getString("host", null)
                if (!host.isNullOrBlank()) {
                    Log.i(TAG, "Read from fallback SharedPrefs: $host")
                    return ConnectionConfig(
                        host = host,
                        port = prefs.getInt("port", 3170),
                        authToken = prefs.getString("auth_token", null),
                        useTls = prefs.getBoolean("use_tls", false)
                    )
                }
                return null
            }
            Log.i(TAG, "Found SharedPrefs XML, but cannot read DataStore XML directly")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Fallback read failed: ${e.message}")
            return null
        }
    }
}
