namespace AliControlWindows.BLE;

/// <summary>
/// Stable BLE GATT identifiers for the Ali Control protocol.
///
/// These MUST match the tablet's <c>AliControlBleProfile.kt</c>. The service and
/// both characteristic UUIDs are unchanged since the original protocol and are
/// preserved on Windows: nothing about them is platform-specific.
/// </summary>
public static class AliControlBleProfile
{
    /// <summary>The Ali Control GATT service; the tablet's scan filter and service lookup use it.</summary>
    public static readonly Guid ServiceUuid = Guid.Parse("7eb4052b-fc5f-436f-ae69-c3c9e7e9809f");

    /// <summary>Written by the tablet (central) to send a command.</summary>
    public static readonly Guid CommandCharacteristicUuid = Guid.Parse("6544209f-88b6-432e-9db1-c672b7a31916");

    /// <summary>Notified by this peripheral with state, acknowledgement, telemetry, media and artwork messages.</summary>
    public static readonly Guid StateCharacteristicUuid = Guid.Parse("71dbc91d-eb99-4a50-b875-c3f045f4ea91");

    /// <summary>
    /// A compact (32-bit) alias service UUID used *only as a fallback* when Windows
    /// refuses to advertise the full 128-bit <see cref="ServiceUuid"/>.
    ///
    /// Some Bluetooth adapters/drivers reject a connectable GATT advertisement
    /// whose payload carries a 128-bit service UUID together with the device name
    /// (the advertisement status goes to <c>Aborted</c>), while a 16- or 32-bit UUID
    /// advertises fine. In that case the agent hosts the *same* characteristics
    /// under this alias service, and the tablet — which scans for both UUIDs and
    /// looks up whichever service it finds — connects exactly as before.
    /// See docs/WINDOWS_MIGRATION.md ("BLE advertising on Windows").
    /// </summary>
    public static readonly Guid DiscoveryServiceUuid = Guid.Parse("a11c0e01-0000-1000-8000-00805f9b34fb");

    /// <summary>Services to try, in order, when starting to advertise.</summary>
    public static readonly IReadOnlyList<Guid> AdvertisingCandidates = new[] { ServiceUuid, DiscoveryServiceUuid };
}
