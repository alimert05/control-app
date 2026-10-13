namespace AliControlWindows.Actions;

/// <summary>
/// The complete set of Windows virtual-key codes this agent ever injects. A
/// closed enum (rather than free-form codes) keeps the keyboard surface as small
/// and auditable as the action list itself: nothing the tablet sends can name a key.
/// </summary>
public enum VirtualKey : ushort
{
    Tab = 0x09,
    Snapshot = 0x2C, // Print Screen
    C = 0x43,
    D = 0x44,
    S = 0x53,
    V = 0x56,
    Y = 0x59,
    Z = 0x5A,
    LeftWindows = 0x5B,
    Control = 0x11,

    VolumeMute = 0xAD,
    VolumeDown = 0xAE,
    VolumeUp = 0xAF,
    MediaNext = 0xB0,
    MediaPrevious = 0xB1,
    MediaPlayPause = 0xB3
}
