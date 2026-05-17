package id.infinia.porta.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import id.infinia.porta.R
import id.infinia.porta.data.SettingsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically checks for running conversations
 * and sends notifications when new activity is detected.
 *
 * Scheduled via WorkManager with PeriodicWorkRequest (15 min intervals).
 */

private val Context.pollDataStore by preferencesDataStore(name = "porta_poll_state")

class ConversationPollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "porta_conversation_poll"
        private const val CHANNEL_ID = "porta_background"

        // Keys for tracking state
        private val LAST_RUNNING_IDS = stringSetPreferencesKey("last_running_ids")
        private val LAST_STEP_COUNTS = stringPreferencesKey("last_step_counts")

        /**
         * Enqueue periodic polling. Safe to call multiple times — replaces existing.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ConversationPollWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10_000L,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Cancel background polling.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Read connection settings via safe direct-file reader
            val config = SettingsReader.readConnectionConfig(applicationContext)
                ?: return@withContext Result.success()

            val notifyEnabled = SettingsReader.readBoolean(applicationContext, "notify_enabled", true)
            if (!notifyEnabled) return@withContext Result.success()

            val scheme = if (config.useTls) "https" else "http"
            val url = "$scheme://${config.host}:${config.port}/api/conversations"

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (!config.authToken.isNullOrBlank()) {
                        addHeader("Authorization", "Bearer ${config.authToken}")
                    }
                }
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.retry()

            val body = response.body?.string() ?: "{}"
            val json = gson.fromJson(body, JsonObject::class.java)
            val summaries = json.getAsJsonObject("trajectorySummaries") ?: JsonObject()

            // Find conversations that are currently running
            val runningNow = mutableSetOf<String>()
            val stepCounts = mutableMapOf<String, Int>()

            for ((id, value) in summaries.entrySet()) {
                val summary = value.asJsonObject
                val status = summary.get("status")?.asString
                val steps = summary.get("stepCount")?.asInt ?: 0
                stepCounts[id] = steps
                if (status == "CASCADE_RUN_STATUS_RUNNING") {
                    runningNow.add(id)
                }
            }

            // Compare with last known state
            val pollStore = applicationContext.pollDataStore
            val pollPrefs = pollStore.data.first()
            val lastRunning = pollPrefs[LAST_RUNNING_IDS] ?: emptySet()

            // Detect newly completed conversations
            val justFinished = lastRunning - runningNow
            if (justFinished.isNotEmpty()) {
                for (id in justFinished) {
                    val title = summaries.getAsJsonObject(id)
                        ?.get("summary")?.asString ?: id.take(8)
                    sendNotification(
                        "Conversation complete",
                        "\"$title\" has finished running."
                    )
                }
            }

            // Save current state
            pollStore.edit { prefs ->
                prefs[LAST_RUNNING_IDS] = runningNow
                prefs[LAST_STEP_COUNTS] = gson.toJson(stepCounts)
            }

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun sendNotification(title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure channel exists (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Periodic conversation status updates"
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
