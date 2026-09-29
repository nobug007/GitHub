using MongLightroomBridge.Core.Models;
using MongLightroomBridge.Core.Profiles;
using Xunit;

namespace MongLightroomBridge.Tests;

public class BuiltInProfilesTests
{
    [Fact]
    public void AllFiveProfiles_AreDefined()
    {
        Assert.Equal(5, BuiltInProfiles.All.Count);
        Assert.Contains(AutoCorrectionProfileName.NaturalPortrait, BuiltInProfiles.All);
        Assert.Contains(AutoCorrectionProfileName.CafeWarm, BuiltInProfiles.All);
        Assert.Contains(AutoCorrectionProfileName.HighKeyClean, BuiltInProfiles.All);
        Assert.Contains(AutoCorrectionProfileName.ProductNeutral, BuiltInProfiles.All);
        Assert.Contains(AutoCorrectionProfileName.BlackAndWhiteSoft, BuiltInProfiles.All);
    }

    [Fact]
    public void CafeWarm_MatchesSpecSampleJobExactly()
    {
        // The spec's own sample job JSON (docs/job-schema.md / samples/jobs/sample-job.json) uses
        // CafeWarm's defaults verbatim - this test pins that relationship so the two can never drift.
        var defaults = BuiltInProfiles.GetDefaults(AutoCorrectionProfileName.CafeWarm);

        Assert.Equal(0.15, defaults.Exposure);
        Assert.Equal(12, defaults.Contrast);
        Assert.Equal(-35, defaults.Highlights);
        Assert.Equal(25, defaults.Shadows);
        Assert.Equal(5, defaults.Whites);
        Assert.Equal(-8, defaults.Blacks);
        Assert.Equal(300, defaults.Temperature);
        Assert.Equal(2, defaults.Tint);
        Assert.Equal(18, defaults.Vibrance);
        Assert.Equal(0, defaults.Saturation);
    }

    [Fact]
    public void ProductNeutral_DefaultsToSquareCrop()
    {
        var defaults = BuiltInProfiles.GetDefaults(AutoCorrectionProfileName.ProductNeutral);
        Assert.Equal("1:1", defaults.CropAspect);
    }

    [Fact]
    public void BlackAndWhiteSoft_ConvertsToGrayscaleWithFullDesaturation()
    {
        var defaults = BuiltInProfiles.GetDefaults(AutoCorrectionProfileName.BlackAndWhiteSoft);
        Assert.True(defaults.ConvertToGrayscale);
        Assert.Equal(-100, defaults.Saturation);
    }

    [Theory]
    [InlineData(AutoCorrectionProfileName.NaturalPortrait)]
    [InlineData(AutoCorrectionProfileName.CafeWarm)]
    [InlineData(AutoCorrectionProfileName.HighKeyClean)]
    [InlineData(AutoCorrectionProfileName.ProductNeutral)]
    [InlineData(AutoCorrectionProfileName.BlackAndWhiteSoft)]
    public void GetDefaults_ReturnsValuesWithinLightroomRanges(AutoCorrectionProfileName profile)
    {
        var d = BuiltInProfiles.GetDefaults(profile);

        Assert.InRange(d.Exposure, -5.0, 5.0);
        Assert.InRange(d.Contrast, -100, 100);
        Assert.InRange(d.Highlights, -100, 100);
        Assert.InRange(d.Shadows, -100, 100);
        Assert.InRange(d.Whites, -100, 100);
        Assert.InRange(d.Blacks, -100, 100);
        Assert.InRange(d.Vibrance, -100, 100);
        Assert.InRange(d.Saturation, -100, 100);
        // Temperature/Tint are deltas, not absolute Kelvin - see AdjustmentValues' doc comment.
        Assert.InRange(d.Temperature, -2000, 2000);
        Assert.InRange(d.Tint, -100, 100);
    }

    [Fact]
    public void GetDefaults_UnknownProfile_Throws()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            BuiltInProfiles.GetDefaults((AutoCorrectionProfileName)999));
    }
}
