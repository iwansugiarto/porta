package id.infinia.porta.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages named server profiles for multi-server support.
 *
 * Users can save, switch between, and manage multiple Porta proxy
 * connections (e.g., "Work", "Home", "Production").
 *
 * Profiles are persisted to a JSON file in the app's internal storage.
 */
class ServerProfileManager(context: Context) {

    data class ServerProfile(
        val id: String = System.currentTimeMillis().toString(),
        val name: String,
        val host: String,
        val port: Int = 443,
        val authToken: String? = null,
        val useTls: Boolean = true,
        val isActive: Boolean = false
    )

    private val gson = Gson()
    private val profileFile = File(context.filesDir, "server_profiles.json")

    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    init {
        loadFromDisk()
    }

    /**
     * Add or update a server profile.
     * If a profile with the same ID exists, it's replaced.
     */
    fun saveProfile(profile: ServerProfile) {
        val updated = _profiles.value.filter { it.id != profile.id } + profile
        _profiles.value = updated
        if (profile.isActive) {
            _activeProfile.value = profile
            // Deactivate others
            _profiles.value = updated.map {
                if (it.id == profile.id) it else it.copy(isActive = false)
            }
        }
        saveToDisk()
    }

    /**
     * Set a profile as active and deactivate all others.
     */
    fun setActive(profileId: String) {
        _profiles.value = _profiles.value.map {
            it.copy(isActive = it.id == profileId)
        }
        _activeProfile.value = _profiles.value.find { it.id == profileId }
        saveToDisk()
    }

    /**
     * Delete a server profile by ID.
     */
    fun deleteProfile(profileId: String) {
        _profiles.value = _profiles.value.filter { it.id != profileId }
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = _profiles.value.firstOrNull()
            _activeProfile.value?.let { setActive(it.id) }
        }
        saveToDisk()
    }

    /**
     * Create a profile from current connection settings (migration helper).
     */
    fun createFromCurrentSettings(
        name: String,
        host: String,
        port: Int,
        authToken: String?,
        useTls: Boolean
    ): ServerProfile {
        val profile = ServerProfile(
            name = name,
            host = host,
            port = port,
            authToken = authToken,
            useTls = useTls,
            isActive = true
        )
        saveProfile(profile)
        return profile
    }

    private fun loadFromDisk() {
        try {
            if (profileFile.exists()) {
                val json = profileFile.readText()
                val type = object : TypeToken<List<ServerProfile>>() {}.type
                val profiles: List<ServerProfile> = gson.fromJson(json, type) ?: emptyList()
                _profiles.value = profiles
                _activeProfile.value = profiles.find { it.isActive }
            }
        } catch (_: Exception) {
            _profiles.value = emptyList()
        }
    }

    private fun saveToDisk() {
        try {
            profileFile.writeText(gson.toJson(_profiles.value))
        } catch (_: Exception) {
            // Silent fail
        }
    }
}
