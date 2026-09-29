using Microsoft.Extensions.Logging;
using MongLightroomBridge.Core.Interfaces;
using MongLightroomBridge.Core.Json;
using MongLightroomBridge.Core.Models;
using MongLightroomBridge.Infrastructure.Settings;

namespace MongLightroomBridge.Infrastructure.Jobs;

/// <summary>Default IJobFileService: plain files under BridgeSettings.JobsFolder, no locking beyond
/// atomic-ish write-then-rename (so the plugin never observes a half-written pending-*.json).</summary>
public sealed class JobFileService : IJobFileService
{
    private readonly BridgeSettings _settings;
    private readonly ILogger<JobFileService> _logger;

    public JobFileService(BridgeSettings settings, ILogger<JobFileService> logger)
    {
        _settings = settings;
        _logger = logger;
        Directory.CreateDirectory(_settings.JobsFolder);
    }

    public string JobsFolder => _settings.JobsFolder;

    public async Task<string> SubmitJobAsync(JobRequest request, CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(_settings.JobsFolder);

        var finalPath = Path.Combine(_settings.JobsFolder, $"pending-{request.JobId}.json");
        var tempPath = finalPath + ".tmp";

        await using (var stream = File.Create(tempPath))
        {
            await System.Text.Json.JsonSerializer
                .SerializeAsync(stream, request, BridgeJsonOptions.Default, cancellationToken)
                .ConfigureAwait(false);
        }

        // Atomic-ish on the same volume: the plugin's directory scan never sees a partial file.
        File.Move(tempPath, finalPath, overwrite: true);

        _logger.LogInformation("Submitted job {JobId} -> {Path}", request.JobId, finalPath);
        return finalPath;
    }

    public async Task<JobStatus?> TryReadStatusAsync(string jobId, CancellationToken cancellationToken = default)
    {
        var path = Path.Combine(_settings.JobsFolder, $"status-{jobId}.json");
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            await using var stream = File.OpenRead(path);
            return await System.Text.Json.JsonSerializer
                .DeserializeAsync<JobStatus>(stream, BridgeJsonOptions.Default, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is System.Text.Json.JsonException or IOException)
        {
            // The plugin may be mid-write; treat a transient parse/IO failure as "not ready yet" rather
            // than a hard error - the next poll a few hundred ms later will almost always succeed.
            _logger.LogDebug(ex, "Status file for {JobId} not readable yet, will retry", jobId);
            return null;
        }
    }

    public async Task<string> ReadLogAsync(string jobId, CancellationToken cancellationToken = default)
    {
        var path = Path.Combine(_settings.JobsFolder, $"log-{jobId}.log");
        if (!File.Exists(path))
        {
            return string.Empty;
        }

        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            using var reader = new StreamReader(stream);
            return await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (IOException ex)
        {
            _logger.LogDebug(ex, "Log file for {JobId} not readable yet, will retry", jobId);
            return string.Empty;
        }
    }

    public async Task<PluginHeartbeat?> TryReadHeartbeatAsync(CancellationToken cancellationToken = default)
    {
        var path = Path.Combine(_settings.JobsFolder, "heartbeat.json");
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            await using var stream = File.OpenRead(path);
            return await System.Text.Json.JsonSerializer
                .DeserializeAsync<PluginHeartbeat>(stream, BridgeJsonOptions.Default, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is System.Text.Json.JsonException or IOException)
        {
            _logger.LogDebug(ex, "heartbeat.json not readable yet, will retry");
            return null;
        }
    }
}
