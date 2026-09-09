import Foundation

/// Why a scripting call didn't produce a result.
enum AppleScriptFailure: Error, Equatable {
    /// The user denied (or has not granted) Automation access for this app.
    case notPermitted
    /// The target application isn't running, or refused/errored the event.
    case unavailable
}

/// Runs AppleScript against a named local application and reports *why* it
/// failed, which is the part that matters here.
///
/// ## Automation permission
///
/// The first time this app sends an Apple event to Music or Spotify, macOS
/// shows the one-time "AliControlMac wants to control …" prompt. If the user
/// declines, every later event returns `errAEEventNotPermitted` (-1743).
/// `AppleScriptRunner` reports that distinctly so `ScriptedMediaProvider` can
/// **stop asking permanently for this process** — TASK-009 requires not
/// re-prompting, and re-issuing the event on every 2-second tick would do
/// exactly that. Telemetry is entirely unaffected: CPU/RAM come from Mach
/// calls that need no permission at all.
///
/// ## Threading
///
/// `NSAppleScript` is not thread-safe. Every call here is made from
/// `LiveStatePublisher`'s single dedicated serial queue — never concurrently,
/// and deliberately *not* on the main queue, so a wedged media application
/// cannot stall CoreBluetooth's delegate callbacks and delay command
/// acknowledgements.
protocol AppleScriptRunning {
    func run(_ source: String) -> Result<String, AppleScriptFailure>

    /// Same execution path as [run], but reads the reply as raw bytes rather
    /// than text — what artwork retrieval needs (`data of artwork 1 of
    /// current track` returns picture data, not a string).
    func runData(_ source: String) -> Result<Data, AppleScriptFailure>
}

struct AppleScriptRunner: AppleScriptRunning {

    /// `errAEEventNotPermitted` — Automation denied for the target app.
    static let notPermittedErrorCode = -1743

    func run(_ source: String) -> Result<String, AppleScriptFailure> {
        switch execute(source) {
        case .failure(let failure): return .failure(failure)
        case .success(let descriptor):
            guard let value = descriptor.stringValue else { return .failure(.unavailable) }
            return .success(value)
        }
    }

    func runData(_ source: String) -> Result<Data, AppleScriptFailure> {
        switch execute(source) {
        case .failure(let failure): return .failure(failure)
        case .success(let descriptor):
            guard let value = descriptor.data as Data?, !value.isEmpty else { return .failure(.unavailable) }
            return .success(value)
        }
    }

    private func execute(_ source: String) -> Result<NSAppleEventDescriptor, AppleScriptFailure> {
        guard let script = NSAppleScript(source: source) else { return .failure(.unavailable) }
        var errorInfo: NSDictionary?
        let descriptor = script.executeAndReturnError(&errorInfo)

        if let errorInfo {
            let code = (errorInfo[NSAppleScript.errorNumber] as? Int) ?? 0
            return .failure(code == Self.notPermittedErrorCode ? .notPermitted : .unavailable)
        }
        return .success(descriptor)
    }
}
