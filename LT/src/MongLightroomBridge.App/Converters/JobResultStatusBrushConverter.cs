using System;
using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;
using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.App.Converters;

/// <summary>
/// Converts a whole JobFileResult into a status color: green for success, amber for
/// Skipped (pre-excluded by the quality filter - not a failure of the export pipeline itself),
/// red for any other failure. Bind the whole item ("{Binding}"), not just Success, so Skipped can
/// be told apart from a genuine export error.
/// </summary>
public sealed class JobResultStatusBrushConverter : IValueConverter
{
    public static readonly JobResultStatusBrushConverter Instance = new();

    private static readonly SolidColorBrush GoodBrush = new(Color.FromRgb(0x2E, 0xA0, 0x4A));
    private static readonly SolidColorBrush BadBrush = new(Color.FromRgb(0xC0, 0x39, 0x2B));
    private static readonly SolidColorBrush WarnBrush = new(Color.FromRgb(0xD6, 0xA4, 0x19));

    static JobResultStatusBrushConverter()
    {
        GoodBrush.Freeze();
        BadBrush.Freeze();
        WarnBrush.Freeze();
    }

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is not JobFileResult result)
        {
            return BadBrush;
        }

        if (result.Success)
        {
            return GoodBrush;
        }

        return result.Skipped ? WarnBrush : BadBrush;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        throw new NotSupportedException();
    }
}
