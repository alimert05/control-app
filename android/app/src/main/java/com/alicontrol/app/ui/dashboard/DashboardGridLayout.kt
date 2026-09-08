package com.alicontrol.app.ui.dashboard

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.alicontrol.app.dashboard.DashboardGrid

/**
 * A lightweight, single-purpose [ViewGroup] that positions children on a
 * fixed logical grid ([columns] x [rows]) by dividing its own measured size
 * evenly into cells and placing each child at its [LayoutParams] grid
 * rect — exactly the same coordinate space [com.alicontrol.app.dashboard.DashboardWidget]
 * uses, so a widget's model position IS its on-screen position with no
 * translation layer to keep in sync.
 *
 * Deliberately not `android.widget.GridLayout`: that view distributes space
 * per-column/row based on child size requests and weights, which does not
 * guarantee the exact uniform NxM grid this app's model assumes. A plain
 * `onMeasure`/`onLayout` override is simpler, cheaper (one pass, no nested
 * weight resolution), and exactly matches the model — appropriate for the
 * MT6580 target.
 */
class DashboardGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var columns: Int = DashboardGrid.COLUMNS
    var rows: Int = DashboardGrid.ROWS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val cellWidth = width / columns
        val cellHeight = height / rows

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val childWidth = (cellWidth * lp.gridWidth - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
            val childHeight = (cellHeight * lp.gridHeight - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellWidth = (r - l) / columns
        val cellHeight = (b - t) / rows

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val left = cellWidth * lp.gridX + lp.leftMargin
            val top = cellHeight * lp.gridY + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    class LayoutParams(val gridX: Int, val gridY: Int, val gridWidth: Int, val gridHeight: Int) :
        MarginLayoutParams(MATCH_PARENT, MATCH_PARENT)

    companion object {
        /** Convenience: adds [view] positioned/sized per [gridX]/[gridY]/[gridWidth]/[gridHeight], with a uniform margin. */
        fun View.setGridPosition(gridX: Int, gridY: Int, gridWidth: Int, gridHeight: Int, marginPx: Int) {
            layoutParams = LayoutParams(gridX, gridY, gridWidth, gridHeight).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
        }
    }
}
