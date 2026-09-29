using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.VisualBasic.FileIO;

namespace ThreeScreen;

public sealed record SessionPhoto(int Number, string Path, BitmapSource Thumbnail, bool Captured);
public sealed record PhotoQuality(double Sharpness, double Contrast, double Exposure, double[] Signature)
{
    public double CenterSharpness { get; init; } = double.NaN;
    public double CenterContrast { get; init; } = double.NaN;
    public double Score => Math.Log(1 + Sharpness) * Exposure;
}
public sealed record AssessedPhoto(SessionPhoto Photo, PhotoQuality Quality);

// Local, deterministic screening. This is not an eye/face or aesthetic model.
public static class PhotoSelection
{
    public static bool Supports(string path) => System.IO.Path.GetExtension(path).ToLowerInvariant() is ".jpg" or ".jpeg" or ".png" or ".bmp";
    public static PhotoQuality Analyze(BitmapSource source)
    {
        double scale = Math.Min(1, 1024.0 / Math.Max(source.PixelWidth, source.PixelHeight));
        BitmapSource resized = scale < 1 ? new TransformedBitmap(source, new ScaleTransform(scale, scale)) : source;
        var gray = new FormatConvertedBitmap(resized, PixelFormats.Gray8, null, 0);
        int w = gray.PixelWidth, h = gray.PixelHeight;
        if (w < 64 || h < 64) throw new InvalidDataException("분석하기에 사진 크기가 너무 작습니다.");
        var pixels = new byte[w * h]; gray.CopyPixels(pixels, w, 0);
        // Center 70%: do not let a sharp projected background dominate the decision.
        double centerContrast = 0;
        var tiles = new List<double>(); double sum = 0, sumSq = 0; int count = 0, clipped = 0;
        for (int ty = 0; ty < 3; ty++) for (int tx = 0; tx < 3; tx++)
        {
            double energy = 0, tileSum = 0, tileSumSq = 0; int n = 0;
            int x0 = (int)(w * (.15 + tx * .7 / 3)), x1 = (int)(w * (.15 + (tx + 1) * .7 / 3));
            int y0 = (int)(h * (.15 + ty * .7 / 3)), y1 = (int)(h * (.15 + (ty + 1) * .7 / 3));
            for (int y = Math.Max(1, y0); y < Math.Min(h - 1, y1); y++) for (int x = Math.Max(1, x0); x < Math.Min(w - 1, x1); x++)
            {
                int i = y * w + x; double v = pixels[i];
                double lap = 4 * v - pixels[i - 1] - pixels[i + 1] - pixels[i - w] - pixels[i + w];
                energy += lap * lap; tileSum += v; tileSumSq += v * v; n++; sum += v; sumSq += v * v; count++; if (v < 5 || v > 250) clipped++;
            }
            tiles.Add(energy / Math.Max(1, n));
            if (tx == 1 && ty == 1) centerContrast = Math.Sqrt(Math.Max(0, tileSumSq / n - Math.Pow(tileSum / n, 2)));
        }
        double centerSharpness = tiles[4];
        tiles.Sort();
        double contrast = Math.Sqrt(Math.Max(0, sumSq / count - Math.Pow(sum / count, 2)));
        // Average the sharper half of the central tiles to tolerate intentional shallow depth of field.
        double sharpness = tiles.Skip(4).Average();
        double exposure = Math.Max(.15, 1 - (double)clipped / count);
        // Block averages of central subject and whole image; small exposure shifts are normalized.
        var signature = new double[32 * 32 * 2];
        for (int region = 0; region < 2; region++)
        {
            double margin = region == 0 ? 0 : .2, span = 1 - margin * 2;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
            {
                int x0 = (int)(w * (margin + x * span / 32)), x1 = Math.Max(x0 + 1, (int)(w * (margin + (x + 1) * span / 32)));
                int y0 = (int)(h * (margin + y * span / 32)), y1 = Math.Max(y0 + 1, (int)(h * (margin + (y + 1) * span / 32)));
                double total = 0; int n = 0;
                for (int yy = y0; yy < Math.Min(h, y1); yy++) for (int xx = x0; xx < Math.Min(w, x1); xx++) { total += pixels[yy * w + xx]; n++; }
                signature[region * 1024 + y * 32 + x] = total / n;
            }
            double mean = signature.Skip(region * 1024).Take(1024).Average();
            double sd = Math.Sqrt(signature.Skip(region * 1024).Take(1024).Average(v => (v - mean) * (v - mean)));
            for (int i = region * 1024; i < (region + 1) * 1024; i++) signature[i] = (signature[i] - mean) / Math.Max(10, sd);
        }
        return new(sharpness, contrast, exposure, signature) { CenterSharpness = centerSharpness, CenterContrast = centerContrast };
    }
    public static bool Similar(PhotoQuality a, PhotoQuality b)
    {
        for (int region = 0; region < 2; region++)
        {
            double error = 0;
            for (int i = region * 1024; i < (region + 1) * 1024; i++) error += Math.Pow(a.Signature[i] - b.Signature[i], 2);
            if (Math.Sqrt(error / 1024) > (region == 0 ? .24 : .32)) return false;
        }
        return true;
    }
    private static IReadOnlyList<AssessedPhoto> ConservativeBlurred(IReadOnlyList<AssessedPhoto> photos) => photos.Where(p =>
        p.Quality.Contrast >= 12 && p.Quality.Exposure >= .65 &&
        (p.Quality.Sharpness < 7 || (p.Quality.Sharpness < 90 && photos.Any(other =>
            other != p && other.Quality.Sharpness > 120 && other.Quality.Sharpness > p.Quality.Sharpness * 5 && Similar(other.Quality, p.Quality))))).ToArray();

    public static IReadOnlyList<AssessedPhoto> Blurred(IReadOnlyList<AssessedPhoto> photos)
    {
        var severe = ConservativeBlurred(photos).Select(p => p.Photo.Number).ToHashSet();
        // Background detail must not rescue a clearly soft central subject. Use the same
        // normalized 1024px scale for both measures; flat centers stay undecided.
        return photos.Where(p => severe.Contains(p.Photo.Number) ||
            (p.Quality.Contrast >= 12 && p.Quality.Exposure >= .65 &&
             p.Quality.CenterContrast >= 12 && p.Quality.CenterSharpness < 150 && p.Quality.Sharpness < 180)).ToArray();
    }
    public static IReadOnlyList<AssessedPhoto> Recommend(IReadOnlyList<AssessedPhoto> photos)
    {
        var bad = ConservativeBlurred(photos).Select(p => p.Photo.Number).ToHashSet();
        var result = new List<AssessedPhoto>();
        foreach (var item in photos.Where(p => !bad.Contains(p.Photo.Number)).OrderByDescending(p => p.Quality.Score).ThenBy(p => p.Photo.Number))
        {
            if (result.All(p => !Similar(p.Quality, item.Quality))) result.Add(item);
            if (result.Count == 8) break;
        }
        return result;
    }
    public static void Recycle(SessionPhoto photo, string root, Action<string>? recycle = null)
    {
        var path = System.IO.Path.GetFullPath(photo.Path);
        var folder = System.IO.Path.GetFullPath(root).TrimEnd(System.IO.Path.DirectorySeparatorChar) + System.IO.Path.DirectorySeparatorChar;
        if (!photo.Captured || !path.StartsWith(folder, StringComparison.OrdinalIgnoreCase) || !Supports(path))
            throw new InvalidOperationException("이번 촬영에서 저장된 사진만 삭제할 수 있습니다.");
        // Reject actual symbolic links/junctions; OneDrive cloud placeholders are not path redirects.
        for (var check = new FileInfo(path).Directory; check != null && check.FullName.Length >= folder.Length - 1; check = check.Parent)
            if (check.LinkTarget != null) throw new IOException("연결된 폴더는 삭제 대상에서 제외합니다.");
        if (new FileInfo(path).LinkTarget != null) throw new IOException("연결된 파일은 삭제하지 않습니다.");
        if (recycle != null) recycle(path);
        else FileSystem.DeleteFile(path, UIOption.OnlyErrorDialogs, RecycleOption.SendToRecycleBin, UICancelOption.ThrowException);
    }
}
