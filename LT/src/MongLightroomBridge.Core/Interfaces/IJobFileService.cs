using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Interfaces;

/// <summary>
/// File-based IPC with the Lightroom plugin (spec section C). Writes pending job files, polls status
/// files the plugin writes back, and reads the plugin's append-only log. No network/HTTP involved in
/// this first implementation - see docs/architecture in README for the localhost-bridge extension point.
/// </summary>
public interface IJobFileService
{
    /// <summary>%LOCALAPPDATA%\MongLightroomBridge\jobs - both pending-*.json and status-*.json live here.</summary>
    string JobsFolder { get; }

    /// <summary>Writes pending-{request.JobId}.json. Returns the full path written.</summary>
    Task<string> SubmitJobAsync(JobRequest request, CancellationToken cancellationToken = default);

    /// <summary>Reads status-{jobId}.json if it exists yet; null if the plugin hasn't picked up the job.</summary>
    Task<JobStatus?> TryReadStatusAsync(string jobId, CancellationToken cancellationToken = default);

    /// <summary>Reads the plugin's log-{jobId}.log tail (whatever has been appended so far), or "" if none yet.</summary>
    Task<string> ReadLogAsync(string jobId, CancellationToken cancellationToken = default);

    /// <summary>Reads heartbeat.json; null if the plugin has never written one (or the file is missing).</summary>
    Task<PluginHeartbeat?> TryReadHeartbeatAsync(CancellationToken cancellationToken = default);
}
