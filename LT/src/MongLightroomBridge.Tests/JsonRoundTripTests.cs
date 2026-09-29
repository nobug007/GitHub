using System.Text.Json;
using MongLightroomBridge.Core.Json;
using MongLightroomBridge.Core.Models;
using Xunit;

namespace MongLightroomBridge.Tests;

public class JsonRoundTripTests
{
    // Matches samples/jobs/sample-job.json exactly (docs/job-schema.md's canonical example).
    private const string SampleJobJson = """
        {
          "jobId": "20260903-150000000",
          "inputFolder": "C:\\GitHub\\Photo",
          "outputFolder": "C:\\GitHub\\LT\\samples\\output",
          "profile": "CafeWarm",
          "format": "jpg",
          "quality": 92,
          "adjustments": {
            "exposure": 0.15,
            "contrast": 12,
            "highlights": -35,
            "shadows": 25,
            "whites": 5,
            "blacks": -8,
            "temperature": 300,
            "tint": 2,
            "vibrance": 18,
            "saturation": 0
          },
          "preserveOriginals": true,
          "dryRun": false,
          "createdAtUtc": "2026-09-03T15:00:00.000Z"
        }
        """;

    [Fact]
    public void Deserialize_SampleJobJson_ProducesExpectedJobRequest()
    {
        var job = JsonSerializer.Deserialize<JobRequest>(SampleJobJson, BridgeJsonOptions.Default);

        Assert.NotNull(job);
        Assert.Equal("20260903-150000000", job!.JobId);
        Assert.Equal(@"C:\GitHub\Photo", job.InputFolder);
        Assert.Equal(@"C:\GitHub\LT\samples\output", job.OutputFolder);
        Assert.Equal(AutoCorrectionProfileName.CafeWarm, job.Profile);
        Assert.Equal(ExportFormat.Jpg, job.Format);
        Assert.Equal(92, job.Quality);
        Assert.True(job.PreserveOriginals);
        Assert.False(job.DryRun);
        Assert.Equal(0.15, job.Adjustments.Exposure);
        Assert.Equal(300, job.Adjustments.Temperature);
    }

    [Fact]
    public void JobRequest_RoundTrips_ThroughSerializeThenDeserialize()
    {
        var original = new JobRequest
        {
            JobId = "unit-test-job",
            InputFolder = @"C:\in",
            OutputFolder = @"C:\out",
            Profile = AutoCorrectionProfileName.HighKeyClean,
            Format = ExportFormat.Tiff,
            Quality = 77,
            Adjustments = new AdjustmentValues { Exposure = 0.4, CropAspect = "4:5" },
            DryRun = true,
        };

        var json = JsonSerializer.Serialize(original, BridgeJsonOptions.Default);
        var roundTripped = JsonSerializer.Deserialize<JobRequest>(json, BridgeJsonOptions.Default);

        Assert.NotNull(roundTripped);
        Assert.Equal(original.JobId, roundTripped!.JobId);
        Assert.Equal(original.InputFolder, roundTripped.InputFolder);
        Assert.Equal(original.OutputFolder, roundTripped.OutputFolder);
        Assert.Equal(original.Profile, roundTripped.Profile);
        Assert.Equal(original.Format, roundTripped.Format);
        Assert.Equal(original.Quality, roundTripped.Quality);
        Assert.Equal(original.Adjustments.Exposure, roundTripped.Adjustments.Exposure);
        Assert.Equal(original.Adjustments.CropAspect, roundTripped.Adjustments.CropAspect);
        Assert.True(roundTripped.DryRun);
    }

    [Fact]
    public void JobRequest_Serializes_WithCamelCaseFieldNamesAndLowercaseEnums()
    {
        var job = new JobRequest
        {
            JobId = "case-check",
            InputFolder = @"C:\in",
            OutputFolder = @"C:\out",
            Profile = AutoCorrectionProfileName.ProductNeutral,
            Format = ExportFormat.Tiff,
            Adjustments = new AdjustmentValues(),
        };

        var json = JsonSerializer.Serialize(job, BridgeJsonOptions.Default);

        // Field names must match docs/job-schema.md exactly - the Lua side hand-matches these strings.
        Assert.Contains("\"jobId\"", json);
        Assert.Contains("\"inputFolder\"", json);
        Assert.Contains("\"outputFolder\"", json);
        Assert.Contains("\"preserveOriginals\"", json);
        // Enums serialize as the lowercase wire strings, not .NET's PascalCase member names.
        Assert.Contains("\"profile\": \"ProductNeutral\"", json);
        Assert.Contains("\"format\": \"tiff\"", json);
    }

    [Fact]
    public void JobStatus_Serializes_EmptyResultsAsJsonArray_NeverAsObject()
    {
        // The C# side deserializes "results" as List<JobFileResult>, so an empty list MUST serialize
        // as "[]", never "{}" - see docs/job-schema.md's results[] note.
        var status = new JobStatus { JobId = "empty-results" };

        var json = JsonSerializer.Serialize(status, BridgeJsonOptions.Default);

        Assert.Contains("\"results\": []", json);
    }

    [Fact]
    public void JobStatus_RoundTrips_WithNonEmptyResults()
    {
        var status = new JobStatus
        {
            JobId = "with-results",
            Status = JobStatusState.Completed,
            TotalFiles = 2,
            ProcessedFiles = 2,
            Results =
            {
                new JobFileResult { FileName = "a.jpg", Success = true, OutputPath = @"C:\out\a.jpg" },
                new JobFileResult { FileName = "b.jpg", Success = false, Error = "corrupt file" },
            },
        };

        var json = JsonSerializer.Serialize(status, BridgeJsonOptions.Default);
        var roundTripped = JsonSerializer.Deserialize<JobStatus>(json, BridgeJsonOptions.Default);

        Assert.NotNull(roundTripped);
        Assert.Equal(JobStatusState.Completed, roundTripped!.Status);
        Assert.Equal(2, roundTripped.Results.Count);
        Assert.True(roundTripped.Results[0].Success);
        Assert.Equal(@"C:\out\a.jpg", roundTripped.Results[0].OutputPath);
        Assert.False(roundTripped.Results[1].Success);
        Assert.Equal("corrupt file", roundTripped.Results[1].Error);
    }

    [Theory]
    [InlineData("pending", JobStatusState.Pending)]
    [InlineData("running", JobStatusState.Running)]
    [InlineData("completed", JobStatusState.Completed)]
    [InlineData("failed", JobStatusState.Failed)]
    public void JobStatusState_ParsesLowercaseWireStrings(string wireValue, JobStatusState expected)
    {
        var json = $$$"""{"jobId":"x","status":"{{{wireValue}}}"}""";
        var status = JsonSerializer.Deserialize<JobStatus>(json, BridgeJsonOptions.Default);
        Assert.Equal(expected, status!.Status);
    }

    [Theory]
    [InlineData("jpg", ExportFormat.Jpg)]
    [InlineData("jpeg", ExportFormat.Jpg)]
    [InlineData("tiff", ExportFormat.Tiff)]
    [InlineData("tif", ExportFormat.Tiff)]
    public void ExportFormat_ParsesCaseInsensitiveAliases(string wireValue, ExportFormat expected)
    {
        var json = $$$"""
            {"jobId":"x","inputFolder":"C:\\i","outputFolder":"C:\\o","format":"{{{wireValue}}}","adjustments":{}}
            """;
        var job = JsonSerializer.Deserialize<JobRequest>(json, BridgeJsonOptions.Default);
        Assert.Equal(expected, job!.Format);
    }

    [Fact]
    public void ExportFormat_UnknownValue_ThrowsJsonException()
    {
        var json = """
            {"jobId":"x","inputFolder":"C:\\i","outputFolder":"C:\\o","format":"png","adjustments":{}}
            """;
        Assert.Throws<JsonException>(() =>
            JsonSerializer.Deserialize<JobRequest>(json, BridgeJsonOptions.Default));
    }

    // --- Quality-filter extension (ExcludedFileNames / Skipped) - see docs/job-schema.md ---

    [Fact]
    public void JobRequest_WithoutExcludedFileNames_OmitsFieldFromJson_AndRoundTripsAsNull()
    {
        // Old job files (including the spec's own sample-job.json) never set this field at all -
        // serializing a JobRequest that never touched it must not introduce a new "excludedFileNames"
        // key, and deserializing back must leave it null, not an empty array.
        var original = new JobRequest
        {
            JobId = "no-exclusions",
            InputFolder = @"C:\in",
            OutputFolder = @"C:\out",
            Adjustments = new AdjustmentValues(),
        };

        var json = JsonSerializer.Serialize(original, BridgeJsonOptions.Default);
        Assert.DoesNotContain("excludedFileNames", json);

        var roundTripped = JsonSerializer.Deserialize<JobRequest>(json, BridgeJsonOptions.Default);
        Assert.NotNull(roundTripped);
        Assert.Null(roundTripped!.ExcludedFileNames);
    }

    [Fact]
    public void JobRequest_WithExcludedFileNames_RoundTrips()
    {
        var original = new JobRequest
        {
            JobId = "with-exclusions",
            InputFolder = @"C:\in",
            OutputFolder = @"C:\out",
            Adjustments = new AdjustmentValues(),
            ExcludedFileNames = new[] { "IMG_0001.jpg", "IMG_0002.jpg" },
        };

        var json = JsonSerializer.Serialize(original, BridgeJsonOptions.Default);
        Assert.Contains("\"excludedFileNames\"", json);

        var roundTripped = JsonSerializer.Deserialize<JobRequest>(json, BridgeJsonOptions.Default);
        Assert.NotNull(roundTripped);
        Assert.Equal(new[] { "IMG_0001.jpg", "IMG_0002.jpg" }, roundTripped!.ExcludedFileNames);
    }

    [Fact]
    public void JobFileResult_Skipped_RoundTrips_AndDefaultsToFalse()
    {
        var status = new JobStatus
        {
            JobId = "skip-check",
            Results =
            {
                new JobFileResult { FileName = "a.jpg", Success = true, OutputPath = @"C:\out.jpg" },
                new JobFileResult { FileName = "b.jpg", Success = false, Skipped = true, Error = "excluded" },
            },
        };

        var json = JsonSerializer.Serialize(status, BridgeJsonOptions.Default);
        var roundTripped = JsonSerializer.Deserialize<JobStatus>(json, BridgeJsonOptions.Default);

        Assert.NotNull(roundTripped);
        // Ordinary success result never mentions "skipped" and defaults back to false.
        Assert.False(roundTripped!.Results[0].Skipped);
        Assert.True(roundTripped.Results[1].Skipped);
        Assert.False(roundTripped.Results[1].Success);
        Assert.Equal("excluded", roundTripped.Results[1].Error);
    }
}
