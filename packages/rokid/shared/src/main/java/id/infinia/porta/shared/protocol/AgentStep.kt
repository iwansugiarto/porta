package id.infinia.porta.shared.protocol

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

    /** Question details if this is an ask_question step. */
    val questionInfo: QuestionInfo?,

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
            val plannerText = plannerResponse?.get("modifiedResponse")?.asString
                ?: plannerResponse?.get("text")?.asString
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
            val needsApproval = status == StepStatus.WAITING
            val requestedInteraction = json.getAsJsonObject("requestedInteraction")

            val approvalInfo = if (needsApproval) {
                if (requestedInteraction != null) {
                    parseApprovalInfo(json, requestedInteraction)
                } else {
                    val meta = json.getAsJsonObject("metadata")
                        ?.getAsJsonObject("sourceTrajectoryStepInfo")
                    val trajectoryId = meta?.get("trajectoryId")?.asString ?: ""
                    val stepIndex = meta?.get("stepIndex")?.asInt ?: 0
                    ApprovalInfo(
                        trajectoryId = trajectoryId,
                        stepIndex = stepIndex,
                        type = ApprovalType.OTHER
                    )
                }
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

            // Parse question info
            val questionInfo = if (needsApproval && requestedInteraction != null) {
                parseQuestionInfo(requestedInteraction)
            } else null

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
                questionInfo = questionInfo,
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
            val isQuestion = interaction.has("askQuestion") || interaction.has("AskQuestion")

            return ApprovalInfo(
                trajectoryId = trajectoryId,
                stepIndex = stepIndex,
                type = when {
                    isCommand -> ApprovalType.COMMAND
                    isPermission -> ApprovalType.PERMISSION
                    isQuestion -> ApprovalType.QUESTION
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

        /**
         * Parse question info from requestedInteraction.askQuestion.
         */
        private fun parseQuestionInfo(interaction: JsonObject): QuestionInfo? {
            val aq = interaction.getAsJsonObject("askQuestion")
                ?: interaction.getAsJsonObject("AskQuestion")
                ?: return null

            val question = aq.get("question")?.asString ?: ""
            val isMultiSelect = aq.get("isMultiSelect")?.asBoolean
                ?: aq.get("is_multi_select")?.asBoolean
                ?: false

            val options = mutableListOf<QuestionOption>()
            val optionsArray = aq.getAsJsonArray("options")
                ?: aq.getAsJsonArray("questions")?.firstOrNull()
                    ?.asJsonObject?.getAsJsonArray("options")
            if (optionsArray != null) {
                for (opt in optionsArray) {
                    val optText = if (opt.isJsonPrimitive) {
                        opt.asString
                    } else {
                        opt.asJsonObject?.get("text")?.asString
                            ?: opt.asJsonObject?.get("option")?.asString
                            ?: opt.toString()
                    }
                    options.add(QuestionOption(text = optText))
                }
            }

            // Also check for nested questions array (the ask_question tool format)
            val questionsArray = aq.getAsJsonArray("questions")
            if (questionsArray != null && questionsArray.size() > 0) {
                val firstQ = questionsArray[0].asJsonObject
                val qText = firstQ.get("question")?.asString ?: question
                val qMulti = firstQ.get("is_multi_select")?.asBoolean ?: isMultiSelect
                val qOptions = mutableListOf<QuestionOption>()
                firstQ.getAsJsonArray("options")?.forEach { opt ->
                    val optText = if (opt.isJsonPrimitive) opt.asString
                    else opt.asJsonObject?.get("text")?.asString ?: opt.toString()
                    qOptions.add(QuestionOption(text = optText))
                }
                if (qOptions.isNotEmpty() || qText.isNotBlank()) {
                    return QuestionInfo(
                        question = qText,
                        options = qOptions,
                        isMultiSelect = qMulti
                    )
                }
            }

            return QuestionInfo(
                question = question,
                options = options,
                isMultiSelect = isMultiSelect
            )
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
    QUESTION,
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

/** Question data from an ask_question interaction. */
data class QuestionInfo(
    val question: String,
    val options: List<QuestionOption>,
    val isMultiSelect: Boolean
)

data class QuestionOption(
    val text: String
)
