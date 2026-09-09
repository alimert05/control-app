package com.alicontrol.app.live

/**
 * Turns live values into the exact strings the widgets show.
 *
 * Pure and free of Android types so every formatting rule — especially the
 * unavailable cases — is unit-tested rather than eyeballed on a tablet.
 *
 * Two rules run through all of it:
 *
 * - **Unavailable is a real state**, rendered as `--`, never as `0%` and
 *   never as a stale previous value.
 * - **Whole numbers only.** A dashboard tile is a glanceable readout; a
 *   decimal place would add width and noise without adding meaning.
 */
object LiveValueFormatter {

    const val UNAVAILABLE = "--"
    const val UNAVAILABLE_TIME = "--:--"

    fun metric(value: Int?, unit: MetricUnit): String {
        if (value == null) return UNAVAILABLE
        return when (unit) {
            MetricUnit.PERCENT -> "${value.coerceIn(0, 100)}%"
            MetricUnit.CELSIUS -> "$value°"
        }
    }

    /**
     * `m:ss`, or `h:mm:ss` past an hour. Returns [UNAVAILABLE_TIME] for a
     * missing or nonsensical value rather than rendering "0:00", which would
     * read as a real position at the start of a track.
     */
    fun time(seconds: Int?): String {
        if (seconds == null || seconds < 0) return UNAVAILABLE_TIME
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
        } else {
            "%d:%02d".format(minutes, remainingSeconds)
        }
    }

    /**
     * Track progress as 0..1 for the progress bar, or null when it can't be
     * known. Guards the division so a zero-length track can never produce
     * NaN, and clamps a position past the end (which a player can briefly
     * report between tracks).
     */
    fun progressFraction(positionSeconds: Int?, durationSeconds: Int?): Float? {
        if (positionSeconds == null || durationSeconds == null) return null
        if (positionSeconds < 0 || durationSeconds <= 0) return null
        return (positionSeconds.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * The single line under the title: the artist, with the album appended
     * when there's one and they differ. Returns null when there's nothing
     * worth a second line.
     */
    fun artistLine(artist: String?, album: String?): String? {
        val cleanArtist = artist?.trim().takeUnless { it.isNullOrEmpty() }
        val cleanAlbum = album?.trim().takeUnless { it.isNullOrEmpty() }
        return when {
            cleanArtist != null && cleanAlbum != null && !cleanArtist.equals(cleanAlbum, ignoreCase = true) ->
                "$cleanArtist — $cleanAlbum"
            cleanArtist != null -> cleanArtist
            else -> cleanAlbum
        }
    }
}
