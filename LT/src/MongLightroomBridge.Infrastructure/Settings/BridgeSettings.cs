namespace MongLightroomBridge.Infrastructure.Settings;

/// <summary>
/// User-editable settings, persisted to %LOCALAPPDATA%\MongLightroomBridge\settings.json
/// (BridgeSettingsStore). Spec explicitly asks for the plugin install path and job folder path to be
/// changeable from a settings screen.
/// </summary>
public sealed class BridgeSettings
{
    /// <summary>%LOCALAPPDATA%\MongLightroomBridge\jobs by default - where pending/status/log files live.</summary>
    public string JobsFolder { get; set; } = DefaultJobsFolder();

    /// <summary>
    /// Where MongLightroomBridge.lrdevplugin is expected to be installed (Lightroom Plug-in Manager ->
    /// Add). Purely informational/for the "open plugin folder" button - the Windows app cannot install
    /// or enable a Lightroom plugin itself (Plug-in Manager is a Lightroom-side, user-driven step).
    /// </summary>
    public string LightroomPluginInstallPath { get; set; } = string.Empty;

    /// <summary>How often the app polls status-{jobId}.json while a job is running.</summary>
    public int PollIntervalMilliseconds { get; set; } = 750;

    /// <summary>How long to wait for the plugin to even start a job before treating it as "not responding".</summary>
    public int PluginResponseTimeoutSeconds { get; set; } = 20;

    public static string DefaultJobsFolder() => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "MongLightroomBridge",
        "jobs");
}
