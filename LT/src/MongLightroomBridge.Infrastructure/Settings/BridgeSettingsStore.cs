using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace MongLightroomBridge.Infrastructure.Settings;

/// <summary>Loads/saves BridgeSettings as plain JSON, same pattern as ShineMung's AppSettingsStore.</summary>
public sealed class BridgeSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private readonly ILogger<BridgeSettingsStore> _logger;

    public BridgeSettingsStore(ILogger<BridgeSettingsStore> logger)
    {
        _logger = logger;
    }

    public string SettingsFilePath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "MongLightroomBridge",
        "settings.json");

    public BridgeSettings LoadOrCreateDefault()
    {
        try
        {
            if (File.Exists(SettingsFilePath))
            {
                var json = File.ReadAllText(SettingsFilePath);
                var loaded = JsonSerializer.Deserialize<BridgeSettings>(json, JsonOptions);
                if (loaded is not null)
                {
                    return loaded;
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to load settings from {Path}; using defaults", SettingsFilePath);
        }

        var defaults = new BridgeSettings();
        try
        {
            SaveAsync(defaults).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to write default settings to {Path}", SettingsFilePath);
        }

        return defaults;
    }

    public async Task SaveAsync(BridgeSettings settings, CancellationToken cancellationToken = default)
    {
        var directory = Path.GetDirectoryName(SettingsFilePath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var json = JsonSerializer.Serialize(settings, JsonOptions);
        await File.WriteAllTextAsync(SettingsFilePath, json, cancellationToken).ConfigureAwait(false);
    }
}
