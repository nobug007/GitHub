using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace ThreeScreen;

public interface IBackupDrive
{
    Task CheckAccountAsync(CancellationToken cancellationToken = default);
    Task UploadAsync(string local, string remote, CancellationToken cancellationToken = default);
    Task<bool> MatchesAsync(string remote, long size, string md5, CancellationToken cancellationToken = default);
}

public sealed class RcloneDrive : IBackupDrive
{
    public const string Account = "nobug007@gmail.com";
    public const string FolderId = "1gRB5LYbYu2TbqFG4-ExW2CpeFP5K5bqs";
    public static string ConfigPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShineMungSM004", "rclone.conf");
    private static async Task<string> RunAsync(CancellationToken cancellationToken, params string[] args)
    {
        for (int attempt = 0; ; attempt++) {
            try { return await RunOnceAsync(cancellationToken, args); }
            catch (IOException ex) when (attempt < 3 && (ex.Message.Contains("403") || ex.Message.Contains("429") || ex.Message.Contains("Quota exceeded") || ex.Message.Contains("rateLimitExceeded"))) {
                await Task.Delay(TimeSpan.FromSeconds(10 * (attempt + 1)), cancellationToken);
            }
        }
    }
    private static async Task<string> RunOnceAsync(CancellationToken cancellationToken, params string[] args)
    {
        var exe = Path.Combine(AppContext.BaseDirectory, "tools", "rclone.exe");
        if (!File.Exists(exe)) throw new IOException("tools/rclone.exe가 없습니다. SM004를 다시 빌드하세요.");
        if (!File.Exists(ConfigPath)) throw new IOException("Google Drive 연결이 필요합니다. Connect-Drive.cmd를 실행하세요.");
        var info = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        foreach (var arg in new[] { "--config", ConfigPath, "--drive-root-folder-id", FolderId, "--contimeout", "30s", "--timeout", "2m", "--retries", "3", "--low-level-retries", "3" }) info.ArgumentList.Add(arg);
        using var process = Process.Start(info) ?? throw new IOException("백업 도구를 시작하지 못했습니다.");
        var output = process.StandardOutput.ReadToEndAsync();
        var error = process.StandardError.ReadToEndAsync();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken); timeout.CancelAfter(TimeSpan.FromMinutes(10));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException) { if (!process.HasExited) process.Kill(true); await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)); cancellationToken.ThrowIfCancellationRequested(); throw new IOException("백업 응답 시간이 초과되었습니다. PC 원본을 보존했습니다."); }
        string result = await output, details = await error;
        if (process.ExitCode != 0) throw new IOException("Google Drive 처리 실패. 연결 또는 네트워크를 확인하고 백업 재시도를 누르세요. " + (details.Length > 600 ? details[..600] : details));
        return result;
    }
    public async Task CheckAccountAsync(CancellationToken cancellationToken = default)
    {
        // rclone refreshes its OAuth token before this API identity check.
        await RunAsync(cancellationToken, "about", "sm004:", "--json");
        bool section = false; string? tokenJson = null;
        foreach (string line in File.ReadLines(ConfigPath)) {
            string value = line.Trim();
            if (value.StartsWith("[")) section = value == "[sm004]";
            else if (section && value.StartsWith("token =", StringComparison.Ordinal)) tokenJson = value[(value.IndexOf('=') + 1)..].Trim();
        }
        if (tokenJson == null) throw new IOException("Google Drive 인증이 필요합니다.");
        using var token = JsonDocument.Parse(tokenJson);
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token.RootElement.GetProperty("access_token").GetString());
        string profileJson = "";
        for (int attempt = 0; ; attempt++) {
            using var response = await client.GetAsync("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", cancellationToken);
            if (response.IsSuccessStatusCode) { profileJson = await response.Content.ReadAsStringAsync(); break; }
            if (attempt < 3 && ((int)response.StatusCode == 403 || (int)response.StatusCode == 429)) { await Task.Delay(TimeSpan.FromSeconds(10 * (attempt + 1)), cancellationToken); continue; }
            throw new IOException("Google 계정 확인 실패: " + (int)response.StatusCode + " · 인증/요청 한도를 확인하고 재시도하세요.");
        }
        using var profile = JsonDocument.Parse(profileJson);
        if (!profile.RootElement.GetProperty("user").TryGetProperty("emailAddress", out var email) || !string.Equals(email.GetString(), Account, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Google Drive 계정이 " + Account + "과 다릅니다. 원본을 보존했습니다.");
    }
    public async Task UploadAsync(string local, string remote, CancellationToken cancellationToken = default) => await RunAsync(cancellationToken, "copyto", local, "sm004:" + remote, "--checksum", "--immutable");
    public async Task<bool> MatchesAsync(string remote, long size, string md5, CancellationToken cancellationToken = default)
    {
        using var result = JsonDocument.Parse(await RunAsync(cancellationToken, "lsjson", "sm004:" + remote, "--stat", "--hash", "--hash-type", "MD5"));
        var file = result.RootElement;
        return !file.GetProperty("IsDir").GetBoolean() && file.GetProperty("Size").GetInt64() == size &&
            file.TryGetProperty("Hashes", out var hashes) && hashes.TryGetProperty("md5", out var hash) &&
            string.Equals(hash.GetString(), md5, StringComparison.OrdinalIgnoreCase);
    }
}

public sealed class BackupJob
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Dog { get; set; } = "";
    public string Root { get; set; } = "";
    public bool Sealed { get; set; }
    public DateTimeOffset? StartedAt { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }
    public double ActiveSeconds { get; set; }
    public string Error { get; set; } = "";
    public List<BackupFile> Files { get; set; } = new();
}
public sealed class BackupFile
{
    public string Path { get; set; } = "";
    public string Remote { get; set; } = "";
    public long Size { get; set; }
    public string Md5 { get; set; } = "";
    public bool Verified { get; set; }
    public bool Deleted { get; set; }
}

public sealed class DriveBackup
{
    private readonly string journal;
    private readonly IBackupDrive drive;
    private readonly SemaphoreSlim worker = new(1, 1);
    private readonly object gate = new();
    private readonly List<BackupJob> jobs = new();
    public event Action<string>? Status;
    public DriveBackup(string journalFolder, IBackupDrive driveClient)
    {
        journal = journalFolder; drive = driveClient; Directory.CreateDirectory(journal);
        foreach (var path in Directory.EnumerateFiles(journal, "*.json")) {
            // A damaged journal must remain visible instead of silently losing its files.
            var job = JsonSerializer.Deserialize<BackupJob>(File.ReadAllText(path)) ?? throw new IOException("백업 기록을 읽지 못했습니다: " + path);
            job.Sealed = true; jobs.Add(job); Save(job);
        }
    }
    private void Save(BackupJob job)
    {
        if (!Guid.TryParseExact(job.Id, "N", out _)) throw new IOException("잘못된 백업 기록 ID");
        string path = System.IO.Path.Combine(journal, job.Id + ".json");
        File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(job, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(path + ".tmp", path, true);
    }
    public BackupJob Begin(string dog, string root)
    {
        var job = new BackupJob { Dog = dog, Root = System.IO.Path.GetFullPath(root) };
        lock (gate) { Save(job); jobs.Add(job); }
        return job;
    }
    public void Record(BackupJob job, string path)
    {
        if (!string.Equals(System.IO.Path.GetExtension(path), ".cr3", StringComparison.OrdinalIgnoreCase)) return;
        lock (gate) {
            if (job.Sealed) throw new IOException("종료된 촬영에 파일이 도착했습니다. 원본을 확인하세요.");
            var relative = SafeRelative(job.Root, path);
            if (job.Files.Any(f => f.Path == path)) return;
            job.Files.Add(new BackupFile { Path = path, Remote = relative.Replace('\\', '/') }); Save(job);
        }
    }
    public void Seal(BackupJob job) { lock (gate) { job.Sealed = true; Save(job); } }
    internal static string SafeRelative(string root, string path)
    {
        string full = System.IO.Path.GetFullPath(path), basePath = System.IO.Path.GetFullPath(root).TrimEnd('\\', '/') + System.IO.Path.DirectorySeparatorChar;
        if (!full.StartsWith(basePath, StringComparison.OrdinalIgnoreCase) || !full.EndsWith(".cr3", StringComparison.OrdinalIgnoreCase)) throw new IOException("백업 대상 폴더 밖의 파일입니다.");
        var relative = System.IO.Path.GetRelativePath(root, full);
        var parts = relative.Split(System.IO.Path.DirectorySeparatorChar);
        if (parts.Length != 3 || !DateTime.TryParseExact(parts[0], "yyyy-MM-dd", null, System.Globalization.DateTimeStyles.None, out _) || !parts[1].StartsWith("DOG_", StringComparison.Ordinal)) throw new IOException("사진 폴더 구조가 올바르지 않습니다.");
        for (FileSystemInfo? entry = new FileInfo(full); entry != null; entry = entry is FileInfo f ? f.Directory : ((DirectoryInfo)entry).Parent) {
            if (entry.Exists && entry.LinkTarget != null) throw new IOException("연결된 폴더 또는 파일은 자동 삭제할 수 없습니다.");
        }
        return relative;
    }
    private void Report(string value) => Status?.Invoke(value);
    public async Task RunPendingAsync(CancellationToken cancellationToken = default)
    {
        if (!await worker.WaitAsync(0, cancellationToken)) return;
        try {
            BackupJob[] pending;
            lock (gate) pending = jobs.Where(j => j.Sealed && j.CompletedAt == null).ToArray();
            foreach (var job in pending) await ProcessAsync(job, cancellationToken);
        } finally { worker.Release(); }
    }
    private async Task ProcessAsync(BackupJob job, CancellationToken cancellationToken)
    {
        var timer = Stopwatch.StartNew();
        try {
            if (job.Files.Count == 0) { job.CompletedAt = DateTimeOffset.Now; Save(job); Report(job.Dog + " · 백업할 CR3 파일이 없습니다."); return; }
            await drive.CheckAccountAsync(cancellationToken);
            job.StartedAt ??= DateTimeOffset.Now; job.Error = ""; Save(job);
            timer.Restart();
            Report($"{job.Dog} · CR3 {job.Files.Count}개 백업 시작 {job.StartedAt:HH:mm:ss}");
            foreach (var file in job.Files.Where(f => !f.Deleted)) {
                SafeRelative(job.Root, file.Path);
                if (file.Remote != System.IO.Path.GetRelativePath(job.Root, file.Path).Replace('\\', '/')) throw new IOException("백업 경로가 변경되었습니다.");
                if (!File.Exists(file.Path)) {
                    if (!file.Verified || !await drive.MatchesAsync(file.Remote, file.Size, file.Md5, cancellationToken)) throw new IOException("PC 원본이 없으며 클라우드 검증이 완료되지 않았습니다: " + file.Path);
                    file.Deleted = true; Save(job); continue;
                }
                // Deny writes and renames while uploading and verifying this exact local file.
                using (var source = new FileStream(file.Path, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                    file.Size = source.Length; file.Md5 = Convert.ToHexString(await MD5.HashDataAsync(source, cancellationToken)); file.Verified = false; Save(job);
                    await drive.UploadAsync(file.Path, file.Remote, cancellationToken);
                    if (!await drive.MatchesAsync(file.Remote, file.Size, file.Md5, cancellationToken)) throw new IOException("업로드 크기/MD5 불일치: " + System.IO.Path.GetFileName(file.Path));
                    file.Verified = true; Save(job);
                }
                SafeRelative(job.Root, file.Path);
                cancellationToken.ThrowIfCancellationRequested(); File.Delete(file.Path); file.Deleted = true; Save(job);
                Report($"{job.Dog} · CR3 백업 및 PC 정리 {job.Files.Count(f => f.Deleted)}/{job.Files.Count}");
            }
            job.CompletedAt = DateTimeOffset.Now;
            Report($"{job.Dog} · CR3 {job.Files.Count}개 백업·PC 삭제 완료 · {job.StartedAt:HH:mm:ss} → {job.CompletedAt:HH:mm:ss} · 총 {(job.CompletedAt.Value - job.StartedAt!.Value).TotalSeconds:F1}초");
        } catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { job.Error = "프로그램 종료로 백업 일시 중지 · 다음 실행 시 재개"; Report(job.Error); throw; } catch (Exception ex) { job.Error = ex.Message; Report(job.Dog + " · 백업 대기/실패 · 미완료 원본 보존 · " + ex.Message); }
        finally { job.ActiveSeconds += timer.Elapsed.TotalSeconds; Save(job); }
    }
}

