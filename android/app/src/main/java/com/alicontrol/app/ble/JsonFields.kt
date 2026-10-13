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
     * Reads a JSON array of strings (`"field": ["a","b"]`). Returns null when the
     * field is absent, is not an array, or contains anything other than strings —
     * a malformed list is never half-read.
     */
    fun stringArray(json: String, field: String): List<String>? {
        val header = Regex("(?<!\\\\)\"${Regex.escape(field)}\"\\s*:\\s*\\[").find(json) ?: return null
        val items = ArrayList<String>()
        val element = Regex("\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*")
        var index = header.range.last + 1

        // Empty array.
        Regex("\\s*]").matchAt(json, index)?.let { return items }

        while (true) {
            val match = element.matchAt(json, index) ?: return null
            items.add(unescape(match.groupValues[1]))
            index = match.range.last + 1
            when (json.getOrNull(index)) {
                ',' -> index++
                ']' -> return items
                else -> return null
            }
        }
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

    /** Escapes only `"` and `\` — the two characters this app's own messages ever need escaped. */
    fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Decodes every escape sequence standard JSON allows, in one left-to-right
     * pass (so `\\n` is a backslash followed by `n`, not a newline). The Windows
     * agent only ever escapes `"` and `\`, but a spec-compliant producer may
     * also emit `\/`, `\n`, `\uXXXX` and the rest, and a title must never show
     * up with a literal backslash sequence in it. An unknown or truncated escape
     * is kept verbatim rather than guessed at.
     */
    internal fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '/' -> { out.append('/'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'u' -> {
                    val hex = value.substring(i + 2, minOf(i + 6, value.length))
                    val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append(c).append(next)
                        i += 2
                    }
                }
                else -> { out.append(c).append(next); i += 2 }
            }
        }
        return out.toString()
    }
}
