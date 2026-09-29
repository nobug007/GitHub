using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Interfaces;

/// <summary>
/// The Windows app's OWN dry-run path - separate from JobRequest.DryRun (which is a flag the plugin
/// honors). This one runs entirely inside the .NET app with no Lightroom involved at all, so the UI and
/// job-file plumbing can be smoke-tested on a machine that doesn't even have Lightroom installed (spec
/// item 10, "샘플 이미지 없이도 동작하는 dry-run 모드"). It approximates the adjustments with ImageSharp
/// (exposure/contrast/saturation only - nowhere near Lightroom's real Develop engine) purely so there is
/// something visible to look at; README calls this out clearly so nobody mistakes the output for what
/// Lightroom will actually produce.
/// </summary>
public interface IDryRunProcessor
{
    Task<JobStatus> RunAsync(JobRequest request, IProgress<JobStatus>? progress, CancellationToken cancellationToken = default);
}
