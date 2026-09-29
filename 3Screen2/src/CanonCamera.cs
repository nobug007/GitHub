using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using EDSDKLib;

namespace ThreeScreen;

public sealed class CanonCamera : IAsyncDisposable
{
    private readonly string root;
    private readonly Thread thread;
    private readonly TaskCompletionSource<Dispatcher> ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly TaskCompletionSource finished = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly EDSDK.EdsObjectEventHandler objectHandler;
    private readonly EDSDK.EdsStateEventHandler stateHandler;
    private readonly HashSet<string> completed = new(), jpegShots = new();
    private readonly CaptureLease lease;
    private Dispatcher? dispatcher;
    private DispatcherTimer? reconnect, events;
    private string? lastStatus;
    private string model = "Canon";
    private IntPtr camera;
    private bool initialized, stopping, sessionOpen, downloading, cameraSaveRestored;
    private int pendingTransfers;
    private DateTime nextLockAttempt;
    public string DogName = "";
    public int RemainingSeconds => lease.RemainingSeconds;
    public CapturePhase Phase => lease.Phase;
    public event Action<string>? Status;
    public event Action<string, bool>? ConnectionState;
    public event Action<byte[], string, string>? Photo;
    public event Action<CapturePhase>? CaptureChanged;

    [DllImport("EDSDK.dll", CallingConvention = CallingConvention.StdCall)]
    internal static extern uint EdsGetEvent();
    public CanonCamera(string photoRoot)
    {
        root = photoRoot;
        objectHandler = OnObject; stateHandler = OnState;
        lease = new CaptureLease(EnableHost, RestoreAndClose);
        thread = new Thread(Run) { IsBackground = true, Name = "ShineMung3Screen.EDSDK.STA" };
        thread.SetApartmentState(ApartmentState.STA);
    }
    public void Start() => thread.Start();
    private async Task<Dispatcher> Worker()
    {
        var d = await ready.Task;
        if (finished.Task.IsCompleted || d.HasShutdownStarted) throw new InvalidOperationException("SDK가 종료되었습니다. 앱을 다시 실행하세요.");
        return d;
    }
    public async Task<bool> StartCaptureAsync()
    {
        try
        {
            var d = await Worker();
            return await d.InvokeAsync(() =>
            {
                if (stopping || lease.Active) return false;
                try
                {
                    if (camera == IntPtr.Zero) Discover();
                    if (camera == IntPtr.Zero) throw new InvalidOperationException("카메라가 연결되지 않았습니다.");
                    if (lease.Phase != CapturePhase.Locked) lease.Lock();
                    // Check the destination before allowing cardless shooting.
                    Directory.CreateDirectory(root);
                    var probe = Path.Combine(root, Guid.NewGuid().ToString("N") + ".write-test");
                    using (var file = new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1, FileOptions.DeleteOnClose)) file.WriteByte(0);
                    lease.Start(); NotifyPhase();
                    Report("촬영 허용 · SaveTo.Host · 20분 후 Camera 복원 및 세션 종료");
                    return true;
                }
                catch (Exception ex) { NotifyPhase(); Report("촬영 시작 실패 · " + ex.Message); return false; }
            });
        }
        catch (Exception ex) { Report(ex.Message); return false; }
    }
    public async Task ReconnectAsync()
    {
        try
        {
            var d = await Worker();
            await d.InvokeAsync(() =>
            {
                if (lease.Active) { Report("촬영 중 · 남은 시간은 연장되지 않습니다"); return; }
                Discover();
                if (camera != IntPtr.Zero && lease.Phase != CapturePhase.Locked) EnforceLock();
            });
        }
        catch (Exception ex) { Report(ex.Message); }
    }
    public async Task<bool> LockCaptureAsync()
    {
        try
        {
            var d = await Worker();
            return await d.InvokeAsync(() =>
            {
                if (camera == IntPtr.Zero) return !lease.HasStarted;
                if (lease.Phase == CapturePhase.Locked) return true;
                EnforceLock();
                return lease.Phase == CapturePhase.Locked;
            });
        }
        catch (Exception ex) { Report("카메라 잠금 확인 실패 · " + ex.Message); return !lease.HasStarted; }
    }
    private void Run()
    {
        dispatcher = Dispatcher.CurrentDispatcher; ready.TrySetResult(dispatcher);
        try
        {
            Report("EDSDK 초기화 중 · Canon USB 카메라 검색");
            Check(EDSDK.EdsInitializeSDK(), "EDSDK 초기화"); initialized = true;
            Discover();
            events = new DispatcherTimer(TimeSpan.FromMilliseconds(25), DispatcherPriority.Send, (_, _) => Pump(), dispatcher);
            reconnect = new DispatcherTimer(TimeSpan.FromSeconds(5), DispatcherPriority.Background, (_, _) => Discover(), dispatcher);
            events.Start(); reconnect.Start(); Dispatcher.Run();
        }
        catch (Exception ex) { Report("카메라 준비 실패 · " + ex.Message); }
        finally
        {
            try
            {
                if (camera != IntPtr.Zero)
                {
                    try { if (lease.Phase != CapturePhase.Locked) lease.Lock(); } catch (Exception ex) { Report("종료 시 잠금 확인 실패 · " + ex.Message); }
                    ReleaseCamera();
                }
                if (initialized) EDSDK.EdsTerminateSDK();
            }
            finally { finished.TrySetResult(); }
        }
    }
    private void OpenSession()
    {
        if (camera == IntPtr.Zero) throw new InvalidOperationException("카메라 미연결");
        if (sessionOpen) return;
        Check(EDSDK.EdsOpenSession(camera), "카메라 세션 열기 (0xC0: EOS Utility가 카메라 사용 중)"); sessionOpen = true;
        Check(EDSDK.EdsSetObjectEventHandler(camera, EDSDK.ObjectEvent_All, objectHandler, IntPtr.Zero), "촬영 이벤트 등록");
        Check(EDSDK.EdsSetCameraStateEventHandler(camera, EDSDK.StateEvent_All, stateHandler, IntPtr.Zero), "카메라 상태 등록");
    }
    private void EnableHost()
    {
        OpenSession(); cameraSaveRestored = false;
        Check(EDSDK.EdsSetPropertyData(camera, EDSDK.PropID_SaveTo, 0, sizeof(uint), (uint)EDSDK.EdsSaveTo.Host), "SaveTo.Host 설정");
        Check(EDSDK.EdsSetCapacity(camera, new EDSDK.EdsCapacity { NumberOfFreeClusters = 0x7FFFFFFF, BytesPerSector = 0x1000, Reset = 1 }), "PC 저장 용량 설정");
        VerifySaveTo(EDSDK.EdsSaveTo.Host);
        completed.Clear(); jpegShots.Clear();
    }
    private void RestoreAndClose()
    {
        OpenSession();
        if (!cameraSaveRestored)
        {
            Check(EDSDK.EdsSetPropertyData(camera, EDSDK.PropID_SaveTo, 0, sizeof(uint), (uint)EDSDK.EdsSaveTo.Camera), "SaveTo.Camera 복원");
            VerifySaveTo(EDSDK.EdsSaveTo.Camera);
            cameraSaveRestored = true;
            Report("SaveTo.Camera 복원 완료");
        }
        if (downloading || pendingTransfers > 0) throw new InvalidOperationException("시간 내 촬영된 사진 전송 완료 후 세션을 닫습니다");
        Check(EDSDK.EdsCloseSession(camera), "EdsCloseSession"); sessionOpen = false;
        Report("EdsCloseSession 완료 · Host 촬영 허용 해제");
    }
    private void VerifySaveTo(EDSDK.EdsSaveTo expected)
    {
        Check(EDSDK.EdsGetPropertyData(camera, EDSDK.PropID_SaveTo, 0, out uint actual), "SaveTo 설정 확인");
        if (actual != (uint)expected) throw new InvalidOperationException($"SaveTo 확인 불일치: {actual}, 예상 {(uint)expected}");
    }
    private void Discover()
    {
        if (stopping) return;
        IntPtr list = IntPtr.Zero;
        try
        {
            if (lease.Active)
            {
                uint health = EDSDK.EdsGetPropertyData(camera, EDSDK.PropID_ProductName, 0, out string _);
                if (health == EDSDK.EDS_ERR_DEVICE_NOT_FOUND || health == EDSDK.EDS_ERR_COMM_DISCONNECTED) LostCamera();
                return;
            }
            Check(EDSDK.EdsGetCameraList(out list), "카메라 검색");
            Check(EDSDK.EdsGetChildCount(list, out int count), "카메라 수 확인");
            if (count == 0)
            {
                if (camera != IntPtr.Zero) LostCamera();
                ConnectionState?.Invoke("카메라 미연결 · 촬영 허용 안 됨", false);
                Report("Canon이 검색되지 않습니다 · USB 연결 / 전원 확인"); return;
            }
            if (camera == IntPtr.Zero)
            {
                Check(EDSDK.EdsGetChildAtIndex(list, 0, out camera), "카메라 선택");
                Check(EDSDK.EdsGetDeviceInfo(camera, out var info), "카메라 정보"); model = info.szDeviceDescription;
                cameraSaveRestored = false; lease.MarkUnavailable();
            }
            if (lease.Phase != CapturePhase.Locked) EnforceLock();
            else NotifyPhase(); // detection only; do not reopen a successfully closed session
        }
        catch (Exception ex) { ConnectionState?.Invoke("카메라 잠금 확인 실패", false); Report(ex.Message); }
        finally { if (list != IntPtr.Zero) EDSDK.EdsRelease(list); }
    }
    private void NotifyPhase()
    {
        CaptureChanged?.Invoke(lease.Phase);
        ConnectionState?.Invoke(lease.Phase switch
        {
            CapturePhase.Active => model + " · 촬영 가능 (Host)",
            CapturePhase.Locked => model + " · 촬영 대기 / 세션 닫힘",
            _ => model + " · 잠금 확인 중 / 실패"
        }, lease.Active || lease.Phase == CapturePhase.Locked);
    }
    private void EnforceLock()
    {
        try { lease.Lock(); }
        catch (Exception ex) { Report("잠금 미완료 · " + ex.Message + " · 자동 재시도"); }
        finally { nextLockAttempt = DateTime.UtcNow.AddSeconds(1); NotifyPhase(); }
    }
    private void CheckDeadline()
    {
        if (!lease.Active || lease.RemainingSeconds > 0) return;
        try { lease.Tick(); }
        catch (Exception ex) { Report("촬영 시간 종료 · 잠금 처리 중 · " + ex.Message); }
        finally { nextLockAttempt = DateTime.UtcNow.AddSeconds(1); NotifyPhase(); }
    }
    private void Pump()
    {
        try
        {
            CheckDeadline(); // camera deadline is independent of WPF's UI timer
            if (!stopping && camera != IntPtr.Zero && (lease.Phase is CapturePhase.NeedsLock or CapturePhase.LockFailed) && DateTime.UtcNow >= nextLockAttempt) EnforceLock();
            Check(EdsGetEvent(), "촬영 이벤트 수신");
        }
        catch (Exception ex) { Report(ex.Message); }
    }
    private void LostCamera()
    {
        lease.MarkUnavailable(); ReleaseCamera(); cameraSaveRestored = false;
        CaptureChanged?.Invoke(lease.Phase);
        ConnectionState?.Invoke("카메라 연결 끊김 · 촬영 허용 해제", false);
        Report("연결 끊김 · 카메라 설정 복원은 재연결 후 확인 · 자동 Host 재개 안 함");
    }
    private uint OnState(uint evt, uint parameter, IntPtr context)
    {
        try
        {
            if (evt == EDSDK.StateEvent_WillSoonShutDown && lease.Active && camera != IntPtr.Zero)
                EDSDK.EdsSendCommand(camera, EDSDK.CameraCommand_ExtendShutDownTimer, 0);
            if (evt == EDSDK.StateEvent_Shutdown) LostCamera();
        }
        catch (Exception ex) { Report(ex.Message); }
        return EDSDK.EDS_ERR_OK;
    }
    private uint OnObject(uint evt, IntPtr item, IntPtr context)
    {
        if (item == IntPtr.Zero) return EDSDK.EDS_ERR_OK;
        try
        {
            // Finish transfers from a just-expired shot too: without a card, the PC is its only copy.
            if (evt == EDSDK.ObjectEvent_DirItemRequestTransfer)
            {
                string dog = DogName;
                Report("촬영 전송 이벤트 수신 · 즉시 다운로드 시작");
                pendingTransfers++;
                dispatcher!.BeginInvoke(DispatcherPriority.Normal, new Action(() => Download(item, dog)));
                return EDSDK.EDS_ERR_OK;
            }
        }
        catch (Exception ex) { Report(ex.Message); }
        EDSDK.EdsRelease(item); return EDSDK.EDS_ERR_OK;
    }
    private void Download(IntPtr item, string dog)
    {
        downloading = true;
        IntPtr stream = IntPtr.Zero;
        bool transferStarted = false, transferComplete = false;
        try
        {
            Check(EDSDK.EdsGetDirectoryItemInfo(item, out var info), "사진 정보");
            if (info.isFolder != 0) { EDSDK.EdsDownloadCancel(item); return; }
            string extension = Path.GetExtension(info.szFileName).ToLowerInvariant();
            if (extension is not (".jpg" or ".jpeg" or ".cr2" or ".cr3" or ".crw")) { EDSDK.EdsDownloadCancel(item); return; }
            string key = $"{info.szFileName}:{info.Size}:{info.dateTime}:{info.GroupID}";
            if (completed.Contains(key)) { EDSDK.EdsDownloadCancel(item); return; }
            Report("사진 다운로드 중 · " + info.szFileName);
            Check(EDSDK.EdsCreateMemoryStream(0, out stream), "다운로드 메모리");
            transferStarted = true;
            ulong left = info.Size;
            while (left > 0)
            {
                CheckDeadline(); // enforce at chunk boundaries rather than after a whole large RAW file
                ulong chunk = Math.Min(left, 1024UL * 1024);
                Check(EDSDK.EdsDownload(item, chunk, stream), "사진 다운로드");
                left -= chunk;
            }
            byte[] data = ReadStream(stream);
            var safeName = string.Join("_", dog.Split(Path.GetInvalidFileNameChars())).Trim().TrimEnd('.');
            if (safeName.Length > 60) safeName = safeName[..60];
            if (string.IsNullOrWhiteSpace(safeName)) safeName = "강아지";
            var folder = Path.Combine(root, DateTime.Now.ToString("yyyy-MM-dd"), "DOG_" + safeName);
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, DateTime.Now.ToString("HHmmss_fff") + "_" + Guid.NewGuid().ToString("N")[..8] + extension);
            File.WriteAllBytes(path + ".partial", data);
            File.Move(path + ".partial", path);
            Check(EDSDK.EdsDownloadComplete(item), "다운로드 완료");
            transferComplete = true;
            completed.Add(key);
            string shotKey = $"{Path.GetFileNameWithoutExtension(info.szFileName)}:{info.dateTime}:{info.GroupID}";
            if (extension is not (".jpg" or ".jpeg"))
            {
                // RAW+JPEG can arrive in either order; a RAW thumbnail must never replace its JPEG.
                if (jpegShots.Contains(shotKey)) { Report("RAW 원본 저장 완료 · JPEG 사진 유지"); return; }
                EDSDK.EdsRelease(stream); stream = IntPtr.Zero;
                Check(EDSDK.EdsCreateMemoryStream(0, out stream), "RAW 미리보기 메모리");
                Check(EDSDK.EdsDownloadThumbnail(item, stream), "RAW 미리보기 (고화질 표시에는 RAW+JPEG 권장)");
                data = ReadStream(stream);
            }
            else jpegShots.Add(shotKey);
            Photo?.Invoke(data, path, dog);
            Report("PC 복사 완료 · " + path);
        }
        catch (Exception ex) { Report("사진 저장 실패 · " + ex.Message + " · 카드 없는 Host 촬영이므로 저장 파일을 확인하세요"); }
        finally
        {
            if (transferStarted && !transferComplete) EDSDK.EdsDownloadCancel(item);
            if (stream != IntPtr.Zero) EDSDK.EdsRelease(stream);
            EDSDK.EdsRelease(item);
            downloading = false;
            pendingTransfers = Math.Max(0, pendingTransfers - 1);
            CheckDeadline();
            if (!lease.Active && camera != IntPtr.Zero && lease.Phase != CapturePhase.Locked) EnforceLock();
        }
    }
    private static byte[] ReadStream(IntPtr stream)
    {
        Check(EDSDK.EdsGetLength(stream, out ulong length), "사진 크기");
        Check(EDSDK.EdsGetPointer(stream, out IntPtr ptr), "사진 메모리");
        var bytes = new byte[checked((int)length)];
        Marshal.Copy(ptr, bytes, 0, bytes.Length);
        return bytes;
    }
    private static void Check(uint code, string operation)
    {
        if (code != EDSDK.EDS_ERR_OK) throw new InvalidOperationException($"{operation}: 0x{code:X8}");
    }
    private void Report(string message)
    {
        if (message == lastStatus) return;
        lastStatus = message;
        try
        {
            var folder = Path.GetDirectoryName(Settings.FilePath)!;
            Directory.CreateDirectory(folder);
            File.AppendAllText(Path.Combine(folder, "camera.log"), $"{DateTime.Now:O} {message}{Environment.NewLine}");
        }
        catch { }
        Status?.Invoke(message);
    }
    private void ReleaseCamera()
    {
        if (camera == IntPtr.Zero) return;
        if (sessionOpen) EDSDK.EdsCloseSession(camera);
        EDSDK.EdsRelease(camera);
        camera = IntPtr.Zero; sessionOpen = false;
    }
    public async ValueTask DisposeAsync()
    {
        var d = await ready.Task;
        if (!finished.Task.IsCompleted && thread.IsAlive && !d.HasShutdownStarted)
        {
            var stop = d.InvokeAsync(() =>
            {
                stopping = true; reconnect?.Stop();
                if (camera != IntPtr.Zero) EnforceLock();
            }, DispatcherPriority.Send).Task;
            await Task.WhenAny(stop, finished.Task);
            if (finished.Task.IsCompleted) return;
            // Shutdown at idle drains queued downloads and releases their references first.
            d.BeginInvokeShutdown(DispatcherPriority.ApplicationIdle);
            await finished.Task;
        }
    }
}

