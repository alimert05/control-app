package com.alicontrol.app.live

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of the desktop→tablet live-state messages, including every way a
 * notification can arrive wrong.
 */
class LiveStateMessageTest {

    // -- metrics --

    @Test
    fun parsesTheMetricsMessageTheDesktopSends() {
        val message = LiveStateMessage.fromJson("{\"type\":\"metrics\",\"cpu\":34,\"memory\":48}")

        assertEquals(LiveStateMessage.Metrics(cpuPercent = 34, memoryPercent = 48), message)
    }

    @Test
    fun anOmittedMetricMeansUnavailableNotZero() {
        val message = LiveStateMessage.fromJson("{\"type\":\"metrics\",\"cpu\":0,\"memory\":50}") as LiveStateMessage.Metrics

        // cpu really is 0% here; gpu is simply absent. The difference is what
        // lets a widget choose between "0%" and "--".
        assertEquals(0, message.cpuPercent)
        assertNull(message.gpuPercent)
        assertNull(message.cpuTemperatureCelsius)
        assertNull(message.gpuTemperatureCelsius)
    }

    @Test
    fun parsesOptionalGpuAndTemperatureFieldsWhenAnAgentSuppliesThem() {
        val json = "{\"type\":\"metrics\",\"cpu\":10,\"memory\":20,\"gpu\":55,\"cpu_temp\":61,\"gpu_temp\":58}"

        val message = LiveStateMessage.fromJson(json) as LiveStateMessage.Metrics

        assertEquals(55, message.gpuPercent)
        assertEquals(61, message.cpuTemperatureCelsius)
        assertEquals(58, message.gpuTemperatureCelsius)
    }

    @Test
    fun percentagesAreClampedAndDecimalsRounded() {
        val message = LiveStateMessage.fromJson("{\"type\":\"metrics\",\"cpu\":34.6,\"memory\":150}") as LiveStateMessage.Metrics

        assertEquals(35, message.cpuPercent)
        assertEquals(100, message.memoryPercent)
    }

    @Test
    fun negativePercentagesAreClampedRatherThanRendered() {
        val message = LiveStateMessage.fromJson("{\"type\":\"metrics\",\"cpu\":-5}") as LiveStateMessage.Metrics

        assertEquals(0, message.cpuPercent)
    }

    // -- playback --

    @Test
    fun parsesThePlaybackMessageTheDesktopSends() {
        val json = "{\"type\":\"media\",\"available\":true,\"source\":\"spotify\",\"playing\":true,\"position\":83,\"duration\":214}"

        val message = LiveStateMessage.fromJson(json)

        assertEquals(
            LiveStateMessage.Playback(
                isAvailable = true, source = "spotify", isPlaying = true,
                positionSeconds = 83, durationSeconds = 214
            ),
            message
        )
    }

    @Test
    fun parsesTheUnavailablePlaybackMessage() {
        val message = LiveStateMessage.fromJson("{\"type\":\"media\",\"available\":false}") as LiveStateMessage.Playback

        assertFalse(message.isAvailable)
        assertNull(message.source)
        assertFalse(message.isPlaying)
    }

    @Test
    fun parsesPausedPlayback() {
        val json = "{\"type\":\"media\",\"available\":true,\"source\":\"apple_music\",\"playing\":false}"

        val message = LiveStateMessage.fromJson(json) as LiveStateMessage.Playback

        assertTrue(message.isAvailable)
        assertFalse(message.isPlaying)
    }

    @Test
    fun aPlaybackMessageWithoutAnAvailabilityFlagIsRejected() {
        // Defaulting this either way would invent a playback state.
        assertNull(LiveStateMessage.fromJson("{\"type\":\"media\",\"playing\":true}"))
    }

    @Test
    fun negativeTimesAreRejectedRatherThanShown() {
        val json = "{\"type\":\"media\",\"available\":true,\"position\":-3,\"duration\":-1}"

        val message = LiveStateMessage.fromJson(json) as LiveStateMessage.Playback

        assertNull(message.positionSeconds)
        assertNull(message.durationSeconds)
    }

    // -- track --

    @Test
    fun parsesTheTrackMessageTheDesktopSends() {
        val json = "{\"type\":\"track\",\"title\":\"Song\",\"artist\":\"Artist\",\"album\":\"Album\",\"artworkId\":\"0123456789abcdef\"}"

        assertEquals(LiveStateMessage.Track("Song", "Artist", "Album", "0123456789abcdef"), LiveStateMessage.fromJson(json))
    }

    @Test
    fun parsesTrackMetadataWithEscapedQuotesAndBackslashes() {
        val json = "{\"type\":\"track\",\"title\":\"He said \\\"hi\\\"\",\"artist\":\"AC\\\\DC\"}"

        val message = LiveStateMessage.fromJson(json) as LiveStateMessage.Track

        assertEquals("He said \"hi\"", message.title)
        assertEquals("AC\\DC", message.artist)
    }

    @Test
    fun aFieldNameHiddenInsideATitleIsNotMistakenForARealField() {
        // The escaped quotes mean this is text, not structure.
        val json = "{\"type\":\"track\",\"title\":\"\\\"artist\\\":\\\"Fake\\\"\",\"artist\":\"Real\"}"

        val message = LiveStateMessage.fromJson(json) as LiveStateMessage.Track

        assertEquals("Real", message.artist)
    }

    @Test
    fun parsesMultibyteMetadataFromBytesIntact() {
        val title = "東京の夜 🎧"
        val json = "{\"type\":\"track\",\"title\":\"$title\",\"artist\":\"Ärtist\"}"

        val message = LiveStateMessage.fromBytes(json.toByteArray(StandardCharsets.UTF_8)) as LiveStateMessage.Track

        assertEquals(title, message.title)
        assertEquals("Ärtist", message.artist)
    }

    @Test
    fun parsesALongTrackMessageOfTheMaximumSizeTheDesktopCanSend() {
        // The desktop bounds each field, but the parser must not care how long the
        // payload is — only that it is complete.
        val title = "A".repeat(48)
        val artist = "B".repeat(36)
        val album = "C".repeat(26)
        val json = "{\"type\":\"track\",\"title\":\"$title\",\"artist\":\"$artist\",\"album\":\"$album\"}"

        val message = LiveStateMessage.fromJson(json) as LiveStateMessage.Track

        assertEquals(title, message.title)
        assertEquals(album, message.album)
    }

    // -- malformed / foreign messages --

    @Test
    fun malformedPayloadsFailSafely() {
        assertNull(LiveStateMessage.fromJson(""))
        assertNull(LiveStateMessage.fromJson("   "))
        assertNull(LiveStateMessage.fromJson("not json"))
        assertNull(LiveStateMessage.fromJson("[]"))
        assertNull(LiveStateMessage.fromJson("{}"))
        assertNull(LiveStateMessage.fromJson("{\"cpu\":50}"))
    }

    @Test
    fun aTruncatedNotificationIsRejectedRatherThanHalfParsed() {
        // A payload cut short by an MTU problem must never look like a valid
        // partial reading — the same rule the command envelope already has.
        assertNull(LiveStateMessage.fromJson("{\"type\":\"metrics\",\"cpu\":3"))
        assertNull(LiveStateMessage.fromJson("{\"type\":\"track\",\"title\":\"Son"))
    }

    @Test
    fun anUnknownMessageTypeIsIgnored() {
        assertNull(LiveStateMessage.fromJson("{\"type\":\"weather\",\"temp\":12}"))
    }

    @Test
    fun existingCommandAcknowledgementsAreNotLiveStateMessages() {
        // The state characteristic carries both; live parsing must leave the
        // TASK-003..008 protocol completely alone.
        assertNull(LiveStateMessage.fromJson("{\"type\":\"state\",\"action\":\"volume_up\",\"status\":\"ok\"}"))
        assertNull(LiveStateMessage.fromJson("{\"type\":\"state\",\"status\":\"connected\"}"))
        assertNull(LiveStateMessage.fromJson("{\"type\":\"command\",\"action\":\"volume_up\"}"))
    }

    @Test
    fun invalidUtf8BytesDoNotCrashTheParser() {
        val garbage = byteArrayOf(0x7B, 0x22, 0xC3.toByte(), 0x28, 0x22, 0x7D)

        assertNull(LiveStateMessage.fromBytes(garbage))
    }
}
