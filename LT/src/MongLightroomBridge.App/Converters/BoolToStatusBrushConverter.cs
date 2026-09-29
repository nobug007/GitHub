using System;
using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;

namespace MongLightroomBridge.App.Converters;

/// <summary>
/// Converts a boolean "healthy/success" flag into a status color brush.
/// Used for the Lightroom connection indicator and per-file result dots.
/// </summary>
public sealed class BoolToStatusBrushConverter : IValueConverter
{
    public static readonly BoolToStatusBrushConverter Instance = new();

    private static readonly SolidColorBrush GoodBrush = new(Color.FromRgb(0x2E, 0xA0, 0x4A));
    private static readonly SolidColorBrush BadBrush = new(Color.FromRgb(0xC0, 0x39, 0x2B));

    static BoolToStatusBrushConverter()
    {
        GoodBrush.Freeze();
        BadBrush.Freeze();
    }

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var isGood = value is bool b && b;
        return isGood ? GoodBrush : BadBrush;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        throw new NotSupportedException();
    }
}
