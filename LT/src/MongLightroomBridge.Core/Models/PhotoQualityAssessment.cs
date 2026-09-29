namespace MongLightroomBridge.Core.Models;

/// <summary>
/// Result of running IPhotoQualityFilter over one photo. This is a Windows-app-side extension
/// (not part of the original spec) - a local pre-filter that runs before a job is ever built, so
/// obviously-blurry or eyes-uncertain photos can be left out of what gets sent to Lightroom.
/// </summary>
public sealed class PhotoQualityAssessment
{
    public required string FileName { get; set; }

    /// <summary>Variance of the Laplacian of the (downscaled) image - a standard, well-established
    /// focus-blur metric. Higher = sharper. Not comparable across wildly different subjects/scenes,
    /// only used here as a threshold against BlurThreshold.</summary>
    public double SharpnessScore { get; set; }

    public bool IsBlurry { get; set; }

    public EyeOpennessResult EyeOpenness { get; set; } = EyeOpennessResult.NotChecked;

    /// <summary>True if this photo would be left out of the job based on the options passed to
    /// IPhotoQualityFilter.Assess.</summary>
    public bool Excluded { get; set; }

    /// <summary>Human-readable reason, set only when Excluded is true.</summary>
    public string? ExclusionReason { get; set; }
}

/// <summary>
/// Best-effort eye-state signal from a Haar-cascade eye detector run inside the detected face's
/// upper region. IMPORTANT: this can only ever positively confirm "an eye-shaped feature was
/// found" - it can NOT reliably tell open eyes from closed eyes, and glasses glare/reflections
/// (very common with webcam-style shots near a monitor) routinely defeat the detector even when
/// the eyes are wide open. So this deliberately has no "Closed" value - only "detected something
/// eye-like" vs "couldn't confirm, for whatever reason". Never treat Uncertain as proof of closed
/// eyes; it usually just means glare, angle, or partial occlusion.
/// </summary>
public enum EyeOpennessResult
{
    /// <summary>PhotoQualityFilterOptions.ExcludeUncertainEyes was false, so this was never run.</summary>
    NotChecked,

    /// <summary>At least one eye-like feature was found in the expected region of a detected face.</summary>
    OpenDetected,

    /// <summary>No face detected, or a face was detected but no eye-like feature was found in it -
    /// most often glasses glare/reflection, an extreme angle, or partial occlusion. NOT a claim
    /// that the eyes are closed.</summary>
    Uncertain,
}
