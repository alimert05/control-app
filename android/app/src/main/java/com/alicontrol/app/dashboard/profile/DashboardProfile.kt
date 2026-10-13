package com.alicontrol.app.dashboard.profile

import com.alicontrol.app.dashboard.Dashboard
import java.util.UUID

/**
 * A named, independently customizable dashboard — one "mode" of the tablet
 * (Default, Dev, Game, Media, or one the user created):
 *
 * ```
 * DashboardProfile
 * └── Dashboard            (the existing model — not duplicated or wrapped)
 *     └── DashboardPage
 *         └── DashboardWidget
 * ```
 *
 * A profile owns exactly one [Dashboard], and nothing else of the app's state:
 * connection, desktop identity, capabilities, telemetry, media and artwork all
 * stay in the application-scoped `DesktopStateStore`. A profile is therefore
 * pure *presentation* — which widgets exist and where — and switching profiles
 * can never disturb anything live.
 *
 * - [id] is the stable identity: it is what persistence, the startup policy and
 *   every edit key off, and it never changes — renaming only touches [name].
 *   Built-in ids are the fixed [ProfileIds] constants; custom ids are generated
 *   and are not derived from the name.
 * - [order] (not list position) is the display order, rewritten to 0..n-1 after
 *   any change, mirroring `DashboardPage.order`.
 * - [builtIn] is true exactly for the shipped ids. Built-in profiles can be
 *   renamed, reordered and reset to their template, but never deleted.
 *
 * Immutable like the rest of the dashboard model: every edit produces a new
 * value (see [ProfileEditor]), so a profile that is not being edited cannot
 * change underneath you.
 */
data class DashboardProfile(
    val id: String,
    val name: String,
    val order: Int,
    val builtIn: Boolean,
    val dashboard: Dashboard
) {

    /**
     * Whether this profile is safe to render and persist: it has an identity and a
     * name, its built-in flag agrees with its id, and its dashboard satisfies every
     * structural rule (6x4 grid, no overlap, valid sizes, unique ids).
     */
    fun isValid(): Boolean =
        id.isNotBlank() && name.isNotBlank() && builtIn == ProfileIds.isBuiltIn(id) && dashboard.isValid()
}

/** The stable profile identifiers. Display names are separate data — see [BuiltInProfiles.displayName]. */
object ProfileIds {
    const val DEFAULT = "default"
    const val DEV = "dev"
    const val GAME = "game"
    const val MEDIA = "media"

    /** The shipped profiles in their canonical (initial) order. */
    val BUILT_IN: List<String> = listOf(DEFAULT, DEV, GAME, MEDIA)

    private const val CUSTOM_PREFIX = "custom_"
    private const val GENERATED_LENGTH = 12

    fun isBuiltIn(id: String): Boolean = id in BUILT_IN

    /**
     * A fresh custom profile id such as `custom_7f9127ab04c3`. Random, never
     * derived from the profile's name, so renaming — or two profiles sharing a
     * name — can never affect identity.
     */
    fun generate(): String = CUSTOM_PREFIX + UUID.randomUUID().toString().replace("-", "").take(GENERATED_LENGTH)
}
