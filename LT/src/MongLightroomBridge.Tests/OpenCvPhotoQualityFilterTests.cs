using Microsoft.Extensions.Logging.Abstractions;
using MongLightroomBridge.Core.Interfaces;
using MongLightroomBridge.Core.Models;
using MongLightroomBridge.Infrastructure.Quality;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;
using Xunit;

namespace MongLightroomBridge.Tests;

/// <summary>
/// Covers the Windows-app-only quality pre-filter (blur / eyes-uncertain exclusion) added on top
/// of the original spec. The blur check is deterministic and tested with synthetic images built
/// in-test (a checkerboard = sharp, a flat single color = maximally blurry - Laplacian variance is
/// ~0 for a flat image by construction, no threshold-tuning guesswork needed). The eye check is
/// inherently best-effort (see EyeOpennessResult's doc comment), so it is only tested for the one
/// thing that must always hold: a photo with no detectable face must come back Uncertain, never a
/// false-confidence "open" or - especially - any kind of "closed" claim (no such value even exists
/// on the enum).
/// </summary>
public class OpenCvPhotoQualityFilterTests
{
    [Fact]
    public void Assess_WithMissingInputFolder_ReturnsEmptyList()
    {
        var filter = new OpenCvPhotoQualityFilter(NullLogger<OpenCvPhotoQualityFilter>.Instance);
        var options = new PhotoQualityFilterOptions { ExcludeBlurry = true };

        var results = filter.Assess(
            Path.Combine(Path.GetTempPath(), "mlb-quality-does-not-exist-" + Guid.NewGuid()),
            options);

        Assert.Empty(results);
    }

    [Fact]
    public void Assess_NeitherOptionEnabled_NeverExcludesAnything()
    {
        var filter = new OpenCvPhotoQualityFilter(NullLogger<OpenCvPhotoQualityFilter>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-quality-off-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);
        WriteFlatJpeg(Path.Combine(tempIn, "flat.jpg"));

        try
        {
            var results = filter.Assess(tempIn, new PhotoQualityFilterOptions());

            Assert.Single(results);
            Assert.False(results[0].Excluded);
            Assert.Equal(EyeOpennessResult.NotChecked, results[0].EyeOpenness);
        }
        finally
        {
            Directory.Delete(tempIn, recursive: true);
        }
    }

    [Fact]
    public void Assess_FlatImage_HasLowerSharpnessThanCheckerboard_AndOnlyFlatIsExcluded()
    {
        var filter = new OpenCvPhotoQualityFilter(NullLogger<OpenCvPhotoQualityFilter>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-quality-blur-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);
        WriteFlatJpeg(Path.Combine(tempIn, "a_flat.jpg"));
        WriteCheckerboardJpeg(Path.Combine(tempIn, "b_checkerboard.jpg"));

        try
        {
            // A flat image's Laplacian variance is ~0 by construction (no edges anywhere), so any
            // positive threshold reliably excludes it while leaving a real high-frequency pattern in.
            var options = new PhotoQualityFilterOptions { ExcludeBlurry = true, BlurThreshold = 50.0 };
            var results = filter.Assess(tempIn, options);

            Assert.Equal(2, results.Count);
            var flat = results.Single(r => r.FileName == "a_flat.jpg");
            var checkerboard = results.Single(r => r.FileName == "b_checkerboard.jpg");

            Assert.True(flat.IsBlurry);
            Assert.True(flat.Excluded);
            Assert.NotNull(flat.ExclusionReason);

            Assert.False(checkerboard.IsBlurry);
            Assert.False(checkerboard.Excluded);
            Assert.True(checkerboard.SharpnessScore > flat.SharpnessScore);
        }
        finally
        {
            Directory.Delete(tempIn, recursive: true);
        }
    }

    [Fact]
    public void Assess_ImageWithNoFace_ReportsUncertain_NeverClosedOrFalseOpen()
    {
        var filter = new OpenCvPhotoQualityFilter(NullLogger<OpenCvPhotoQualityFilter>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-quality-eyes-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);
        // No face anywhere in a flat image - the detector must come back Uncertain, not a
        // false-confidence OpenDetected, and there is no Closed value to even accidentally return.
        WriteFlatJpeg(Path.Combine(tempIn, "no_face.jpg"));

        try
        {
            var options = new PhotoQualityFilterOptions { ExcludeUncertainEyes = true };
            var results = filter.Assess(tempIn, options);

            Assert.Single(results);
            Assert.Equal(EyeOpennessResult.Uncertain, results[0].EyeOpenness);
            Assert.True(results[0].Excluded);
            Assert.NotNull(results[0].ExclusionReason);
        }
        finally
        {
            Directory.Delete(tempIn, recursive: true);
        }
    }

    private static void WriteFlatJpeg(string path, int size = 256)
    {
        using var image = new Image<Rgba32>(size, size, new Rgba32(128, 128, 128));
        image.SaveAsJpeg(path);
    }

    private static void WriteCheckerboardJpeg(string path, int size = 256, int cell = 4)
    {
        using var image = new Image<Rgba32>(size, size);
        var white = new Rgba32(255, 255, 255);
        var black = new Rgba32(0, 0, 0);
        for (var y = 0; y < size; y++)
        {
            for (var x = 0; x < size; x++)
            {
                image[x, y] = ((x / cell) + (y / cell)) % 2 == 0 ? white : black;
            }
        }
        image.SaveAsJpeg(path);
    }
}
