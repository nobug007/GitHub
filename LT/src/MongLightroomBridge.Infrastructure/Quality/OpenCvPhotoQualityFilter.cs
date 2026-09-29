using Microsoft.Extensions.Logging;
using MongLightroomBridge.Core.Interfaces;
using MongLightroomBridge.Core.Models;
using OpenCvSharp;

namespace MongLightroomBridge.Infrastructure.Quality;

/// <summary>
/// IPhotoQualityFilter implementation backed by OpenCvSharp. Entirely local/offline - no network
/// calls, no AI model beyond the two stock Haar cascades shipped in Cascades/ (both from OpenCV's
/// own data/haarcascades, unmodified). Two independent checks, each individually opt-in via
/// PhotoQualityFilterOptions:
///   - Blur: variance of the Laplacian of a downscaled grayscale copy of the image. This is a
///     standard, well-documented focus metric (see e.g. Pech-Pacheco et al. 2000) - a sharp image
///     has strong edges in many directions, which the Laplacian responds to strongly; a blurred
///     image's edges are soft, so the Laplacian response - and its variance across the image - is
///     low.
///   - Eyes: best-effort only, see EyeOpennessResult's doc comment. Detects the largest face with
///     haarcascade_frontalface_default, then looks for an eye-like feature with
///     haarcascade_eye_tree_eyeglasses (chosen specifically because it copes better with glasses
///     than the plain eye cascade) inside the upper half of that face region. This can only ever
///     positively confirm "found an eye-like feature" - it is NOT a closed-eye detector, and is
///     never allowed to produce a "closed" verdict; failure to detect always maps to Uncertain.
/// </summary>
public sealed class OpenCvPhotoQualityFilter : IPhotoQualityFilter
{
    private static readonly string[] SupportedExtensions = { ".jpg", ".jpeg", ".tif", ".tiff", ".png" };

    // Same folder the .csproj copies the cascades into next to the built DLL (see
    // MongLightroomBridge.Infrastructure.csproj's Content items).
    private static readonly string CascadesFolder =
        Path.Combine(AppContext.BaseDirectory, "Cascades");

    private readonly ILogger<OpenCvPhotoQualityFilter> _logger;

    public OpenCvPhotoQualityFilter(ILogger<OpenCvPhotoQualityFilter> logger)
    {
        _logger = logger;
    }

    public IReadOnlyList<PhotoQualityAssessment> Assess(string inputFolder, PhotoQualityFilterOptions options)
    {
        var results = new List<PhotoQualityAssessment>();

        if (!Directory.Exists(inputFolder))
        {
            return results;
        }

        var files = Directory.EnumerateFiles(inputFolder)
            .Where(f => SupportedExtensions.Contains(Path.GetExtension(f).ToLowerInvariant()))
            .OrderBy(f => f, StringComparer.OrdinalIgnoreCase)
            .ToList();

        if (files.Count == 0)
        {
            return results;
        }

        using var faceCascade = TryLoadCascade("haarcascade_frontalface_default.xml");
        using var eyeCascade = options.ExcludeUncertainEyes
            ? TryLoadCascade("haarcascade_eye_tree_eyeglasses.xml")
            : null;

        foreach (var file in files)
        {
            var fileName = Path.GetFileName(file);
            var assessment = new PhotoQualityAssessment { FileName = fileName };

            try
            {
                using var mat = Cv2.ImRead(file, ImreadModes.Color);
                if (mat.Empty())
                {
                    _logger.LogWarning("Quality filter: could not decode {File}, skipping assessment", fileName);
                    results.Add(assessment);
                    continue;
                }

                using var gray = new Mat();
                Cv2.CvtColor(mat, gray, ColorConversionCodes.BGR2GRAY);

                // Downscale to a fixed-ish width first: the Laplacian-variance metric's raw scale depends
                // on resolution, and our real photos are all much larger than the ~1600px this is tuned at.
                using var scaled = DownscaleForAnalysis(gray, maxWidth: 1600);

                assessment.SharpnessScore = ComputeLaplacianVariance(scaled);

                if (options.ExcludeBlurry && assessment.SharpnessScore < options.BlurThreshold)
                {
                    assessment.IsBlurry = true;
                    assessment.Excluded = true;
                    assessment.ExclusionReason =
                        $"Blurry (sharpness {assessment.SharpnessScore:F1} < threshold {options.BlurThreshold:F1})";
                }

                if (options.ExcludeUncertainEyes)
                {
                    assessment.EyeOpenness = DetectEyeOpenness(gray, faceCascade, eyeCascade);
                    if (assessment.EyeOpenness == EyeOpennessResult.Uncertain && !assessment.Excluded)
                    {
                        assessment.Excluded = true;
                        assessment.ExclusionReason =
                            "Could not confirm an open eye (no face/eye feature detected - may just be glare, angle, or glasses, not necessarily closed eyes)";
                    }
                }
            }
            catch (Exception ex)
            {
                // Never let one bad/corrupt file abort the whole assessment pass - just leave it
                // un-excluded (NotChecked / not blurry) and let the user see it in the UI as usual.
                _logger.LogWarning(ex, "Quality filter: assessment failed for {File}", fileName);
            }

            results.Add(assessment);
        }

        return results;
    }

    private static Mat DownscaleForAnalysis(Mat gray, int maxWidth)
    {
        if (gray.Width <= maxWidth)
        {
            return gray.Clone();
        }

        var scale = (double)maxWidth / gray.Width;
        var size = new Size(maxWidth, (int)Math.Round(gray.Height * scale));
        var scaled = new Mat();
        Cv2.Resize(gray, scaled, size, interpolation: InterpolationFlags.Area);
        return scaled;
    }

    private static double ComputeLaplacianVariance(Mat gray)
    {
        using var laplacian = new Mat();
        Cv2.Laplacian(gray, laplacian, MatType.CV_64F);
        Cv2.MeanStdDev(laplacian, out _, out var stdDev);
        var sigma = stdDev.Val0;
        return sigma * sigma;
    }

    private EyeOpennessResult DetectEyeOpenness(Mat gray, CascadeClassifier? faceCascade, CascadeClassifier? eyeCascade)
    {
        if (faceCascade is null || eyeCascade is null)
        {
            // Cascade file failed to load (see TryLoadCascade) - can't check, and we must never
            // silently claim OpenDetected when we didn't actually look.
            return EyeOpennessResult.Uncertain;
        }

        var faces = faceCascade.DetectMultiScale(
            gray, scaleFactor: 1.1, minNeighbors: 5, flags: HaarDetectionTypes.ScaleImage,
            minSize: new Size(gray.Width / 8, gray.Height / 8));

        if (faces.Length == 0)
        {
            return EyeOpennessResult.Uncertain;
        }

        // Largest detected face = most likely the main subject.
        var face = faces.OrderByDescending(f => f.Width * f.Height).First();

        // Eyes live in roughly the upper half of a detected face box.
        var upperHalf = new Rect(face.X, face.Y, face.Width, face.Height / 2);
        upperHalf = upperHalf.Intersect(new Rect(0, 0, gray.Width, gray.Height));
        if (upperHalf.Width <= 0 || upperHalf.Height <= 0)
        {
            return EyeOpennessResult.Uncertain;
        }

        using var faceRegion = new Mat(gray, upperHalf);
        var eyes = eyeCascade.DetectMultiScale(
            faceRegion, scaleFactor: 1.1, minNeighbors: 5, flags: HaarDetectionTypes.ScaleImage);

        return eyes.Length > 0 ? EyeOpennessResult.OpenDetected : EyeOpennessResult.Uncertain;
    }

    private CascadeClassifier? TryLoadCascade(string fileName)
    {
        var path = Path.Combine(CascadesFolder, fileName);
        if (!File.Exists(path))
        {
            _logger.LogWarning("Quality filter: cascade file not found at {Path} - eye-openness checks will report Uncertain", path);
            return null;
        }

        try
        {
            return new CascadeClassifier(path);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Quality filter: failed to load cascade {Path} - eye-openness checks will report Uncertain", path);
            return null;
        }
    }
}
