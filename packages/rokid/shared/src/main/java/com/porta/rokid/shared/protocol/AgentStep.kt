package com.porta.rokid.shared.protocol

import com.google.gson.JsonObject

/**
 * Parsed representation of an Antigravity agent step.
 *
 * Steps come from Porta proxy's WebSocket `steps` messages.
 * Each step contains information about what the agent is doing:
 * planning, writing code, running commands, etc.
 */
data class AgentStep(
    /** Step index in the trajectory. */
    val index: Int,

    /** Step status (PENDING, RUNNING, COMPLETE, ERROR, WAITING). */
    val status: StepStatus,

    /** The planner response text (agent's thinking/message to user). */
    val plannerText: String?,

    /** Tool action summary (e.g., "Editing file", "Running command"). */
    val toolAction: String?,

    /** Tool summary (e.g., "File edit", "Command execution"). */
    val toolSummary: String?,

    /** Whether this step requires user approval. */
    val needsApproval: Boolean,

    /** Approval details if step is WAITING. */
    val approvalInfo: ApprovalInfo?,

    /** Command details if this is a command step. */
    val commandInfo: CommandInfo?,

    /** File operation details if this is a file step. */
    val fileInfo: FileInfo?,

    /** Raw step JSON for advanced inspection. */
    val raw: JsonObject
) {

    /**
     * Get a display-friendly summary of this step for the HUD.
     */
    fun hudSummary(): String {
        return when {
            needsApproval -> "⚠️ Approval needed: ${toolAction ?: toolSummary ?: "action"}"
            toolAction != null -> "🔧 $toolAction"
            plannerText != null -> plannerText.take(200)
            else -> "Step $index: ${status.name.lowercase()}"
        }
    }

    companion object {
        /**
         * Parse a raw step JSON object into an AgentStep.
         */
        fun fromJson(index: Int, json: JsonObject): AgentStep {
            val status = StepStatus.fromPortaStatus(
                json.get("status")?.asString
            )

            // Extract planner response text
            val plannerResponse = json.getAsJsonObject("plannerResponse")
            val plannerText = plannerResponse?.get("text")?.asString
                ?: plannerResponse?.getAsJsonArray("items")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("text")
                    ?.asString

            // Extract tool metadata
            val metadata = json.getAsJsonObject("metadata")
            val toolAction = metadata?.get("toolAction")?.asString
            val toolSummary = metadata?.get("toolSummary")?.asString

            // Check for approval requirement
            val requestedInteraction = json.getAsJsonObject("requestedInteraction")
            val needsApproval = status == StepStatus.WAITING && requestedInteraction != null

            val approvalInfo = if (needsApproval) {
                parseApprovalInfo(json, requestedInteraction!!)
            } else null

            // Parse command info
            val runCommand = json.getAsJsonObject("runCommand")
            val commandInfo = runCommand?.let {
                CommandInfo(
                    commandLine = it.get("commandLine")?.asString ?: "",
                    cwd = it.get("cwd")?.asString,
                    approved = it.get("approved")?.asBoolean
                )
            }

            // Parse file info
            val fileInfo = parseFileInfo(json)

            return AgentStep(
                index = index,
                status = status,
                plannerText = plannerText,
                toolAction = toolAction,
                toolSummary = toolSummary,
                needsApproval = needsApproval,
                approvalInfo = approvalInfo,
                commandInfo = commandInfo,
                fileInfo = fileInfo,
                raw = json
            )
        }

        private fun parseApprovalInfo(
            step: JsonObject,
            interaction: JsonObject
        ): ApprovalInfo {
            val meta = step.getAsJsonObject("metadata")
                ?.getAsJsonObject("sourceTrajectoryStepInfo")
            val trajectoryId = meta?.get("trajectoryId")?.asString ?: ""
            val stepIndex = meta?.get("stepIndex")?.asInt ?: 0

            val isCommand = interaction.has("runCommand") || interaction.has("RunCommand")
            val isPermission = interaction.has("permission") || interaction.has("Permission")

            return ApprovalInfo(
                trajectoryId = trajectoryId,
                stepIndex = stepIndex,
                type = when {
                    isCommand -> ApprovalType.COMMAND
                    isPermission -> ApprovalType.PERMISSION
                    else -> ApprovalType.OTHER
                }
            )
        }

        private fun parseFileInfo(json: JsonObject): FileInfo? {
            // Check writeFile, editFile, etc.
            val writeFile = json.getAsJsonObject("writeFile")
            if (writeFile != null) {
                return FileInfo(
                    path = writeFile.get("filePath")?.asString ?: "",
                    action = "write"
                )
            }
            val editFile = json.getAsJsonObject("editFile")
            if (editFile != null) {
                return FileInfo(
                    path = editFile.get("filePath")?.asString ?: "",
                    action = "edit"
                )
            }
            return null
        }
    }
}

data class ApprovalInfo(
    val trajectoryId: String,
    val stepIndex: Int,
    val type: ApprovalType
)

enum class ApprovalType {
    COMMAND,
    PERMISSION,
    OTHER
}

data class CommandInfo(
    val commandLine: String,
    val cwd: String?,
    val approved: Boolean?
)

data class FileInfo(
    val path: String,
    val action: String
)
