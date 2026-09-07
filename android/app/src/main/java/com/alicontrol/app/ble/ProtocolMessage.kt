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
        append("\"type\":\"").append(escape(type)).append('"')
        action?.let { append(",\"action\":\"").append(escape(it)).append('"') }
        status?.let { append(",\"status\":\"").append(escape(it)).append('"') }
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
            val type = extractField(json, "type") ?: return null
            return ProtocolMessage(
                type = type,
                action = extractField(json, "action"),
                status = extractField(json, "status")
            )
        }

        private fun extractField(json: String, field: String): String? {
            val regex = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            val raw = regex.find(json)?.groupValues?.get(1) ?: return null
            return unescape(raw)
        }

        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun unescape(value: String): String =
            value.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
