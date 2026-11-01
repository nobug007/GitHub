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
    Task EnsureDirectoryAsync(string remote, CancellationToken cancellationToken = default) => Task.CompletedTask;
    Task CheckAccountAsync(CancellationToken cancellationToken = default);
    Task UploadAsync(string local, string remote, CancellationToken cancellationToken = default);
    Task<bool> MatchesAsync(string remote, long size, string md5, CancellationToken cancellationToken = default);
}

public sealed class RcloneDrive : IBackupDrive
{
    public async Task EnsureDirectoryAsync(string remote, CancellationToken cancellationToken = default) => await RunAsync(cancellationToken, "mkdir", "sm006:" + remote);
    public const string Account = "nobug007@gmail.com";
    public const string FolderId = "root";
    public static string ConfigPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShineMungSM006", "rclone.conf");
    private readonly string configPath;
    public RcloneDrive(string? configPath = null) { this.configPath = configPath ?? ConfigPath; }
    public static void ImportPreviousConnection()
    {
        if (File.Exists(ConfigPath)) return;
        var previous = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShineMungSM005", "rclone.conf");
        if (!File.Exists(previous)) return;
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        File.WriteAllText(ConfigPath, File.ReadAllText(previous).Replace("[sm005]", "[sm006]"));
    }
    public static async Task ConnectAsync(CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        string pending = ConfigPath + ".connecting";
        if (File.Exists(ConfigPath)) File.Copy(ConfigPath, pending, true);
        else File.WriteAllText(pending, "");
        var info = new ProcessStartInfo(Path.Combine(AppContext.BaseDirectory, "tools", "rclone.exe")) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        bool existing = File.ReadAllText(pending).Contains("[sm006]", StringComparison.Ordinal);
        foreach (var arg in existing ? new[] { "config", "update", "sm006", "type", "drive" } : new[] { "config", "create", "sm006", "drive" }) info.ArgumentList.Add(arg);
        foreach (var arg in new[] { "scope", "drive", "root_folder_id", FolderId, "config_refresh_token", "true", "config_shared_client_id", "true", "config_is_local", "true", "config_change_team_drive", "false", "--config", pending, "--no-output" }) info.ArgumentList.Add(arg);
        using var process = Process.Start(info) ?? throw new IOException("Google 연결 창을 열지 못했습니다.");
        var stdout = process.StandardOutput.ReadToEndAsync(); var stderr = process.StandardError.ReadToEndAsync();
        try {
            await process.WaitForExitAsync(cancellationToken);
            await stdout; await stderr; // Never display OAuth responses or tokens.
            if (process.ExitCode != 0) throw new IOException("Google 인증을 완료하지 못했습니다. 브라우저에서 계정을 확인하고 다시 연결하세요.");
            await new RcloneDrive(pending).CheckAccountAsync(cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            File.Move(pending, ConfigPath, true);
        } finally {
            if (!process.HasExited) { process.Kill(true); await process.WaitForExitAsync(); }
            if (File.Exists(pending)) File.Delete(pending);
        }
    }
    private async Task<string> RunAsync(CancellationToken cancellationToken, params string[] args)
    {
        for (int attempt = 0; ; attempt++) {
            try { return await RunOnceAsync(cancellationToken, args); }
            catch (IOException ex) when (attempt < 3 && (ex.Message.Contains("403") || ex.Message.Contains("429") || ex.Message.Contains("Quota exceeded") || ex.Message.Contains("rateLimitExceeded"))) {
                await Task.Delay(TimeSpan.FromSeconds(10 * (attempt + 1)), cancellationToken);
            }
        }
    }
    private async Task<string> RunOnceAsync(CancellationToken cancellationToken, params string[] args)
    {
        var exe = Path.Combine(AppContext.BaseDirectory, "tools", "rclone.exe");
        if (!File.Exists(exe)) throw new IOException("tools/rclone.exe가 없습니다. SM006를 다시 빌드하세요.");
        if (!File.Exists(configPath)) throw new IOException("관리자 메뉴에서 Google Drive를 연결해 주세요.");
        var info = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        foreach (var arg in new[] { "--config", configPath, "--drive-root-folder-id", FolderId, "--contimeout", "30s", "--timeout", "2m", "--retries", "3", "--low-level-retries", "3" }) info.ArgumentList.Add(arg);
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
        await RunAsync(cancellationToken, "about", "sm006:", "--json");
        bool section = false; string? tokenJson = null;
        foreach (string line in File.ReadLines(configPath)) {
            string value = line.Trim();
            if (value.StartsWith("[")) section = value == "[sm006]";
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
    public async Task UploadAsync(string local, string remote, CancellationToken cancellationToken = default) => await RunAsync(cancellationToken, "copyto", local, "sm006:" + remote, "--checksum", "--immutable");
    public async Task<bool> MatchesAsync(string remote, long size, string md5, CancellationToken cancellationToken = default)
    {
        using var result = JsonDocument.Parse(await RunAsync(cancellationToken, "lsjson", "sm006:" + remote, "--stat", "--hash", "--hash-type", "MD5"));
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
    public string SessionFolder { get; set; } = "";
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
            jobs.Add(job);
            if (job.CompletedAt == null) { job.Sealed = false; Seal(job); }
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
        var job = new BackupJob { Dog = dog, Root = StoragePaths.ValidateRoot(root) };
        job.SessionFolder = StoragePaths.CreateSessionFolder(job.Root, dog, DateTime.Now);
        Directory.CreateDirectory(Path.Combine(job.SessionFolder, "Printed"));
        lock (gate) { Save(job); jobs.Add(job); }
        return job;
    }
    public void Record(BackupJob job, string path)
    {
        if (!StoragePaths.IsPhoto(path)) return;
        lock (gate) {
            if (job.Sealed) throw new IOException("종료된 촬영에 파일이 도착했습니다. 원본을 확인하세요.");
            var relative = SafeRelative(job.Root, path);
            if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(job.SessionFolder).TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) throw new IOException("다른 촬영의 파일입니다.");
            if (job.Files.Any(f => f.Path == path)) return;
            job.Files.Add(new BackupFile { Path = path, Remote = "ShineMung/" + relative.Replace(Path.DirectorySeparatorChar, '/') }); Save(job);
        }
    }
    public void Seal(BackupJob job)
    {
        lock (gate) {
            if (!job.Sealed && Directory.Exists(job.SessionFolder)) {
                PrintedArchive.Recover(this, job);
                foreach (var path in Directory.EnumerateFiles(job.SessionFolder).Where(StoragePaths.IsPhoto)) Record(job, path);
                var printed = Path.Combine(job.SessionFolder, "Printed");
                if (Directory.Exists(printed)) foreach (var path in Directory.EnumerateFiles(printed).Where(StoragePaths.IsPhoto)) Record(job, path);
            }
            job.Sealed = true; Save(job);
        }
    }
    public void ForgetRecycled(BackupJob job, string path)
    {
        lock (gate) {
            if (job.Sealed || !StoragePaths.IsJpeg(path) || File.Exists(path)) return;
            job.Files.RemoveAll(f => f.Path == path); Save(job);
        }
    }
    internal static string SafeRelative(string root, string path)
    {
        string full = System.IO.Path.GetFullPath(path), basePath = System.IO.Path.GetFullPath(root).TrimEnd('\\', '/') + System.IO.Path.DirectorySeparatorChar;
        if (!full.StartsWith(basePath, StringComparison.OrdinalIgnoreCase) || !StoragePaths.IsPhoto(full)) throw new IOException("백업 대상 폴더 밖의 파일입니다.");
        var relative = System.IO.Path.GetRelativePath(root, full);
        var parts = relative.Split(System.IO.Path.DirectorySeparatorChar);
        if ((parts.Length != 3 && !(parts.Length == 4 && parts[2] == "Printed")) || !DateTime.TryParseExact(parts[0], "yyyy-MM-dd", null, System.Globalization.DateTimeStyles.None, out _) || !System.Text.RegularExpressions.Regex.IsMatch(parts[1], @"^.+_\d{6}_\d{3}(?:_\d+)?$")) throw new IOException("사진 폴더 구조가 올바르지 않습니다.");
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
            if (job.Files.Count == 0) { job.CompletedAt = DateTimeOffset.Now; Save(job); Report(job.Dog + " · 백업할 사진이 없습니다."); return; }
            await drive.CheckAccountAsync(cancellationToken);
            await drive.EnsureDirectoryAsync("ShineMung/" + Path.GetRelativePath(job.Root, job.SessionFolder).Replace('\\', '/') + "/Printed", cancellationToken);
            job.StartedAt ??= DateTimeOffset.Now; job.Error = ""; Save(job); timer.Restart();
            Report($"{job.Dog} · 사진 {job.Files.Count}개 백업 시작");
            // Finish and verify the entire session, including Printed, before any JPEG deletion.
            foreach (var file in job.Files) {
                SafeRelative(job.Root, file.Path);
                if (file.Remote != "ShineMung/" + Path.GetRelativePath(job.Root, file.Path).Replace(Path.DirectorySeparatorChar, '/')) throw new IOException("백업 경로가 변경되었습니다.");
                if (!File.Exists(file.Path)) {
                    if (!StoragePaths.IsJpeg(file.Path) || !file.Verified || !await drive.MatchesAsync(file.Remote, file.Size, file.Md5, cancellationToken)) throw new IOException("PC 파일이 없고 백업 확인이 불가능합니다: " + file.Path);
                    continue;
                }
                using var source = new FileStream(file.Path, FileMode.Open, FileAccess.Read, FileShare.Read);
                string hash = Convert.ToHexString(await MD5.HashDataAsync(source, cancellationToken));
                file.Size = source.Length; file.Md5 = hash; file.Verified = false; Save(job);
                await drive.UploadAsync(file.Path, file.Remote, cancellationToken);
                if (!await drive.MatchesAsync(file.Remote, file.Size, file.Md5, cancellationToken)) throw new IOException("업로드 크기/MD5 불일치: " + Path.GetFileName(file.Path));
                file.Verified = true; Save(job);
                Report($"{job.Dog} · 업로드 검증 {job.Files.Count(f => f.Verified)}/{job.Files.Count}");
            }
            foreach (var file in job.Files.Where(f => StoragePaths.IsJpeg(f.Path) && !f.Deleted)) {
                cancellationToken.ThrowIfCancellationRequested();
                SafeRelative(job.Root, file.Path);
                if (File.Exists(file.Path)) {
                    // Recheck local contents after the other uploads, before deleting.
                    using (var source = new FileStream(file.Path, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                        if (source.Length != file.Size || !string.Equals(Convert.ToHexString(await MD5.HashDataAsync(source, cancellationToken)), file.Md5, StringComparison.OrdinalIgnoreCase)) throw new IOException("백업 후 JPG가 변경되어 삭제를 중단했습니다.");
                    }
                    File.Delete(file.Path);
                }
                file.Deleted = true; Save(job);
            }
            job.CompletedAt = DateTimeOffset.Now;
            Report($"{job.Dog} · 전체 백업 완료 / JPG {job.Files.Count(f => f.Deleted)}개 삭제 / CR3 보관 · {job.StartedAt:HH:mm:ss} → {job.CompletedAt:HH:mm:ss} · 총 {(job.CompletedAt.Value - job.StartedAt!.Value).TotalSeconds:F1}초");
        } catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { job.Error = "백업 일시 중지 · 다음 실행 시 재개"; Report(job.Error); throw; }
        catch (Exception ex) { job.Error = ex.Message; Report(job.Dog + " · 백업/정리 미완료 · " + ex.Message); }
        finally { job.ActiveSeconds += timer.Elapsed.TotalSeconds; Save(job); }
    }
}
