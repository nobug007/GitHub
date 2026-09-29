using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Interfaces;

/// <summary>
/// Local, Lightroom-free photo culling pass the Windows app can run over an input folder before
/// building a job - e.g. to leave motion-blurred shots, or shots where an eye can't be confirmed
/// open, out of what gets sent to Lightroom. Entirely a Windows-app-side convenience; the job
/// schema stays backward compatible (see JobRequest.ExcludedFileNames) and the Lightroom SDK
/// surface is untouched by this feature.
/// </summary>
public interface IPhotoQualityFilter
{
    /// <summary>
    /// Assesses every supported image directly inside inputFolder (non-recursive, matching
    /// DryRunProcessor/TaskRunner.lua's own folder scan). Returns one assessment per file, sorted
    /// the same way the processors sort (ordinal file name); an empty/missing folder returns an
    /// empty list rather than throwing.
    /// </summary>
    IReadOnlyList<PhotoQualityAssessment> Assess(string inputFolder, PhotoQualityFilterOptions options);
}

/// <summary>Options for IPhotoQualityFilter.Assess. Both checks default to off - the filter never
/// excludes anything unless explicitly asked to.</summary>
public sealed class PhotoQualityFilterOptions
{
    public bool ExcludeBlurry { get; set; }

    /// <summary>Laplacian-variance threshold below which a photo is considered blurry. The
    /// default (100) was picked empirically - see OpenCvPhotoQualityFilterTests for worked
    /// sharp/blurred examples - and is deliberately conservative (misses some soft photos rather
    /// than risk excluding sharp ones), since a false "blurry" verdict silently drops a photo the
    /// user might have wanted.</summary>
    public double BlurThreshold { get; set; } = 100.0;

    /// <summary>When true, also excludes photos where IPhotoQualityFilter could not confirm an
    /// eye is open (see EyeOpennessResult.Uncertain's doc comment for why that is NOT the same as
    /// "eyes closed").</summary>
    public bool ExcludeUncertainEyes { get; set; }
}
