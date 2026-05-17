package id.infinia.porta.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
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

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(): Session {
        return PortaCarSession()
    }
}

/**
 * Manages the lifecycle of a single Auto session.
 * Creates the main screen when the user opens Porta on their car display.
 */
class PortaCarSession : Session() {
    override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
        return PortaMainCarScreen(carContext)
    }
}
