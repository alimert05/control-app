package com.alicontrol.app.ui.dashboard

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.alicontrol.app.dashboard.DashboardGrid
import com.alicontrol.app.dashboard.DashboardWidget
import com.alicontrol.app.dashboard.WidgetPlacement
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch handling for moving and resizing widgets in edit mode, written
 * directly against [MotionEvent] rather than a drag-and-drop framework: the
 * whole interaction is "follow the finger, snap to a cell, ask if that cell
 * is legal", which needs no library and no allocation per touch event.
 *
 * Both gestures share the same shape:
 *
 * 1. the widget follows the finger via `translationX/Y` (a pure draw-time
 *    offset — no layout pass per move event);
 * 2. the candidate cells are recomputed each move, but the grid is only
 *    invalidated when the *cell* changes, not per pixel;
 * 3. on release the candidate is committed through
 *    [DashboardEditListener], which validates it against
 *    [com.alicontrol.app.dashboard.DashboardEditor]. An illegal drop simply
 *    snaps back — the widget keeps the valid position it already had.
 *
 * A press that never travels past the touch slop is treated as a tap
 * (selection), so selecting and dragging use one gesture without a long-press
 * delay to wait through.
 */
object WidgetEditGestures {

    @SuppressLint("ClickableViewAccessibility")
    fun attachDrag(card: View, widget: DashboardWidget, listener: DashboardEditListener) {
        val slop = ViewConfiguration.get(card.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        var candidate = widget.placement

        card.setOnTouchListener { view, event ->
            val grid = view.parent as? DashboardGridLayout ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    candidate = widget.placement
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        // Deliberately does NOT notify selection here: selection
                        // re-renders the page, which would destroy the very view
                        // this gesture is tracking. Selection is a tap-only
                        // outcome (see ACTION_UP).
                        dragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.bringToFront()
                        view.alpha = DRAG_ALPHA
                    }
                    if (dragging) {
                        view.translationX = dx
                        view.translationY = dy
                        val next = candidateMove(grid, view, widget, dx, dy)
                        if (next != candidate) {
                            candidate = next
                            grid.setPreview(next, listener.canMoveWidget(widget.id, next.x, next.y))
                        }
                    }
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    view.alpha = 1f
                    view.translationX = 0f
                    view.translationY = 0f
                    grid.setPreview(null, true)
                    if (wasDragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP && candidate != widget.placement) {
                            listener.onMoveWidget(widget.id, candidate.x, candidate.y)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                        listener.onWidgetTapped(widget.id)
                    }
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachResize(handle: View, card: View, widget: DashboardWidget, listener: DashboardEditListener) {
        var downX = 0f
        var downY = 0f
        var candidate = widget.placement

        handle.setOnTouchListener { view, event ->
            val grid = card.parent as? DashboardGridLayout ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    candidate = widget.placement
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val next = candidateResize(grid, card, widget, event.rawX - downX, event.rawY - downY)
                    if (next != candidate) {
                        candidate = next
                        grid.setPreview(next, listener.canResizeWidget(widget.id, next.width, next.height))
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    grid.setPreview(null, true)
                    if (event.actionMasked == MotionEvent.ACTION_UP && candidate != widget.placement) {
                        listener.onResizeWidget(widget.id, candidate.width, candidate.height)
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * The cell the widget's top-left corner has been dragged over, clamped so
     * the widget always stays fully on the grid. Clamping (rather than
     * rejecting) keeps a drag toward the edge feeling like it sticks to the
     * border instead of snapping back to where it started.
     */
    private fun candidateMove(
        grid: DashboardGridLayout,
        card: View,
        widget: DashboardWidget,
        dx: Float,
        dy: Float
    ): WidgetPlacement {
        val cellWidth = grid.cellWidth
        val cellHeight = grid.cellHeight
        if (cellWidth <= 0 || cellHeight <= 0) return widget.placement
        val x = ((card.left + dx) / cellWidth).roundToInt()
            .coerceIn(0, DashboardGrid.COLUMNS - widget.gridWidth)
        val y = ((card.top + dy) / cellHeight).roundToInt()
            .coerceIn(0, DashboardGrid.ROWS - widget.gridHeight)
        return widget.placement.movedTo(x, y)
    }

    /**
     * The size the resize handle has been dragged to, clamped to the widget
     * type's own min/max and to the space left on the grid — so the handle
     * physically cannot express a size the model would reject for those
     * reasons. Overlap is still checked on commit (and previewed live).
     */
    private fun candidateResize(
        grid: DashboardGridLayout,
        card: View,
        widget: DashboardWidget,
        dx: Float,
        dy: Float
    ): WidgetPlacement {
        val cellWidth = grid.cellWidth
        val cellHeight = grid.cellHeight
        if (cellWidth <= 0 || cellHeight <= 0) return widget.placement
        val width = ((card.width + dx) / cellWidth).roundToInt()
            .coerceIn(widget.type.minWidth, minOf(widget.type.maxWidth, DashboardGrid.COLUMNS - widget.gridX))
        val height = ((card.height + dy) / cellHeight).roundToInt()
            .coerceIn(widget.type.minHeight, minOf(widget.type.maxHeight, DashboardGrid.ROWS - widget.gridY))
        return widget.placement.resizedTo(width, height)
    }

    private const val DRAG_ALPHA = 0.85f
}
