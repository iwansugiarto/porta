package com.porta.rokid.shared.protocol

/**
 * Connection state for both Porta WebSocket and Rokid CXR connections.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Agent run status — mirrors Porta proxy CASCADE_RUN_STATUS_* values.
 */
enum class AgentStatus {
    IDLE,
    RUNNING,
    ERROR,
    UNLOADED;

    companion object {
        fun fromPortaStatus(status: String?): AgentStatus = when (status) {
            "CASCADE_RUN_STATUS_RUNNING" -> RUNNING
            "CASCADE_RUN_STATUS_ERROR" -> ERROR
            "CASCADE_RUN_STATUS_UNLOADED" -> UNLOADED
            else -> IDLE
        }
    }
}

/**
 * Step status — mirrors Porta proxy CORTEX_STEP_STATUS_* values.
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETE,
    ERROR,
    WAITING;

    companion object {
        fun fromPortaStatus(status: String?): StepStatus = when (status) {
            "CORTEX_STEP_STATUS_PENDING" -> PENDING
            "CORTEX_STEP_STATUS_RUNNING" -> RUNNING
            "CORTEX_STEP_STATUS_COMPLETE" -> COMPLETE
            "CORTEX_STEP_STATUS_ERROR" -> ERROR
            "CORTEX_STEP_STATUS_WAITING" -> WAITING
            else -> PENDING
        }
    }
}
