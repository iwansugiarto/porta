package id.infinia.porta.ui

import com.google.gson.JsonObject

/**
 * Shared UI utility functions used across multiple screens.
 */
object UiUtils {

    /**
     * Format an ISO timestamp as a human-readable relative time.
     * e.g. "just now", "5m ago", "2h ago", "3d ago"
     */
    fun relativeTime(iso: String?): String {
        if (iso == null) return ""
        return try {
            val diff = System.currentTimeMillis() -
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(iso.take(19))!!.time
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
