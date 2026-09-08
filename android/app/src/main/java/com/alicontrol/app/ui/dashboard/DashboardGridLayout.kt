package com.alicontrol.app.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.alicontrol.app.R
import com.alicontrol.app.dashboard.DashboardGrid
import com.alicontrol.app.dashboard.WidgetPlacement

/**
 * A lightweight, single-purpose [ViewGroup] that positions children on the
 * logical [DashboardGrid] by dividing its own measured size evenly into cells
 * and placing each child at its [LayoutParams] grid rect — exactly the
 * coordinate space [com.alicontrol.app.dashboard.DashboardWidget] uses, so a
 * widget's model position *is* its on-screen position with no translation
 * layer to keep in sync.
 *
 * Deliberately not `android.widget.GridLayout`: that view distributes space
 * per-column/row from child size requests and weights, which does not
 * guarantee the exact uniform 6x4 grid this app's model assumes. A plain
 * `onMeasure`/`onLayout` override is simpler, cheaper (one pass, no nested
 * weight resolution), and exactly matches the model.
 *
 * In edit mode it also draws the subtle cell guides and the drag/resize
 * preview rectangle. Both are static shapes redrawn only when the candidate
 * placement actually changes cells — never per touch-move pixel, and never on
 * a timer.
 */
class DashboardGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var columns: Int = DashboardGrid.COLUMNS
    var rows: Int = DashboardGrid.ROWS

    /** Draws the 6x4 cell guides. Set only while the dashboard is being edited. */
    var showGridGuides: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The cells a drag/resize would land on, or null when nothing is being dragged. */
    private var previewPlacement: WidgetPlacement? = null
    private var previewValid: Boolean = true

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.divider)
    }
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val previewRect = RectF()
    private val guideCornerRadius = 10f * resources.displayMetrics.density

    init {
        // ViewGroups skip onDraw by default; the guides/preview are drawn
        // beneath the children, which is exactly where they belong.
        setWillNotDraw(false)
    }

    val cellWidth: Int get() = if (columns > 0) width / columns else 0
    val cellHeight: Int get() = if (rows > 0) height / rows else 0

    /**
     * Shows (or clears, with null) the candidate placement preview.
     * [valid] tints it as an accepted or rejected drop. No-ops when nothing
     * changed, so a touch-move that stays inside the same cell costs nothing.
     */
    fun setPreview(placement: WidgetPlacement?, valid: Boolean) {
        if (previewPlacement == placement && previewValid == valid) return
        previewPlacement = placement
        previewValid = valid
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val cellWidth = if (columns > 0) width / columns else 0
        val cellHeight = if (rows > 0) height / rows else 0

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
        val cellWidth = if (columns > 0) (r - l) / columns else 0
        val cellHeight = if (rows > 0) (b - t) / rows else 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val left = cellWidth * lp.gridX + lp.leftMargin
            val top = cellHeight * lp.gridY + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGridGuides) return

        val cw = cellWidth
        val ch = cellHeight
        if (cw <= 0 || ch <= 0) return

        for (column in 0..columns) {
            val x = (column * cw).toFloat()
            canvas.drawLine(x, 0f, x, (rows * ch).toFloat(), guidePaint)
        }
        for (row in 0..rows) {
            val y = (row * ch).toFloat()
            canvas.drawLine(0f, y, (columns * cw).toFloat(), y, guidePaint)
        }

        val preview = previewPlacement ?: return
        val color = ContextCompat.getColor(context, if (previewValid) R.color.accent else R.color.danger)
        previewFillPaint.color = (color and 0x00FFFFFF) or PREVIEW_FILL_ALPHA
        previewStrokePaint.color = color
        previewRect.set(
            (preview.x * cw).toFloat(),
            (preview.y * ch).toFloat(),
            (preview.right * cw).toFloat(),
            (preview.bottom * ch).toFloat()
        )
        canvas.drawRoundRect(previewRect, guideCornerRadius, guideCornerRadius, previewFillPaint)
        canvas.drawRoundRect(previewRect, guideCornerRadius, guideCornerRadius, previewStrokePaint)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(0, 0, 1, 1)

    class LayoutParams(val gridX: Int, val gridY: Int, val gridWidth: Int, val gridHeight: Int) :
        MarginLayoutParams(MATCH_PARENT, MATCH_PARENT)

    companion object {
        /** ~22% alpha: enough to read the target cells through the guides, not enough to hide them. */
        private const val PREVIEW_FILL_ALPHA = 0x38000000

        /** Positions/sizes [view] per the grid coordinates, with a uniform margin between cards. */
        fun View.setGridPosition(gridX: Int, gridY: Int, gridWidth: Int, gridHeight: Int, marginPx: Int) {
            layoutParams = LayoutParams(gridX, gridY, gridWidth, gridHeight).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
        }
    }
}
