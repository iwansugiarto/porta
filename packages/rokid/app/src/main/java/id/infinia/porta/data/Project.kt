package id.infinia.porta.data

import com.google.gson.annotations.SerializedName

enum class ProjectStatus(val value: String) {
    @SerializedName("Blocked")
    BLOCKED("Blocked"),

    @SerializedName("In Progress")
    IN_PROGRESS("In Progress"),

    @SerializedName("Idle")
    IDLE("Idle")
}

data class Project(
    val id: String,
    val title: String,
    val status: ProjectStatus,
    val lastUpdated: String,
    val timeBadge: String? = null,
    val hasIndicator: Boolean = false
)
