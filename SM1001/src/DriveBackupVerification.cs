using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Security.Cryptography;

namespace ThreeScreen;

internal static class DriveBackupVerification
{
    private sealed class FakeDrive : IBackupDrive
    {
        public bool WrongAccount, BadHash, FailUpload, BlockUpload;
        public readonly Dictionary<string, byte[]> Files = new();
        public Action? BeforeUpload;
        public readonly TaskCompletionSource Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public Task CheckAccountAsync(CancellationToken token = default) => WrongAccount ? Task.FromException(new IOException("wrong account")) : Task.CompletedTask;
        public async Task UploadAsync(string local, string remote, CancellationToken token = default) {
            BeforeUpload?.Invoke(); Started.TrySetResult();
            if (BlockUpload) await Task.Delay(Timeout.Infinite, token);
            if (FailUpload) throw new IOException("network failure");
            Files[remote] = File.ReadAllBytes(local);
        }
        public Task<bool> MatchesAsync(string remote, long size, string hash, CancellationToken token = default) => Task.FromResult(!BadHash && Files.TryGetValue(remote, out var bytes) && bytes.Length == size && Convert.ToHexString(MD5.HashData(bytes)) == hash);
    }
    public static async Task RunAsync()
    {
        string root = Path.Combine(Path.GetTempPath(), "SM1001-test-" + Guid.NewGuid().ToString("N"));
        string photos = Path.Combine(root, "photos"), journal = Path.Combine(root, "jobs");
        var drive = new FakeDrive(); var backup = new DriveBackup(journal, drive);
        var job = backup.Begin("몽이", photos);
        string raw = Path.Combine(job.SessionFolder, "one.CR3"); File.WriteAllText(raw, "raw original"); backup.Record(job, raw);
        var selected = Enumerable.Range(1, 8).Select(i => {
            string path = Path.Combine(job.SessionFolder, $"{i}.jpg"); File.WriteAllText(path, "jpeg fixture " + i); backup.Record(job, path);
            return new SessionPhoto(i, path, null!, true);
        }).ToArray();
        var print = new PrintedArchive(job, selected);
        if (job.Files.Count != 9) throw new Exception("Unsent print must not be registered");
        print.Commit(backup, job);
        if (job.Files.Count != 17 || job.Files.Count(f => f.Remote.Contains("/Printed/")) != 8) throw new Exception("Printed must contain exactly eight archived files");
        await backup.RunPendingAsync(); if (drive.Files.Count != 0) throw new Exception("Active session uploaded");
        backup.Seal(job);
        drive.BeforeUpload = () => { if (selected.Any(p => !File.Exists(p.Path))) throw new Exception("JPEG deleted before all uploads completed"); };
        drive.WrongAccount = true; await backup.RunPendingAsync();
        drive.WrongAccount = false; drive.FailUpload = true; await backup.RunPendingAsync();
        drive.FailUpload = false; drive.BadHash = true; await backup.RunPendingAsync();
        if (!File.Exists(raw) || selected.Any(p => !File.Exists(p.Path)) || job.CompletedAt != null) throw new Exception("Failure must preserve all originals");
        drive.BadHash = false;
        backup = new DriveBackup(journal, drive); await backup.RunPendingAsync();
        if (!File.Exists(raw) || selected.Any(p => File.Exists(p.Path)) || Directory.GetFiles(Path.Combine(job.SessionFolder, "Printed"), "*.jpg").Length != 0) throw new Exception("Only JPEG cleanup required; RAW must remain");
        if (drive.Files.Count != 17 || drive.Files.Keys.Any(p => !p.StartsWith("ShineMung/"))) throw new Exception("Cloud layout or file count wrong");
        var saved = System.Text.Json.JsonSerializer.Deserialize<BackupJob>(File.ReadAllText(Directory.GetFiles(journal, "*.json").Single()))!;
        if (saved.CompletedAt == null || saved.Files.Count(f => f.Deleted) != 16 || saved.Files.Single(f => f.Path == raw).Deleted) throw new Exception("Completion/deletion journal wrong");
        var second = backup.Begin("몽이", Path.Combine(root, "new-root"));
        if (second.Root == job.Root || second.SessionFolder == job.SessionFolder || !Directory.Exists(Path.Combine(second.SessionFolder, "Printed"))) throw new Exception("Storage root/session isolation failed");
        string same1 = StoragePaths.CreateSessionFolder(photos, "A:B", new DateTime(2026, 10, 19, 23, 59, 59));
        string same2 = StoragePaths.CreateSessionFolder(photos, "A:B", new DateTime(2026, 10, 19, 23, 59, 59));
        if (same1 == same2 || !same1.Contains("2026-10-19") || !Path.GetFileName(same1).StartsWith("A_B_235959")) throw new Exception("Session naming/collision failed");
        foreach (string invalid in new[] { Path.Combine(root, "outside.jpg"), Path.Combine(photos, "bad.cr3"), Path.Combine(job.SessionFolder, "Printed", "nested", "x.jpg") }) {
            bool rejected = false; try { DriveBackup.SafeRelative(photos, invalid); } catch (IOException) { rejected = true; }
            if (!rejected) throw new Exception("Unsafe path accepted");
        }
        var blocked = new FakeDrive { BlockUpload = true };
        string pauseJournal = Path.Combine(root, "paused"); var paused = new DriveBackup(pauseJournal, blocked);
        var pj = paused.Begin("중지", photos); string jpg = Path.Combine(pj.SessionFolder, "p.jpg"); File.WriteAllText(jpg, "pause"); paused.Record(pj, jpg); paused.Seal(pj);
        using var cancel = new CancellationTokenSource(); var task = paused.RunPendingAsync(cancel.Token);
        await blocked.Started.Task.WaitAsync(TimeSpan.FromSeconds(3)); cancel.Cancel();
        try { await task; throw new Exception("Cancellation ignored"); } catch (OperationCanceledException) { }
        if (!File.Exists(jpg)) throw new Exception("Cancelled JPEG lost");
        blocked.BlockUpload = false; await new DriveBackup(pauseJournal, blocked).RunPendingAsync();
        if (File.Exists(jpg)) throw new Exception("Restart retry failed");
    }
    public static async Task LiveAsync()
    {
        string root = Path.Combine(Path.GetTempPath(), "SM1001-probe-" + Guid.NewGuid().ToString("N"));
        var backup = new DriveBackup(Path.Combine(root, "jobs"), new RcloneDrive());
        var job = backup.Begin("SM1001_TEST", Path.Combine(root, "photos"));
        string jpg = Path.Combine(job.SessionFolder, "probe.jpg"), raw = Path.Combine(job.SessionFolder, "probe.cr3");
        File.WriteAllText(jpg, "disposable JPEG test"); File.WriteAllText(raw, "disposable RAW test"); backup.Record(job, jpg); backup.Record(job, raw); backup.Seal(job);
        await backup.RunPendingAsync();
        if (job.CompletedAt == null || File.Exists(jpg) || !File.Exists(raw)) throw new Exception(job.Error);
        File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "drive-verification.json"), System.Text.Json.JsonSerializer.Serialize(job));
    }
}
