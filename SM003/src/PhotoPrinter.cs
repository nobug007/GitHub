using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Printing;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Xml.Linq;
using System.Windows.Xps.Packaging;

namespace ThreeScreen;

internal static class PhotoPrinter
{
    private static readonly XNamespace F = "http://schemas.microsoft.com/windows/2003/08/printing/printschemaframework";
    private static string Local(XElement element) => ((string?)element.Attribute("name") ?? "").Split(':').Last();
    internal static PrintQueue FindQueue(LocalPrintServer server)
    {
        var matches = server.GetPrintQueues().Where(q => q.Name.Equals("DS-RX1HS", StringComparison.OrdinalIgnoreCase) || q.Name.Equals("DS-RX1", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (matches.Length != 1) throw new InvalidOperationException("DS-RX1HS 또는 DS-RX1 프린터를 한 대 등록해 주세요.");
        return matches[0];
    }
    internal static PrintTicket Ticket(PrintQueue queue)
    {
        using var capsStream = queue.GetPrintCapabilitiesAsXml(); var caps = XDocument.Load(capsStream);
        var baseTicket = queue.DefaultPrintTicket ?? new PrintTicket();
        using var ticketStream = baseTicket.GetXmlStream(); var xml = XDocument.Load(ticketStream);
        void SetOption(string feature, string option)
        {
            var supported = caps.Descendants(F + "Feature").FirstOrDefault(x => Local(x) == feature) ?? throw new InvalidOperationException("드라이버 설정 없음: " + feature);
            var choice = supported.Elements(F + "Option").FirstOrDefault(x => Local(x) == option) ?? throw new InvalidOperationException("드라이버 옵션 없음: " + option);
            // Preserve namespaces used in vendor QName attributes.
            foreach (var ns in caps.Root!.Attributes().Where(a => a.IsNamespaceDeclaration)) xml.Root!.SetAttributeValue(ns.Name, ns.Value);
            xml.Root!.Elements(F + "Feature").Where(x => Local(x) == feature).Remove();
            var selected = new XElement(choice); selected.Attribute("constrained")?.Remove(); selected.Elements(F + "Property").Remove();
            xml.Root!.Add(new XElement(F + "Feature", new XAttribute("name", (string)supported.Attribute("name")!), selected));
        }
        SetOption("PageMediaSize", "PC"); // Driver's (6x4), including its required bleed dimensions.
        SetOption("DocumentCUTTERCONTROL", "CUT_STANDARD"); // Disable 2-inch cuts; cut each 4-inch print normally.
        using var ms = new MemoryStream(); xml.Save(ms); ms.Position = 0;
        var requested = new PrintTicket(ms) { CopyCount = 1, PagesPerSheet = 1, PageOrientation = PageOrientation.Portrait, Duplexing = Duplexing.OneSided };
        var result = queue.MergeAndValidatePrintTicket(baseTicket, requested).ValidatedPrintTicket;
        using var validatedStream = result.GetXmlStream(); var validated = XDocument.Load(validatedStream);
        string? Selected(string feature) => validated.Descendants(F + "Feature").FirstOrDefault(x => Local(x) == feature)?.Elements(F + "Option").Select(Local).FirstOrDefault();
        if (Selected("PageMediaSize") != "PC" || Selected("DocumentCUTTERCONTROL") != "CUT_STANDARD" || result.CopyCount != 1 || result.PagesPerSheet != 1 || result.PageOrientation != PageOrientation.Portrait)
            throw new InvalidOperationException("드라이버가 6×4 / 표준 절단 / 1부 설정을 적용하지 못했습니다. 출력하지 않았습니다.");
        return result;
    }
    internal static BitmapSource Compose(IReadOnlyList<BitmapSource> photos)
    {
        if (photos.Count != 4) throw new ArgumentException("한 장에는 사진 4개가 필요합니다.");
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen()) {
            dc.DrawRectangle(Brushes.White, null, new Rect(0, 0, 576, 384));
            for (int i = 0; i < 4; i++) {
                var cell = new Rect(12 + i % 2 * 282, 12 + i / 2 * 186, 270, 174);
                var photo = photos[i]; double factor = Math.Min(cell.Width / photo.PixelWidth, cell.Height / photo.PixelHeight);
                double w = photo.PixelWidth * factor, h = photo.PixelHeight * factor;
                dc.DrawImage(photo, new Rect(cell.X + (cell.Width - w) / 2, cell.Y + (cell.Height - h) / 2, w, h));
            }
        }
        var image = new RenderTargetBitmap(1800, 1200, 300, 300, PixelFormats.Pbgra32); image.Render(visual); image.Freeze(); return image;
    }
    private static BitmapSource Load(string path)
    {
        // Detach a print-sized image from the large camera original before loading the next.
        var original = PhotoDecoder.Decode(File.ReadAllBytes(path));
        double scale = Math.Min(1, 1200.0 / Math.Max(original.PixelWidth, original.PixelHeight));
        int w = Math.Max(1, (int)(original.PixelWidth * scale)), h = Math.Max(1, (int)(original.PixelHeight * scale));
        var v = new DrawingVisual(); using (var dc = v.RenderOpen()) dc.DrawImage(original, new Rect(0, 0, w, h));
        var result = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32); result.Render(v); result.Freeze(); return result;
    }
    internal static FixedDocument Document(IReadOnlyList<BitmapSource> sheets, double width, double height)
    {
        if (sheets.Count != 2) throw new ArgumentException("인화지 2장이 필요합니다.");
        var doc = new FixedDocument();
        foreach (var sheet in sheets) {
            var page = new FixedPage { Width = width, Height = height, Background = Brushes.White };
            // Center nominal 6x4 within the driver's overscan area; white safety margins protect faces.
            var image = new Image { Source = sheet, Width = 576, Height = 384, Stretch = Stretch.Fill };
            FixedPage.SetLeft(image, (width - 576) / 2); FixedPage.SetTop(image, (height - 384) / 2); page.Children.Add(image);
            var content = new PageContent { Child = page }; doc.Pages.Add(content);
        }
        return doc;
    }
    public static Task<string> PrintAsync(IReadOnlyList<SessionPhoto> photos)
    {
        if (photos.Count != 8 || photos.Select(p => p.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() != 8) throw new ArgumentException("추천 사진 8장이 필요합니다.");
        var paths = photos.Select(p => p.Path).ToArray();
        var completion = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() => {
            bool sending = false;
            try {
                using var server = new LocalPrintServer(); using var queue = FindQueue(server); queue.Refresh();
                if (queue.IsOffline || queue.IsInError || ((queue.QueueStatus & PrintQueueStatus.PaperOut) != 0) || queue.IsPaused) throw new InvalidOperationException("프린터 전원·용지·대기열 상태를 확인해 주세요.");
                var ticket = Ticket(queue);
                var sheets = new List<BitmapSource>();
                for (int page = 0; page < 2; page++) sheets.Add(Compose(paths.Skip(page * 4).Take(4).Select(Load).ToArray()));
                var size = ticket.PageMediaSize ?? throw new InvalidOperationException("용지 크기를 읽을 수 없습니다.");
                var doc = Document(sheets, size.Width!.Value, size.Height!.Value);
                queue.CurrentJobSettings.Description = "샤인멍 · 추천 8장 · 4컷 2매";
                sending = true;
                PrintQueue.CreateXpsDocumentWriter(queue).Write(doc.DocumentPaginator, ticket);
                completion.SetResult(queue.Name + "에 4컷 인화지 2장 전송 완료 · 실제 출력은 프린터에서 확인하세요.");
            } catch (Exception ex) { completion.SetException(new InvalidOperationException((sending ? "출력 전송 중 오류 · 일부 출력됐을 수 있으니 재출력 전 대기열을 확인하세요. " : "출력 전 확인 실패 · ") + ex.Message, ex)); }
        }) { IsBackground = true, Name = "SM003.Print.STA" };
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); return completion.Task;
    }
    internal static void Verify()
    {
        var output = Path.Combine(AppContext.BaseDirectory, "verification"); Directory.CreateDirectory(output);
        var sheets = Enumerable.Range(0, 2).Select(page => Compose(Backgrounds.All.Skip(page * 4).Take(4).Select(b => (BitmapSource)b.Image).ToArray())).ToArray();
        for (int i = 0; i < 2; i++) {
            if (sheets[i].PixelWidth != 1800 || sheets[i].PixelHeight != 1200) throw new Exception("Print resolution failed");
            var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(sheets[i])); using var file = File.Create(Path.Combine(output, $"print-{i + 1}.png")); png.Save(file);
        }
        var doc = Document(sheets, 590, 397); if (doc.Pages.Count != 2) throw new Exception("Two sheet pagination failed");
        using var server = new LocalPrintServer(); using var queue = FindQueue(server); var ticket = Ticket(queue);
        var media = ticket.PageMediaSize!;
        var printable = Document(sheets, media.Width!.Value, media.Height!.Value);
        var xpsPath = Path.Combine(output, "print-preview.xps");
        using (var xps = new XpsDocument(xpsPath, FileAccess.Write)) XpsDocument.CreateXpsDocumentWriter(xps).Write(printable.DocumentPaginator, ticket);
        using (var xps = new XpsDocument(xpsPath, FileAccess.Read)) {
            var sequence = xps.GetFixedDocumentSequence(); sequence.DocumentPaginator.ComputePageCount();
            if (sequence.DocumentPaginator.PageCount != 2) throw new Exception("Serialized print job must contain exactly two pages");
        }
        try { PrintAsync(Array.Empty<SessionPhoto>()); throw new Exception("Incomplete selection accepted"); }
        catch (ArgumentException) { }
        using var xml = ticket.GetXmlStream(); using var target = File.Create(Path.Combine(output, "print-ticket.xml")); xml.CopyTo(target);
        File.WriteAllText(Path.Combine(output, "printer.txt"), queue.Name + " · (6x4) PC · CUT_STANDARD · 1 copy · 2 pages. No physical print submitted.");
    }
}
