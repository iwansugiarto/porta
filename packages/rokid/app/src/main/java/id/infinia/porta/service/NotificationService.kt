package id.infinia.porta.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * Handles Android system notifications for Porta task events.
 *
 * Features:
 * - System notification when agent task completes
 * - Optional sound + vibration
 * - Quick reply directly from notification shade
 */
class NotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "porta_task_complete"
        const val CHANNEL_NAME = "Task Completion"
        const val NOTIFICATION_ID_TASK_COMPLETE = 1001

        const val ACTION_QUICK_REPLY = "id.infinia.porta.QUICK_REPLY"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_CASCADE_ID = "cascade_id"
        const val KEY_REPLY = "key_quick_reply"

        private var quickReplyListener: ((String, String) -> Unit)? = null

        fun setQuickReplyListener(listener: ((String, String) -> Unit)?) {
            quickReplyListener = listener
        }

        internal fun handleQuickReply(cascadeId: String, replyText: String) {
            quickReplyListener?.invoke(cascadeId, replyText)
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies when an AI coding task completes"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Show a task completion notification.
     *
     * @param title   Notification title (e.g., "Task Complete")
     * @param summary Brief summary of what the agent did
     * @param cascadeId The conversation ID for quick reply routing
     * @param playSound Whether to play notification sound
     * @param vibrate Whether to vibrate
     */
    fun showTaskComplete(
        title: String,
        summary: String,
        cascadeId: String,
        playSound: Boolean = true,
        vibrate: Boolean = true
    ) {
        // Launch intent — opens the app
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CASCADE_ID, cascadeId)
            }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Quick reply action
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply to agent...")
            .build()

        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = ACTION_QUICK_REPLY
            putExtra(EXTRA_CASCADE_ID, cascadeId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID_TASK_COMPLETE, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(replyAction)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (!playSound) {
            builder.setSound(null)
        }

        if (!vibrate) {
            builder.setVibrate(longArrayOf(0))
        }

        notificationManager.notify(NOTIFICATION_ID_TASK_COMPLETE, builder.build())

        // Manual vibration for extra feedback when enabled
        if (vibrate) {
            triggerVibration()
        }
    }

    /** Dismiss any active task completion notification. */
    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID_TASK_COMPLETE)
    }

    private fun triggerVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150), -1)
        )
    }

    fun destroy() {
        quickReplyListener = null
    }
}

/**
 * BroadcastReceiver for handling quick reply from notification shade.
 */
class QuickReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationService.ACTION_QUICK_REPLY) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationService.KEY_REPLY)?.toString()
        val cascadeId = intent.getStringExtra(NotificationService.EXTRA_CASCADE_ID)

        if (replyText != null && cascadeId != null) {
            NotificationService.handleQuickReply(cascadeId, replyText)

            // Update notification to show reply was sent
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val updatedNotification = NotificationCompat.Builder(context, NotificationService.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Reply sent")
                .setContentText("\"$replyText\"")
                .setAutoCancel(true)
                .build()
            nm.notify(NotificationService.NOTIFICATION_ID_TASK_COMPLETE, updatedNotification)
        }
    }
}
