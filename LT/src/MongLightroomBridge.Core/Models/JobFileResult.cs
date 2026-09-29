namespace MongLightroomBridge.Core.Models;

/// <summary>One processed (or failed) file inside a JobStatus.Results list.</summary>
public sealed class JobFileResult
{
    public required string FileName { get; set; }

    public bool Success { get; set; }

    /// <summary>Full path of the exported file. Null when Success is false or DryRun produced no file.</summary>
    public string? OutputPath { get; set; }

    /// <summary>Human-readable failure reason. Null when Success is true.</summary>
    public string? Error { get; set; }

    /// <summary>
    /// True when this file was never handed to the export pipeline at all because it was
    /// pre-excluded by the Windows app's optional quality filter (blurry / eyes-uncertain) -
    /// see JobRequest.ExcludedFileNames. Success is false and Error explains why, exactly as
    /// for any other failed file, so older UI code that only checks Success/Error keeps working
    /// unchanged; Skipped just lets the WPF results list show a distinct "skipped" icon/reason
    /// instead of a red failure icon.
    /// </summary>
    public bool Skipped { get; set; }
}
