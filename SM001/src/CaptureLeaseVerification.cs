using System;
using System.Collections.Generic;
using System.Linq;

namespace ThreeScreen;

internal static class CaptureLeaseVerification
{
    private sealed class Clock : TimeProvider
    {
        public long Ticks;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override long GetTimestamp() => Ticks;
        public void Advance(TimeSpan elapsed) => Ticks += elapsed.Ticks;
    }
    private static void Require(bool condition, string message) { if (!condition) throw new Exception(message); }
    public static void Run()
    {
        var clock = new Clock(); var calls = new List<string>(); bool failHost = false, failLock = false;
        var lease = new CaptureLease(() =>
        {
            calls.Add("OpenSession"); calls.Add("Host");
            if (failHost) throw new InvalidOperationException("Capacity failed");
            calls.Add("Capacity");
        }, () =>
        {
            calls.Add("Camera");
            if (failLock) throw new InvalidOperationException("Busy");
            calls.Add("CloseSession");
        }, clock);
        Require(!lease.Active, "Startup must not enable Host");
        lease.Lock();
        Require(calls.SequenceEqual(new[] { "Camera", "CloseSession" }), "Initial lock order");
        calls.Clear(); lease.Start();
        Require(lease.Active && lease.RemainingSeconds == 1200, "Host success starts 20 minutes");
        Require(calls.SequenceEqual(new[] { "OpenSession", "Host", "Capacity" }), "Start ordering");
        clock.Advance(TimeSpan.FromMinutes(10));
        try { lease.Start(); throw new Exception("Repeated start accepted"); } catch (InvalidOperationException) { }
        Require(lease.RemainingSeconds == 600, "Repeated start must not extend deadline");
        clock.Advance(TimeSpan.FromMinutes(10) - TimeSpan.FromMilliseconds(1)); lease.Tick();
        Require(lease.Active && lease.RemainingSeconds == 1, "Must remain active before exact boundary");
        clock.Advance(TimeSpan.FromMilliseconds(1)); lease.Tick();
        Require(lease.Phase == CapturePhase.Locked && lease.RemainingSeconds == 0, "20-minute expiry must lock");
        Require(calls.TakeLast(2).SequenceEqual(new[] { "Camera", "CloseSession" }), "Expiry restore-before-close ordering");
        int count = calls.Count; clock.Advance(TimeSpan.FromHours(1)); lease.Tick();
        Require(calls.Count == count && !lease.Active, "Expired timer must never reopen Host");
        failHost = true;
        try { lease.Start(); throw new Exception("Failed Host start accepted"); } catch (InvalidOperationException) { }
        Require(!lease.Active && lease.Phase == CapturePhase.Locked, "Host failure rollback");
        Require(calls.TakeLast(2).SequenceEqual(new[] { "Camera", "CloseSession" }), "Rollback must close");
        failHost = false; lease.Start(); failLock = true;
        clock.Advance(TimeSpan.FromMinutes(20));
        try { lease.Tick(); throw new Exception("Lock failure hidden"); } catch (InvalidOperationException) { }
        Require(!lease.Active && lease.Phase == CapturePhase.LockFailed, "Do not claim locked after SDK failure");
        try { lease.Start(); throw new Exception("Started during lock failure"); } catch (InvalidOperationException) { }
        failLock = false; lease.Lock(); Require(lease.Phase == CapturePhase.Locked, "Lock retry");
        lease.Start(); lease.MarkUnavailable();
        Require(!lease.Active && lease.RemainingSeconds == 0, "Disconnect revokes capture window");
        lease.Lock(); lease.Tick(); Require(!lease.Active, "Reconnect must not resume Host");
        lease.Start(); lease.Lock(); Require(!lease.Active, "Early application exit restores Camera and closes");
    }
}
