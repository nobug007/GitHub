namespace MongLightroomBridge.Core.Models;

/// <summary>
/// The "pending job" file the Windows app writes to
/// %LOCALAPPDATA%\MongLightroomBridge\jobs\pending-{jobId}.json for the Lightroom plugin to pick up.
/// Field names/casing match docs/job-schema.md exactly - see BridgeJsonOptions.
/// </summary>
public sealed class JobRequest
{
    public required string JobId { get; set; }

    /// <summary>UTC timestamp the Windows app created this job at. Not in the original spec sample; added
    /// so the plugin (and a human reading the jobs folder) can tell stale jobs apart from fresh ones.</summary>
    public DateTimeOffset CreatedAtUtc { get; set; } = DateTimeOffset.UtcNow;

    public required string InputFolder { get; set; }

    public required string OutputFolder { get; set; }

    public AutoCorrectionProfileName Profile { get; set; } = AutoCorrectionProfileName.NaturalPortrait;

    public ExportFormat Format { get; set; } = ExportFormat.Jpg;

    /// <summary>1..100. Only meaningful for Format == Jpg (maps to LR_jpeg_quality = Quality / 100).</summary>
    public int Quality { get; set; } = 90;

    public required AdjustmentValues Adjustments { get; set; }

    /// <summary>
    /// Always true in practice - principle #4 ("원본 사진은 수정하지 않는다") means this bridge never offers
    /// a way to turn it off from the UI. Kept as an explicit field (rather than assumed) so the job file
    /// is self-documenting and the plugin can refuse to run a job that somehow arrives with this false.
    /// </summary>
    public bool PreserveOriginals { get; set; } = true;

    /// <summary>
    /// When true, the plugin should still import/read the photos and write a normal status file, but
    /// must NOT call catalog:exportPhotos / write any output file - see TaskRunner.lua's dry-run branch.
    /// The Windows app also has its own local dry-run (DryRunProcessor) for testing the UI/job-file
    /// plumbing without Lightroom running at all - the two are independent, see README "Dry-run modes".
    /// </summary>
    public bool DryRun { get; set; }

    /// <summary>
    /// Windows-app-side extension, NOT part of the original spec's job schema - file names (not
    /// full paths) inside InputFolder to skip even though they're otherwise-supported images.
    /// Populated by the app's local IPhotoQualityFilter pre-filter (blur / eyes-uncertain) before
    /// a job is submitted; the user can always override individual exclusions in the UI first.
    /// Null/empty means "process everything", exactly as before this field existed - both
    /// DryRunProcessor and the Lua plugin's TaskRunner treat a missing/absent value identically
    /// to an empty list, so old job files (and the spec's own sample job) are unaffected.
    /// </summary>
    public IReadOnlyList<string>? ExcludedFileNames { get; set; }
}
