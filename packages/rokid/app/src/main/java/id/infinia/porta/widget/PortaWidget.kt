package id.infinia.porta.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.google.gson.Gson
import com.google.gson.JsonObject
import id.infinia.porta.MainActivity
import id.infinia.porta.data.SettingsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Porta home screen widget — shows active conversation count
 * and quick-launch buttons for new conversation and voice mode.
 */
class PortaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fetch conversation data
        val data = fetchWidgetData(context)

        provideContent {
            PortaWidgetContent(data)
        }
    }

    private suspend fun fetchWidgetData(context: Context): WidgetData =
        withContext(Dispatchers.IO) {
            try {
                val config = SettingsReader.readConnectionConfig(context)
                    ?: return@withContext WidgetData()

                val scheme = if (config.useTls) "https" else "http"
                val url = "$scheme://${config.host}:${config.port}/api/conversations"

                val httpClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (!config.authToken.isNullOrBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                    }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext WidgetData(connected = false)

                    val body = response.body?.string() ?: "{}"
                    val json = Gson().fromJson(body, JsonObject::class.java)
                    val summaries = json.getAsJsonObject("trajectorySummaries") ?: JsonObject()

                    val total = summaries.size()
                    val running = summaries.entrySet().count { (_, v) ->
                        v.asJsonObject?.get("status")?.asString == "CASCADE_RUN_STATUS_RUNNING"
                    }
                    val latest = summaries.entrySet()
                        .maxByOrNull { it.value.asJsonObject?.get("lastModifiedTime")?.asString ?: "" }
                        ?.let { it.value.asJsonObject?.get("summary")?.asString }

                    WidgetData(
                        connected = true,
                        totalConversations = total,
                        runningCount = running,
                        latestTitle = latest
                    )
                }
            } catch (_: Exception) {
                WidgetData(connected = false)
            }
        }
}

data class WidgetData(
    val connected: Boolean = false,
    val totalConversations: Int = 0,
    val runningCount: Int = 0,
    val latestTitle: String? = null
)

@Composable
private fun PortaWidgetContent(data: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .background(GlanceTheme.colors.background)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Header
        Text(
            "Porta",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = GlanceTheme.colors.onBackground
            )
        )

        Spacer(GlanceModifier.height(4.dp))

        if (!data.connected) {
            Text(
                "Not connected",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = GlanceTheme.colors.onBackground
                )
            )
        } else {
            // Stats row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    "${data.totalConversations} conversations",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = GlanceTheme.colors.onBackground
                    )
                )
                if (data.runningCount > 0) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        "⚡ ${data.runningCount} running",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = GlanceTheme.colors.primary
                        )
                    )
                }
            }

            // Latest conversation
            if (data.latestTitle != null) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    data.latestTitle,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = GlanceTheme.colors.onBackground
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * BroadcastReceiver that Android uses to manage the widget lifecycle.
 */
class PortaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PortaWidget()
}
