using System.Text;

namespace AliControlWindows.Protocol;

/// <summary>
/// A tiny hand-rolled JSON writer for the protocol's flat objects.
///
/// It exists (rather than System.Text.Json's writer) to pin the exact wire
/// form the tablet's dependency-free parser expects: compact output, field
/// order as written, only <c>"</c> and <c>\</c> escaped, non-ASCII emitted as
/// raw UTF-8, forward slashes left alone. Absent fields are simply not written.
/// </summary>
internal sealed class JsonWire
{
    private readonly StringBuilder _builder = new();
    private bool _first = true;

    public JsonWire()
    {
        _builder.Append('{');
    }

    public JsonWire String(string name, string? value)
    {
        if (value == null) return this;
        Key(name);
        AppendQuoted(_builder, value);
        return this;
    }

    public JsonWire Int(string name, int? value)
    {
        if (value == null) return this;
        Key(name);
        _builder.Append(value.Value.ToString(System.Globalization.CultureInfo.InvariantCulture));
        return this;
    }

    public JsonWire Bool(string name, bool? value)
    {
        if (value == null) return this;
        Key(name);
        _builder.Append(value.Value ? "true" : "false");
        return this;
    }

    public JsonWire StringArray(string name, IEnumerable<string> values)
    {
        Key(name);
        _builder.Append('[');
        bool first = true;
        foreach (string value in values)
        {
            if (!first) _builder.Append(',');
            first = false;
            AppendQuoted(_builder, value);
        }
        _builder.Append(']');
        return this;
    }

    public override string ToString() => _builder.ToString() + "}";

    public byte[] ToUtf8() => new UTF8Encoding(false).GetBytes(ToString());

    private void Key(string name)
    {
        if (!_first) _builder.Append(',');
        _first = false;
        AppendQuoted(_builder, name);
        _builder.Append(':');
    }

    internal static void AppendQuoted(StringBuilder builder, string value)
    {
        builder.Append('"');
        foreach (char c in value)
        {
            switch (c)
            {
                case '"': builder.Append("\\\""); break;
                case '\\': builder.Append("\\\\"); break;
                case < ' ': builder.Append("\\u").Append(((int)c).ToString("x4")); break;
                default: builder.Append(c); break;
            }
        }
        builder.Append('"');
    }
}
