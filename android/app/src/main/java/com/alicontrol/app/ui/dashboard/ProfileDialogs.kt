package com.alicontrol.app.ui.dashboard

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.widget.TextViewCompat
import com.alicontrol.app.R
import com.alicontrol.app.dashboard.profile.DashboardProfile
import com.alicontrol.app.dashboard.profile.ProfileCollection

/**
 * Everything the user sees for profiles besides the header button itself: the compact selector
 * popup, and the Manage Profiles dialog with its per-profile actions and confirmations.
 *
 * Plain `PopupMenu` / `AlertDialog`s — the same lightweight, XML-Views-era building blocks as
 * [DashboardEditDialogs]; no new library and no Compose.
 *
 * These dialogs only *ask*: every decision (is this name valid, may this profile be deleted)
 * is made by `ProfileEditor` and reported back by [Callbacks]. There is no way in here to type
 * an id or an action — the one text field produces a display name.
 *
 * The manager is rebuilt from [Callbacks.profiles] after each action, so it always shows the
 * persisted state (new order, new name, the current startup default) rather than a stale copy.
 */
class ProfileDialogs(
    private val activity: AppCompatActivity,
    private val editDialogs: DashboardEditDialogs
) {

    /** What the profile UI needs from the screen that owns the [com.alicontrol.app.dashboard.profile.ProfileSession]. */
    interface Callbacks {
        /** The current profiles, read afresh each time a dialog is (re)built. */
        fun profiles(): ProfileCollection

        fun onSwitchProfile(profileId: String)
        fun onCreateProfile(name: String)
        fun onDuplicateProfile(profileId: String)
        fun onRenameProfile(profileId: String, name: String)
        fun onMoveProfile(profileId: String, offset: Int)
        fun onSetStartupDefault(profileId: String)
        fun onResetProfile(profileId: String)
        fun onDeleteProfile(profileId: String)
    }

    // -- selector --

    /**
     * The compact header selector: the profiles in their persisted order (the active one
     * checked), a divider, then **Manage Profiles**. A popup anchored to the header button — no
     * full-screen UI and no animation to pay for on a low-end tablet.
     */
    fun showSelector(anchor: View, collection: ProfileCollection, onSelect: (String) -> Unit, onManage: () -> Unit) {
        val ordered = collection.orderedProfiles
        val activeId = collection.activeProfile.id
        val popup = PopupMenu(activity, anchor)
        val menu = popup.menu
        ordered.forEachIndexed { index, profile ->
            menu.add(GROUP_PROFILES, index, index, profile.name).apply {
                isCheckable = true
                isChecked = profile.id == activeId
            }
        }
        menu.setGroupCheckable(GROUP_PROFILES, true, true)
        menu.add(GROUP_MANAGE, ITEM_MANAGE, ordered.size, R.string.profile_manage)
        MenuCompat.setGroupDividerEnabled(menu, true)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == ITEM_MANAGE) onManage() else onSelect(ordered[item.itemId].id)
            true
        }
        popup.show()
    }

    // -- manager --

    /** The Manage Profiles dialog: every profile with its badges, tap one for its actions, **Add profile** at the end. */
    fun showManager(callbacks: Callbacks) {
        val collection = callbacks.profiles()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        for (profile in collection.orderedProfiles) {
            container.addView(profileRow(profile, collection) { showProfileActions(profile.id, callbacks) })
        }
        container.addView(addRow { promptForNewProfile(callbacks) })

        AlertDialog.Builder(activity)
            .setTitle(R.string.profile_manager_title)
            .setView(ScrollView(activity).apply { addView(container) })
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun profileRow(profile: DashboardProfile, collection: ProfileCollection, onClick: () -> Unit): View {
        val isActive = profile.id == collection.activeProfile.id
        val badges = buildList {
            add(activity.getString(if (profile.builtIn) R.string.profile_badge_built_in else R.string.profile_badge_custom))
            if (isActive) add(activity.getString(R.string.profile_badge_active))
            if (profile.id == collection.defaultProfileId) add(activity.getString(R.string.profile_badge_startup))
            val pages = profile.dashboard.pages.size
            add(activity.resources.getQuantityString(R.plurals.profile_page_count, pages, pages))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            setBackgroundFromAttribute(this, android.R.attr.selectableItemBackground)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(activity).apply {
            text = profile.name
            setTextColor(ContextCompat.getColor(activity, if (isActive) R.color.accent else R.color.text_primary))
            textSize = 16f
            if (isActive) setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(activity).apply {
            text = badges.joinToString(" · ")
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    private fun addRow(onClick: () -> Unit): View = TextView(activity).apply {
        text = activity.getString(R.string.profile_add)
        setTextColor(ContextCompat.getColor(activity, R.color.accent))
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        compoundDrawablePadding = dp(14)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent)))
        isClickable = true
        isFocusable = true
        setBackgroundFromAttribute(this, android.R.attr.selectableItemBackground)
        setOnClickListener { onClick() }
    }

    private fun promptForNewProfile(callbacks: Callbacks) {
        editDialogs.showNameInput(
            titleRes = R.string.profile_add,
            hintRes = R.string.profile_name_hint,
            initial = activity.getString(R.string.profile_new_default_name),
            onName = { name ->
                callbacks.onCreateProfile(name)
                showManager(callbacks)
            },
            selectAll = true,
            onCancelled = { showManager(callbacks) }
        )
    }

    // -- per-profile actions --

    /**
     * What can be done to one profile. Built-in profiles offer **Reset to Default Layout** and a
     * **Delete** that explains itself and offers the reset instead; custom profiles offer
     * **Clear layout** and a real **Delete**.
     */
    private fun showProfileActions(profileId: String, callbacks: Callbacks) {
        val collection = callbacks.profiles()
        val profile = collection.profile(profileId) ?: return showManager(callbacks)
        val ordered = collection.orderedProfiles
        val position = ordered.indexOfFirst { it.id == profileId }

        val entries = mutableListOf<Pair<Int, () -> Unit>>()
        if (profile.id != collection.activeProfile.id) {
            entries += R.string.profile_switch_to to { callbacks.onSwitchProfile(profileId) }
        }
        entries += R.string.profile_rename to { promptForRename(profile, callbacks) }
        entries += R.string.profile_duplicate to {
            callbacks.onDuplicateProfile(profileId)
            showManager(callbacks)
        }
        if (position > 0) {
            entries += R.string.profile_move_up to {
                callbacks.onMoveProfile(profileId, -1)
                showManager(callbacks)
            }
        }
        if (position < ordered.size - 1) {
            entries += R.string.profile_move_down to {
                callbacks.onMoveProfile(profileId, 1)
                showManager(callbacks)
            }
        }
        if (profile.id != collection.defaultProfileId) {
            entries += R.string.profile_set_default to {
                callbacks.onSetStartupDefault(profileId)
                showManager(callbacks)
            }
        }
        entries += (if (profile.builtIn) R.string.profile_reset_builtin else R.string.profile_reset_custom) to {
            showResetConfirm(
                profile,
                onConfirm = {
                    callbacks.onResetProfile(profileId)
                    showManager(callbacks)
                },
                onCancelled = { showManager(callbacks) }
            )
        }
        entries += R.string.profile_delete to {
            if (profile.builtIn) {
                showBuiltInDeleteExplanation(profile, callbacks)
            } else {
                showDeleteConfirm(profile, callbacks)
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(profile.name)
            .setItems(entries.map { activity.getString(it.first) }.toTypedArray()) { _, index -> entries[index].second.invoke() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showManager(callbacks) }
            .setOnCancelListener { showManager(callbacks) }
            .show()
    }

    private fun promptForRename(profile: DashboardProfile, callbacks: Callbacks) {
        editDialogs.showNameInput(
            titleRes = R.string.profile_rename,
            hintRes = R.string.profile_name_hint,
            initial = profile.name,
            onName = { name ->
                callbacks.onRenameProfile(profile.id, name)
                showManager(callbacks)
            },
            onCancelled = { showManager(callbacks) }
        )
    }

    // -- confirmations --

    /**
     * Resetting is destructive for that one profile, so it always asks — and says exactly what
     * it will and will not touch (other profiles, the connection and settings are untouched).
     */
    fun showResetConfirm(profile: DashboardProfile, onConfirm: () -> Unit, onCancelled: () -> Unit = {}) {
        val builtIn = profile.builtIn
        AlertDialog.Builder(activity)
            .setTitle(if (builtIn) R.string.profile_reset_builtin_title else R.string.profile_reset_custom_title)
            .setMessage(
                activity.getString(
                    if (builtIn) R.string.profile_reset_builtin_message else R.string.profile_reset_custom_message,
                    profile.name
                )
            )
            .setPositiveButton(R.string.action_reset) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .show()
    }

    private fun showDeleteConfirm(profile: DashboardProfile, callbacks: Callbacks) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.profile_delete_confirm_title)
            .setMessage(activity.getString(R.string.profile_delete_confirm_message, profile.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                callbacks.onDeleteProfile(profile.id)
                showManager(callbacks)
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showManager(callbacks) }
            .setOnCancelListener { showManager(callbacks) }
            .show()
    }

    /** A built-in profile cannot be deleted; say so, and put the reset one tap away. */
    private fun showBuiltInDeleteExplanation(profile: DashboardProfile, callbacks: Callbacks) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.profile_builtin_delete_title)
            .setMessage(activity.getString(R.string.profile_builtin_delete_message, profile.name))
            .setPositiveButton(R.string.profile_reset_builtin) { _, _ ->
                showResetConfirm(profile, onConfirm = {
                    callbacks.onResetProfile(profile.id)
                    showManager(callbacks)
                }, onCancelled = { showManager(callbacks) })
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showManager(callbacks) }
            .setOnCancelListener { showManager(callbacks) }
            .show()
    }

    // -- helpers --

    private fun setBackgroundFromAttribute(view: View, attribute: Int) {
        val value = TypedValue()
        if (activity.theme.resolveAttribute(attribute, value, true) && value.resourceId != 0) {
            view.setBackgroundResource(value.resourceId)
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val GROUP_PROFILES = 1
        const val GROUP_MANAGE = 2
        const val ITEM_MANAGE = 1000
    }
}
