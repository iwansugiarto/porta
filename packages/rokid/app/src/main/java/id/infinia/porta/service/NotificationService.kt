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
        const val CHANNEL_APPROVAL_ID = "porta_approval"
        const val CHANNEL_APPROVAL_NAME = "Approval Requests"
        const val CHANNEL_GLASSES_ID = "porta_glasses"
        const val CHANNEL_GLASSES_NAME = "AR Glasses Display"
        const val NOTIFICATION_ID_TASK_COMPLETE = 1001
        const val NOTIFICATION_ID_APPROVAL = 1002
        const val NOTIFICATION_ID_GLASSES = 2001
        const val NOTIFICATION_ID_GLASSES_STREAM = 2002

        const val ACTION_QUICK_REPLY = "id.infinia.porta.QUICK_REPLY"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_CASCADE_ID = "cascade_id"
        const val KEY_REPLY = "key_quick_reply"

        /** Max chars for glasses display (Micro-LED readability) */
        private const val GLASSES_MAX_CHARS = 500
        /** Max chars per line for glasses */
        private const val GLASSES_LINE_WIDTH = 40

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

        // Approval channel — urgent, persistent
        val approvalChannel = NotificationChannel(
            CHANNEL_APPROVAL_ID,
            CHANNEL_APPROVAL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when agent needs your approval to proceed"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        notificationManager.createNotificationChannel(approvalChannel)

        // Glasses channel — for AR glasses notification mirroring
        val glassesChannel = NotificationChannel(
            CHANNEL_GLASSES_ID,
            CHANNEL_GLASSES_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI responses displayed on AR glasses via notification mirroring"
            enableVibration(false)  // Don't vibrate phone for glasses-only notifications
            setSound(null, null)    // Silent on phone — glasses handle display
        }
        notificationManager.createNotificationChannel(glassesChannel)
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

    /** Dismiss approval notification. */
    fun dismissApproval() {
        notificationManager.cancel(NOTIFICATION_ID_APPROVAL)
    }

    /** Dismiss a specific approval notification by ID. */
    fun dismissApproval(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    /**
     * Show an approval-needed notification.
     *
     * @param title      Notification title (e.g., "⚠️ Approval Needed")
     * @param description What the agent wants to do
     * @param cascadeId  The conversation ID
     * @param playSound  Whether to play notification sound
     * @param vibrate    Whether to vibrate
     */
    fun showApprovalNeeded(
        title: String,
        description: String,
        cascadeId: String,
        playSound: Boolean = true,
        vibrate: Boolean = true,
        notificationId: Int = NOTIFICATION_ID_APPROVAL
    ) {
        // Launch intent — opens the app
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CASCADE_ID, cascadeId)
            }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 2, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVAL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true) // Persistent until user acts
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup("porta_approvals")

        if (!playSound) {
            builder.setSound(null)
        }

        if (!vibrate) {
            builder.setVibrate(longArrayOf(0))
        }

        notificationManager.notify(notificationId, builder.build())

        // Group summary notification so multiple approvals stack nicely
        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_APPROVAL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Approvals Needed")
            .setContentText("Multiple conversations need your approval")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setGroup("porta_approvals")
            .setGroupSummary(true)
            .setAutoCancel(true)
        notificationManager.notify(NOTIFICATION_ID_APPROVAL, summaryBuilder.build())

        // Urgent vibration pattern for approval
        if (vibrate) {
            triggerApprovalVibration()
        }
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

    private fun triggerApprovalVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 250, 100, 250, 100, 250), -1)
        )
    }

    // ── Glasses-optimized notifications ──

    /**
     * Show an AI response notification optimized for AR glasses display.
     *
     * Uses MessagingStyle for Rokid Relay / notification mirroring compatibility.
     * Includes direct reply action for replying from glasses.
     * Text is smart-truncated for Micro-LED readability.
     *
     * @param response  The AI response text
     * @param cascadeId Conversation ID for reply routing
     * @param title     Optional title (workspace/conversation name)
     */
    fun showGlassesResponse(
        response: String,
        cascadeId: String,
        title: String = "Porta AI"
    ) {
        // Smart truncate for glasses readability
        val glassesText = formatForGlasses(response)

        // Launch intent
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CASCADE_ID, cascadeId)
            }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 3, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Direct reply action (works with Rokid Relay!)
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply...")
            .build()

        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = ACTION_QUICK_REPLY
            putExtra(EXTRA_CASCADE_ID, cascadeId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID_GLASSES, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // Use MessagingStyle for maximum compatibility with notification mirroring
        val person = androidx.core.app.Person.Builder()
            .setName(title)
            .setImportant(true)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(person)
            .setConversationTitle(title)
            .addMessage(
                glassesText,
                System.currentTimeMillis(),
                person
            )

        val builder = NotificationCompat.Builder(context, CHANNEL_GLASSES_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(glassesText.take(100))
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(replyAction)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(null)     // Silent on phone
            .setVibrate(null)   // No vibration

        // Cancel any streaming notification
        notificationManager.cancel(NOTIFICATION_ID_GLASSES_STREAM)

        notificationManager.notify(NOTIFICATION_ID_GLASSES, builder.build())
    }

    /**
     * Show a "thinking" streaming notification on glasses.
     * Replaced by showGlassesResponse when the response arrives.
     */
    fun showGlassesThinking(cascadeId: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_GLASSES_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Porta AI")
            .setContentText("⏳ Thinking...")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setSound(null)
            .setVibrate(null)

        notificationManager.notify(NOTIFICATION_ID_GLASSES_STREAM, builder.build())
    }

    /** Dismiss glasses notifications. */
    fun dismissGlasses() {
        notificationManager.cancel(NOTIFICATION_ID_GLASSES)
        notificationManager.cancel(NOTIFICATION_ID_GLASSES_STREAM)
    }

    /**
     * Format AI response text for AR glasses readability.
     *
     * - Strips markdown formatting (bold, headers, code fences)
     * - Truncates to GLASSES_MAX_CHARS
     * - Adds ellipsis if truncated
     * - Preserves paragraph breaks
     */
    private fun formatForGlasses(text: String): String {
        var formatted = text
            // Strip markdown headers
            .replace(Regex("^#{1,6}\\s+"), "")
            // Strip bold/italic markers
            .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
            // Strip code fences
            .replace(Regex("```[\\s\\S]*?```"), "[code]")
            // Strip inline code
            .replace(Regex("`([^`]+)`"), "$1")
            // Strip links: [text](url) → text
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            // Collapse multiple newlines
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        // Truncate with ellipsis
        if (formatted.length > GLASSES_MAX_CHARS) {
            // Try to cut at a sentence boundary
            val cutPoint = formatted.lastIndexOf(". ", GLASSES_MAX_CHARS)
            formatted = if (cutPoint > GLASSES_MAX_CHARS / 2) {
                formatted.substring(0, cutPoint + 1) + "\n\n[... open phone for full response]"
            } else {
                formatted.take(GLASSES_MAX_CHARS) + "...\n\n[open phone for more]"
            }
        }

        return formatted
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
