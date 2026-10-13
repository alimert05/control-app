using AliControlWindows.Protocol;

namespace AliControlWindows.Actions;

/// <summary>
/// Turns a decoded <see cref="ProtocolMessage"/> command into an executed action and
/// produces the state acknowledgement to send back.
///
/// <code>
/// BLE peripheral → ProtocolMessage.Decode → CommandDispatcher (this)
///                → ActionRegistry (id → handler lookup) → controllers
/// </code>
///
/// This is the only place that turns a protocol action ID into Windows behaviour. The
/// BLE layer never touches the registry or controllers, and nothing here touches
/// Bluetooth. Any action ID that is not in the registry is rejected with an error
/// state and never executed. A handler that fails — or even throws — produces an
/// error acknowledgement for that command only; it can never affect the next one.
/// </summary>
public sealed class CommandDispatcher
{
    private readonly ActionRegistry _registry;
    private readonly Action<string>? _log;

    public CommandDispatcher(ActionRegistry registry, Action<string>? log = null)
    {
        _registry = registry;
        _log = log;
    }

    public ProtocolMessage Dispatch(ProtocolMessage message)
    {
        if (message.Type != ProtocolMessage.TypeCommand)
        {
            return ProtocolMessage.State(ProtocolMessage.StatusError, Echo(message.Action));
        }
        if (message.Action is not { } actionId || _registry.Find(actionId) is not { } handler)
        {
            return ProtocolMessage.State(ProtocolMessage.StatusError, Echo(message.Action));
        }

        bool succeeded;
        try
        {
            succeeded = handler.Execute();
        }
        catch (Exception e)
        {
            _log?.Invoke($"action '{actionId}' threw {e.GetType().Name}");
            succeeded = false;
        }
        return ProtocolMessage.State(succeeded ? ProtocolMessage.StatusOk : ProtocolMessage.StatusError, actionId);
    }

    /// <summary>
    /// An unrecognised action is echoed back (so the tablet can tell which command
    /// failed) but bounded, so an oversized or odd string can never push the
    /// acknowledgement past the notification budget.
    /// </summary>
    private static string? Echo(string? action) =>
        action == null ? null : WireText.BoundedForWire(action, MaxEchoedActionBytes);

    private const int MaxEchoedActionBytes = 40;
}
