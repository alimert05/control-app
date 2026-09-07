import AppKit

/// Menu bar UI: an icon reflecting connection state, a few informational
/// lines (Bluetooth/tablet/advertising state), and actions (Open at Login,
/// Restart Advertising, Quit). No other UI, no windows — everything the app
/// needs to expose is in this one menu.
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {

    private var statusItem: NSStatusItem!
    private var peripheralManager: PeripheralManager!

    private var bluetoothItem: NSMenuItem!
    private var tabletItem: NSMenuItem!
    private var advertisingItem: NSMenuItem!
    private var loginItem: NSMenuItem!

    func applicationDidFinishLaunching(_ notification: Notification) {
        peripheralManager = PeripheralManager()

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)

        let menu = NSMenu()
        menu.delegate = self

        let titleItem = NSMenuItem(title: "Ali Control", action: nil, keyEquivalent: "")
        titleItem.isEnabled = false
        menu.addItem(titleItem)

        bluetoothItem = disabledInfoItem()
        tabletItem = disabledInfoItem()
        advertisingItem = disabledInfoItem()
        menu.addItem(bluetoothItem)
        menu.addItem(tabletItem)
        menu.addItem(advertisingItem)

        menu.addItem(.separator())

        loginItem = NSMenuItem(title: "Open at Login", action: #selector(toggleOpenAtLogin), keyEquivalent: "")
        loginItem.target = self
        menu.addItem(loginItem)

        let restartItem = NSMenuItem(title: "Restart Advertising", action: #selector(restartAdvertising), keyEquivalent: "")
        restartItem.target = self
        menu.addItem(restartItem)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(quit), keyEquivalent: "q"))

        statusItem.menu = menu

        peripheralManager.onStatusChanged = { [weak self] status in
            DispatchQueue.main.async { self?.render(status) }
        }
        render(peripheralManager.status)
    }

    /// Refreshes state that can change without a `PeripheralManager` status
    /// transition (currently just the Open at Login checkbox) right before
    /// the menu is shown — event-driven (AppKit calls this on click), not a
    /// timer.
    func menuWillOpen(_ menu: NSMenu) {
        loginItem.state = LoginItemController.isEnabled ? .on : .off
    }

    private func disabledInfoItem() -> NSMenuItem {
        let item = NSMenuItem(title: "", action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func render(_ status: PeripheralManager.Status) {
        let symbol: String
        let bluetoothText: String
        let tabletText: String
        let advertisingText: String

        switch status {
        case .poweredOff:
            symbol = "○"
            bluetoothText = "Bluetooth: Off"
            tabletText = "Tablet: Not connected"
            advertisingText = "Advertising: No"
        case .unauthorized:
            symbol = "⚠︎"
            bluetoothText = "Bluetooth: Permission needed"
            tabletText = "Tablet: Not connected"
            advertisingText = "Advertising: No"
        case .unsupported:
            symbol = "⚠︎"
            bluetoothText = "Bluetooth: Not supported"
            tabletText = "Tablet: Not connected"
            advertisingText = "Advertising: No"
        case .advertising:
            symbol = "◐"
            bluetoothText = "Bluetooth: On"
            tabletText = "Tablet: Not connected"
            advertisingText = "Advertising: Yes"
        case .subscribed:
            symbol = "●"
            bluetoothText = "Bluetooth: On"
            tabletText = peripheralManager.subscribedCentralIdentifier.map { "Tablet: Connected (\($0))" } ?? "Tablet: Connected"
            advertisingText = "Advertising: Yes"
        }

        statusItem.button?.title = symbol
        bluetoothItem.title = bluetoothText
        tabletItem.title = tabletText
        advertisingItem.title = advertisingText
    }

    @objc private func toggleOpenAtLogin() {
        LoginItemController.setEnabled(!LoginItemController.isEnabled)
        // menuWillOpen refreshes the checkbox next time the menu is shown;
        // update it immediately too since this click already closed the menu.
        loginItem.state = LoginItemController.isEnabled ? .on : .off
    }

    @objc private func restartAdvertising() {
        peripheralManager.restartAdvertising()
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}
