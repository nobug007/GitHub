namespace MongLightroomBridge.Core.Models;

/// <summary>
/// Built-in auto-correction profiles (spec section D). The string value is what travels in the
/// job JSON's "profile" field and must match a key in BuiltInProfiles.Defaults.
/// </summary>
public enum AutoCorrectionProfileName
{
    NaturalPortrait,
    CafeWarm,
    HighKeyClean,
    ProductNeutral,
    BlackAndWhiteSoft,
}
