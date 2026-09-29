using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace ThreeScreen;

public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        bool verify = args.Contains("--verify");
        using var instance = new System.Threading.Mutex(false, "Local\\ShineMung3Screen.SingleInstance");
        bool ownsInstance = false;
        if (!verify)
        {
            try { ownsInstance = instance.WaitOne(0); }
            catch (System.Threading.AbandonedMutexException) { ownsInstance = true; }
            if (!ownsInstance)
            {
                MessageBox.Show("샤인멍 3Screen2가 이미 실행 중입니다. 작업 표시줄에서 열려 있는 창을 선택하세요.", "샤인멍");
                return 0;
            }
        }
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var window = new MainWindow(verify);
        app.MainWindow = window;
        if (verify)
        {
            window.Width = 1440; window.Height = 960;
            window.Show();
            window.Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, new Action(() =>
            {
                try
                {
                    CaptureLeaseVerification.Run();
                    var session = new SessionState();
                    var t = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
                    session.Apply(Backgrounds.All.Take(2), t);
                    if (session.Index(t.AddSeconds(59)) != 0 || session.Index(t.AddSeconds(60)) != 1 || session.Index(t.AddSeconds(120)) != 0)
                        throw new Exception("60-second rotation failed");
                    foreach (var coat in new[] { "흑색", "갈색", "흰색" })
                        if (Backgrounds.Recommend(coat).Select(x => x.Id).Distinct().Count() != 8) throw new Exception("Recommendation count failed");
                    try { session.Apply(Backgrounds.All.Take(1), t); throw new Exception("Invalid selection accepted"); }
                    catch (ArgumentException) { }
                    window.VerifyInteractions();
                    // A Canon portrait JPEG may store unrotated pixels and EXIF orientation=6.
                    var pixels = BitmapSource.Create(12, 8, 96, 96, PixelFormats.Bgr24, null, new byte[12 * 8 * 3], 12 * 3);
                    var metadata = new BitmapMetadata("jpg"); metadata.SetQuery("/app1/ifd/{ushort=274}", (ushort)6);
                    var jpeg = new JpegBitmapEncoder(); jpeg.Frames.Add(BitmapFrame.Create(pixels, null, metadata, null));
                    using var encoded = new MemoryStream(); jpeg.Save(encoded);
                    var decoded = PhotoDecoder.Decode(encoded.ToArray());
                    if (decoded.PixelWidth != 8 || decoded.PixelHeight != 12) throw new Exception("Portrait EXIF orientation failed");
                    uint init = EDSDKLib.EDSDK.EdsInitializeSDK();
                    if (init != 0) throw new Exception($"Native EDSDK init failed: {init:X8}");
                    int cameraCount = 0;
                    IntPtr cameras = IntPtr.Zero;
                    try
                    {
                        uint eventCode = CanonCamera.EdsGetEvent();
                        if (eventCode != 0) throw new Exception($"Native event pump failed: {eventCode:X8}");
                        uint code = EDSDKLib.EDSDK.EdsGetCameraList(out cameras);
                        if (code != 0) throw new Exception($"Native camera discovery failed: {code:X8}");
                        code = EDSDKLib.EDSDK.EdsGetChildCount(cameras, out cameraCount);
                        if (code != 0) throw new Exception($"Native camera count failed: {code:X8}");
                    }
                    finally { if (cameras != IntPtr.Zero) EDSDKLib.EDSDK.EdsRelease(cameras); EDSDKLib.EDSDK.EdsTerminateSDK(); }
                    if (cameraCount == 0)
                    {
                        // Exercise the production STA/message-pump startup and graceful shutdown too.
                        System.Threading.Tasks.Task.Run(async () =>
                        {
                            var reported = new System.Threading.Tasks.TaskCompletionSource<string>(System.Threading.Tasks.TaskCreationOptions.RunContinuationsAsynchronously);
                            await using var probe = new CanonCamera(Path.Combine(Path.GetTempPath(), "ShineMung3ScreenProbe"));
                            probe.Status += message => { if (!message.Contains("EDSDK 초기화 중")) reported.TrySetResult(message); };
                            probe.Start();
                            var message = await reported.Task.WaitAsync(TimeSpan.FromSeconds(15));
                            if (!message.Contains("Canon이 검색되지 않습니다")) throw new Exception("Camera worker startup failed: " + message);
                        }).GetAwaiter().GetResult();
                    }
                    string output = Path.Combine(AppContext.BaseDirectory, "verification");
                    Directory.CreateDirectory(output);
                    foreach (bool portrait in new[] { false, true })
                    {
                        var sample = new PhotoView();
                        sample.SetOrientation(portrait, false);
                        sample.SetPhoto(Backgrounds.All[2].Image);
                        sample.UpdateInfo("테스트 강아지", "19:59");
                        var size = portrait ? new Size(900, 1600) : new Size(1600, 900);
                        sample.Measure(size); sample.Arrange(new Rect(size)); sample.UpdateLayout();
                        var preview = new RenderTargetBitmap((int)size.Width, (int)size.Height, 96, 96, PixelFormats.Pbgra32);
                        preview.Render(sample);
                        var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(preview));
                        using var previewFile = File.Create(Path.Combine(output, portrait ? "portrait.png" : "landscape.png"));
                        png.Save(previewFile);
                    }
                    var bitmap = new RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                    bitmap.Render(window);
                    var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                    using (var file = File.Create(Path.Combine(output, "control.png"))) encoder.Save(file);
                    File.WriteAllText(Path.Combine(output, "result.txt"), $"PASS: Host start, 20-minute exact expiry, Camera-before-Close ordering, failed Host rollback, lock failure/retry, disconnect invalidation, no automatic Host resume, no repeated-start extension, UI start gating, backgrounds, EXIF, portrait rendering, native SDK discovery. Cameras detected: {cameraCount}. Shutter behavior requires a physical cardless camera test.");
                    app.Shutdown(0);
                }
                catch (Exception ex) { File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "verification-error.txt"), ex.ToString()); app.Shutdown(1); }
            }));
        }
        else window.Show();
        try { return app.Run(); }
        finally { if (ownsInstance) instance.ReleaseMutex(); }
    }
}
