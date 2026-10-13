using System.Globalization;
using System.Text;

namespace AliControlWindows.Protocol;

/// <summary>
/// Text rules for anything that travels in a notification.
///
/// The tablet's JSON reader deliberately handles only a small set of escapes,
/// so the desktop keeps the encoded form simple: the only characters that are
/// ever escaped are <c>"</c> and <c>\</c>, and everything else is written as
/// raw UTF-8. Control characters (which JSON would force into <c>\uXXXX</c>
/// escapes) are replaced with a space before encoding, and lone surrogates —
/// which are not representable in UTF-8 — become U+FFFD.
/// </summary>
public static class WireText
{
    /// <summary>Replaces control characters with a space and lone surrogates with U+FFFD.</summary>
    public static string Sanitize(string value)
    {
        if (string.IsNullOrEmpty(value)) return string.Empty;

        StringBuilder? builder = null;
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            char replacement = c;
            if (c < 0x20 || c == 0x7F)
            {
                replacement = ' ';
            }
            else if (char.IsHighSurrogate(c))
            {
                if (i + 1 < value.Length && char.IsLowSurrogate(value[i + 1]))
                {
                    builder?.Append(c).Append(value[i + 1]);
                    i++;
                    continue;
                }
                replacement = '�';
            }
            else if (char.IsLowSurrogate(c))
            {
                replacement = '�';
            }

            if (replacement != c && builder == null)
            {
                builder = new StringBuilder(value.Length);
                builder.Append(value, 0, i);
            }
            builder?.Append(replacement);
        }
        return builder?.ToString() ?? value;
    }

    /// <summary>
    /// Conservative byte cost of <paramref name="value"/> once JSON-escaped and
    /// UTF-8 encoded under this protocol's rules: quotes and backslashes double,
    /// control characters would cost a six-byte escape, everything else costs its
    /// UTF-8 length.
    /// </summary>
    public static int EscapedByteCount(string value)
    {
        int total = 0;
        foreach (Rune rune in value.EnumerateRunes())
        {
            int v = rune.Value;
            if (v == '"' || v == '\\') total += 2;
            else if (v < 0x20) total += 6;
            else total += rune.Utf8SequenceLength;
        }
        return total;
    }

    /// <summary>
    /// Splits <paramref name="available"/> bytes between several text fields so that
    /// no field exceeds its own cap, no field gets more than it needs, and the total
    /// never exceeds the budget. Fields that need little leave their unused share to
    /// the others ("water-filling"), so a short artist name does not waste room a
    /// long title could use. Allocation is deterministic: ties go to earlier fields.
    /// </summary>
    /// <param name="demands">The escaped byte length each field would like (before its cap).</param>
    /// <param name="caps">The per-field maximum.</param>
    public static int[] AllocateBudgets(int available, IReadOnlyList<int> demands, IReadOnlyList<int> caps)
    {
        int count = demands.Count;
        var wanted = new int[count];
        for (int i = 0; i < count; i++) wanted[i] = Math.Max(0, Math.Min(demands[i], caps[i]));

        var allocation = new int[count];
        int remaining = Math.Max(0, available);
        var active = Enumerable.Range(0, count).Where(i => wanted[i] > 0).ToList();

        while (active.Count > 0 && remaining > 0)
        {
            int share = remaining / active.Count;
            if (share == 0)
            {
                // Fewer bytes than fields: hand them out one at a time, earliest field first.
                foreach (int i in active)
                {
                    if (remaining == 0) break;
                    allocation[i]++;
                    remaining--;
                }
                break;
            }

            var satisfied = active.Where(i => wanted[i] - allocation[i] <= share).ToList();
            if (satisfied.Count == 0)
            {
                foreach (int i in active) allocation[i] += share;
                remaining -= share * active.Count;
                // Leftover bytes (remaining < active.Count) go to the earliest fields.
                foreach (int i in active)
                {
                    if (remaining == 0) break;
                    if (allocation[i] < wanted[i])
                    {
                        allocation[i]++;
                        remaining--;
                    }
                }
                break;
            }

            foreach (int i in satisfied)
            {
                remaining -= wanted[i] - allocation[i];
                allocation[i] = wanted[i];
                active.Remove(i);
            }
        }
        return allocation;
    }

    /// <summary>
    /// Truncates so the escaped UTF-8 encoding fits in <paramref name="maxEscapedBytes"/>,
    /// dropping whole grapheme clusters from the end. Working in grapheme
    /// clusters (not bytes, chars or code points) guarantees no mid-UTF-8
    /// truncation and no broken emoji sequences.
    /// </summary>
    public static string BoundedForWire(string? value, int maxEscapedBytes)
    {
        if (maxEscapedBytes <= 0 || string.IsNullOrEmpty(value)) return string.Empty;

        string clean = Sanitize(value);
        if (EscapedByteCount(clean) <= maxEscapedBytes) return clean;

        var result = new StringBuilder();
        int budget = maxEscapedBytes;
        TextElementEnumerator elements = StringInfo.GetTextElementEnumerator(clean);
        while (elements.MoveNext())
        {
            string element = (string)elements.Current;
            int cost = EscapedByteCount(element);
            if (cost > budget) break;
            budget -= cost;
            result.Append(element);
        }
        return result.ToString();
    }
}
