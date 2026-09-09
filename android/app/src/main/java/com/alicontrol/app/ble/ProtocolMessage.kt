package com.alicontrol.app.ble

import java.nio.charset.StandardCharsets

/**
 * Minimal, transport-independent message envelope for the Ali Control protocol.
 *
 * This is deliberately not tied to BLE (or to any future USB/Wi-Fi transport)
 * and not tied to any desktop operating system — it is just the shape of data
 * that flows over [com.alicontrol.app.transport.Transport.send] and
 * [com.alicontrol.app.transport.TransportListener.onDataReceived].
 *
 * A hand-rolled encoder/decoder is used instead of a JSON library: the shape is
 * fixed and tiny, and this keeps the app free of an extra dependency.
 */
data class ProtocolMessage(
    val type: String,
    val action: String? = null,
    val status: String? = null
) {

    fun toJson(): String = buildString {
        append('{')
        append("\"type\":\"").append(JsonFields.escape(type)).append('"')
        action?.let { append(",\"action\":\"").append(JsonFields.escape(it)).append('"') }
        status?.let { append(",\"status\":\"").append(JsonFields.escape(it)).append('"') }
        append('}')
    }

    fun toBytes(): ByteArray = toJson().toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val TYPE_COMMAND = "command"
        const val TYPE_STATE = "state"

        fun command(action: String) = ProtocolMessage(type = TYPE_COMMAND, action = action)

        fun state(status: String) = ProtocolMessage(type = TYPE_STATE, status = status)

        fun fromBytes(data: ByteArray): ProtocolMessage? =
            fromJson(String(data, StandardCharsets.UTF_8))

        fun fromJson(json: String): ProtocolMessage? {
            val trimmed = json.trim()
            // Reject anything that isn't a complete, closed object up front —
            // otherwise a truncated payload (e.g. cut short by BLE MTU limits
            // mid-transmission) can still "successfully" parse as long as the
            // first field it contains happens to be well-formed, silently
            // hiding real transport-level corruption instead of failing safely.
            if (!JsonFields.isCompleteObject(trimmed)) return null

            val type = JsonFields.string(trimmed, "type") ?: return null
            return ProtocolMessage(
                type = type,
                action = JsonFields.string(trimmed, "action"),
                status = JsonFields.string(trimmed, "status")
            )
        }
    }
}
