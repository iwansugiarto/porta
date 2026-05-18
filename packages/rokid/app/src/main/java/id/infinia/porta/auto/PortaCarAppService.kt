package id.infinia.porta.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Porta Android Auto experience.
 *
 * Provides a voice-first AI assistant interface designed for
 * safe driving interactions. Users can:
 * - Browse recent conversations
 * - Start new conversations via voice
 * - Listen to conversation summaries
 * - Monitor running agent status
 */
class PortaCarAppService : CarAppService() {

    companion object {
        private const val TAG = "PortaCarService"
    }

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(): Session {
        return try {
            PortaCarSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session", e)
            // Return a safe fallback session
            PortaErrorSession()
        }
    }
}

/**
 * Manages the lifecycle of a single Auto session.
 * Creates the main screen when the user opens Porta on their car display.
 */
class PortaCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return try {
            PortaMainCarScreen(carContext)
        } catch (e: Exception) {
            Log.e("PortaCarSession", "Failed to create main screen", e)
            PortaErrorScreen(carContext, e.message ?: "Unknown error")
        }
    }
}

/**
 * Fallback session shown when the main session fails to initialize.
 */
class PortaErrorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return PortaErrorScreen(carContext, "Failed to initialize Porta.")
    }
}

/**
 * Fallback error screen with retry capability.
 */
class PortaErrorScreen(
    carContext: androidx.car.app.CarContext,
    private val errorMsg: String
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder("Porta encountered an error:\n$errorMsg")
            .setTitle("Porta")
            .addAction(
                Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener {
                        screenManager.push(PortaMainCarScreen(carContext))
                    }
                    .build()
            )
            .build()
    }
}
