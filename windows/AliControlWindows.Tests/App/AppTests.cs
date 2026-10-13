using AliControlWindows.App;
using AliControlWindows.State;
using AliControlWindows.Tests.TestSupport;

namespace AliControlWindows.Tests.App;

public class StartupRegistrationTests
{
    private const string Exe = @"C:\Program Files\Ali Control\AliControl.exe";
    private static readonly string Quoted = "\"" + Exe + "\"";

    [Fact]
    public void ItIsOffUntilTheUserTurnsItOn()
    {
        var registration = new StartupRegistration(new FakeStartupStore(), Exe);
        Assert.False(registration.IsEnabled);
    }

    [Fact]
    public void EnablingWritesOnlyTheQuotedExecutablePathUnderTheRunKey()
    {
        var store = new FakeStartupStore();
        var registration = new StartupRegistration(store, Exe);

        Assert.True(registration.SetEnabled(true));

        Assert.Equal(Quoted, store.RunValue); // quoted so a path with spaces launches correctly
        Assert.True(registration.IsEnabled);
    }

    [Fact]
    public void DisablingRemovesTheEntry()
    {
        var store = new FakeStartupStore { RunValue = Quoted };
        var registration = new StartupRegistration(store, Exe);

        Assert.True(registration.SetEnabled(false));

        Assert.Null(store.RunValue);
        Assert.False(registration.IsEnabled);
    }

    [Fact]
    public void ToggleIsIdempotent()
    {
        var store = new FakeStartupStore();
        var registration = new StartupRegistration(store, Exe);
        registration.SetEnabled(true);
        registration.SetEnabled(true);
        Assert.Equal(Quoted, store.RunValue);
        registration.SetEnabled(false);
        registration.SetEnabled(false);
        Assert.Null(store.RunValue);
    }

    [Fact]
    public void AnEntryPointingAtADifferentExecutableIsNotReportedAsEnabled()
    {
        var store = new FakeStartupStore { RunValue = "\"C:\\Elsewhere\\AliControl.exe\"" };
        Assert.False(new StartupRegistration(store, Exe).IsEnabled);
    }

    [Fact]
    public void PathComparisonIgnoresCase()
    {
        var store = new FakeStartupStore { RunValue = Quoted.ToUpperInvariant() };
        Assert.True(new StartupRegistration(store, Exe).IsEnabled);
    }

    [Theory]
    [InlineData(new byte[] { 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, true)]
    [InlineData(new byte[] { 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, true)]
    [InlineData(new byte[] { 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, false)]
    public void AnEntryDisabledInTaskManagerIsReportedAsDisabled(byte[] approval, bool expectedEnabled)
    {
        var store = new FakeStartupStore { RunValue = Quoted, Approved = approval };
        Assert.Equal(expectedEnabled, new StartupRegistration(store, Exe).IsEnabled);
    }

    [Fact]
    public void EnablingClearsAStaleDisabledFlagSoTheMenuMatchesReality()
    {
        var store = new FakeStartupStore { RunValue = Quoted, Approved = new byte[] { 0x03, 0, 0, 0 } };
        var registration = new StartupRegistration(store, Exe);
        Assert.False(registration.IsEnabled);

        Assert.True(registration.SetEnabled(true));

        Assert.Null(store.Approved);
        Assert.True(registration.IsEnabled);
    }

    [Theory]
    [InlineData(@"C:\Program Files\dotnet\dotnet.exe")]
    [InlineData(@"C:\Tools\testhost.exe")]
    [InlineData(null)]
    [InlineData("")]
    public void ItRefusesToRegisterAnythingButTheRealAgentExecutable(string? path)
    {
        var store = new FakeStartupStore();
        var registration = new StartupRegistration(store, path);

        Assert.False(registration.CanRegister);
        Assert.False(registration.SetEnabled(true)); // cannot reach the requested state
        Assert.Null(store.RunValue);                 // and wrote nothing
        Assert.False(registration.IsEnabled);
        Assert.True(registration.SetEnabled(false)); // "off" is trivially satisfied
    }

    [Fact]
    public void AMissingWritePermissionIsReportedNotThrown()
    {
        var store = new FakeStartupStore { ThrowOnWrite = true };
        var registration = new StartupRegistration(store, Exe);
        Assert.False(registration.SetEnabled(true));
        Assert.False(registration.IsEnabled);
    }

    [Fact]
    public void TheValueNameIsStableAndSpecific()
    {
        Assert.Equal("AliControl", StartupRegistration.ValueName);
    }
}

public class TrayStatusTextTests
{
    [Fact]
    public void BluetoothOffShowsTheOffIconAndNoConnection()
    {
        var text = TrayStatusText.From(new AgentStatus(BluetoothState.Off));
        Assert.Equal("Bluetooth: Off", text.Bluetooth);
        Assert.Equal("Tablet: Not connected", text.Tablet);
        Assert.Equal("Advertising: No", text.Advertising);
        Assert.Equal(TrayIconKind.Off, text.Icon);
        Assert.Contains("Bluetooth is off", text.Tooltip);
    }

    [Fact]
    public void AdvertisingAndWaitingShowsTheWaitingIcon()
    {
        var text = TrayStatusText.From(new AgentStatus(BluetoothState.On, AdvertisingState.Advertising, AdvertisingMode.FullServiceUuid));
        Assert.Equal("Bluetooth: On", text.Bluetooth);
        Assert.Equal("Advertising: Yes", text.Advertising);
        Assert.Equal(TrayIconKind.Waiting, text.Icon);
        Assert.Contains("Waiting for the tablet", text.Tooltip);
    }

    [Fact]
    public void CompactDiscoveryIsMentionedSoItCanBeDiagnosed()
    {
        var text = TrayStatusText.From(new AgentStatus(BluetoothState.On, AdvertisingState.Advertising, AdvertisingMode.CompactDiscoveryUuid));
        Assert.Equal("Advertising: Yes (compact discovery)", text.Advertising);
    }

    [Fact]
    public void ConnectedShowsTheTabletAddressAndTheConnectedIcon()
    {
        var text = TrayStatusText.From(new AgentStatus(BluetoothState.On, AdvertisingState.Advertising, AdvertisingMode.FullServiceUuid, "AA:BB:CC:DD:EE:FF"));
        Assert.Equal("Tablet: Connected (AA:BB:CC:DD:EE:FF)", text.Tablet);
        Assert.Equal(TrayIconKind.Connected, text.Icon);
        Assert.Contains("Tablet connected", text.Tooltip);
    }

    [Fact]
    public void AdvertisingFailureIsAProblemWithARecoveryHint()
    {
        var text = TrayStatusText.From(new AgentStatus(BluetoothState.On, AdvertisingState.Failed));
        Assert.Contains("Restart Advertising", text.Advertising);
        Assert.Equal(TrayIconKind.Problem, text.Icon);
    }

    [Theory]
    [InlineData(BluetoothState.NoAdapter, "No adapter")]
    [InlineData(BluetoothState.Unsupported, "cannot act as a peripheral")]
    public void AnUnusableBluetoothSetupIsAProblem(BluetoothState state, string expectedText)
    {
        var text = TrayStatusText.From(new AgentStatus(state));
        Assert.Contains(expectedText, text.Bluetooth);
        Assert.Equal(TrayIconKind.Problem, text.Icon);
    }

    [Fact]
    public void StartingIsNeitherAProblemNorConnected()
    {
        var text = TrayStatusText.From(new AgentStatus(BluetoothState.On, AdvertisingState.Starting));
        Assert.Equal("Advertising: Starting…", text.Advertising);
        Assert.Equal(TrayIconKind.Waiting, text.Icon);
    }

    [Fact]
    public void EveryPossibleStatusProducesAShortTooltipAndAnIcon()
    {
        foreach (BluetoothState bluetooth in Enum.GetValues<BluetoothState>())
        foreach (AdvertisingState advertising in Enum.GetValues<AdvertisingState>())
        foreach (AdvertisingMode mode in Enum.GetValues<AdvertisingMode>())
        foreach (string? tablet in new string?[] { null, "AA:BB:CC:DD:EE:FF" })
        {
            var text = TrayStatusText.From(new AgentStatus(bluetooth, advertising, mode, tablet));
            Assert.InRange(text.Tooltip.Length, 1, 127); // NotifyIcon.Text limit
            Assert.False(string.IsNullOrWhiteSpace(text.Bluetooth));
            Assert.False(string.IsNullOrWhiteSpace(text.Tablet));
            Assert.False(string.IsNullOrWhiteSpace(text.Advertising));
        }
    }
}

public class ClientLabelTests
{
    [Fact]
    public void ThePeerAddressIsExtractedFromAWindowsDeviceId()
    {
        Assert.Equal("AA:BB:CC:DD:EE:FF",
            ClientLabel.FromDeviceId("BluetoothLE#BluetoothLE5c:fb:3a:00:00:01-aa:bb:cc:dd:ee:ff"));
    }

    [Fact]
    public void AnUnrecognisedIdIsShortenedNotShownInFull()
    {
        Assert.Equal("0123456789AB", ClientLabel.FromDeviceId("some-very-long-opaque-identifier-0123456789AB"));
        Assert.Equal("short", ClientLabel.FromDeviceId("short"));
    }
}
