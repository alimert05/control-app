package com.alicontrol.app.ui.dashboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.alicontrol.app.R

/**
 * A minimal "● ○ ○" page indicator — a row of small tinted circle Views,
 * one per page, with the active one swapped to the accent color. No custom
 * Canvas drawing, no animation; a page change just flips two backgrounds.
 */
class DotsIndicator(private val container: LinearLayout) {

    private val dots = mutableListOf<View>()

    fun bind(pageCount: Int, activeIndex: Int) {
        if (dots.size != pageCount) {
            container.removeAllViews()
            dots.clear()
            val context = container.context
            val size = dp(context, 8)
            val margin = dp(context, 5)
            for (i in 0 until pageCount) {
                val dot = View(context).apply {
                    background = dotDrawable(context, i == activeIndex)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = margin
                        marginEnd = margin
                    }
                }
                dots.add(dot)
                container.addView(dot)
            }
        } else {
            dots.forEachIndexed { index, dot -> dot.background = dotDrawable(container.context, index == activeIndex) }
        }
    }

    private fun dotDrawable(context: Context, active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ContextCompat.getColor(context, if (active) R.color.accent else R.color.divider))
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
