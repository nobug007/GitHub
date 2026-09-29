using System.Text.Json;
using System.Text.Json.Serialization;
using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Json;

/// <summary>
/// Single source of truth for how job/status JSON is (de)serialized on the .NET side. The Lua side
/// (JobFile.lua) has to agree on the same field names and enum spellings by hand, since Lightroom's
/// Lua sandbox has no access to a .NET assembly to share this with - see docs/job-schema.md.
/// </summary>
public static class BridgeJsonOptions
{
    public static readonly JsonSerializerOptions Default = Create();

    private static JsonSerializerOptions Create()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        };

        options.Converters.Add(new JsonStringEnumConverter<AutoCorrectionProfileName>());
        options.Converters.Add(new ExportFormatJsonConverter());
        options.Converters.Add(new JobStatusStateJsonConverter());
        return options;
    }
}
