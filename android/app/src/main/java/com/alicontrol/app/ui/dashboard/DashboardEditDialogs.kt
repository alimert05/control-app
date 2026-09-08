package com.alicontrol.app.ui.dashboard

import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.alicontrol.app.R
import com.alicontrol.app.actions.ActionDefinition
import com.alicontrol.app.actions.ActionLibrary
import com.alicontrol.app.actions.DangerLevel
import com.alicontrol.app.actions.PermissionRequirement
import com.alicontrol.app.ble.AliControlCommands
import com.alicontrol.app.dashboard.DashboardEditor
import com.alicontrol.app.dashboard.DashboardPage
import com.alicontrol.app.dashboard.WidgetType
import com.alicontrol.app.icons.ActionIconRegistry

/**
 * Every dialog the dashboard editor needs: the widget picker, the action
 * picker, small value pickers for metric/gauge/group widgets, and page
 * management.
 *
 * These are plain `AlertDialog`s built from the existing action metadata —
 * the action picker in particular is generated entirely from
 * [ActionLibrary], so it can never offer an action the desktop agent doesn't
 * implement, and there is no free-text field anywhere that could introduce an
 * arbitrary action id or command.
 */
class DashboardEditDialogs(private val activity: AppCompatActivity) {

    /** Presets for ACTION_GROUP widgets — each is a set of existing library action ids. */
    private val groupPresets: List<Pair<Int, List<String>>> = listOf(
        R.string.group_preset_transport to listOf(
            AliControlCommands.PREVIOUS_TRACK, AliControlCommands.PLAY_PAUSE, AliControlCommands.NEXT_TRACK
        ),
        R.string.group_preset_volume to listOf(
            AliControlCommands.VOLUME_DOWN, AliControlCommands.MUTE_TOGGLE, AliControlCommands.VOLUME_UP
        ),
        R.string.group_preset_window to listOf(
            AliControlCommands.MINIMIZE_WINDOW, AliControlCommands.FULLSCREEN_TOGGLE, AliControlCommands.CLOSE_WINDOW
        ),
        R.string.group_preset_clipboard to listOf(
            AliControlCommands.COPY, AliControlCommands.PASTE, AliControlCommands.UNDO, AliControlCommands.REDO
        )
    )

    private val addableTypes = listOf(
        WidgetType.ACTION,
        WidgetType.ACTION_GROUP,
        WidgetType.METRIC,
        WidgetType.GAUGE,
        WidgetType.CLOCK,
        WidgetType.DAILY_OVERVIEW,
        WidgetType.MEDIA,
        WidgetType.SLIDER,
        WidgetType.SHORTCUT
    )

    /**
     * The widget type picker. Types that cannot currently fit on [page] at
     * their minimum size are labelled as such up front rather than failing
     * after the user picks one.
     */
    fun showWidgetPicker(page: DashboardPage, onPicked: (WidgetType) -> Unit) {
        val labels = addableTypes.map { type ->
            val name = activity.getString(displayNameRes(type))
            val template = if (DashboardEditor.hasSpaceFor(page, type)) {
                R.string.widget_type_size_format
            } else {
                R.string.widget_type_size_full
            }
            activity.getString(template, name, type.defaultWidth, type.defaultHeight)
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(R.string.widget_picker_title)
            .setItems(labels) { _, index -> onPicked(addableTypes[index]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * The action picker: every [ActionLibrary] action supported by the
     * current platform, grouped by its existing category, with its
     * [ActionIconRegistry] icon and its confirmation/permission metadata
     * shown as small badges. Selection returns a library definition — never a
     * raw string the caller invented.
     */
    fun showActionPicker(onPicked: (ActionDefinition) -> Unit) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        lateinit var dialog: AlertDialog
        val byCategory = ActionLibrary
            .forPlatform(DashboardWidgetViewFactory.CURRENT_PLATFORM)
            .groupBy { it.category }

        for ((category, actions) in byCategory) {
            container.addView(TextView(activity).apply {
                text = category.name
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                textSize = 11f
                isAllCaps = true
                letterSpacing = 0.08f
                setPadding(0, dp(14), 0, dp(4))
            })
            for (action in actions) {
                container.addView(actionRow(action) {
                    dialog.dismiss()
                    onPicked(action)
                })
            }
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.action_picker_title)
            .setView(ScrollView(activity).apply { addView(container) })
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
    }

    private fun actionRow(action: ActionDefinition, onClick: () -> Unit): View {
        val badges = buildList {
            if (action.dangerLevel == DangerLevel.CONFIRM) add(activity.getString(R.string.action_badge_confirm))
            if (action.permissionRequirement == PermissionRequirement.ACCESSIBILITY) {
                add(activity.getString(R.string.action_badge_accessibility))
            }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(activity).apply {
            text = action.label
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(14)
            setCompoundDrawablesRelativeWithIntrinsicBounds(ActionIconRegistry.iconFor(action.id), 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(
                this,
                ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
            )
        })
        if (badges.isNotEmpty()) {
            row.addView(TextView(activity).apply {
                text = badges.joinToString(" · ")
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                textSize = 11f
                setPadding(dp(38), dp(2), 0, 0)
            })
        }
        return row
    }

    /** Small value picker used for METRIC/GAUGE readings (CPU / GPU / RAM). */
    fun showLabelPicker(titleRes: Int, labels: List<String>, onPicked: (String) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, index -> onPicked(labels[index]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** ACTION_GROUP preset picker — each preset is a fixed list of library action ids. */
    fun showActionGroupPicker(onPicked: (List<String>) -> Unit) {
        val labels = groupPresets.map { activity.getString(it.first) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.group_picker_title)
            .setItems(labels) { _, index -> onPicked(groupPresets[index].second) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** The page-management menu: create, rename, reorder, delete, and Reset to Default. */
    fun showPageMenu(callbacks: PageMenuCallbacks) {
        val entries = listOf(
            R.string.page_new to callbacks::onCreatePage,
            R.string.page_rename to callbacks::onRenamePage,
            R.string.page_move_left to callbacks::onMovePageLeft,
            R.string.page_move_right to callbacks::onMovePageRight,
            R.string.page_delete to callbacks::onDeletePage,
            R.string.page_reset to callbacks::onResetToDefault
        )
        val labels = entries.map { activity.getString(it.first) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.page_menu_title)
            .setItems(labels) { _, index -> entries[index].second.invoke() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun showRenamePage(currentName: String, onName: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(currentName)
            setSelection(currentName.length)
            hint = activity.getString(R.string.page_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        }
        val wrapper = LinearLayout(activity).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.page_rename)
            .setView(wrapper)
            .setPositiveButton(R.string.action_save) { _, _ -> onName(input.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Deleting a page with widgets on it always asks first — it is the one destructive edit. */
    fun showDeletePageConfirm(page: DashboardPage, onConfirm: () -> Unit) {
        if (page.widgets.isEmpty()) {
            onConfirm()
            return
        }
        val message = activity.resources
            .getQuantityString(R.plurals.page_delete_confirm, page.widgets.size, page.name, page.widgets.size)
        AlertDialog.Builder(activity)
            .setTitle(R.string.page_delete_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun showResetConfirm(onConfirm: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setPositiveButton(R.string.action_reset) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun displayNameRes(type: WidgetType): Int = when (type) {
        WidgetType.ACTION -> R.string.widget_type_action
        WidgetType.ACTION_GROUP -> R.string.widget_type_action_group
        WidgetType.METRIC -> R.string.widget_type_metric
        WidgetType.GAUGE -> R.string.widget_type_gauge
        WidgetType.CLOCK -> R.string.widget_type_clock
        WidgetType.DAILY_OVERVIEW -> R.string.widget_type_daily_overview
        WidgetType.MEDIA -> R.string.widget_type_media
        WidgetType.SLIDER -> R.string.widget_type_slider
        WidgetType.SHORTCUT -> R.string.widget_type_shortcut
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** The page-management actions [showPageMenu] can trigger. */
    interface PageMenuCallbacks {
        fun onCreatePage()
        fun onRenamePage()
        fun onMovePageLeft()
        fun onMovePageRight()
        fun onDeletePage()
        fun onResetToDefault()
    }
}
