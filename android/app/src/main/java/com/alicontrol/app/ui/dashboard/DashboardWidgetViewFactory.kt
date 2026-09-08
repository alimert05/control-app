package com.alicontrol.app.ui.dashboard

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.alicontrol.app.R
import com.alicontrol.app.actions.ActionDispatcher
import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.Platform
import com.alicontrol.app.dashboard.DashboardWidget
import com.alicontrol.app.dashboard.ShortcutDestination
import com.alicontrol.app.dashboard.WidgetConfig
import com.alicontrol.app.icons.ActionIconRegistry
import com.alicontrol.app.ui.dashboard.DashboardGridLayout.Companion.setGridPosition
import com.google.android.material.card.MaterialCardView
import java.util.Calendar
import java.util.Date

/**
 * Turns one [DashboardWidget] into a real, laid-out card [View]. This is the
 * only place that knows how each [com.alicontrol.app.dashboard.WidgetType]
 * looks — [DashboardPageRenderer] just asks it to build every widget on a
 * page and drops the results into a [DashboardGridLayout].
 *
 * Remote-control widgets (ACTION / ACTION_GROUP / MEDIA's transport row) are
 * dimmed and disabled while disconnected — see [applyRemoteEnabledState] —
 * but [ActionDispatcher] independently re-checks the live connection right
 * before sending regardless, so this is UX polish, not the actual guard.
 *
 * Platform awareness: today only the macOS agent exists and the protocol
 * carries no "connected platform" field, so [CURRENT_PLATFORM] is a constant
 * exactly like [com.alicontrol.app.ui.ActionsActivity] already assumes. An
 * action unsupported by [CURRENT_PLATFORM] renders **disabled**, not hidden —
 * consistent with the disconnected-state treatment above, and keeps the grid
 * geometry stable. A future Windows agent only needs this constant swapped
 * for a real connected-platform signal — no dashboard architecture change.
 */
class DashboardWidgetViewFactory(
    private val activity: AppCompatActivity,
    private val dispatcher: ActionDispatcher
) {

    private val density get() = activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    fun build(widget: DashboardWidget, isConnected: Boolean): View {
        val card = MaterialCardView(activity).apply {
            radius = dp(CARD_CORNER_RADIUS_DP).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.surface))
            strokeColor = ContextCompat.getColor(activity, R.color.divider)
            strokeWidth = dp(1)
            setGridPosition(widget.gridX, widget.gridY, widget.gridWidth, widget.gridHeight, dp(CARD_MARGIN_DP))
        }

        val content: View = when (val config = widget.config) {
            is WidgetConfig.Action -> buildActionContent(config.actionId)
            is WidgetConfig.ActionGroup -> buildActionRow(config.actionIds)
            is WidgetConfig.Metric -> buildMetricContent(config.label)
            is WidgetConfig.Gauge -> buildGaugeContent(config.label)
            is WidgetConfig.Clock -> buildClockContent(config.showDate)
            is WidgetConfig.Media -> buildMediaContent(config)
            is WidgetConfig.Slider -> buildSliderContent(config.label)
            is WidgetConfig.Shortcut -> buildShortcutContent(config)
        }
        card.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (widget.isRemoteControl()) {
            val platformOk = widget.isSupportedOn(CURRENT_PLATFORM)
            applyRemoteEnabledState(card, isConnected && platformOk)
        }
        return card
    }

    // -- ACTION --

    private fun buildActionContent(actionId: String): View {
        val action = ActionLibrary.find(actionId)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setBackgroundResource(pressedBackgroundRes())
            setOnClickListener { if (isActionAvailableForCurrentPlatform(actionId)) dispatcher.dispatch(actionId) }
            contentDescription = action?.label ?: actionId
        }
        column.addView(iconView(actionId, ICON_SIZE_DP))
        column.addView(labelView(action?.label ?: actionId).apply {
            setPadding(0, dp(6), 0, 0)
        })
        return column
    }

    // -- ACTION_GROUP / MEDIA transport row --

    private fun buildActionRow(actionIds: List<String>): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionIds.forEachIndexed { index, actionId ->
            if (index > 0) row.addView(verticalDivider())
            row.addView(iconButton(actionId), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    private fun iconButton(actionId: String): View {
        val action = ActionLibrary.find(actionId)
        return FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            setBackgroundResource(pressedBackgroundRes())
            minimumHeight = dp(MIN_TOUCH_TARGET_DP)
            minimumWidth = dp(MIN_TOUCH_TARGET_DP)
            contentDescription = action?.label ?: actionId
            setOnClickListener { if (isActionAvailableForCurrentPlatform(actionId)) dispatcher.dispatch(actionId) }
            addView(iconView(actionId, ICON_SIZE_DP).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER)
            })
        }
    }

    // -- METRIC --

    private fun buildMetricContent(label: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(captionView(label))
        addView(TextView(activity).apply {
            text = activity.getString(R.string.value_placeholder)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
    }

    // -- GAUGE (unavailable shell — no real telemetry, no animation) --

    private fun buildGaugeContent(label: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        addView(captionView(label))
        addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(8)
            }
        })
        addView(TextView(activity).apply {
            text = activity.getString(R.string.value_placeholder)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
    }

    // -- CLOCK (minute-boundary updates only — no high-frequency timer) --

    private fun buildClockContent(showDate: Boolean): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val timeView = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        column.addView(timeView)
        val dateView = if (showDate) {
            TextView(activity).apply {
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }.also { column.addView(it) }
        } else null

        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                refreshClock(timeView, dateView)
                handler.postDelayed(this, millisUntilNextMinute())
            }
        }
        column.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                refreshClock(timeView, dateView)
                handler.postDelayed(tick, millisUntilNextMinute())
            }
            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(tick)
            }
        })
        return column
    }

    private fun refreshClock(timeView: TextView, dateView: TextView?) {
        val now = Date()
        val pattern = if (DateFormat.is24HourFormat(activity)) "HH:mm" else "h:mm a"
        timeView.text = DateFormat.format(pattern, now)
        dateView?.text = DateFormat.format("EEE, MMM d", now)
    }

    private fun millisUntilNextMinute(): Long {
        val calendar = Calendar.getInstance()
        val secondsIntoMinute = calendar.get(Calendar.SECOND)
        val millisIntoSecond = calendar.get(Calendar.MILLISECOND)
        val elapsed = secondsIntoMinute * 1000L + millisIntoSecond
        return (60_000L - elapsed).coerceAtLeast(1_000L)
    }

    // -- MEDIA --

    private fun buildMediaContent(config: WidgetConfig.Media): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        column.addView(TextView(activity).apply {
            text = activity.getString(R.string.widget_now_playing)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 11f
            isAllCaps = true
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        })
        column.addView(TextView(activity).apply {
            text = activity.getString(R.string.widget_media_unavailable)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        column.addView(View(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(
            buildActionRow(listOf(config.previousActionId, config.playPauseActionId, config.nextActionId)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_TOUCH_TARGET_DP))
        )
        return column
    }

    // -- SLIDER (visual foundation only; never fakes a value — see TASK-007) --

    private fun buildSliderContent(label: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(captionView(label))
        addView(View(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                topMargin = dp(4)
            }
        })
        addView(TextView(activity).apply {
            text = activity.getString(R.string.widget_slider_unavailable)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 11f
            gravity = Gravity.CENTER
        })
    }

    // -- SHORTCUT (local navigation only, never gated by connection) --

    private fun buildShortcutContent(config: WidgetConfig.Shortcut): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setBackgroundResource(pressedBackgroundRes())
            contentDescription = config.label
            setOnClickListener {
                val destination = when (config.destination) {
                    ShortcutDestination.DEVICE_SETTINGS -> com.alicontrol.app.ui.DeviceSettingsActivity::class.java
                    ShortcutDestination.ALL_ACTIONS -> com.alicontrol.app.ui.ActionsActivity::class.java
                }
                activity.startActivity(android.content.Intent(activity, destination))
            }
        }
        val iconRes = when (config.destination) {
            ShortcutDestination.DEVICE_SETTINGS -> R.drawable.ic_settings
            ShortcutDestination.ALL_ACTIONS -> R.drawable.ic_apps
        }
        column.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP))
        })
        column.addView(labelView(config.label).apply { setPadding(0, dp(6), 0, 0) })
        return column
    }

    // -- shared helpers --

    private fun iconView(actionId: String, sizeDp: Int): ImageView = ImageView(activity).apply {
        setImageResource(ActionIconRegistry.iconFor(actionId))
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun labelView(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun captionView(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        textSize = 11f
        isAllCaps = true
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
    }

    private fun verticalDivider(): View = View(activity).apply {
        setBackgroundColor(ContextCompat.getColor(activity, R.color.divider))
        layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        }
    }

    private fun pressedBackgroundRes(): Int {
        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private fun applyRemoteEnabledState(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyRemoteEnabledStateRecursive(view.getChildAt(i), enabled)
        }
    }

    private fun applyRemoteEnabledStateRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyRemoteEnabledStateRecursive(view.getChildAt(i), enabled)
        }
    }

    private fun isActionAvailableForCurrentPlatform(actionId: String): Boolean =
        ActionLibrary.find(actionId)?.supports(CURRENT_PLATFORM) == true

    companion object {
        /**
         * No BLE "connected platform" signal exists yet (see class doc) —
         * matches [com.alicontrol.app.ui.ActionsActivity]'s existing
         * `Platform.MACOS` assumption exactly, since macOS is the only
         * implemented agent.
         */
        val CURRENT_PLATFORM = Platform.MACOS

        private const val CARD_CORNER_RADIUS_DP = 16
        private const val CARD_MARGIN_DP = 5
        private const val ICON_SIZE_DP = 26
        private const val MIN_TOUCH_TARGET_DP = 48
        private const val DISABLED_ALPHA = 0.35f
    }
}
