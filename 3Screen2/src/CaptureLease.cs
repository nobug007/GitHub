using System;

namespace ThreeScreen;

public enum CapturePhase { NeedsLock, Locked, Active, LockFailed }

// The camera worker owns mutations. The UI only reads state and remaining time.
// A monotonic clock prevents wall-clock changes from extending the paid shooting window.
public sealed class CaptureLease
{
    public static readonly TimeSpan Duration = TimeSpan.FromMinutes(20);
    private readonly Action enableHost;
    private readonly Action restoreAndClose;
    private readonly TimeProvider clock;
    private long started;
    private volatile CapturePhase phase = CapturePhase.NeedsLock;
    public CapturePhase Phase => phase;
    public bool HasStarted { get; private set; }
    public bool Active => phase == CapturePhase.Active;
    public int RemainingSeconds => !HasStarted ? 1200 : !Active ? 0 :
        Math.Max(0, (int)Math.Ceiling((Duration - clock.GetElapsedTime(started)).TotalSeconds));
    public CaptureLease(Action enableHost, Action restoreAndClose, TimeProvider? clock = null)
    {
        this.enableHost = enableHost; this.restoreAndClose = restoreAndClose;
        this.clock = clock ?? TimeProvider.System;
    }
    public void Lock()
    {
        phase = CapturePhase.NeedsLock; // revoke permission before calling native APIs
        try { restoreAndClose(); phase = CapturePhase.Locked; }
        catch { phase = CapturePhase.LockFailed; throw; }
    }
    public void Start()
    {
        if (Active) throw new InvalidOperationException("촬영 중에는 20분을 연장하거나 다시 시작할 수 없습니다.");
        if (phase != CapturePhase.Locked) throw new InvalidOperationException("카메라 잠금 상태를 먼저 확인해야 합니다.");
        try
        {
            enableHost(); // no timer and no permission until Host/capacity configuration succeeds
            started = clock.GetTimestamp(); HasStarted = true; phase = CapturePhase.Active;
        }
        catch
        {
            Lock(); // partial Host setup must roll back to Camera and close the session
            throw;
        }
    }
    public void Tick()
    {
        if (Active && clock.GetElapsedTime(started) >= Duration) Lock();
    }
    public void MarkUnavailable() { phase = CapturePhase.NeedsLock; }
}
