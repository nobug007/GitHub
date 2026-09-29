using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Profiles;

/// <summary>
/// Default AdjustmentValues for each built-in profile (spec section D). These are the values the
/// Windows app's sliders start at when a profile is selected; the job JSON sent to the plugin always
/// carries the (possibly user-edited) final numbers, never the profile name's semantics - see
/// AdjustmentValues' doc comment.
///
/// CafeWarm's numbers intentionally match the spec's own sample job JSON verbatim.
/// </summary>
public static class BuiltInProfiles
{
    public static AdjustmentValues GetDefaults(AutoCorrectionProfileName profile) => profile switch
    {
        AutoCorrectionProfileName.NaturalPortrait => new AdjustmentValues
        {
            Exposure = 0.05,
            Contrast = 6,
            Highlights = -15,
            Shadows = 12,
            Whites = 3,
            Blacks = -3,
            Temperature = 80,
            Tint = 1,
            Vibrance = 10,
            Saturation = 0,
        },
        AutoCorrectionProfileName.CafeWarm => new AdjustmentValues
        {
            Exposure = 0.15,
            Contrast = 12,
            Highlights = -35,
            Shadows = 25,
            Whites = 5,
            Blacks = -8,
            Temperature = 300,
            Tint = 2,
            Vibrance = 18,
            Saturation = 0,
        },
        AutoCorrectionProfileName.HighKeyClean => new AdjustmentValues
        {
            Exposure = 0.4,
            Contrast = -8,
            Highlights = -10,
            Shadows = 35,
            Whites = 15,
            Blacks = 10,
            Temperature = -50,
            Tint = 0,
            Vibrance = 5,
            Saturation = -5,
        },
        AutoCorrectionProfileName.ProductNeutral => new AdjustmentValues
        {
            Exposure = 0.1,
            Contrast = 15,
            Highlights = -20,
            Shadows = 15,
            Whites = 8,
            Blacks = -5,
            Temperature = 0,
            Tint = 0,
            Vibrance = 8,
            Saturation = 5,
            CropAspect = "1:1",
        },
        AutoCorrectionProfileName.BlackAndWhiteSoft => new AdjustmentValues
        {
            Exposure = 0.1,
            Contrast = -5,
            Highlights = -20,
            Shadows = 20,
            Whites = 5,
            Blacks = 8,
            Temperature = 0,
            Tint = 0,
            Vibrance = 0,
            Saturation = -100,
            ConvertToGrayscale = true,
        },
        _ => throw new ArgumentOutOfRangeException(nameof(profile), profile, null),
    };

    public static IReadOnlyList<AutoCorrectionProfileName> All { get; } =
        Enum.GetValues<AutoCorrectionProfileName>();
}
