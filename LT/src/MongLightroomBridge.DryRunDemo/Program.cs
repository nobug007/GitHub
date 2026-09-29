// Manual demo runner - NOT part of the shipped app. Calls the exact same DryRunProcessor class the
// Windows app uses, against one real sample photo, so the correction pipeline can be verified end to
// end without driving the WPF UI. Original photos are only ever opened for reading; every output is a
// brand-new file under OutputFolder (see DryRunProcessor.ProcessOne).
using Microsoft.Extensions.Logging.Abstractions;
using MongLightroomBridge.Core.Models;
using MongLightroomBridge.Core.Profiles;
using MongLightroomBridge.Infrastructure.DryRun;

var inputFolder = args.Length > 0 ? args[0] : @"C:\GitHub\Photo\_sample_input";
var outputFolder = args.Length > 1 ? args[1] : @"C:\GitHub\Photo\output";

const AutoCorrectionProfileName profile = AutoCorrectionProfileName.CafeWarm;
var adjustments = BuiltInProfiles.GetDefaults(profile);

var request = new JobRequest
{
    JobId = "manual-demo-" + DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    InputFolder = inputFolder,
    OutputFolder = outputFolder,
    Profile = profile,
    Format = ExportFormat.Jpg,
    Quality = 92,
    Adjustments = adjustments,
    DryRun = false,
};

Console.WriteLine($"Profile: {profile}");
Console.WriteLine($"Input:  {inputFolder}");
Console.WriteLine($"Output: {outputFolder}");
Console.WriteLine();

var processor = new DryRunProcessor(NullLogger<DryRunProcessor>.Instance);

var progress = new Progress<JobStatus>(status =>
    Console.WriteLine($"[{status.Status}] {status.ProcessedFiles}/{status.TotalFiles}"));

var result = await processor.RunAsync(request, progress);

Console.WriteLine();
Console.WriteLine("=== RESULT ===");
Console.WriteLine($"Status: {result.Status}");
Console.WriteLine($"Total: {result.TotalFiles}, Processed: {result.ProcessedFiles}");
foreach (var r in result.Results)
{
    Console.WriteLine(r.Success
        ? $"OK   {r.FileName} -> {r.OutputPath}"
        : $"FAIL {r.FileName}: {r.Error}");
}
