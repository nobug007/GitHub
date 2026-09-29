using System;
using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;

namespace MongLightroomBridge.App.Converters;

/// <summary>Colors the per-file error/skip-reason text amber when Skipped is true (quality-filter
/// exclusion, not a real failure) and red otherwise (an actual export error).</summary>
public sealed class SkippedToWarnOrBadBrushConverter : IValueConverter
{
    public static readonly SkippedToWarnOrBadBrushConverter Instance = new();

    private static readonly SolidColorBrush BadBrush = new(Color.FromRgb(0xC0, 0x39, 0x2B));
    private static readonly SolidColorBrush WarnBrush = new(Color.FromRgb(0xD6, 0xA4, 0x19));

    static SkippedToWarnOrBadBrushConverter()
    {
        BadBrush.Freeze();
        WarnBrush.Freeze();
    }

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var isSkipped = value is bool b && b;
        return isSkipped ? WarnBrush : BadBrush;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        throw new NotSupportedException();
    }
}
