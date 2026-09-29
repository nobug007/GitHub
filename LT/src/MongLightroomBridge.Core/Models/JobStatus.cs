namespace MongLightroomBridge.Core.Models;

/// <summary>
/// The status file the Lightroom plugin writes to
/// %LOCALAPPDATA%\MongLightroomBridge\jobs\status-{jobId}.json, polled by the Windows app
/// (JobFileService) to drive the progress bar/log/result list.
/// </summary>
public sealed class JobStatus
{
    public required string JobId { get; set; }

    public JobStatusState Status { get; set; } = JobStatusState.Pending;

    public DateTimeOffset? StartedAtUtc { get; set; }

    public DateTimeOffset? FinishedAtUtc { get; set; }

    public int TotalFiles { get; set; }

    public int ProcessedFiles { get; set; }

    public List<JobFileResult> Results { get; set; } = new();

    /// <summary>Set when Status == Failed and the job could not even start (e.g. bad input folder).</summary>
    public string? ErrorMessage { get; set; }
}
