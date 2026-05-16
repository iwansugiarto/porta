package com.porta.rokid.shared.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Messages exchanged between the Porta proxy WebSocket and the bridge app.
 *
 * ## Porta Proxy → Client (Incoming)
 * - `ready`  — Connection established, includes current step count
 * - `steps`  — New/updated steps (streaming delta)
 * - `status` — Agent running status change
 *
 * ## Client → Porta Proxy (Outgoing)
 * - `sync`    — Update the step offset cursor
 * - `refresh` — Full re-fetch from step 0
 */
sealed class PortaMessage {

    // ── Incoming messages (proxy → client) ──

    /** Sent immediately after WebSocket connection is established. */
    data class Ready(
        val stepCount: Int = 0
    ) : PortaMessage()

    /** Streaming step delta — new or updated steps. */
    data class Steps(
        val offset: Int = 0,
        val steps: List<JsonObject> = emptyList()
    ) : PortaMessage()

    /** Agent status change notification. */
    data class Status(
        val running: Boolean = false
    ) : PortaMessage()

    // ── Outgoing messages (client → proxy) ──

    /** Sync cursor offset — tells proxy where the client left off. */
    data class Sync(
        val fromOffset: Int
    ) : PortaMessage() {
        val type: String = "sync"
    }

    /** Request full re-fetch from step 0. */
    class Refresh : PortaMessage() {
        val type: String = "refresh"
    }

    companion object {
        private val gson = Gson()

        /**
         * Parse a raw JSON WebSocket message into a typed PortaMessage.
         */
        fun parse(raw: String): PortaMessage? {
            return try {
                val json = gson.fromJson(raw, JsonObject::class.java)
                when (json.get("type")?.asString) {
                    "ready" -> Ready(
                        stepCount = json.get("stepCount")?.asInt ?: 0
                    )
                    "steps" -> Steps(
                        offset = json.get("offset")?.asInt ?: 0,
                        steps = json.getAsJsonArray("steps")
                            ?.map { it.asJsonObject }
                            ?: emptyList()
                    )
                    "status" -> Status(
                        running = json.get("running")?.asBoolean ?: false
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Serialize an outgoing message to JSON.
         */
        fun serialize(message: PortaMessage): String {
            return gson.toJson(message)
        }
    }
}

/**
 * Messages exchanged between the phone bridge app and the glasses HUD.
 *
 * ## Phone → Glasses
 * - `chat_stream`      — Streaming text chunk
 * - `chat_stream_end`  — End of streaming response
 * - `agent_thinking`   — Agent is processing
 * - `connection_update` — Connection state change
 * - `session_list`     — Available conversations
 * - `approval_request` — Step waiting for user approval
 * - `voice_state`      — Voice recording state
 *
 * ## Glasses → Phone
 * - `user_input`       — Text + optional photo from user
 * - `start_voice`      — Begin voice recording
 * - `cancel_voice`     — Cancel voice recording
 * - `approve_action`   — Approve a waiting step
 * - `reject_action`    — Reject a waiting step
 * - `switch_session`   — Switch conversation
 */
enum class BridgeMessageType {
    // Phone → Glasses
    @SerializedName("chat_stream") CHAT_STREAM,
    @SerializedName("chat_stream_end") CHAT_STREAM_END,
    @SerializedName("agent_thinking") AGENT_THINKING,
    @SerializedName("connection_update") CONNECTION_UPDATE,
    @SerializedName("session_list") SESSION_LIST,
    @SerializedName("approval_request") APPROVAL_REQUEST,
    @SerializedName("voice_state") VOICE_STATE,

    // Glasses → Phone
    @SerializedName("user_input") USER_INPUT,
    @SerializedName("start_voice") START_VOICE,
    @SerializedName("cancel_voice") CANCEL_VOICE,
    @SerializedName("approve_action") APPROVE_ACTION,
    @SerializedName("reject_action") REJECT_ACTION,
    @SerializedName("switch_session") SWITCH_SESSION,
}

/**
 * Bridge message structure for phone ↔ glasses communication.
 */
data class BridgeMessage(
    val type: BridgeMessageType,
    val data: JsonObject? = null
) {
    companion object {
        private val gson = Gson()

        fun parse(raw: String): BridgeMessage? {
            return try {
                gson.fromJson(raw, BridgeMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun serialize(message: BridgeMessage): String {
            return gson.toJson(message)
        }
    }
}
