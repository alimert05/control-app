package com.alicontrol.app.live

import com.alicontrol.app.ui.dashboard.LiveWidgetBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exactly what the widgets display, including every unavailable case. */
class LiveValueFormatterTest {

    // -- metrics --

    @Test
    fun percentagesRenderAsWholeNumbers() {
        assertEquals("34%", LiveValueFormatter.metric(34, MetricUnit.PERCENT))
        assertEquals("0%", LiveValueFormatter.metric(0, MetricUnit.PERCENT))
        assertEquals("100%", LiveValueFormatter.metric(100, MetricUnit.PERCENT))
    }

    @Test
    fun anUnavailableMetricRendersAsTheUnavailableMarkerNotZero() {
        assertEquals("--", LiveValueFormatter.metric(null, MetricUnit.PERCENT))
        assertEquals("--", LiveValueFormatter.metric(null, MetricUnit.CELSIUS))
    }

    @Test
    fun outOfRangePercentagesAreClamped() {
        assertEquals("100%", LiveValueFormatter.metric(140, MetricUnit.PERCENT))
        assertEquals("0%", LiveValueFormatter.metric(-4, MetricUnit.PERCENT))
    }

    @Test
    fun temperaturesRenderWithADegreeSign() {
        assertEquals("61°", LiveValueFormatter.metric(61, MetricUnit.CELSIUS))
    }

    // -- media times --

    @Test
    fun trackTimesRenderAsMinutesAndSeconds() {
        assertEquals("0:00", LiveValueFormatter.time(0))
        assertEquals("0:07", LiveValueFormatter.time(7))
        assertEquals("1:23", LiveValueFormatter.time(83))
        assertEquals("3:34", LiveValueFormatter.time(214))
    }

    @Test
    fun longTracksGainAnHoursComponent() {
        assertEquals("1:00:00", LiveValueFormatter.time(3600))
        assertEquals("2:05:09", LiveValueFormatter.time(7509))
    }

    @Test
    fun unknownOrNonsensicalTimesRenderAsUnavailable() {
        // "0:00" would read as a real position at the start of a track.
        assertEquals("--:--", LiveValueFormatter.time(null))
        assertEquals("--:--", LiveValueFormatter.time(-1))
    }

    // -- progress --

    @Test
    fun progressIsThePositionOverTheDuration() {
        assertEquals(0.5f, LiveValueFormatter.progressFraction(50, 100)!!, 0.0001f)
        assertEquals(0f, LiveValueFormatter.progressFraction(0, 100)!!, 0.0001f)
        assertEquals(1f, LiveValueFormatter.progressFraction(100, 100)!!, 0.0001f)
    }

    @Test
    fun progressIsUnknownWithoutBothValues() {
        assertNull(LiveValueFormatter.progressFraction(null, 100))
        assertNull(LiveValueFormatter.progressFraction(50, null))
    }

    @Test
    fun aZeroLengthTrackCannotProduceNaN() {
        assertNull(LiveValueFormatter.progressFraction(10, 0))
    }

    @Test
    fun aPositionPastTheEndIsClampedRatherThanOverflowingTheBar() {
        assertEquals(1f, LiveValueFormatter.progressFraction(120, 100)!!, 0.0001f)
    }

    // -- subtitle --

    @Test
    fun theSubtitleCombinesArtistAndAlbum() {
        assertEquals("Artist — Album", LiveValueFormatter.artistLine("Artist", "Album"))
    }

    @Test
    fun theSubtitleFallsBackToWhicheverFieldExists() {
        assertEquals("Artist", LiveValueFormatter.artistLine("Artist", null))
        assertEquals("Album", LiveValueFormatter.artistLine(null, "Album"))
        assertEquals("Artist", LiveValueFormatter.artistLine("Artist", "  "))
        assertNull(LiveValueFormatter.artistLine(null, null))
        assertNull(LiveValueFormatter.artistLine("", ""))
    }

    @Test
    fun aSelfTitledAlbumIsNotRepeatedTwice() {
        assertEquals("Artist", LiveValueFormatter.artistLine("Artist", "artist"))
    }

    // -- metric identity --

    @Test
    fun widgetLabelsResolveToTheReadingTheyName() {
        assertEquals(MetricType.CPU, MetricType.forLabel("CPU"))
        assertEquals(MetricType.MEMORY, MetricType.forLabel("RAM"))
        assertEquals(MetricType.MEMORY, MetricType.forLabel("memory"))
        assertEquals(MetricType.GPU, MetricType.forLabel(" gpu "))
        assertEquals(MetricType.UNKNOWN, MetricType.forLabel("Fan speed"))
    }

    @Test
    fun eachReadingPullsItsOwnValueOutOfTheMetrics() {
        val metrics = SystemMetrics(cpuPercent = 1, memoryPercent = 2, gpuPercent = 3, cpuTemperatureCelsius = 4)

        assertEquals(1, MetricType.CPU.valueIn(metrics))
        assertEquals(2, MetricType.MEMORY.valueIn(metrics))
        assertEquals(3, MetricType.GPU.valueIn(metrics))
        assertEquals(4, MetricType.CPU_TEMPERATURE.valueIn(metrics))
        assertNull(MetricType.GPU_TEMPERATURE.valueIn(metrics))
        assertNull(MetricType.UNKNOWN.valueIn(metrics))
    }

    @Test
    fun anUnknownReadingIsAlwaysUnavailableRatherThanShowingSomeoneElsesNumber() {
        val metrics = SystemMetrics(cpuPercent = 90, memoryPercent = 90)

        assertEquals("--", LiveValueFormatter.metric(MetricType.UNKNOWN.valueIn(metrics), MetricUnit.PERCENT))
    }

    // -- widget binding --

    @Test
    fun everyRegisteredWidgetBindingReceivesTheSameStateInstance() {
        // This is the mechanism behind "all copies of a widget show the same
        // value": one state object, pushed to every registered view.
        val bindings = LiveWidgetBindings()
        val seen = mutableListOf<DesktopState>()
        repeat(3) { bindings.register { state -> seen.add(state) } }

        val state = DesktopState(metrics = SystemMetrics(cpuPercent = 12), metricsReceivedAtMs = 1)
        bindings.apply(state)

        assertEquals(3, seen.size)
        assertEquals(setOf(state), seen.toSet())
    }

    @Test
    fun clearedBindingsStopReceivingUpdates() {
        val bindings = LiveWidgetBindings()
        var updates = 0
        bindings.register { updates++ }

        bindings.clear()
        bindings.apply(DesktopState.EMPTY)

        assertEquals(0, updates)
    }
}
