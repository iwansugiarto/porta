package id.infinia.porta.shared.protocol

import com.google.gson.JsonObject

/**
 * Chat message — the display unit for the chat UI.
 *
 * Raw trajectory steps from the Porta proxy are transformed into ChatMessages
 * for rendering. This mirrors the PWA's `stepsToMessages.ts` transform exactly.
 */
data class ChatMessage(
    /** "user", "assistant", or "system" */
    val role: String,

    /** Display text content (may contain markdown). */
    val content: String,

    /** Step index in the trajectory. */
    val stepIndex: Int,

    /** Original step type from Porta. */
    val type: String? = null,

    /** Optional icon hint for system messages: "search", "eye", "folder", etc. */
    val icon: String? = null,

    /** Raw step data (for command/code/permission cards). */
    val step: JsonObject? = null,

    /** Agent thinking text (collapsible in UI). */
    val thinking: String? = null,

    /** Duration of thinking phase. */
    val thinkingDuration: String? = null,

    /** Media attachments (images). */
    val media: List<JsonObject>? = null,
)

/**
 * Converts raw trajectory steps into displayable chat messages.
 *
 * This is the Kotlin port of the PWA's `stepsToMessages.ts` — the core
 * transform that makes raw API data look like a proper chat conversation.
 *
 * Step types handled:
 * - USER_INPUT → user bubble
 * - PLANNER_RESPONSE → assistant bubble (with markdown + thinking)
 * - RUN_COMMAND → system card (command with expandable output)
 * - CODE_ACTION → system card (file diff with +/- stats)
 * - GREP_SEARCH → system info line
 * - VIEW_FILE → system info line
 * - VIEW_FILE_OUTLINE → system info line
 * - VIEW_CODE_ITEM → system info line
 * - LIST_DIRECTORY → system info line
 * - FIND → system info line
 * - FILE_PERMISSION → system card (allow/deny)
 * - COMMAND_STATUS → merged into parent command card
 * - SEND_COMMAND_INPUT → terminate notification
 */
fun stepsToMessages(steps: List<JsonObject>): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()

    for (i in steps.indices) {
        val step = steps[i]
        val type = step.get("type")?.asString ?: continue

        // ── File permission request ──
        val fpr = getFilePermissionRequest(step)
        if (fpr != null) {
            messages.add(ChatMessage(
                role = "system",
                content = "",
                stepIndex = i,
                type = "CORTEX_STEP_TYPE_FILE_PERMISSION",
                step = step
            ))
            continue
        }

        // ── Ask question interaction ──
        val reqInteraction = step.getAsJsonObject("requestedInteraction")
        val isAskQuestion = reqInteraction != null &&
            (reqInteraction.has("askQuestion") || reqInteraction.has("AskQuestion"))
        if (isAskQuestion && step.get("status")?.asString == "CORTEX_STEP_STATUS_WAITING") {
            messages.add(ChatMessage(
                role = "system",
                content = "",
                stepIndex = i,
                type = "ASK_QUESTION",
                step = step
            ))
            continue
        }

        when (type) {
            // ── User message ──
            "CORTEX_STEP_TYPE_USER_INPUT" -> {
                val userInput = step.getAsJsonObject("userInput") ?: continue
                val items = userInput.getAsJsonArray("items") ?: continue
                val texts = items
                    .mapNotNull { it.asJsonObject.get("text")?.asString?.trim() }
                    .filter { it.isNotEmpty() }
                val mediaArray = userInput.getAsJsonArray("media")
                    ?.map { it.asJsonObject }
                if (texts.isNotEmpty() || !mediaArray.isNullOrEmpty()) {
                    messages.add(ChatMessage(
                        role = "user",
                        content = texts.joinToString("\n\n"),
                        stepIndex = i,
                        type = type,
                        media = mediaArray
                    ))
                }
            }

            // ── Assistant response ──
            "CORTEX_STEP_TYPE_PLANNER_RESPONSE" -> {
                val pr = step.getAsJsonObject("plannerResponse") ?: continue
                val text = pr.get("modifiedResponse")?.asString ?: ""
                val thinking = pr.get("thinking")?.asString ?: ""
                val thinkingDuration = pr.get("thinkingDuration")?.asString ?: ""

                if (text.trim().isNotEmpty() || thinking.trim().isNotEmpty()) {
                    messages.add(ChatMessage(
                        role = "assistant",
                        content = text,
                        stepIndex = i,
                        type = type,
                        thinking = thinking.ifBlank { null },
                        thinkingDuration = thinkingDuration.ifBlank { null }
                    ))
                }
            }

            // ── Command execution ──
            "CORTEX_STEP_TYPE_RUN_COMMAND" -> {
                val cmd = step.getAsJsonObject("runCommand") ?: continue
                val commandLine = cmd.get("commandLine")?.asString
                    ?: cmd.get("command")?.asString
                    ?: cmd.get("proposedCommandLine")?.asString
                    ?: ""
                if (commandLine.isNotEmpty()) {
                    messages.add(ChatMessage(
                        role = "system",
                        content = "",
                        stepIndex = i,
                        type = type,
                        step = step
                    ))
                }
            }

            // ── Code edit ──
            "CORTEX_STEP_TYPE_CODE_ACTION" -> {
                if (step.has("codeAction")) {
                    messages.add(ChatMessage(
                        role = "system",
                        content = "",
                        stepIndex = i,
                        type = type,
                        step = step
                    ))
                }
            }

            // ── Command status update → merge into parent command ──
            "CORTEX_STEP_TYPE_COMMAND_STATUS" -> {
                val cs = step.getAsJsonObject("commandStatus") ?: continue
                val cmdId = cs.get("commandId")?.asString ?: continue
                val combined = cs.get("combined")?.asString ?: ""
                val status = cs.get("status")?.asString

                // Find the matching command card and update its output
                for (j in messages.indices.reversed()) {
                    val m = messages[j]
                    val mCmd = m.step?.getAsJsonObject("runCommand")
                    if (mCmd != null && mCmd.get("commandId")?.asString == cmdId) {
                        if (status == "CORTEX_STEP_STATUS_DONE" && combined.isNotEmpty()) {
                            // Inject combinedOutput into the step data
                            val output = JsonObject().apply {
                                addProperty("full", combined)
                            }
                            mCmd.add("combinedOutput", output)
                        }
                        break
                    }
                }
            }

            // ── Send command input (terminate) ──
            "CORTEX_STEP_TYPE_SEND_COMMAND_INPUT" -> {
                val sci = step.getAsJsonObject("sendCommandInput")
                if (sci?.get("terminate")?.asBoolean == true) {
                    messages.add(ChatMessage(
                        role = "system",
                        content = "⏹ Sending termination to command",
                        stepIndex = i,
                        type = type
                    ))
                }
            }

            // ── Grep search ──
            "CORTEX_STEP_TYPE_GREP_SEARCH" -> {
                val gs = step.getAsJsonObject("grepSearch") ?: continue
                val query = gs.get("query")?.asString ?: ""
                val results = gs.getAsJsonArray("results")?.size() ?: 0
                val searchPath = (gs.get("searchPathUri")?.asString ?: "")
                    .removePrefix("file://")
                val pathLabel = searchPath.substringAfterLast("/")
                messages.add(ChatMessage(
                    role = "system",
                    content = "Searched `$query` in **$pathLabel** — $results result${if (results != 1) "s" else ""}",
                    stepIndex = i,
                    type = type,
                    icon = "search"
                ))
            }

            // ── View file ──
            "CORTEX_STEP_TYPE_VIEW_FILE" -> {
                val vf = step.getAsJsonObject("viewFile") ?: continue
                val uri = (vf.get("absolutePathUri")?.asString ?: "").removePrefix("file://")
                val name = uri.substringAfterLast("/")
                val startLine = vf.get("startLine")?.asInt
                val endLine = vf.get("endLine")?.asInt
                val range = if (startLine != null && endLine != null) " #L$startLine-$endLine" else ""
                messages.add(ChatMessage(
                    role = "system",
                    content = "Viewed **$name**$range",
                    stepIndex = i,
                    type = type,
                    icon = "eye"
                ))
            }

            // ── View file outline ──
            "CORTEX_STEP_TYPE_VIEW_FILE_OUTLINE" -> {
                val vfo = step.getAsJsonObject("viewFileOutline") ?: continue
                val uri = (vfo.get("absolutePathUri")?.asString ?: "").removePrefix("file://")
                val name = uri.substringAfterLast("/")
                messages.add(ChatMessage(
                    role = "system",
                    content = "Outlined **$name**",
                    stepIndex = i,
                    type = type,
                    icon = "list"
                ))
            }

            // ── View code item ──
            "CORTEX_STEP_TYPE_VIEW_CODE_ITEM" -> {
                val vci = step.getAsJsonObject("viewCodeItem") ?: continue
                val uri = (vci.get("absoluteUri")?.asString ?: "").removePrefix("file://")
                val name = uri.substringAfterLast("/")
                val nodes = vci.getAsJsonArray("nodePaths")
                    ?.joinToString(", ") { it.asString } ?: ""
                val suffix = if (nodes.isNotEmpty()) " → $nodes" else ""
                messages.add(ChatMessage(
                    role = "system",
                    content = "Analyzed **$name**$suffix",
                    stepIndex = i,
                    type = type,
                    icon = "file-search"
                ))
            }

            // ── List directory ──
            "CORTEX_STEP_TYPE_LIST_DIRECTORY" -> {
                val ld = step.getAsJsonObject("listDirectory") ?: continue
                val uri = (ld.get("directoryPathUri")?.asString ?: "").removePrefix("file://")
                val name = uri.substringAfterLast("/")
                val count = ld.getAsJsonArray("results")?.size() ?: 0
                messages.add(ChatMessage(
                    role = "system",
                    content = "Listed **$name/** — $count items",
                    stepIndex = i,
                    type = type,
                    icon = "folder"
                ))
            }

            // ── Find ──
            "CORTEX_STEP_TYPE_FIND" -> {
                val f = step.getAsJsonObject("find") ?: continue
                val pattern = f.get("pattern")?.asString ?: "*"
                val results = f.getAsJsonArray("results")?.size() ?: 0
                messages.add(ChatMessage(
                    role = "system",
                    content = "Find `$pattern` — $results result${if (results != 1) "s" else ""}",
                    stepIndex = i,
                    type = type,
                    icon = "search"
                ))
            }

            // ── Generate Image ──
            "CORTEX_STEP_TYPE_GENERATE_IMAGE" -> {
                val gi = step.getAsJsonObject("generateImage") ?: continue
                val prompt = gi.get("prompt")?.asString ?: "image"
                val name = gi.get("imageName")?.asString ?: ""
                messages.add(ChatMessage(
                    role = "system",
                    content = "Generated image: **$name**\n_\"${prompt.take(80)}${if (prompt.length > 80) "…" else ""}\"_",
                    stepIndex = i,
                    type = type,
                    icon = "image",
                    step = step
                ))
            }

            // ── Web Search ──
            "CORTEX_STEP_TYPE_SEARCH_WEB" -> {
                val sw = step.getAsJsonObject("searchWeb") ?: continue
                val query = sw.get("query")?.asString ?: ""
                messages.add(ChatMessage(
                    role = "system",
                    content = "Searched web: **\"$query\"**",
                    stepIndex = i,
                    type = type,
                    icon = "search"
                ))
            }

            // ── Read URL Content ──
            "CORTEX_STEP_TYPE_READ_URL_CONTENT" -> {
                val ruc = step.getAsJsonObject("readUrlContent") ?: continue
                val url = ruc.get("url")?.asString ?: ""
                val domain = try {
                    java.net.URI(url).host ?: url.take(30)
                } catch (_: Exception) { url.take(30) }
                messages.add(ChatMessage(
                    role = "system",
                    content = "Fetched **$domain**",
                    stepIndex = i,
                    type = type,
                    icon = "eye"
                ))
            }

            // ── Browser Subagent ──
            "CORTEX_STEP_TYPE_BROWSER_SUBAGENT" -> {
                val bs = step.getAsJsonObject("browserSubagent") ?: continue
                val taskName = bs.get("taskName")?.asString ?: "Browser task"
                messages.add(ChatMessage(
                    role = "system",
                    content = "Browser: **$taskName**",
                    stepIndex = i,
                    type = type,
                    icon = "eye"
                ))
            }

            // ── Semantic Search ──
            "CORTEX_STEP_TYPE_SEMANTIC_SEARCH" -> {
                val ss = step.getAsJsonObject("semanticSearch") ?: continue
                val query = ss.get("query")?.asString ?: ""
                val results = ss.getAsJsonArray("results")?.size() ?: 0
                messages.add(ChatMessage(
                    role = "system",
                    content = "Semantic search `$query` — $results result${if (results != 1) "s" else ""}",
                    stepIndex = i,
                    type = type,
                    icon = "search"
                ))
            }

            // ── Catch-all for unhandled tool types ──
            else -> {
                // Show unknown step types as compact info cards
                val toolName = step.getAsJsonObject("metadata")
                    ?.getAsJsonObject("toolCall")?.get("name")?.asString
                val toolAction = step.getAsJsonObject("metadata")
                    ?.getAsJsonObject("toolCall")?.get("toolAction")?.asString
                val toolSummary = step.getAsJsonObject("metadata")
                    ?.getAsJsonObject("toolCall")?.get("toolSummary")?.asString
                val label = toolAction ?: toolSummary ?: toolName
                if (label != null) {
                    messages.add(ChatMessage(
                        role = "system",
                        content = label,
                        stepIndex = i,
                        type = type,
                        icon = "info"
                    ))
                }
            }
        }
    }

    // Collapse consecutive text-only system messages (same as PWA)
    val collapsed = mutableListOf<ChatMessage>()
    for (msg in messages) {
        if (msg.role == "system" && msg.step == null) {
            val prev = collapsed.lastOrNull()
            if (prev != null && prev.role == "system" && prev.step == null) {
                collapsed[collapsed.lastIndex] = prev.copy(
                    content = prev.content + "\n" + msg.content
                )
                continue
            }
        }
        collapsed.add(msg)
    }

    return collapsed
}

/**
 * Extract file permission request from step data.
 * The LS embeds it in various tool data fields.
 */
private fun getFilePermissionRequest(step: JsonObject): JsonObject? {
    return step.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("viewFile")?.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("listDirectory")?.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("codeAction")?.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("grepSearch")?.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("viewFileOutline")?.getAsJsonObject("filePermissionRequest")
        ?: step.getAsJsonObject("viewCodeItem")?.getAsJsonObject("filePermissionRequest")
}
