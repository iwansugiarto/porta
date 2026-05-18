package id.infinia.porta.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

/**
 * Android Auto screen: shows conversations filtered by a specific workspace.
 * Also used for "Recent" mode showing cross-workspace recent conversations.
 */
class PortaWorkspaceConvosScreen(
    carContext: CarContext,
    private val title: String,
    private val conversations: List<PortaMainCarScreen.ConvoSummary>,
    private val connectionConfig: PortaMainCarScreen.ConnectionConfig?
) : Screen(carContext) {

    companion object {
        private const val TAG = "PortaAutoWorkspace"
        private const val MAX_LIST_ITEMS = 6
    }

    override fun onGetTemplate(): Template {
        return try {
            buildTemplate()
        } catch (e: Exception) {
            Log.e(TAG, "onGetTemplate crashed", e)
            MessageTemplate.Builder("Something went wrong.\n${e.message?.take(80)}")
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }
    }

    private fun buildTemplate(): Template {
        if (conversations.isEmpty()) {
            return MessageTemplate.Builder("No conversations in this workspace.")
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }

        val listBuilder = ItemList.Builder()

        for (convo in conversations.take(MAX_LIST_ITEMS)) {
            val isRunning = convo.status == "CASCADE_RUN_STATUS_RUNNING"
            val statusText = if (isRunning) "Active - Running" else "${convo.stepCount} steps"

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(CarTextUtils.sanitize(convo.title, 80))
                    .addText(CarTextUtils.sanitize(statusText))
                    .setOnClickListener {
                        screenManager.push(
                            PortaConvoDetailScreen(carContext, convo, connectionConfig)
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
