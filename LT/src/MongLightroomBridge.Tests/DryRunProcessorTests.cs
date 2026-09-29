using Microsoft.Extensions.Logging.Abstractions;
using MongLightroomBridge.Core.Models;
using MongLightroomBridge.Infrastructure.DryRun;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;
using Xunit;

namespace MongLightroomBridge.Tests;

/// <summary>
/// Covers spec item 10: the dry-run mode must work "even without sample images" - the app should
/// still produce a clean Completed status with zero files rather than erroring out.
/// </summary>
public class DryRunProcessorTests
{
    [Fact]
    public async Task RunAsync_WithNonExistentInputFolder_CompletesWithZeroFiles()
    {
        var processor = new DryRunProcessor(NullLogger<DryRunProcessor>.Instance);
        var tempOut = Path.Combine(Path.GetTempPath(), "mlb-dryrun-test-" + Guid.NewGuid());

        var request = new JobRequest
        {
            JobId = "dryrun-no-input",
            InputFolder = Path.Combine(Path.GetTempPath(), "mlb-does-not-exist-" + Guid.NewGuid()),
            OutputFolder = tempOut,
            Adjustments = new AdjustmentValues(),
            DryRun = true,
        };

        try
        {
            var status = await processor.RunAsync(request, progress: null);

            Assert.Equal(JobStatusState.Completed, status.Status);
            Assert.Equal(0, status.TotalFiles);
            Assert.Equal(0, status.ProcessedFiles);
            Assert.Empty(status.Results);
            Assert.NotNull(status.StartedAtUtc);
            Assert.NotNull(status.FinishedAtUtc);
        }
        finally
        {
            if (Directory.Exists(tempOut))
            {
                Directory.Delete(tempOut, recursive: true);
            }
        }
    }

    [Fact]
    public async Task RunAsync_WithEmptyExistingInputFolder_CompletesWithZeroFiles()
    {
        var processor = new DryRunProcessor(NullLogger<DryRunProcessor>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-dryrun-empty-in-" + Guid.NewGuid());
        var tempOut = Path.Combine(Path.GetTempPath(), "mlb-dryrun-empty-out-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);

        var request = new JobRequest
        {
            JobId = "dryrun-empty-input",
            InputFolder = tempIn,
            OutputFolder = tempOut,
            Adjustments = new AdjustmentValues(),
            DryRun = true,
        };

        try
        {
            var status = await processor.RunAsync(request, progress: null);

            Assert.Equal(JobStatusState.Completed, status.Status);
            Assert.Equal(0, status.TotalFiles);
            // Even with nothing to do, the output folder itself must still be created - the Windows app
            // relies on it existing for the "processed image list" / open-output-folder affordance.
            Assert.True(Directory.Exists(tempOut));
        }
        finally
        {
            if (Directory.Exists(tempIn)) Directory.Delete(tempIn, recursive: true);
            if (Directory.Exists(tempOut)) Directory.Delete(tempOut, recursive: true);
        }
    }

    [Fact]
    public async Task RunAsync_ReportsProgress_AsFilesAreProcessed()
    {
        var processor = new DryRunProcessor(NullLogger<DryRunProcessor>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-dryrun-progress-in-" + Guid.NewGuid());
        var tempOut = Path.Combine(Path.GetTempPath(), "mlb-dryrun-progress-out-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);

        var request = new JobRequest
        {
            JobId = "dryrun-progress",
            InputFolder = tempIn,
            OutputFolder = tempOut,
            Adjustments = new AdjustmentValues(),
            DryRun = true,
        };

        var reportedStates = new List<JobStatusState>();
        var progress = new Progress<JobStatus>(s => reportedStates.Add(s.Status));

        try
        {
            await processor.RunAsync(request, progress);

            // At minimum: an initial Running report, and a final Completed report.
            Assert.Contains(JobStatusState.Running, reportedStates);
            Assert.Contains(JobStatusState.Completed, reportedStates);
            Assert.Equal(JobStatusState.Completed, reportedStates[^1]);
        }
        finally
        {
            if (Directory.Exists(tempIn)) Directory.Delete(tempIn, recursive: true);
            if (Directory.Exists(tempOut)) Directory.Delete(tempOut, recursive: true);
        }
    }

    // --- JobRequest.ExcludedFileNames (Windows-app quality pre-filter extension) ---

    [Fact]
    public async Task RunAsync_SkipsExcludedFileNames_ButStillProcessesTheRest()
    {
        var processor = new DryRunProcessor(NullLogger<DryRunProcessor>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-dryrun-excl-in-" + Guid.NewGuid());
        var tempOut = Path.Combine(Path.GetTempPath(), "mlb-dryrun-excl-out-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);

        var keptFile = Path.Combine(tempIn, "kept.jpg");
        var excludedFile = Path.Combine(tempIn, "excluded.jpg");
        WriteTestJpeg(keptFile);
        WriteTestJpeg(excludedFile);

        var request = new JobRequest
        {
            JobId = "dryrun-exclusion",
            InputFolder = tempIn,
            OutputFolder = tempOut,
            Adjustments = new AdjustmentValues(),
            DryRun = true,
            ExcludedFileNames = new[] { "excluded.jpg" },
        };

        try
        {
            var status = await processor.RunAsync(request, progress: null);

            Assert.Equal(JobStatusState.Completed, status.Status);
            // Excluded files still count toward totals/progress - they're accounted for, just not exported.
            Assert.Equal(2, status.TotalFiles);
            Assert.Equal(2, status.ProcessedFiles);
            Assert.Equal(2, status.Results.Count);

            var keptResult = status.Results.Single(r => r.FileName == "kept.jpg");
            Assert.True(keptResult.Success);
            Assert.False(keptResult.Skipped);
            Assert.NotNull(keptResult.OutputPath);
            Assert.True(File.Exists(keptResult.OutputPath));

            var excludedResult = status.Results.Single(r => r.FileName == "excluded.jpg");
            Assert.False(excludedResult.Success);
            Assert.True(excludedResult.Skipped);
            Assert.Null(excludedResult.OutputPath);
            Assert.NotNull(excludedResult.Error);

            // The excluded file must never have been written to the output folder at all.
            Assert.DoesNotContain(
                Directory.EnumerateFiles(tempOut),
                f => Path.GetFileName(f).Contains("excluded", StringComparison.OrdinalIgnoreCase));
        }
        finally
        {
            if (Directory.Exists(tempIn)) Directory.Delete(tempIn, recursive: true);
            if (Directory.Exists(tempOut)) Directory.Delete(tempOut, recursive: true);
        }
    }

    [Fact]
    public async Task RunAsync_WithNullExcludedFileNames_ProcessesEverythingAsBefore()
    {
        // Backward-compatibility guard: a request built before this feature existed (ExcludedFileNames
        // left at its default null) must behave exactly as it always did.
        var processor = new DryRunProcessor(NullLogger<DryRunProcessor>.Instance);
        var tempIn = Path.Combine(Path.GetTempPath(), "mlb-dryrun-noexcl-in-" + Guid.NewGuid());
        var tempOut = Path.Combine(Path.GetTempPath(), "mlb-dryrun-noexcl-out-" + Guid.NewGuid());
        Directory.CreateDirectory(tempIn);
        WriteTestJpeg(Path.Combine(tempIn, "only.jpg"));

        var request = new JobRequest
        {
            JobId = "dryrun-no-exclusion",
            InputFolder = tempIn,
            OutputFolder = tempOut,
            Adjustments = new AdjustmentValues(),
            DryRun = true,
        };

        try
        {
            var status = await processor.RunAsync(request, progress: null);

            Assert.Single(status.Results);
            Assert.True(status.Results[0].Success);
            Assert.False(status.Results[0].Skipped);
        }
        finally
        {
            if (Directory.Exists(tempIn)) Directory.Delete(tempIn, recursive: true);
            if (Directory.Exists(tempOut)) Directory.Delete(tempOut, recursive: true);
        }
    }

    private static void WriteTestJpeg(string path)
    {
        using var image = new Image<Rgba32>(64, 64);
        image.SaveAsJpeg(path);
    }
}
