using System.Text.Json;
using System.Text.Json.Serialization;
using MongLightroomBridge.Core.Models;

namespace MongLightroomBridge.Core.Json;

/// <summary>Reads/writes JobStatusState as the lowercase "pending"/"running"/"completed"/"failed" strings.</summary>
public sealed class JobStatusStateJsonConverter : JsonConverter<JobStatusState>
{
    public override JobStatusState Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var text = reader.GetString();
        return text?.Trim().ToLowerInvariant() switch
        {
            "pending" => JobStatusState.Pending,
            "running" => JobStatusState.Running,
            "completed" or "complete" or "done" => JobStatusState.Completed,
            "failed" or "error" => JobStatusState.Failed,
            _ => throw new JsonException($"Unknown job status '{text}'."),
        };
    }

    public override void Write(Utf8JsonWriter writer, JobStatusState value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value.ToString().ToLowerInvariant());
    }
}
