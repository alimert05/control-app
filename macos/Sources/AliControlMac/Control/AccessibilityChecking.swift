import ApplicationServices

/// Thin wrapper around `AXIsProcessTrusted()` so `ActionRegistry` is testable
/// without touching the real Accessibility TCC state.
///
/// This ONLY ever reads current trust — nothing in this app calls
/// `AXIsProcessTrustedWithOptions` with the prompt option, which would
/// trigger the system's "grant Accessibility" dialog. Permission is never
/// requested automatically; if it's missing, permission-gated actions simply
/// report an error (see `ActionRegistry.canExecute`).
protocol AccessibilityChecking {
    var isTrusted: Bool { get }
}

struct AccessibilityChecker: AccessibilityChecking {
    var isTrusted: Bool { AXIsProcessTrusted() }
}
