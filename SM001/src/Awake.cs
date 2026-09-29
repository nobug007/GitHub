using System;
using System.Runtime.InteropServices;
namespace ThreeScreen;
internal static class Awake {
    [DllImport("kernel32.dll")] private static extern uint SetThreadExecutionState(uint flags);
    public static void Enable() { if (SetThreadExecutionState(0x80000003) == 0) throw new InvalidOperationException("PC 절전 방지 설정 실패"); }
    public static void Disable() => SetThreadExecutionState(0x80000000);
}
