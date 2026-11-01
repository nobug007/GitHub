using System;
using System.IO;

namespace ThreeScreen;

public static class StoragePaths
{
    public static bool IsJpeg(string path) => Path.GetExtension(path).Equals(".jpg", StringComparison.OrdinalIgnoreCase) || Path.GetExtension(path).Equals(".jpeg", StringComparison.OrdinalIgnoreCase);
    public static bool IsPhoto(string path) => IsJpeg(path) || new[] { ".cr3", ".cr2", ".crw" }.ContainsIgnoreCase(Path.GetExtension(path));
    private static bool ContainsIgnoreCase(this string[] values, string value) => Array.Exists(values, x => x.Equals(value, StringComparison.OrdinalIgnoreCase));
    public static string CreateSessionFolder(string root, string dog, DateTime time)
    {
        string name = string.Join("_", dog.Split(Path.GetInvalidFileNameChars())).Trim().TrimEnd('.');
        if (name.Length > 60) name = name[..60];
        if (string.IsNullOrWhiteSpace(name)) name = "강아지";
        string parent = Path.Combine(Path.GetFullPath(root), time.ToString("yyyy-MM-dd"));
        string stem = name + "_" + time.ToString("HHmmss_fff");
        string folder = Path.Combine(parent, stem);
        for (int suffix = 2; Directory.Exists(folder); suffix++) folder = Path.Combine(parent, stem + "_" + suffix);
        Directory.CreateDirectory(folder); return folder;
    }
    public static string ValidateRoot(string root)
    {
        if (string.IsNullOrWhiteSpace(root) || !Path.IsPathFullyQualified(root)) throw new IOException("저장 루트는 절대 경로로 지정해 주세요.");
        string full = Path.GetFullPath(root); Directory.CreateDirectory(full);
        using var probe = new FileStream(Path.Combine(full, Guid.NewGuid().ToString("N") + ".write-test"), FileMode.CreateNew, FileAccess.Write, FileShare.None, 1, FileOptions.DeleteOnClose);
        probe.WriteByte(0); return full;
    }
}
