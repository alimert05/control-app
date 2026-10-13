package com.alicontrol.app.actions

/**
 * What the connected desktop agent needs in order to actually perform this
 * action. Purely descriptive on the Android side — Android never requests or
 * checks this itself (it doesn't run on the same machine); it only informs the
 * UI of what might fail, and why, on the desktop end. The desktop agent is what
 * actually performs (and independently re-validates) every action.
 *
 * Windows has no equivalent of a granted-permission gate. The one real
 * constraint is [INPUT_SIMULATION]: the action works by injecting keyboard
 * input into the focused window, which Windows refuses toward an elevated
 * (administrator) window because the agent runs as a standard user. Such an
 * action fails safely with an error acknowledgement; nothing is ever escalated.
 */
enum class PermissionRequirement {
    NONE,
    INPUT_SIMULATION
}
