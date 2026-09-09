package com.alicontrol.app.live

import com.alicontrol.app.ble.JsonFields
import java.nio.charset.StandardCharsets

/**
 * The desktop→tablet live-state messages added in TASK-009.
 *
 * These travel over the **same** BLE state characteristic as the existing
 * command acknowledgements and are told apart by their `type` field, so no
 * UUID, MTU behaviour or reconnect logic changed to accommodate them. A
 * message this build doesn't recognise (including every existing
 * `{"type":"state",...}` ack) simply isn't a [LiveStateMessage] and is left
 * for [com.alicontrol.app.ble.ProtocolMessage] to handle.
 *
 * Wire formats:
 *
 * ```json
 * {"type":"metrics","cpu":34,"memory":48}
 * {"type":"media","available":true,"source":"spotify","playing":true,"position":83,"duration":214}
 * {"type":"track","title":"Song","artist":"Artist","album":"Album"}
 * ```
 *
 * Playback and track identity are separate messages on purpose: playback is
 * small and changes every couple of seconds, while track text is the only
 * unbounded content in the protocol and changes rarely. Splitting them is
 * half of the payload-size strategy (the other half is the desktop bounding
 * each text field to a fixed escaped-UTF-8 budget), and it means the frequent
 * message can never approach the MTU at all.
 */
sealed class LiveStateMessage {

    /** System telemetry. Absent fields mean unavailable, never zero. */
    data class Metrics(
        val cpuPercent: Int? = null,
        val memoryPercent: Int? = null,
        val gpuPercent: Int? = null,
        val cpuTemperatureCelsius: Int? = null,
        val gpuTemperatureCelsius: Int? = null
    ) : LiveStateMessage()

    /** Playback state, with no track text. */
    data class Playback(
        val isAvailable: Boolean,
        val source: String? = null,
        val isPlaying: Boolean = false,
        val positionSeconds: Int? = null,
        val durationSeconds: Int? = null
    ) : LiveStateMessage()

    /** Track identity, sent only when the track actually changes. */
    data class Track(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artworkId: String? = null
    ) : LiveStateMessage()

    companion object {
        const val TYPE_METRICS = "metrics"
        const val TYPE_MEDIA = "media"
        const val TYPE_TRACK = "track"

        fun fromBytes(data: ByteArray): LiveStateMessage? =
            fromJson(String(data, StandardCharsets.UTF_8))

        /**
         * Returns null for anything that isn't a well-formed live-state
         * message — including a truncated payload, an unknown `type`, and
         * ordinary command acknowledgements. Parsing never throws, so a
         * garbled notification can't take down the receive path.
         */
        fun fromJson(json: String): LiveStateMessage? {
            val trimmed = json.trim()
            if (!JsonFields.isCompleteObject(trimmed)) return null

            return when (JsonFields.string(trimmed, "type")) {
                TYPE_METRICS -> Metrics(
                    cpuPercent = percent(trimmed, "cpu"),
                    memoryPercent = percent(trimmed, "memory"),
                    gpuPercent = percent(trimmed, "gpu"),
                    cpuTemperatureCelsius = JsonFields.int(trimmed, "cpu_temp"),
                    gpuTemperatureCelsius = JsonFields.int(trimmed, "gpu_temp")
                )

                TYPE_MEDIA -> Playback(
                    // A media message without `available` is malformed rather
                    // than "available by default" — better to ignore it than
                    // to invent a playback state.
                    isAvailable = JsonFields.boolean(trimmed, "available") ?: return null,
                    source = JsonFields.string(trimmed, "source"),
                    isPlaying = JsonFields.boolean(trimmed, "playing") ?: false,
                    positionSeconds = nonNegative(trimmed, "position"),
                    durationSeconds = nonNegative(trimmed, "duration")
                )

                TYPE_TRACK -> Track(
                    title = JsonFields.string(trimmed, "title"),
                    artist = JsonFields.string(trimmed, "artist"),
                    album = JsonFields.string(trimmed, "album"),
                    artworkId = JsonFields.string(trimmed, "artworkId")
                )

                else -> null
            }
        }

        /** Clamps to 0-100 so a nonsense value can never reach a gauge or a label. */
        private fun percent(json: String, field: String): Int? =
            JsonFields.int(json, field)?.coerceIn(0, 100)

        private fun nonNegative(json: String, field: String): Int? =
            JsonFields.int(json, field)?.takeIf { it >= 0 }
    }
}
