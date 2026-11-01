using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace ThreeScreen;

internal static class DriveBackupVerification
{
    private sealed class FakeDrive : IBackupDrive
    {
        public bool Valid = true, WrongAccount, FailUpload;
        public int Uploads;
        public bool BlockUpload;
        public readonly TaskCompletionSource UploadStarted = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public Task CheckAccountAsync(System.Threading.CancellationToken cancellationToken = default) => WrongAccount ? Task.FromException(new IOException("wrong account")) : Task.CompletedTask;
        public async Task UploadAsync(string local, string remote, System.Threading.CancellationToken cancellationToken = default) {
            Uploads++; UploadStarted.TrySetResult();
            if (BlockUpload) await Task.Delay(System.Threading.Timeout.Infinite, cancellationToken);
            if (FailUpload) throw new IOException("network failure");
        }
        public Task<bool> MatchesAsync(string remote, long size, string md5, System.Threading.CancellationToken cancellationToken = default) => Task.FromResult(Valid);
    }
    public static async Task RunAsync()
    {
        string root = Path.Combine(Path.GetTempPath(), "SM005-backup-test-" + Guid.NewGuid().ToString("N"));
        string photos = Path.Combine(root, "photos"), journal = Path.Combine(root, "jobs");
        string folder = Path.Combine(photos, "2026-10-03", "DOG_검증"); Directory.CreateDirectory(folder);
        var drive = new FakeDrive(); var backup = new DriveBackup(journal, drive);
        string raw = Path.Combine(folder, "one.cr3"), jpeg = Path.Combine(folder, "one.jpg");
        File.WriteAllText(raw, "disposable raw fixture"); File.WriteAllText(jpeg, "keep jpeg");
        var job = backup.Begin("검증", photos); backup.Record(job, raw); backup.Record(job, jpeg);
        await backup.RunPendingAsync();
        if (!File.Exists(raw) || drive.Uploads != 0) throw new Exception("Active capture must not upload");
        backup.Seal(job); drive.Valid = false; await backup.RunPendingAsync();
        if (!File.Exists(raw) || job.CompletedAt != null) throw new Exception("Hash mismatch must retain original");
        drive.Valid = true; drive.WrongAccount = true; await backup.RunPendingAsync();
        if (!File.Exists(raw)) throw new Exception("Wrong account must retain original");
        drive.WrongAccount = false; drive.FailUpload = true; await backup.RunPendingAsync();
        if (!File.Exists(raw)) throw new Exception("Network failure must retain original");
        drive.FailUpload = false;
        // Recreate the service to verify durable retry after an app restart.
        backup = new DriveBackup(journal, drive); await backup.RunPendingAsync();
        if (File.Exists(raw) || !File.Exists(jpeg)) throw new Exception("Verified CR3 only must be deleted");
        int uploads = drive.Uploads; await backup.RunPendingAsync();
        if (drive.Uploads != uploads) throw new Exception("Completed job must not upload twice");
        foreach (string invalid in new[] { jpeg, Path.Combine(root, "outside.cr3"), Path.Combine(photos, "bad.cr3") }) {
            bool rejected = false; try { DriveBackup.SafeRelative(photos, invalid); } catch (IOException) { rejected = true; }
            if (!rejected) throw new Exception("Unsafe path accepted");
        }
        // Closing must cancel a stalled upload, retain its raw, and resume from disk.
        var blocked = new FakeDrive { BlockUpload = true };
        var paused = new DriveBackup(Path.Combine(root, "paused-jobs"), blocked);
        string pausedRaw = Path.Combine(folder, "paused.cr3"); File.WriteAllText(pausedRaw, "pause fixture");
        var pausedJob = paused.Begin("중지", photos); paused.Record(pausedJob, pausedRaw); paused.Seal(pausedJob);
        using var cancellation = new System.Threading.CancellationTokenSource();
        var running = paused.RunPendingAsync(cancellation.Token);
        await blocked.UploadStarted.Task.WaitAsync(TimeSpan.FromSeconds(3));
        await paused.RunPendingAsync().WaitAsync(TimeSpan.FromSeconds(1));
        if (blocked.Uploads != 1) throw new Exception("Retry must not queue a second worker");
        cancellation.Cancel();
        try { await running.WaitAsync(TimeSpan.FromSeconds(3)); throw new Exception("Cancellation ignored"); }
        catch (OperationCanceledException) { }
        if (!File.Exists(pausedRaw) || pausedJob.CompletedAt != null) throw new Exception("Cancelled raw lost");
        var recovered = new DriveBackup(Path.Combine(root, "paused-jobs"), new FakeDrive());
        await recovered.RunPendingAsync();
        if (File.Exists(pausedRaw)) throw new Exception("Cancelled job did not resume");
        var saved = System.Text.Json.JsonSerializer.Deserialize<BackupJob>(File.ReadAllText(Directory.GetFiles(journal, "*.json").Single()))!;
        if (saved.CompletedAt == null || saved.StartedAt == null || !saved.Files.Single().Deleted) throw new Exception("Completion timing/journal missing");
    }
    public static async Task LiveAsync()
    {
        // Upload and delete only an explicitly generated test fixture, never a camera photo.
        string id = Guid.NewGuid().ToString("N");
        string photos = Path.Combine(Path.GetTempPath(), "SM005-drive-probe-" + id);
        string path = Path.Combine(photos, DateTime.Now.ToString("yyyy-MM-dd"), "DOG_SM005_TEST_" + id, "connection-test.cr3");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllText(path, "SM005 disposable upload verification " + id);
        var backup = new DriveBackup(Path.Combine(photos, "jobs"), new RcloneDrive());
        var job = backup.Begin("연결 검증", photos); backup.Record(job, path); backup.Seal(job);
        await backup.RunPendingAsync();
        if (job.CompletedAt == null || File.Exists(path)) throw new Exception("Live backup failed: " + job.Error);
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "drive-verification.json"), System.Text.Json.JsonSerializer.Serialize(job, new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
    }
}


