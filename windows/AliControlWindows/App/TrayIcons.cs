using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace AliControlWindows.App;

/// <summary>
/// The four tray icons, drawn at runtime with GDI+ (no image assets to ship) at the
/// system's current small-icon size: hollow grey ring = Bluetooth off, half-filled
/// blue = waiting for the tablet, solid green = connected, amber with a bar = needs
/// attention. They are created once and reused, so there is no per-update GDI churn.
/// </summary>
internal sealed class TrayIcons : IDisposable
{
    private readonly Dictionary<TrayIconKind, Icon> _icons = new();

    public TrayIcons(int size)
    {
        foreach (TrayIconKind kind in Enum.GetValues<TrayIconKind>())
        {
            _icons[kind] = Render(kind, size);
        }
    }

    public Icon Get(TrayIconKind kind) => _icons[kind];

    private static Icon Render(TrayIconKind kind, int size)
    {
        using var bitmap = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using (Graphics g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            float margin = Math.Max(1.5f, size / 8f);
            var bounds = new RectangleF(margin, margin, size - 2 * margin, size - 2 * margin);
            float stroke = Math.Max(1.5f, size / 10f);

            switch (kind)
            {
                case TrayIconKind.Off:
                    using (var pen = new Pen(Color.FromArgb(150, 150, 155), stroke)) g.DrawEllipse(pen, bounds);
                    break;

                case TrayIconKind.Waiting:
                {
                    var blue = Color.FromArgb(77, 163, 255);
                    using (var pen = new Pen(blue, stroke)) g.DrawEllipse(pen, bounds);
                    using (var brush = new SolidBrush(blue)) g.FillPie(brush, Rectangle.Round(bounds), 90, 180);
                    break;
                }

                case TrayIconKind.Connected:
                    using (var brush = new SolidBrush(Color.FromArgb(70, 200, 120))) g.FillEllipse(brush, bounds);
                    break;

                case TrayIconKind.Problem:
                {
                    var amber = Color.FromArgb(245, 170, 40);
                    using (var brush = new SolidBrush(amber)) g.FillEllipse(brush, bounds);
                    using var mark = new Pen(Color.FromArgb(40, 30, 10), Math.Max(1.5f, size / 9f)) { StartCap = LineCap.Round, EndCap = LineCap.Round };
                    float cx = size / 2f;
                    g.DrawLine(mark, cx, size * 0.28f, cx, size * 0.58f);
                    g.DrawLine(mark, cx, size * 0.72f, cx, size * 0.74f);
                    break;
                }
            }
        }

        IntPtr handle = bitmap.GetHicon();
        try
        {
            using Icon temporary = Icon.FromHandle(handle);
            return (Icon)temporary.Clone(); // detach from the native handle so it can be destroyed
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    public void Dispose()
    {
        foreach (Icon icon in _icons.Values) icon.Dispose();
        _icons.Clear();
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr handle);
}
