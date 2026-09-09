package com.alicontrol.app.ui.dashboard

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.alicontrol.app.live.LiveValueFormatter
import com.alicontrol.app.live.MediaState
import com.alicontrol.app.live.MetricType
import com.alicontrol.app.live.artwork.BitmapArtworkImage
import com.alicontrol.app.ui.ActionsActivity
import com.alicontrol.app.ui.DeviceSettingsActivity
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
 * **Responsive by grid size.** A widget's allocated cells decide its internal
 * presentation, not a fixed template: a 1x1 ACTION is an icon over a short
 * label, a 2x1 puts the full label beside the icon, a 2x2 goes large. Text
 * sizes step with the widget rather than being scaled down until unreadable.
 *
 * **Edit mode changes what a touch means.** While editing, no widget wires up
 * its normal click handler at all — a tap selects, a drag moves (see
 * [WidgetEditGestures]) — so a stray finger can never fire a remote action
 * while rearranging the dashboard.
 *
 * Remote-control widgets are dimmed and disabled while disconnected, but
 * [ActionDispatcher] independently re-checks the live connection right before
 * sending, so that dimming is UX, not the actual guard.
 *
 * Platform awareness: only the macOS agent exists and the protocol carries no
 * "connected platform" field, so [CURRENT_PLATFORM] is a constant, exactly as
 * [ActionsActivity] already assumes. An action unsupported by that platform
 * renders **disabled** and stays in the saved layout — never silently
 * dropped. A future Windows agent only needs this constant replaced by a real
 * signal.
 */
class DashboardWidgetViewFactory(
    private val activity: AppCompatActivity,
    private val dispatcher: ActionDispatcher
) {

    private enum class SizeClass { COMPACT, WIDE, LARGE }

    private val density get() = activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    fun build(
        widget: DashboardWidget,
        isConnected: Boolean,
        editState: DashboardEditState = DashboardEditState(),
        editListener: DashboardEditListener? = null,
        bindings: LiveWidgetBindings? = null
    ): View {
        val editing = editState.isEditing
        val selected = editState.isSelected(widget.id)

        val card = MaterialCardView(activity).apply {
            radius = dp(CARD_CORNER_RADIUS_DP).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.surface))
            strokeColor = ContextCompat.getColor(activity, if (selected) R.color.accent else R.color.divider)
            strokeWidth = dp(if (selected) 2 else 1)
            setGridPosition(widget.gridX, widget.gridY, widget.gridWidth, widget.gridHeight, dp(CARD_MARGIN_DP))
        }

        val size = sizeClassOf(widget)
        val content: View = when (val config = widget.config) {
            is WidgetConfig.Action -> buildActionContent(config.actionId, size, interactive = !editing)
            is WidgetConfig.ActionGroup -> buildActionRow(config.actionIds, interactive = !editing)
            is WidgetConfig.Metric -> buildMetricContent(config.label, size, bindings)
            is WidgetConfig.Gauge -> buildGaugeContent(config.label, size, bindings)
            is WidgetConfig.Clock -> buildClockContent(config.showDate && widget.gridHeight >= 2, size)
            is WidgetConfig.DailyOverview -> buildDailyOverviewContent(config, widget)
            is WidgetConfig.Media -> buildMediaContent(config, widget, interactive = !editing, bindings = bindings)
            is WidgetConfig.Slider -> buildSliderContent(config.label)
            is WidgetConfig.Shortcut -> buildShortcutContent(config, size, interactive = !editing)
        }

        val container = FrameLayout(activity)
        container.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        card.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        if (editing && editListener != null) {
            WidgetEditGestures.attachDrag(card, widget, editListener)
            if (selected) addSelectionChrome(container, card, widget, editListener)
        } else if (widget.isRemoteControl()) {
            // Outside edit mode only: dim what can't currently be sent.
            applyRemoteEnabledState(card, isConnected && widget.isSupportedOn(CURRENT_PLATFORM))
        }
        return card
    }

    // -- edit chrome --

    private fun addSelectionChrome(
        container: FrameLayout,
        card: View,
        widget: DashboardWidget,
        listener: DashboardEditListener
    ) {
        val badgeSize = dp(EDIT_BADGE_SIZE_DP)

        val remove = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
            background = circle(R.color.danger)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = activity.getString(R.string.edit_remove_widget)
            isClickable = true
            isFocusable = true
            setOnClickListener { listener.onRemoveWidget(widget.id) }
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(2), dp(2), 0)
            }
        }
        container.addView(remove)

        val resize = ImageView(activity).apply {
            setImageResource(R.drawable.ic_resize)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.background))
            background = circle(R.color.accent)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = activity.getString(R.string.edit_resize_widget)
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(2), dp(2))
            }
        }
        container.addView(resize)
        WidgetEditGestures.attachResize(resize, card, widget, listener)
    }

    private fun circle(colorRes: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ContextCompat.getColor(activity, colorRes))
    }

    // -- ACTION --

    private fun buildActionContent(actionId: String, size: SizeClass, interactive: Boolean): View {
        val action = ActionLibrary.find(actionId)
        val label = action?.label ?: actionId
        val root = when (size) {
            SizeClass.WIDE -> LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                addView(iconView(actionId, ICON_SIZE_WIDE_DP))
                addView(labelView(label, 12f).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(8)
                    }
                })
            }

            SizeClass.LARGE -> LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(iconView(actionId, ICON_SIZE_LARGE_DP))
                addView(labelView(label, 13f).apply { setPadding(0, dp(8), 0, 0) })
            }

            SizeClass.COMPACT -> LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
                addView(iconView(actionId, ICON_SIZE_COMPACT_DP))
                addView(labelView(shortLabel(label), 9f).apply { setPadding(0, dp(4), 0, 0) })
            }
        }
        root.contentDescription = label
        if (interactive) {
            root.isClickable = true
            root.isFocusable = true
            root.setBackgroundResource(pressedBackgroundRes())
            root.setOnClickListener { if (isActionAvailableForCurrentPlatform(actionId)) dispatcher.dispatch(actionId) }
        }
        return root
    }

    /** A 1x1 tile has room for one word — "Mission Control" becomes "Mission", the icon carries the rest. */
    private fun shortLabel(label: String): String = label.substringBefore(' ').trim()

    // -- ACTION_GROUP / MEDIA transport row --

    private fun buildActionRow(actionIds: List<String>, interactive: Boolean): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionIds.forEachIndexed { index, actionId ->
            if (index > 0) row.addView(verticalDivider())
            row.addView(
                iconButton(actionId, interactive),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        return row
    }

    private fun iconButton(actionId: String, interactive: Boolean): View {
        val action = ActionLibrary.find(actionId)
        return FrameLayout(activity).apply {
            contentDescription = action?.label ?: actionId
            if (interactive) {
                isClickable = true
                isFocusable = true
                setBackgroundResource(pressedBackgroundRes())
                setOnClickListener { if (isActionAvailableForCurrentPlatform(actionId)) dispatcher.dispatch(actionId) }
            }
            addView(iconView(actionId, ICON_SIZE_WIDE_DP).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ICON_SIZE_WIDE_DP), dp(ICON_SIZE_WIDE_DP), Gravity.CENTER)
            })
        }
    }

    // -- METRIC --

    private fun buildMetricContent(label: String, size: SizeClass, bindings: LiveWidgetBindings?): View {
        val metric = MetricType.forLabel(label)
        val valueView = TextView(activity).apply {
            text = activity.getString(R.string.value_placeholder)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = if (size == SizeClass.COMPACT) 16f else 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        // The widget owns no timer and no subscription: it just re-renders
        // whatever the shared DesktopState says, whenever that changes.
        bindings?.register { state ->
            valueView.text = LiveValueFormatter.metric(metric.valueIn(state.metrics), metric.unit)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(captionView(label, if (size == SizeClass.COMPACT) 9f else 11f))
            addView(valueView)
        }
    }

    // -- GAUGE --

    /**
     * A flat bar whose fill is a weight, so a value change is one cheap
     * layout pass every couple of seconds — no custom drawing, no chart
     * library, and nothing animating between updates.
     */
    private fun buildGaugeContent(label: String, size: SizeClass, bindings: LiveWidgetBindings?): View {
        val metric = MetricType.forLabel(label)

        val fill = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.accent))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
        }
        val remainder = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val track = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.divider))
            }
            addView(fill)
            addView(remainder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply {
                topMargin = dp(6)
            }
        }
        val valueView = TextView(activity).apply {
            text = activity.getString(R.string.value_placeholder)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        bindings?.register { state ->
            val value = metric.valueIn(state.metrics)
            valueView.text = LiveValueFormatter.metric(value, metric.unit)
            val fraction = value?.coerceIn(0, 100)?.let { it / 100f } ?: 0f
            (fill.layoutParams as LinearLayout.LayoutParams).weight = fraction
            (remainder.layoutParams as LinearLayout.LayoutParams).weight = 1f - fraction
            track.requestLayout()
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(captionView(label, if (size == SizeClass.COMPACT) 9f else 11f))
            addView(track)
            addView(valueView)
        }
    }

    // -- CLOCK / DAILY_OVERVIEW (minute-boundary updates only — no high-frequency timer) --

    private fun buildClockContent(showDate: Boolean, size: SizeClass): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val timeView = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = if (size == SizeClass.LARGE) 26f else 20f
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

        bindMinuteTicker(column) {
            timeView.text = formattedTime()
            dateView?.text = formattedDate()
        }
        return column
    }

    /**
     * TASK-008's DAILY_OVERVIEW: real local time and date, and explicit
     * unavailable states for weather, calendar and next event. Nothing here
     * fetches anything — a weather provider and a calendar source are
     * TASK-015's job, and showing plausible-looking placeholder values in the
     * meantime would be worse than showing none.
     */
    private fun buildDailyOverviewContent(config: WidgetConfig.DailyOverview, widget: DashboardWidget): View {
        val roomy = widget.gridWidth >= 4 || widget.gridHeight >= 3
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val headline = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeView = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = if (roomy) 34f else 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        val dateView = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = if (roomy) 14f else 12f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headline.addView(timeView)
        headline.addView(dateView)
        column.addView(headline)

        val weatherText = if (config.locationLabel.isBlank()) {
            activity.getString(R.string.overview_weather_unavailable)
        } else {
            activity.getString(R.string.overview_weather_unavailable_at, config.locationLabel)
        }
        column.addView(overviewLine(weatherText, dp(6)))

        column.addView(captionView(activity.getString(R.string.overview_today), 10f).apply {
            gravity = Gravity.START
            setPadding(0, dp(if (roomy) 12 else 8), 0, 0)
        })
        column.addView(overviewLine(activity.getString(R.string.overview_calendar_unavailable), dp(2)))
        column.addView(overviewLine(activity.getString(R.string.overview_next_event_unavailable), dp(4)))

        bindMinuteTicker(column) {
            timeView.text = formattedTime()
            dateView.text = formattedDate()
        }
        return column
    }

    private fun overviewLine(text: String, topPaddingPx: Int): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        textSize = 12f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, topPaddingPx, 0, 0)
    }

    /**
     * Refreshes [onTick] once now and then on each minute boundary, only
     * while the view is actually attached. Nothing polls: one `postDelayed`
     * is outstanding at a time, and it is cancelled the moment the widget
     * leaves the window (e.g. the user swipes to another page, or edit mode
     * rebuilds the page).
     */
    private fun bindMinuteTicker(view: View, onTick: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                onTick()
                handler.postDelayed(this, millisUntilNextMinute())
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                onTick()
                handler.postDelayed(tick, millisUntilNextMinute())
            }

            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(tick)
            }
        })
    }

    private fun formattedTime(): CharSequence {
        val pattern = if (DateFormat.is24HourFormat(activity)) "HH:mm" else "h:mm a"
        return DateFormat.format(pattern, Date())
    }

    private fun formattedDate(): CharSequence = DateFormat.format("EEE, d MMM", Date())

    private fun millisUntilNextMinute(): Long {
        val calendar = Calendar.getInstance()
        val elapsed = calendar.get(Calendar.SECOND) * 1000L + calendar.get(Calendar.MILLISECOND)
        return (60_000L - elapsed).coerceAtLeast(1_000L)
    }

    // -- MEDIA --

    /**
     * The Now Playing widget: real title/artist/album and a progress bar when
     * the desktop reports them, an honest "Media unavailable" when it
     * doesn't, and the same three transport buttons either way.
     *
     * Those buttons are unchanged — they still send `previous_track`,
     * `play_pause` and `next_track` through [ActionLibrary] and the existing
     * dispatcher. Live metadata is a *display* channel; it introduced no
     * second media-control path.
     */
    private fun buildMediaContent(
        config: WidgetConfig.Media,
        widget: DashboardWidget,
        interactive: Boolean,
        bindings: LiveWidgetBindings?
    ): View {
        val roomy = widget.gridHeight >= 3
        val showArtwork = widget.gridWidth >= 3 && widget.gridHeight >= 2

        val titleView = TextView(activity).apply {
            text = activity.getString(R.string.widget_media_unavailable)
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = if (roomy) 16f else 14f
            gravity = if (showArtwork) Gravity.START else Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), if (showArtwork) 0 else dp(6), dp(8), 0)
        }
        val subtitleView = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 12f
            gravity = if (showArtwork) Gravity.START else Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(8), dp(2), dp(8), 0)
        }

        val artworkView = if (showArtwork) ImageView(activity).apply {
            val side = dp(if (roomy) 76 else 54)
            layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageResource(R.drawable.ic_music_note)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_secondary))
            contentDescription = activity.getString(R.string.widget_now_playing)
        } else null

        val metadataColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(subtitleView)
        }
        val metadata = if (artworkView != null) {
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(10), 0)
                addView(artworkView)
                addView(metadataColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        } else metadataColumn

        val positionView = timeLabel()
        val durationView = timeLabel()
        val progressFill = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.accent))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
        }
        val progressRemainder = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val progressTrack = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.divider))
            }
            addView(progressFill)
            addView(progressRemainder)
            layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        val progressRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), 0)
            addView(positionView)
            addView(progressTrack)
            addView(durationView)
        }

        val actionRow = buildActionRow(
            listOf(config.previousActionId, config.playPauseActionId, config.nextActionId),
            interactive
        )

        bindings?.register { state ->
            val media = state.media
            bindMediaText(media, titleView, subtitleView)
            artworkView?.let { bindMediaArtwork(it, media.artworkId, state.artwork.imageFor(media.artworkId)) }
            bindMediaProgress(media, progressRow, progressFill, progressRemainder, progressTrack, positionView, durationView)
            bindPlayPauseIcon(actionRow, config.playPauseActionId, media)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(6))
            addView(captionView(activity.getString(R.string.widget_now_playing), 11f))
            addView(metadata)
            addView(progressRow)
            addView(View(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_TOUCH_TARGET_DP)))
        }
    }

    private fun bindMediaArtwork(view: ImageView, artworkId: String?, image: com.alicontrol.app.live.artwork.ArtworkImage?) {
        val bitmap = (image as? BitmapArtworkImage)?.bitmap
        if (artworkId != null && bitmap != null) {
            view.setPadding(0, 0, 0, 0)
            view.imageTintList = null
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bitmap)
        } else {
            view.setPadding(dp(12), dp(12), dp(12), dp(12))
            view.scaleType = ImageView.ScaleType.CENTER
            view.setImageResource(R.drawable.ic_music_note)
            view.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_secondary))
        }
    }

    private fun bindMediaText(media: MediaState, titleView: TextView, subtitleView: TextView) {
        if (media.isAvailable && media.hasTrackMetadata) {
            titleView.text = media.title
            val subtitle = LiveValueFormatter.artistLine(media.artist, media.album)
            subtitleView.text = subtitle.orEmpty()
            subtitleView.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        } else {
            titleView.text = activity.getString(R.string.widget_media_unavailable)
            subtitleView.visibility = View.GONE
        }
    }

    private fun bindMediaProgress(
        media: MediaState,
        progressRow: View,
        fill: View,
        remainder: View,
        track: View,
        positionView: TextView,
        durationView: TextView
    ) {
        val fraction = LiveValueFormatter.progressFraction(media.positionSeconds, media.durationSeconds)
        if (!media.isAvailable || fraction == null) {
            progressRow.visibility = View.GONE
            return
        }
        progressRow.visibility = View.VISIBLE
        positionView.text = LiveValueFormatter.time(media.positionSeconds)
        durationView.text = LiveValueFormatter.time(media.durationSeconds)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = fraction
        (remainder.layoutParams as LinearLayout.LayoutParams).weight = 1f - fraction
        track.requestLayout()
    }

    /** Shows a pause glyph while something is playing, so the button reflects live state. */
    private fun bindPlayPauseIcon(actionRow: View, playPauseActionId: String, media: MediaState) {
        val icon = actionRow.findViewWithTag<ImageView>(playPauseActionId) ?: return
        val playing = media.isAvailable && media.isPlaying
        icon.setImageResource(if (playing) R.drawable.ic_pause else ActionIconRegistry.iconFor(playPauseActionId))
    }

    private fun timeLabel(): TextView = TextView(activity).apply {
        text = LiveValueFormatter.UNAVAILABLE_TIME
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        textSize = 10f
    }

    // -- SLIDER (visual foundation only; never fakes a value) --

    private fun buildSliderContent(label: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        addView(captionView(label, 11f))
        addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.divider))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
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

    private fun buildShortcutContent(config: WidgetConfig.Shortcut, size: SizeClass, interactive: Boolean): View {
        val iconRes = when (config.destination) {
            ShortcutDestination.DEVICE_SETTINGS -> R.drawable.ic_settings
            ShortcutDestination.ALL_ACTIONS -> R.drawable.ic_apps
        }
        val icon = ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val root = if (size == SizeClass.WIDE) {
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                addView(icon, LinearLayout.LayoutParams(dp(ICON_SIZE_WIDE_DP), dp(ICON_SIZE_WIDE_DP)))
                addView(labelView(config.label, 12f).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(8)
                    }
                })
            }
        } else {
            val iconSize = if (size == SizeClass.LARGE) ICON_SIZE_LARGE_DP else ICON_SIZE_COMPACT_DP
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
                addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
                addView(
                    labelView(if (size == SizeClass.COMPACT) shortLabel(config.label) else config.label, if (size == SizeClass.COMPACT) 9f else 13f)
                        .apply { setPadding(0, dp(4), 0, 0) }
                )
            }
        }
        root.contentDescription = config.label
        if (interactive) {
            root.isClickable = true
            root.isFocusable = true
            root.setBackgroundResource(pressedBackgroundRes())
            root.setOnClickListener {
                val destination = when (config.destination) {
                    ShortcutDestination.DEVICE_SETTINGS -> DeviceSettingsActivity::class.java
                    ShortcutDestination.ALL_ACTIONS -> ActionsActivity::class.java
                }
                activity.startActivity(Intent(activity, destination))
            }
        }
        return root
    }

    // -- shared helpers --

    private fun sizeClassOf(widget: DashboardWidget): SizeClass = when {
        widget.gridHeight >= 2 -> SizeClass.LARGE
        widget.gridWidth >= 2 -> SizeClass.WIDE
        else -> SizeClass.COMPACT
    }

    private fun iconView(actionId: String, sizeDp: Int): ImageView = ImageView(activity).apply {
        setImageResource(ActionIconRegistry.iconFor(actionId))
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        // Tagged with its action id so a live binding can find one specific
        // icon later (the media widget swaps play/pause with playback state).
        tag = actionId
    }

    private fun labelView(text: String, sizeSp: Float): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        textSize = sizeSp
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun captionView(text: String, sizeSp: Float): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        textSize = sizeSp
        isAllCaps = true
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun verticalDivider(): View = View(activity).apply {
        setBackgroundColor(ContextCompat.getColor(activity, R.color.divider))
        layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
    }

    private fun pressedBackgroundRes(): Int {
        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private fun applyRemoteEnabledState(view: View, enabled: Boolean) {
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        applyEnabledRecursive(view, enabled)
    }

    private fun applyEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyEnabledRecursive(view.getChildAt(i), enabled)
        }
    }

    private fun isActionAvailableForCurrentPlatform(actionId: String): Boolean =
        ActionLibrary.find(actionId)?.supports(CURRENT_PLATFORM) == true

    companion object {
        /**
         * No BLE "connected platform" signal exists yet (see class doc) —
         * matches [ActionsActivity]'s existing `Platform.MACOS` assumption,
         * since macOS is the only implemented agent.
         */
        val CURRENT_PLATFORM = Platform.MACOS

        private const val CARD_CORNER_RADIUS_DP = 14
        private const val CARD_MARGIN_DP = 4
        private const val ICON_SIZE_COMPACT_DP = 20
        private const val ICON_SIZE_WIDE_DP = 22
        private const val ICON_SIZE_LARGE_DP = 30
        private const val MIN_TOUCH_TARGET_DP = 48
        private const val EDIT_BADGE_SIZE_DP = 26
        private const val DISABLED_ALPHA = 0.35f
    }
}
