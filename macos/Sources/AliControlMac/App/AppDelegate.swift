import AppKit

/// Minimal menu bar UI: a status icon that reflects `PeripheralManager.Status`
/// for local debugging, and a Quit item. No other UI — the agent has nothing
/// for a user to configure.
final class AppDelegate: NSObject, NSApplicationDelegate {

    private var statusItem: NSStatusItem!
    private var peripheralManager: PeripheralManager!

    func applicationDidFinishLaunching(_ notification: Notification) {
        peripheralManager = PeripheralManager()

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let menu = NSMenu()
        let statusEntry = NSMenuItem(title: "Ali Control", action: nil, keyEquivalent: "")
        statusEntry.tag = statusMenuItemTag
        menu.addItem(statusEntry)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(quit), keyEquivalent: "q"))
        statusItem.menu = menu

        peripheralManager.onStatusChanged = { [weak self] status in
            DispatchQueue.main.async { self?.render(status) }
        }
        render(peripheralManager.status)
    }

    private func render(_ status: PeripheralManager.Status) {
        let (symbol, label): (String, String) = {
            switch status {
            case .poweredOff: return ("○", "Bluetooth is off")
            case .unauthorized: return ("⚠︎", "Bluetooth permission needed")
            case .unsupported: return ("⚠︎", "Bluetooth LE not supported")
            case .advertising: return ("◐", "Waiting for tablet…")
            case .subscribed: return ("●", "Connected")
            }
        }()
        statusItem.button?.title = symbol
        statusItem.menu?.item(withTag: statusMenuItemTag)?.title = "Ali Control — \(label)"
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    private let statusMenuItemTag = 1
}
