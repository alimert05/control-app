package com.alicontrol.app.dashboard

import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.DangerLevel
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.live.DesktopInfo
import com.alicontrol.app.ble.AliControlCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The customization engine: move, resize, add, remove, and page CRUD.
 *
 * Nothing here involves a transport, a Context or a View — which is also the
 * point of the test named [editingWorksWithoutAnyConnection]: layout editing
 * is entirely local and cannot be blocked by BLE being down.
 */
class DashboardEditorTest {

    private fun action(id: String, x: Int, y: Int, w: Int = 1, h: Int = 1, actionId: String = AliControlCommands.LOCK_SCREEN) =
        DashboardWidget(id, WidgetType.ACTION, x, y, w, h, WidgetConfig.Action(actionId))

    private fun page(vararg widgets: DashboardWidget) =
        DashboardPage("page_test", "Test", 0, widgets.toList())

    // -- move --

    @Test
    fun movingToAFreeCellSucceeds() {
        val result = DashboardEditor.moveWidget(page(action("a", 0, 0)), "a", 4, 3)

        val moved = (result as EditResult.Success).value.widget("a")!!
        assertEquals(WidgetPlacement(4, 3, 1, 1), moved.placement)
    }

    @Test
    fun movingOntoAnotherWidgetIsRejectedAndChangesNothing() {
        val original = page(action("a", 0, 0), action("b", 1, 0))

        val result = DashboardEditor.moveWidget(original, "a", 1, 0)

        assertEquals(EditRejection.OVERLAP, (result as EditResult.Rejected).reason)
        assertEquals(WidgetPlacement(0, 0, 1, 1), original.widget("a")!!.placement)
    }

    @Test
    fun movingOffTheGridIsRejected() {
        val result = DashboardEditor.moveWidget(page(action("a", 0, 0, 2, 2)), "a", 5, 0)

        assertEquals(EditRejection.OUT_OF_BOUNDS, (result as EditResult.Rejected).reason)
    }

    @Test
    fun aWidgetDoesNotCollideWithItsOwnCurrentCells() {
        val result = DashboardEditor.moveWidget(page(action("a", 0, 0, 2, 2)), "a", 1, 1)

        assertTrue(result.isSuccess)
    }

    @Test
    fun movingAnUnknownWidgetIsRejected() {
        val result = DashboardEditor.moveWidget(page(action("a", 0, 0)), "ghost", 1, 1)

        assertEquals(EditRejection.UNKNOWN_WIDGET, (result as EditResult.Rejected).reason)
    }

    // -- resize --

    @Test
    fun resizingWithinConstraintsAndFreeSpaceSucceeds() {
        val result = DashboardEditor.resizeWidget(page(action("a", 0, 0)), "a", 2, 2)

        assertEquals(WidgetPlacement(0, 0, 2, 2), (result as EditResult.Success).value.widget("a")!!.placement)
    }

    @Test
    fun resizingBeyondTheWidgetTypeMaximumIsRejected() {
        val result = DashboardEditor.resizeWidget(page(action("a", 0, 0)), "a", 3, 1)

        assertEquals(EditRejection.SIZE_CONSTRAINT, (result as EditResult.Rejected).reason)
    }

    @Test
    fun resizingBelowTheWidgetTypeMinimumIsRejected() {
        val media = DashboardPage(
            "page_test", "Test", 0,
            listOf(
                DashboardWidget(
                    "m", WidgetType.MEDIA, 0, 0, 3, 2,
                    WidgetConfig.Media(
                        AliControlCommands.PREVIOUS_TRACK,
                        AliControlCommands.PLAY_PAUSE,
                        AliControlCommands.NEXT_TRACK
                    )
                )
            )
        )

        val result = DashboardEditor.resizeWidget(media, "m", 2, 2)

        assertEquals(EditRejection.SIZE_CONSTRAINT, (result as EditResult.Rejected).reason)
    }

    @Test
    fun resizingIntoAnOccupiedCellIsRejected() {
        val result = DashboardEditor.resizeWidget(page(action("a", 0, 0), action("b", 1, 0)), "a", 2, 1)

        assertEquals(EditRejection.OVERLAP, (result as EditResult.Rejected).reason)
    }

    @Test
    fun resizingPastTheGridEdgeIsRejected() {
        val result = DashboardEditor.resizeWidget(page(action("a", 5, 0)), "a", 2, 1)

        assertEquals(EditRejection.OUT_OF_BOUNDS, (result as EditResult.Rejected).reason)
    }

    // -- add / remove --

    @Test
    fun addedWidgetsTakeTheFirstFreePositionScanningFromTheTopLeft() {
        val start = page(action("a", 0, 0))

        val afterFirst = (DashboardEditor.addWidget(start, WidgetType.METRIC, WidgetConfig.Metric("CPU")) as EditResult.Success).value
        val afterSecond = (DashboardEditor.addWidget(afterFirst, WidgetType.METRIC, WidgetConfig.Metric("RAM")) as EditResult.Success).value

        assertEquals(WidgetPlacement(1, 0, 1, 1), afterFirst.widgets.last().placement)
        assertEquals(WidgetPlacement(2, 0, 1, 1), afterSecond.widgets.last().placement)
    }

    @Test
    fun addedWidgetsGetUniqueIdsWithinThePage() {
        var current = page()
        repeat(4) {
            current = (DashboardEditor.addWidget(current, WidgetType.METRIC, WidgetConfig.Metric("CPU")) as EditResult.Success).value
        }

        val ids = current.widgets.map { it.id }
        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun addingIsRejectedWhenNoFreeRectangleIsBigEnough() {
        // Fill all 24 cells with 1x1 widgets.
        val full = DashboardPage(
            "page_test", "Test", 0,
            (0 until DashboardGrid.ROWS).flatMap { y ->
                (0 until DashboardGrid.COLUMNS).map { x -> action("w_${x}_$y", x, y) }
            }
        )

        val result = DashboardEditor.addWidget(full, WidgetType.METRIC, WidgetConfig.Metric("CPU"))

        assertEquals(EditRejection.NO_SPACE, (result as EditResult.Rejected).reason)
        assertFalse(DashboardEditor.hasSpaceFor(full, WidgetType.METRIC))
    }

    @Test
    fun addingNeverDisplacesAnExistingWidget() {
        val start = page(action("a", 0, 0, 2, 2))

        val after = (DashboardEditor.addWidget(start, WidgetType.CLOCK, WidgetConfig.Clock()) as EditResult.Success).value

        assertEquals(WidgetPlacement(0, 0, 2, 2), after.widget("a")!!.placement)
        assertTrue(after.isValid())
    }

    @Test
    fun removingAWidgetLeavesOnlyThatWidgetsCellsFree() {
        val start = page(action("a", 0, 0), action("b", 1, 0))

        val after = (DashboardEditor.removeWidget(start, "a") as EditResult.Success).value

        assertNull(after.widget("a"))
        assertNotNull(after.widget("b"))
    }

    @Test
    fun removingAnUnknownWidgetIsRejected() {
        val result = DashboardEditor.removeWidget(page(action("a", 0, 0)), "ghost")

        assertEquals(EditRejection.UNKNOWN_WIDGET, (result as EditResult.Rejected).reason)
    }

    // -- ActionLibrary integration --

    @Test
    fun addingAnActionWidgetRequiresAKnownActionId() {
        val start = page()

        val rejected = DashboardEditor.addActionWidget(start, "rm_-rf_slash")
        val accepted = DashboardEditor.addActionWidget(start, AliControlCommands.OPEN_TERMINAL)

        assertEquals(EditRejection.UNKNOWN_ACTION, (rejected as EditResult.Rejected).reason)
        assertTrue(accepted.isSuccess)
    }

    @Test
    fun addingAGroupWithAnUnknownActionIsRejected() {
        val result = DashboardEditor.addWidget(
            page(),
            WidgetType.ACTION_GROUP,
            WidgetConfig.ActionGroup(listOf(AliControlCommands.VOLUME_UP, "not_a_real_action"))
        )

        assertEquals(EditRejection.UNKNOWN_ACTION, (result as EditResult.Rejected).reason)
    }

    @Test
    fun aConfirmActionKeepsItsDangerMetadataWhenPlacedOnADashboard() {
        val after = (DashboardEditor.addActionWidget(page(), AliControlCommands.SLEEP) as EditResult.Success).value

        val placed = after.widgets.single()
        assertEquals(DangerLevel.CONFIRM, ActionLibrary.find(placed.referencedActionIds().single())?.dangerLevel)
    }

    @Test
    fun aWidgetTheConnectedDesktopCannotServeStaysInTheLayoutAndOnlyRendersDisabled() {
        // A desktop that reports a platform without this action (or without the `actions`
        // capability) must never cause the widget to be dropped: the layout is
        // platform-neutral and availability only changes how a widget is drawn.
        val after = (DashboardEditor.addActionWidget(page(), AliControlCommands.WINDOWS_SEARCH) as EditResult.Success).value
        val widget = after.widgets.single()

        assertTrue(widget.isSupportedOn(Platform.WINDOWS))
        val noActions = DesktopInfo(hasIdentity = true, platform = Platform.WINDOWS, capabilities = emptySet())
        assertEquals(WidgetAvailability.MISSING_CAPABILITY, WidgetSupport.availability(widget, noActions))
        assertEquals(AliControlCommands.WINDOWS_SEARCH, widget.referencedActionIds().single())
        assertEquals(1, after.widgets.size)
    }

    @Test
    fun editingWorksWithoutAnyConnection() {
        // No transport, no Context, no connection state is involved in any of
        // these calls — customization cannot depend on the desktop being up.
        var current = page()
        current = (DashboardEditor.addActionWidget(current, AliControlCommands.LOCK_SCREEN) as EditResult.Success).value
        val id = current.widgets.single().id
        current = (DashboardEditor.moveWidget(current, id, 2, 1) as EditResult.Success).value
        current = (DashboardEditor.resizeWidget(current, id, 2, 2) as EditResult.Success).value
        current = (DashboardEditor.removeWidget(current, id) as EditResult.Success).value

        assertTrue(current.widgets.isEmpty())
    }

    // -- pages --

    @Test
    fun creatingPagesSupportsAnArbitraryCount() {
        var dashboard = DefaultDashboardFactory.create()
        repeat(5) { index ->
            dashboard = (DashboardEditor.createPage(dashboard, "Extra $index") as EditResult.Success).value
        }

        assertEquals(8, dashboard.pages.size)
        assertEquals(List(8) { it }, dashboard.orderedPages.map { it.order })
        assertEquals(dashboard.pages.size, dashboard.pages.map { it.id }.toSet().size)
    }

    @Test
    fun createdPagesStartEmptyAndAtTheEndOfTheOrder() {
        val dashboard = (DashboardEditor.createPage(DefaultDashboardFactory.create(), "Gaming") as EditResult.Success).value

        val created = dashboard.orderedPages.last()
        assertEquals("Gaming", created.name)
        assertTrue(created.widgets.isEmpty())
        assertEquals(DashboardGrid.CELLS, created.freeCellCount())
    }

    @Test
    fun creatingOrRenamingWithABlankNameIsRejected() {
        val dashboard = DefaultDashboardFactory.create()

        assertEquals(
            EditRejection.INVALID_NAME,
            (DashboardEditor.createPage(dashboard, "   ") as EditResult.Rejected).reason
        )
        assertEquals(
            EditRejection.INVALID_NAME,
            (DashboardEditor.renamePage(dashboard, "page_dev", "") as EditResult.Rejected).reason
        )
    }

    @Test
    fun renamingKeepsThePageIdAndItsWidgets() {
        val dashboard = DefaultDashboardFactory.create()
        val before = dashboard.page("page_dev")!!

        val after = (DashboardEditor.renamePage(dashboard, "page_dev", "  Workspace  ") as EditResult.Success).value
            .page("page_dev")!!

        assertEquals("page_dev", after.id)
        assertEquals("Workspace", after.name)
        assertEquals(before.widgets, after.widgets)
    }

    @Test
    fun longPageNamesAreTrimmedToTheAllowedLength() {
        val dashboard = (
            DashboardEditor.renamePage(DefaultDashboardFactory.create(), "page_dev", "x".repeat(80))
                as EditResult.Success
            ).value

        assertEquals(DashboardEditor.MAX_PAGE_NAME_LENGTH, dashboard.page("page_dev")!!.name.length)
    }

    @Test
    fun deletingAPageRenumbersTheRemainingOrder() {
        val dashboard = (DashboardEditor.deletePage(DefaultDashboardFactory.create(), "page_media") as EditResult.Success).value

        assertEquals(listOf("page_dashboard", "page_dev"), dashboard.orderedPages.map { it.id })
        assertEquals(listOf(0, 1), dashboard.orderedPages.map { it.order })
    }

    @Test
    fun theLastRemainingPageCannotBeDeleted() {
        val single = Dashboard(listOf(DashboardPage("only", "Only", 0, emptyList())))

        val result = DashboardEditor.deletePage(single, "only")

        assertEquals(EditRejection.LAST_PAGE, (result as EditResult.Rejected).reason)
    }

    @Test
    fun deletingAnUnknownPageIsRejected() {
        val result = DashboardEditor.deletePage(DefaultDashboardFactory.create(), "page_ghost")

        assertEquals(EditRejection.UNKNOWN_PAGE, (result as EditResult.Rejected).reason)
    }

    @Test
    fun pagesCanBeReorderedInBothDirections() {
        val dashboard = DefaultDashboardFactory.create()

        val movedRight = (DashboardEditor.movePage(dashboard, "page_dashboard", 1) as EditResult.Success).value
        assertEquals(listOf("page_media", "page_dashboard", "page_dev"), movedRight.orderedPages.map { it.id })
        assertEquals(listOf(0, 1, 2), movedRight.orderedPages.map { it.order })

        val movedBack = (DashboardEditor.movePage(movedRight, "page_dashboard", -1) as EditResult.Success).value
        assertEquals(listOf("page_dashboard", "page_media", "page_dev"), movedBack.orderedPages.map { it.id })
    }

    @Test
    fun reorderingPastEitherEndClampsInsteadOfFailing() {
        val dashboard = DefaultDashboardFactory.create()

        val atStart = (DashboardEditor.movePage(dashboard, "page_dashboard", -1) as EditResult.Success).value
        val atEnd = (DashboardEditor.movePage(dashboard, "page_dev", 3) as EditResult.Success).value

        assertEquals(dashboard.orderedPages.map { it.id }, atStart.orderedPages.map { it.id })
        assertEquals(dashboard.orderedPages.map { it.id }, atEnd.orderedPages.map { it.id })
    }
}
