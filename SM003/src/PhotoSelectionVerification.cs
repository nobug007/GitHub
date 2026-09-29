using System;
using System.IO;
using System.Linq;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace ThreeScreen;

internal static class PhotoSelectionVerification
{
    public static void Run()
    {
        const int size = 256;
        var pixels = new byte[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            pixels[y * size + x] = (byte)(55 + (x / 32 % 2) * 50 + (y / 32 % 2) * 50 + (x / 3 % 2) * 25);
        BitmapSource Make(byte[] data) { var b = BitmapSource.Create(size, size, 96, 96, PixelFormats.Gray8, null, data, size); b.Freeze(); return b; }
        byte[] Blur(byte[] input, bool horizontal)
        {
            var output = new byte[input.Length];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int total = 0, n = 0;
                for (int k = -6; k <= 6; k++) { int xx = Math.Clamp(x + (horizontal ? k : 0), 0, size - 1), yy = Math.Clamp(y + (horizontal ? 0 : k), 0, size - 1); total += input[yy * size + xx]; n++; }
                output[y * size + x] = (byte)(total / n);
            }
            return output;
        }
        var sharp = PhotoSelection.Analyze(Make(pixels));
        var blurredPixels = pixels;
        for (int i = 0; i < 3; i++) blurredPixels = Blur(Blur(blurredPixels, true), false);
        var blurred = PhotoSelection.Analyze(Make(blurredPixels));
        if (sharp.Sharpness <= blurred.Sharpness * 5) throw new Exception("Blur discrimination failed");
        var thumbnail = Make(pixels);
        AssessedPhoto Entry(int n, PhotoQuality quality) => new(new SessionPhoto(n, $"{n}.jpg", thumbnail, true), quality);
        var pair = new[] { Entry(1, sharp), Entry(2, blurred) };
        if (!PhotoSelection.Blurred(pair).Any(p => p.Photo.Number == 2) || PhotoSelection.Blurred(pair).Any(p => p.Photo.Number == 1))
            throw new Exception($"Blur screening failed: sharp={sharp.Sharpness}, blurred={blurred.Sharpness}");
        var subjectBlur = Entry(11, sharp with { Sharpness = 104, CenterSharpness = 86, CenterContrast = 35 });
        var sharpSubject = Entry(12, sharp with { Sharpness = 633, CenterSharpness = 569, CenterContrast = 35 });
        var flatCenter = Entry(13, sharp with { Sharpness = 104, CenterSharpness = 10, CenterContrast = 3 });
        var centralDecision = PhotoSelection.Blurred(new[] { subjectBlur, sharpSubject, flatCenter });
        if (centralDecision.Count != 1 || centralDecision[0].Photo.Number != 11) throw new Exception("Blurred subject/sharp background regression failed");
        var brighter = PhotoSelection.Analyze(Make(pixels.Select(v => (byte)(v + 10)).ToArray()));
        if (!PhotoSelection.Similar(sharp, brighter)) throw new Exception("Exposure-shift duplicate failed");
        var flat = PhotoSelection.Analyze(Make(Enumerable.Repeat((byte)120, size * size).ToArray()));
        if (PhotoSelection.Blurred(new[] { Entry(3, flat) }).Count != 0) throw new Exception("Low texture must not be auto deleted");
        if (PhotoSelection.Recommend(new[] { Entry(1, sharp), Entry(2, sharp), Entry(3, brighter) }).Count != 1)
            throw new Exception("Near-duplicate exclusion failed");
        var distinct = Enumerable.Range(1, 12).Select(n => Entry(n, new PhotoQuality(200 + n, 40, .9,
            Enumerable.Range(0, 2048).Select(i => Math.Sin(i * n * .15)).ToArray()))).ToArray();
        var best = PhotoSelection.Recommend(distinct);
        if (best.Count != 8 || best[0].Photo.Number != 12) throw new Exception("Best eight ranking failed");
        if (PhotoSelection.Recommend(Array.Empty<AssessedPhoto>()).Count != 0) throw new Exception("Empty selection failed");

        // Only disposable fixture files; the injected recycle call never deletes user photos.
        var root = Path.Combine(Path.GetTempPath(), "SM003-selection-verification-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        var path = Path.Combine(root, "fixture.jpg"); File.WriteAllBytes(path, new byte[] { 1 });
        var owned = new SessionPhoto(1, path, thumbnail, true); string? recycled = null;
        PhotoSelection.Recycle(owned, root, p => recycled = p);
        if (recycled != path) throw new Exception("Recycle destination failed");
        void MustReject(SessionPhoto photo, string folder) {
            try { PhotoSelection.Recycle(photo, folder, _ => throw new Exception("Unexpected recycle")); throw new Exception("Unsafe deletion accepted"); }
            catch (InvalidOperationException) { }
        }
        MustReject(owned with { Captured = false }, root);
        MustReject(owned, root + "-other");
        MustReject(owned with { Path = Path.Combine(root, "raw.cr3") }, root);
        bool failed = false;
        try { PhotoSelection.Recycle(owned, root, _ => throw new IOException("fixture recycle failure")); } catch (IOException) { failed = true; }
        if (!failed) throw new Exception("Recycle failure must propagate");
    }
}
