package id.infinia.porta.ui

import com.google.gson.JsonObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Shared UI utility functions used across multiple screens.
 */
object UiUtils {

    private val isoFormat by lazy {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** Regex matching UUID-truncated summaries like "6e37b933…" */
    private val UUID_SUMMARY = Regex("^[0-9a-f]{8}…?\\.?$")

    /**
     * Clean and normalize a title string by unescaping common HTML entities,
     * converting to lowercase, and removing all non-alphanumeric characters.
     * Useful for robustly comparing project titles and conversation summaries.
     */
    fun normalizeTitle(title: String?): String {
        if (title == null) return ""
        val unescaped = title.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        return unescaped.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    /**
     * Format an ISO timestamp as a human-readable relative time.
     * e.g. "just now", "5m ago", "2h ago", "3d ago"
     */
    fun relativeTime(iso: String?): String {
        if (iso == null) return ""
        return try {
            val diff = System.currentTimeMillis() -
                    isoFormat.parse(iso.take(19))!!.time
            val mins = diff / 60_000
            when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 1440 -> "${mins / 60}h ago"
                else -> "${mins / 1440}d ago"
            }
        } catch (_: Exception) { "" }
    }

    /**
     * Classify an ISO timestamp into a time group for section headers.
     */
    fun timeGroup(iso: String?): String {
        if (iso == null) return "Earlier"
        return try {
            val ts = isoFormat.parse(iso.take(19))!!.time
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ts }

            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val yesterdayStart = todayStart - 86_400_000L
            val weekStart = todayStart - 6 * 86_400_000L

            when {
                ts >= todayStart -> "Today"
                ts >= yesterdayStart -> "Yesterday"
                ts >= weekStart -> "This Week"
                else -> "Earlier"
            }
        } catch (_: Exception) { "Earlier" }
    }

    /**
     * Get a display-ready title from a conversation summary.
     * Returns the actual summary text if it's meaningful,
     * or "New conversation" for UUID-only placeholders.
     */
    fun displayTitle(summary: JsonObject): String {
        val text = summary.get("summary")?.asString ?: return "New conversation"
        if (text.isBlank() || UUID_SUMMARY.matches(text)) {
            // For disk-only conversations with UUID placeholder titles,
            // try to extract a workspace folder name for context
            val workspace = extractWorkspaceName(summary)
            return if (workspace != "Others") "Conversation · $workspace" else "New conversation"
        }
        return text
    }

    /**
     * Check if a conversation is a "ghost" — disk-only with no real data.
     * These should be hidden from the UI to avoid clutter.
     */
    fun isGhostConversation(summary: JsonObject): Boolean {
        val diskOnly = summary.get("_diskOnly")?.asBoolean == true
        val stepCount = summary.get("stepCount")?.asInt ?: 0
        val title = summary.get("summary")?.asString ?: ""
        val hasTimestamp = summary.has("lastModifiedTime") &&
            !summary.get("lastModifiedTime").isJsonNull
        // A ghost is a disk-only placeholder with no steps, no meaningful title,
        // AND no modification timestamp. Real conversations always have timestamps.
        return diskOnly && stepCount == 0 &&
            (title.isBlank() || UUID_SUMMARY.matches(title)) &&
            !hasTimestamp
    }

    /**
     * Extract workspace display name from conversation summary JSON.
     * Returns the repository's computed name, or the folder URI basename,
     * or "Others" as fallback.
     */
    fun extractWorkspaceName(summary: JsonObject): String {
        val workspaces = summary.getAsJsonArray("workspaces")
        if (workspaces == null || workspaces.size() == 0) return "Others"
        val ws = workspaces[0].asJsonObject
        val repo = ws.getAsJsonObject("repository")?.get("computedName")?.asString
        if (repo != null) return repo.substringAfterLast("/")
        val uri = ws.get("workspaceFolderAbsoluteUri")?.asString
        if (uri != null) return uri.substringAfterLast("/")
        return "Others"
    }
}
