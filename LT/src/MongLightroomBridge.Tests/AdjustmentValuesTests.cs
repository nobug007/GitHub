using MongLightroomBridge.Core.Models;
using Xunit;

namespace MongLightroomBridge.Tests;

public class AdjustmentValuesTests
{
    [Fact]
    public void Clone_ProducesIndependentCopyWithSameValues()
    {
        var original = new AdjustmentValues
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
            CropAspect = "3:2",
            ConvertToGrayscale = false,
        };

        var clone = original.Clone();

        Assert.NotSame(original, clone);
        Assert.Equal(original.Exposure, clone.Exposure);
        Assert.Equal(original.Contrast, clone.Contrast);
        Assert.Equal(original.Highlights, clone.Highlights);
        Assert.Equal(original.Shadows, clone.Shadows);
        Assert.Equal(original.Whites, clone.Whites);
        Assert.Equal(original.Blacks, clone.Blacks);
        Assert.Equal(original.Temperature, clone.Temperature);
        Assert.Equal(original.Tint, clone.Tint);
        Assert.Equal(original.Vibrance, clone.Vibrance);
        Assert.Equal(original.Saturation, clone.Saturation);
        Assert.Equal(original.CropAspect, clone.CropAspect);
        Assert.Equal(original.ConvertToGrayscale, clone.ConvertToGrayscale);

        // Mutating the clone must never affect the original - callers (e.g. the slider-override path
        // that starts from a profile's defaults) rely on this.
        clone.Exposure = 99;
        clone.CropAspect = "1:1";
        Assert.Equal(0.15, original.Exposure);
        Assert.Equal("3:2", original.CropAspect);
    }

    [Fact]
    public void Clone_WithNullCropAspect_StaysNull()
    {
        var original = new AdjustmentValues { CropAspect = null };
        var clone = original.Clone();
        Assert.Null(clone.CropAspect);
    }
}
