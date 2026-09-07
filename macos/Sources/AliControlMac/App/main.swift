import AppKit

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate

// Menu bar only: no Dock icon, no app switcher entry, no windows.
app.setActivationPolicy(.accessory)

app.run()
