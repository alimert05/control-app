package com.alicontrol.app.ble

/**
 * The minimal JSON field reader shared by [ProtocolMessage] and
 * [com.alicontrol.app.live.LiveStateMessage].
 *
 * This app deliberately carries no JSON library (see [ProtocolMessage]): the
 * wire format is a handful of flat objects with known field names, and a
 * dependency-free reader keeps the APK small on a low-end tablet. Extracting
 * it here means there is still exactly **one** implementation of that reading
 * — the live-state parser is not a second, subtly different copy.
 *
 * Each accessor matches a top-level `"field": value` pair. The negative
 * lookbehind rejects a field name that is itself *inside* a string value
 * (where the quote would be backslash-escaped), so a track title containing
 * something like `\"cpu\":99` cannot be mistaken for a real field.
 */
internal object JsonFields {

    /** True only for a complete, closed JSON object — a truncated payload fails here rather than half-parsing. */
    fun isCompleteObject(json: String): Boolean {
        val trimmed = json.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    fun string(json: String, field: String): String? {
        val regex = Regex("(?<!\\\\)\"${Regex.escape(field)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return unescape(raw)
    }

    /**
     * Reads a JSON number as a rounded [Int]. Accepts a decimal form so the
     * parser stays tolerant of a producer that sends `34.6`, even though this
     * protocol's own producer already rounds to whole numbers.
     */
    fun int(json: String, field: String): Int? {
        val regex = Regex("(?<!\\\\)\"${Regex.escape(field)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return raw.toDoubleOrNull()
            ?.takeIf { !it.isNaN() && !it.isInfinite() }
            ?.let { Math.round(it).toInt() }
    }

    fun boolean(json: String, field: String): Boolean? {
        val regex = Regex("(?<!\\\\)\"${Regex.escape(field)}\"\\s*:\\s*(true|false)")
        return when (regex.find(json)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")
}
