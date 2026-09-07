import ServiceManagement

/// User-controllable "Open at Login", via the modern `SMAppService` API
/// (macOS 13 Ventura+ — already this package's minimum deployment target, so
/// no version guard is needed). This registers the app bundle itself to
/// relaunch at login: no shell script, no LaunchDaemon/LaunchAgent plist, no
/// elevated privileges, and nothing hidden — it's the same mechanism and the
/// same visible entry System Settings → General → Login Items shows for any
/// app using this API.
///
/// This only works because AliControlMac now ships as a real `.app` bundle
/// (see Scripts/build_app.sh) — `SMAppService.mainApp` requires one; it does
/// not apply to a bare `swift run`/`swift build` executable.
enum LoginItemController {

    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    /// Returns whether the change succeeded. Never called automatically by
    /// this app — only in direct response to the user toggling the "Open at
    /// Login" menu item.
    @discardableResult
    static func setEnabled(_ enabled: Bool) -> Bool {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            return true
        } catch {
            return false
        }
    }
}
