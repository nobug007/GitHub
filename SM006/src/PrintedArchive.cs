using System;
using System.IO;
using System.Linq;

namespace ThreeScreen;

internal sealed class PrintedArchive
{
    public SessionPhoto[] Photos { get; }
    private readonly string sessionFolder;
    private readonly string batch = DateTime.Now.ToString("HHmmss_fff") + "_" + Guid.NewGuid().ToString("N")[..8];
    public PrintedArchive(BackupJob job, SessionPhoto[] selected)
    {
        if (selected.Length != 8 || selected.Select(p => p.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() != 8) throw new IOException("서로 다른 사진 8장이 필요합니다.");
        sessionFolder = job.SessionFolder;
        string staging = Path.Combine(sessionFolder, ".print-pending-" + batch); Directory.CreateDirectory(staging);
        Photos = selected.Select((photo, i) => {
            DriveBackup.SafeRelative(job.Root, photo.Path);
            if (!photo.Captured || !Path.GetFullPath(photo.Path).StartsWith(Path.GetFullPath(sessionFolder).TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) throw new IOException("이번 촬영의 사진만 인쇄 보관할 수 있습니다.");
            string path = Path.Combine(staging, $"{batch}_{i + 1:00}_" + Path.GetFileName(photo.Path));
            File.Copy(photo.Path, path, false);
            return new SessionPhoto(photo.Number, path, photo.Thumbnail, true);
        }).ToArray();
    }
    public void Commit(DriveBackup backup, BackupJob job)
    {
        string staging = Path.GetDirectoryName(Photos[0].Path)!;
        File.WriteAllText(Path.Combine(staging, "submitted.json"), System.Text.Json.JsonSerializer.Serialize(Photos.Select(p => Path.GetFileName(p.Path)).ToArray()));
        Recover(backup, job);
    }
    public static void Recover(DriveBackup backup, BackupJob job)
    {
        if (!Directory.Exists(job.SessionFolder)) return;
        foreach (string staging in Directory.EnumerateDirectories(job.SessionFolder, ".print-pending-*")) {
            string receipt = Path.Combine(staging, "submitted.json");
            if (!File.Exists(receipt)) continue;
            string[] names = System.Text.Json.JsonSerializer.Deserialize<string[]>(File.ReadAllText(receipt)) ?? Array.Empty<string>();
            if (names.Length != 8 || names.Distinct().Count() != 8 || names.Any(n => Path.GetFileName(n) != n || !StoragePaths.IsPhoto(n))) throw new IOException("인쇄 보관 기록을 확인해 주세요.");
            string folder = Path.Combine(job.SessionFolder, "Printed"); Directory.CreateDirectory(folder);
            foreach (string name in names) {
                string source = Path.Combine(staging, name), target = Path.Combine(folder, name);
                if (File.Exists(source)) File.Move(source, target);
                if (!File.Exists(target)) throw new IOException("인쇄 보관 파일이 없습니다: " + name);
                backup.Record(job, target);
            }
            File.Delete(receipt); Directory.Delete(staging);
        }
    }
}
