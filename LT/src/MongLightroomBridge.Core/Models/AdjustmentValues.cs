namespace MongLightroomBridge.Core.Models;

/// <summary>
/// Develop adjustments carried in a job's "adjustments" object. Values are the FINAL numbers the
/// plugin should apply - not deltas from a profile - because the Windows app already seeds the UI
/// sliders from the selected profile's defaults (see BuiltInProfiles) and any slider the user moves
/// simply overwrites that one field before the job is written. This keeps the wire format
/// unambiguous: the plugin never needs to know which profile produced these numbers.
///
/// Ranges follow what Lightroom Classic's Develop settings actually accept (Camera Raw "2012 process
/// version" keys - Exposure2012/Contrast2012/Highlights2012/Shadows2012/Whites2012/Blacks2012 - see
/// DevelopSettings.lua). Temperature/Tint are the one exception: Lightroom's own Temperature key is an
/// ABSOLUTE Kelvin value (roughly 2000-50000) and blindly setting an absolute value would fight the
/// photo's actual white balance, so this bridge treats Temperature/Tint as DELTAS the plugin adds to
/// each photo's existing (as-shot) Temperature/Tint before clamping - see TaskRunner.lua.
/// </summary>
public sealed class AdjustmentValues
{
    /// <summary>EV. Lightroom Exposure2012 range is roughly -5.0..5.0.</summary>
    public double Exposure { get; set; }

    /// <summary>-100..100 (Contrast2012).</summary>
    public double Contrast { get; set; }

    /// <summary>-100..100 (Highlights2012). Negative recovers blown highlights.</summary>
    public double Highlights { get; set; }

    /// <summary>-100..100 (Shadows2012). Positive lifts shadows.</summary>
    public double Shadows { get; set; }

    /// <summary>-100..100 (Whites2012).</summary>
    public double Whites { get; set; }

    /// <summary>-100..100 (Blacks2012).</summary>
    public double Blacks { get; set; }

    /// <summary>Delta Kelvin added to the photo's as-shot Temperature. Clamped to roughly -2000..2000.</summary>
    public double Temperature { get; set; }

    /// <summary>Delta added to the photo's as-shot Tint. Clamped to roughly -100..100.</summary>
    public double Tint { get; set; }

    /// <summary>-100..100 (Vibrance).</summary>
    public double Vibrance { get; set; }

    /// <summary>-100..100 (Saturation).</summary>
    public double Saturation { get; set; }

    /// <summary>
    /// Optional target aspect ratio for a centered crop, e.g. "3:2", "4:5", "1:1", "16:9". Null/empty
    /// means "leave the crop untouched" - see the crop/aspect limitation noted in README.
    /// </summary>
    public string? CropAspect { get; set; }

    /// <summary>
    /// Extension beyond the original spec's adjustment list: true converts the photo to black and
    /// white via Lightroom's real grayscale conversion (ConvertToGrayscale), not just Saturation = -100
    /// (which only produces a very desaturated color photo, not a true B&amp;W conversion with LR's
    /// black-and-white mixer). Only BlackAndWhiteSoft sets this by default; the UI does not expose a
    /// slider for it since the spec's 10 sliders don't include one.
    /// </summary>
    public bool ConvertToGrayscale { get; set; }

    public AdjustmentValues Clone() => new()
    {
        Exposure = Exposure,
        Contrast = Contrast,
        Highlights = Highlights,
        Shadows = Shadows,
        Whites = Whites,
        Blacks = Blacks,
        Temperature = Temperature,
        Tint = Tint,
        Vibrance = Vibrance,
        Saturation = Saturation,
        CropAspect = CropAspect,
        ConvertToGrayscale = ConvertToGrayscale,
    };
}
