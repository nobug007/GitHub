using Microsoft.Extensions.Logging;
using MongLightroomBridge.Core.Interfaces;
using MongLightroomBridge.Core.Models;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats.Jpeg;
using SixLabors.ImageSharp.Formats.Tiff;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;

namespace MongLightroomBridge.Infrastructure.DryRun;

/// <summary>
/// See IDryRunProcessor's doc comment: this is a rough, Lightroom-free stand-in used to smoke-test the
/// Windows app end to end (folder pickers, job JSON, progress/log UI, result list) without needing
/// Lightroom Classic installed at all. It only approximates four of the ten sliders (exposure, contrast,
/// saturation, and grayscale) with ImageSharp filters - highlights/shadows/whites/blacks/temperature/
/// tint/crop are NOT approximated here because ImageSharp has no tone-region-aware curve operator
/// equivalent to Lightroom's; the real numbers only ever take effect via the Lua plugin.
/// </summary>
public sealed class DryRunProcessor : IDryRunProcessor
{
    private static readonly string[] SupportedExtensions = { ".jpg", ".jpeg", ".tif", ".tiff", ".png" };

    private readonly ILogger<DryRunProcessor> _logger;

    public DryRunProcessor(ILogger<DryRunProcessor> logger)
    {
        _logger = logger;
    }

    public async Task<JobStatus> RunAsync(
        JobRequest request,
        IProgress<JobStatus>? progress,
        CancellationToken cancellationToken = default)
    {
        var status = new JobStatus
        {
            JobId = request.JobId,
            Status = JobStatusState.Running,
            StartedAtUtc = DateTimeOffset.UtcNow,
        };
        progress?.Report(Clone(status));

        try
        {
            Directory.CreateDirectory(request.OutputFolder);

            var files = Directory.Exists(request.InputFolder)
                ? Directory.EnumerateFiles(request.InputFolder)
                    .Where(f => SupportedExtensions.Contains(Path.GetExtension(f).ToLowerInvariant()))
                    .OrderBy(f => f, StringComparer.OrdinalIgnoreCase)
                    .ToList()
                : new List<string>();

            status.TotalFiles = files.Count;
            progress?.Report(Clone(status));

            // Windows-app-only pre-filter (blur / eyes-uncertain quality check) - see
            // JobRequest.ExcludedFileNames doc comment. Case-insensitive on file name only
            // (never a full path) so it matches regardless of how the app built the list.
            var excludedFileNames = new HashSet<string>(
                request.ExcludedFileNames ?? Array.Empty<string>(),
                StringComparer.OrdinalIgnoreCase);

            if (files.Count == 0)
            {
                // Spec item 10: the dry run must still "work" (produce a clean completed status) even
                // with no sample images at all - it just has nothing to do.
                _logger.LogInformation(
                    "Dry run {JobId}: input folder {Folder} has no supported images (or doesn't exist) - completing with 0 files",
                    request.JobId,
                    request.InputFolder);
            }

            foreach (var file in files)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var fileName = Path.GetFileName(file);

                if (excludedFileNames.Contains(fileName))
                {
                    _logger.LogInformation("Dry run {JobId}: skipping {File} (excluded by quality filter)", request.JobId, fileName);
                    status.Results.Add(new JobFileResult
                    {
                        FileName = fileName,
                        Success = false,
                        Skipped = true,
                        Error = "Excluded by quality filter (blurry or eyes-uncertain) before processing.",
                    });
                    status.ProcessedFiles++;
                    progress?.Report(Clone(status));
                    continue;
                }

                try
                {
                    var outputPath = ProcessOne(file, request);
                    status.Results.Add(new JobFileResult { FileName = fileName, Success = true, OutputPath = outputPath });
                }
                catch (Exception ex)
                {
                    _logger.LogWarning(ex, "Dry run failed on {File}", fileName);
                    status.Results.Add(new JobFileResult { FileName = fileName, Success = false, Error = ex.Message });
                }

                status.ProcessedFiles++;
                progress?.Report(Clone(status));
            }

            status.Status = JobStatusState.Completed;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Dry run {JobId} failed", request.JobId);
            status.Status = JobStatusState.Failed;
            status.ErrorMessage = ex.Message;
        }
        finally
        {
            status.FinishedAtUtc = DateTimeOffset.UtcNow;
            progress?.Report(Clone(status));
        }

        return status;
    }

    private string ProcessOne(string sourcePath, JobRequest request)
    {
        var baseName = Path.GetFileNameWithoutExtension(sourcePath);
        var extension = request.Format == ExportFormat.Tiff ? ".tif" : ".jpg";
        var outputPath = Path.Combine(request.OutputFolder, baseName + "_dryrun" + extension);

        using var image = Image.Load<Rgba32>(sourcePath);

        var adjustments = request.Adjustments;
        image.Mutate(ctx =>
        {
            // Rough approximations only - see class doc comment.
            var exposureMultiplier = (float)Math.Pow(2, adjustments.Exposure);
            if (Math.Abs(exposureMultiplier - 1f) > 0.001f)
            {
                ctx.Brightness(Math.Clamp(exposureMultiplier, 0.2f, 3f));
            }

            var contrastFactor = 1f + (float)(adjustments.Contrast / 100.0);
            if (Math.Abs(contrastFactor - 1f) > 0.001f)
            {
                ctx.Contrast(Math.Clamp(contrastFactor, 0.2f, 2f));
            }

            if (adjustments.ConvertToGrayscale)
            {
                ctx.Grayscale();
            }
            else
            {
                var saturationFactor = 1f + (float)(adjustments.Saturation / 100.0);
                if (Math.Abs(saturationFactor - 1f) > 0.001f)
                {
                    ctx.Saturate(Math.Clamp(saturationFactor, 0f, 2f));
                }
            }
        });

        if (request.Format == ExportFormat.Tiff)
        {
            image.Save(outputPath, new TiffEncoder());
        }
        else
        {
            image.Save(outputPath, new JpegEncoder { Quality = Math.Clamp(request.Quality, 1, 100) });
        }

        return outputPath;
    }

    private static JobStatus Clone(JobStatus status) => new()
    {
        JobId = status.JobId,
        Status = status.Status,
        StartedAtUtc = status.StartedAtUtc,
        FinishedAtUtc = status.FinishedAtUtc,
        TotalFiles = status.TotalFiles,
        ProcessedFiles = status.ProcessedFiles,
        Results = new List<JobFileResult>(status.Results),
        ErrorMessage = status.ErrorMessage,
    };
}
