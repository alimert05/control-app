package com.alicontrol.app.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 6x4 grid rules themselves: what fits, what doesn't, and what counts as an overlap. */
class WidgetPlacementTest {

    @Test
    fun gridIsSixColumnsByFourRows() {
        assertEquals(6, DashboardGrid.COLUMNS)
        assertEquals(4, DashboardGrid.ROWS)
        assertEquals(24, DashboardGrid.CELLS)
    }

    @Test
    fun singleCellPlacementIsValid() {
        assertTrue(WidgetPlacement(0, 0, 1, 1).isWithinBounds())
        assertTrue(WidgetPlacement(5, 3, 1, 1).isWithinBounds())
    }

    @Test
    fun fullGridPlacementIsValid() {
        assertTrue(WidgetPlacement(0, 0, 6, 4).isWithinBounds())
    }

    @Test
    fun negativeCoordinatesAreRejected() {
        assertFalse(WidgetPlacement(-1, 0, 1, 1).isWithinBounds())
        assertFalse(WidgetPlacement(0, -1, 1, 1).isWithinBounds())
    }

    @Test
    fun nonPositiveSizesAreRejected() {
        assertFalse(WidgetPlacement(0, 0, 0, 1).isWithinBounds())
        assertFalse(WidgetPlacement(0, 0, 1, 0).isWithinBounds())
        assertFalse(WidgetPlacement(0, 0, -2, 2).isWithinBounds())
    }

    @Test
    fun sizesLargerThanTheGridAreRejected() {
        assertFalse(WidgetPlacement(0, 0, 7, 1).isWithinBounds())
        assertFalse(WidgetPlacement(0, 0, 1, 5).isWithinBounds())
    }

    @Test
    fun placementsRunningOffTheEdgeAreRejected() {
        assertFalse(WidgetPlacement(5, 0, 2, 1).isWithinBounds())
        assertFalse(WidgetPlacement(0, 3, 1, 2).isWithinBounds())
    }

    @Test
    fun overlappingRectanglesAreDetected() {
        val base = WidgetPlacement(1, 1, 2, 2)
        assertTrue(base.overlaps(WidgetPlacement(2, 2, 2, 2)))
        assertTrue(base.overlaps(WidgetPlacement(0, 0, 3, 3)))
        assertTrue(base.overlaps(base))
    }

    @Test
    fun touchingEdgesIsNotAnOverlap() {
        val base = WidgetPlacement(0, 0, 2, 2)
        assertFalse(base.overlaps(WidgetPlacement(2, 0, 2, 2)))
        assertFalse(base.overlaps(WidgetPlacement(0, 2, 2, 2)))
        assertFalse(base.overlaps(WidgetPlacement(2, 2, 2, 2)))
    }

    @Test
    fun moveAndResizeProduceIndependentCopies() {
        val original = WidgetPlacement(0, 0, 2, 2)
        assertEquals(WidgetPlacement(3, 1, 2, 2), original.movedTo(3, 1))
        assertEquals(WidgetPlacement(0, 0, 4, 1), original.resizedTo(4, 1))
        assertEquals(WidgetPlacement(0, 0, 2, 2), original)
    }

    @Test
    fun everyWidgetTypeDeclaresAUsableSizeRangeThatFitsTheGrid() {
        for (type in WidgetType.values()) {
            assertTrue("${type.name} min > max width", type.minWidth <= type.maxWidth)
            assertTrue("${type.name} min > max height", type.minHeight <= type.maxHeight)
            assertTrue("${type.name} max width exceeds grid", type.maxWidth <= DashboardGrid.COLUMNS)
            assertTrue("${type.name} max height exceeds grid", type.maxHeight <= DashboardGrid.ROWS)
            assertTrue("${type.name} default size is not allowed", type.allowsSize(type.defaultWidth, type.defaultHeight))
        }
    }

    @Test
    fun sizeConstraintsMatchTheSpecifiedMinimumsAndMaximums() {
        assertTrue(WidgetType.ACTION.allowsSize(1, 1))
        assertTrue(WidgetType.ACTION.allowsSize(2, 2))
        assertFalse(WidgetType.ACTION.allowsSize(3, 1))
        assertFalse(WidgetType.ACTION.allowsSize(1, 3))

        assertTrue(WidgetType.DAILY_OVERVIEW.allowsSize(3, 2))
        assertTrue(WidgetType.DAILY_OVERVIEW.allowsSize(6, 4))
        assertFalse(WidgetType.DAILY_OVERVIEW.allowsSize(2, 2))
        assertFalse(WidgetType.DAILY_OVERVIEW.allowsSize(3, 1))

        assertTrue(WidgetType.MEDIA.allowsSize(3, 2))
        assertTrue(WidgetType.MEDIA.allowsSize(6, 4))
        assertFalse(WidgetType.MEDIA.allowsSize(2, 2))

        assertTrue(WidgetType.ACTION_GROUP.allowsSize(2, 1))
        assertFalse(WidgetType.ACTION_GROUP.allowsSize(1, 1))
        assertTrue(WidgetType.CLOCK.allowsSize(4, 2))
        assertFalse(WidgetType.CLOCK.allowsSize(5, 1))
    }
}
