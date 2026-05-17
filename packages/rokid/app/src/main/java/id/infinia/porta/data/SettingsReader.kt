package id.infinia.porta.data

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Safe, read-only accessor for the main app's DataStore settings.
 *
 * Background components (widget, poll worker, Android Auto) must NOT
 * create their own DataStore instance — the DataStore contract requires
 * a strict singleton per file. This utility reads the protobuf file
 * directly to avoid the singleton conflict.
 *
 * For write operations, use the main app's `settingsDataStore` delegate
 * only from the ViewModel.
 */
object SettingsReader {

    data class ConnectionConfig(
        val host: String,
        val port: Int,
        val authToken: String?,
        val useTls: Boolean
    )

    /**
     * Read connection config directly from the DataStore protobuf file.
     * Returns null if not configured or on any error.
     */
    fun readConnectionConfig(context: Context): ConnectionConfig? {
        return try {
            val file = File(context.filesDir, "datastore/porta_settings.preferences_pb")
            if (!file.exists() || file.length() == 0L) return null

            // Read the protobuf bytes and decode preferences
            val bytes = file.readBytes()
            val prefs = decodePreferences(bytes)

            val host = prefs["host"] ?: return null
            val port = prefs["port"]?.toIntOrNull() ?: 3170
            val token = prefs["auth_token"]
            val useTls = prefs["use_tls"]?.toBooleanStrictOrNull() ?: false

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
            val file = File(context.filesDir, "datastore/porta_settings.preferences_pb")
            if (!file.exists()) return default
            val prefs = decodePreferences(file.readBytes())
            prefs[key]?.toBooleanStrictOrNull() ?: default
        } catch (_: Exception) {
            default
        }
    }

    /**
     * Decode the preferences protobuf into a string map.
     *
     * The DataStore preferences protobuf format uses:
     * - Field 1 (PreferencesMap): repeated PreferenceEntry
     *   - Field 1 (key): string
     *   - Field 2 (value): oneof PreferenceValue
     *     - Field 1: string
     *     - Field 2: bool
     *     - Field 3: int32
     *     - Field 4: float
     *     - Field 5: double
     *     - Field 6: int64
     *     - Field 7: string_set
     */
    private fun decodePreferences(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 0

        while (offset < bytes.size) {
            val (fieldTag, newOffset) = readVarint(bytes, offset)
            offset = newOffset
            val fieldNumber = (fieldTag shr 3).toInt()
            val wireType = (fieldTag and 0x7).toInt()

            when (wireType) {
                0 -> { // Varint
                    val (_, nextOffset) = readVarint(bytes, offset)
                    offset = nextOffset
                }
                1 -> { // 64-bit
                    offset += 8
                }
                2 -> { // Length-delimited
                    val (length, lenOffset) = readVarint(bytes, offset)
                    offset = lenOffset
                    if (fieldNumber == 1) {
                        // This is a PreferenceEntry — parse the submessage
                        val entryBytes = bytes.copyOfRange(offset, offset + length.toInt())
                        val entry = decodeEntry(entryBytes)
                        if (entry != null) {
                            result[entry.first] = entry.second
                        }
                    }
                    offset += length.toInt()
                }
                5 -> { // 32-bit
                    offset += 4
                }
                else -> break
            }
        }

        return result
    }

    private fun decodeEntry(bytes: ByteArray): Pair<String, String>? {
        var key: String? = null
        var value: String? = null
        var offset = 0

        while (offset < bytes.size) {
            val (fieldTag, newOffset) = readVarint(bytes, offset)
            offset = newOffset
            val fieldNumber = (fieldTag shr 3).toInt()
            val wireType = (fieldTag and 0x7).toInt()

            when {
                fieldNumber == 1 && wireType == 2 -> {
                    // Key (string)
                    val (length, lenOffset) = readVarint(bytes, offset)
                    offset = lenOffset
                    key = String(bytes, offset, length.toInt(), Charsets.UTF_8)
                    offset += length.toInt()
                }
                fieldNumber == 2 && wireType == 2 -> {
                    // Value submessage
                    val (length, lenOffset) = readVarint(bytes, offset)
                    offset = lenOffset
                    val valueBytes = bytes.copyOfRange(offset, offset + length.toInt())
                    value = decodeValue(valueBytes)
                    offset += length.toInt()
                }
                wireType == 0 -> {
                    val (_, nextOffset) = readVarint(bytes, offset)
                    offset = nextOffset
                }
                wireType == 2 -> {
                    val (length, lenOffset) = readVarint(bytes, offset)
                    offset = lenOffset + length.toInt()
                }
                wireType == 1 -> offset += 8
                wireType == 5 -> offset += 4
                else -> break
            }
        }

        return if (key != null && value != null) key to value else null
    }

    private fun decodeValue(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        var offset = 0

        val (fieldTag, newOffset) = readVarint(bytes, offset)
        offset = newOffset
        val fieldNumber = (fieldTag shr 3).toInt()
        val wireType = (fieldTag and 0x7).toInt()

        return when {
            // String value
            fieldNumber == 1 && wireType == 2 -> {
                val (length, lenOffset) = readVarint(bytes, offset)
                String(bytes, lenOffset, length.toInt(), Charsets.UTF_8)
            }
            // Bool value
            fieldNumber == 2 && wireType == 0 -> {
                val (v, _) = readVarint(bytes, offset)
                (v != 0L).toString()
            }
            // Int value
            fieldNumber == 3 && wireType == 0 -> {
                val (v, _) = readVarint(bytes, offset)
                v.toString()
            }
            // Float
            fieldNumber == 4 && wireType == 5 -> {
                if (offset + 4 <= bytes.size) {
                    val bits = (bytes[offset].toInt() and 0xFF) or
                            ((bytes[offset+1].toInt() and 0xFF) shl 8) or
                            ((bytes[offset+2].toInt() and 0xFF) shl 16) or
                            ((bytes[offset+3].toInt() and 0xFF) shl 24)
                    Float.fromBits(bits).toString()
                } else null
            }
            // Long
            fieldNumber == 6 && wireType == 0 -> {
                val (v, _) = readVarint(bytes, offset)
                v.toString()
            }
            else -> null
        }
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < bytes.size) {
            val b = bytes[offset].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            offset++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to offset
    }
}
