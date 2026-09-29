using System;
using System.IO;
using System.Text.Json;

namespace ThreeScreen;

public sealed class Settings
{
    public string DogName { get; set; } = "";
    public bool PhotoPortrait { get; set; }
    public string Coat { get; set; } = "흑색";
    public string[] Selected { get; set; } = { "flowers", "ocean" };
    public string MainScreen { get; set; } = "";
    public bool Swapped { get; set; }
    public string PhotoFolder { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "ShineMung3Screen");
    public static string FilePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShineMung3Screen", "settings.json");
    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new(); }
        catch { return new(); }
    }
    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        var temp = FilePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, FilePath, true);
    }
}
