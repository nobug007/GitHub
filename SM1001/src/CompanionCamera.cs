using System;
using EDSDKLib;

namespace ThreeScreen;

// Owned exclusively by CanonCamera's STA and SDK lifetime.
internal sealed class CompanionCamera
{
    internal IntPtr Handle;
    private bool open, mirrorRaised;
    private readonly EDSDK.EdsObjectEventHandler objects;
    private readonly EDSDK.EdsStateEventHandler states;
    internal CompanionCamera(EDSDK.EdsObjectEventHandler objects, EDSDK.EdsStateEventHandler states)
    { this.objects = objects; this.states = states; }
    internal static bool IsMirrorless(string model) => model.Trim().Equals("Canon EOS R8", StringComparison.OrdinalIgnoreCase) || model.Trim().Equals("Canon EOS R5 Mark II", StringComparison.OrdinalIgnoreCase) || model.Trim().Equals("Canon EOS R5m2", StringComparison.OrdinalIgnoreCase);
    internal static bool Is200D(string model) => model.Equals("Canon EOS 200D", StringComparison.OrdinalIgnoreCase) || model.Equals("Canon EOS Rebel SL2", StringComparison.OrdinalIgnoreCase) || model.Equals("Canon EOS Kiss X9", StringComparison.OrdinalIgnoreCase);
    private static void Check(uint result, string operation) { if (result != 0) throw new InvalidOperationException($"200D {operation}: 0x{result:X8}"); }
    private void Set(uint property, uint value)
    {
        Check(EDSDK.EdsSetPropertyData(Handle, property, 0, sizeof(uint), value), "설정");
        Check(EDSDK.EdsGetPropertyData(Handle, property, 0, out uint actual), "설정 확인");
        if (actual != value) throw new InvalidOperationException("200D 설정 확인 불일치");
    }
    internal void Open()
    {
        if (Handle == IntPtr.Zero) throw new InvalidOperationException("EOS 200D를 연결해 주세요.");
        if (open) return;
        var result = EDSDK.EdsOpenSession(Handle);
        Check(result, result == EDSDK.EDS_ERR_COMM_PORT_IS_IN_USE ? "통신 포트 사용 중 · EOS Utility를 종료해 주세요" : "세션 열기"); open = true;
        Check(EDSDK.EdsSetObjectEventHandler(Handle, EDSDK.ObjectEvent_All, objects, IntPtr.Zero), "촬영 이벤트");
        Check(EDSDK.EdsSetCameraStateEventHandler(Handle, EDSDK.StateEvent_All, states, IntPtr.Zero), "상태 이벤트");
    }
    internal void Start()
    {
        Open();
        Set(EDSDK.PropID_ImageQuality, 0x00640013); // RAW + large fine JPEG
        Set(EDSDK.PropID_SaveTo, (uint)EDSDK.EdsSaveTo.Host);
        Check(EDSDK.EdsSetCapacity(Handle, new EDSDK.EdsCapacity { NumberOfFreeClusters=0x7fffffff, BytesPerSector=0x1000, Reset=1 }), "PC 용량");
        // Live View holds the DSLR mirror up without taking a throwaway exposure.
        // Mark before either write so a partially failed start also attempts mirror-down.
        mirrorRaised = true;
        Set(EDSDK.PropID_Evf_Mode, 1);
        Set(EDSDK.PropID_Evf_OutputDevice, 1); // camera LCD; no second PC video stream
    }
    internal void KeepAlive() => Check(EDSDK.EdsSendCommand(Handle, EDSDK.CameraCommand_ExtendShutDownTimer, 0), "절전 타이머 연장");
    internal void Trigger()
    {
        if (!open || !mirrorRaised) throw new InvalidOperationException("200D 촬영 세션이 준비되지 않았습니다.");
        try { Check(EDSDK.EdsSendCommand(Handle, EDSDK.CameraCommand_PressShutterButton, 0x00010003), "연동 셔터"); }
        finally { Check(EDSDK.EdsSendCommand(Handle, EDSDK.CameraCommand_PressShutterButton, 0), "셔터 해제"); }
    }
    internal void Restore(bool close)
    {
        if (Handle == IntPtr.Zero) return;
        Open();
        Exception? error = null;
        try { Set(EDSDK.PropID_SaveTo, (uint)EDSDK.EdsSaveTo.Camera); } catch(Exception ex) { error = ex; }
        if (mirrorRaised)
        {
            try { Set(EDSDK.PropID_Evf_Mode, 0); mirrorRaised = false; } catch(Exception ex) { error ??= ex; }
        }
        if (error != null) throw error;
        if (close) { Check(EDSDK.EdsCloseSession(Handle), "세션 종료"); open=false; }
    }
    internal void Release()
    {
        if (Handle == IntPtr.Zero) return;
        try { Restore(true); }
        finally { if(open) EDSDK.EdsCloseSession(Handle); EDSDK.EdsRelease(Handle); Handle=IntPtr.Zero; open=false; }
    }
}
