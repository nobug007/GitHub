using System.Text.Json;
using System.Text.Json.Serialization;
using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Json;

/// <summary>Reads/writes ExportFormat as the lowercase "jpg"/"tiff" strings the job schema uses.</summary>
public sealed class ExportFormatJsonConverter : JsonConverter<ExportFormat>
{
    public override ExportFormat Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var text = reader.GetString();
        return text?.Trim().ToLowerInvariant() switch
        {
            "jpg" or "jpeg" => ExportFormat.Jpg,
            "tiff" or "tif" => ExportFormat.Tiff,
            _ => throw new JsonException($"Unknown export format '{text}'. Expected \"jpg\" or \"tiff\"."),
        };
    }

    public override void Write(Utf8JsonWriter writer, ExportFormat value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value == ExportFormat.Tiff ? "tiff" : "jpg");
    }
}
