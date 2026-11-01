using System;
using System.Collections.Generic;
using System.IO;

namespace ThreeScreen;

internal sealed class LinkedShutter
{
    private readonly HashSet<string> shots = new(StringComparer.OrdinalIgnoreCase);
    internal void Reset() => shots.Clear();
    internal bool Accept(bool secondary, bool active, string filename, uint timestamp)
    {
        if (secondary || !active || !StoragePaths.IsPhoto(filename)) return false;
        return shots.Add($"{Path.GetFileNameWithoutExtension(filename)}:{timestamp}");
    }
    internal static void Verify()
    {
        var gate = new LinkedShutter();
        if (!gate.Accept(false, true, "IMG_0001.CR3", 1) || gate.Accept(false, true, "IMG_0001.JPG", 1)) throw new Exception("RAW first must trigger once");
        if (!gate.Accept(false, true, "IMG_0002.JPG", 2) || gate.Accept(false, true, "IMG_0002.CR3", 2)) throw new Exception("JPEG first must trigger once");
        if (gate.Accept(true, true, "IMG_0003.JPG", 3) || gate.Accept(false, false, "IMG_0003.JPG", 3) || gate.Accept(false, true, "clip.MP4", 3)) throw new Exception("Secondary/inactive/video must not trigger");
        gate.Reset(); if (!gate.Accept(false, true, "IMG_0001.JPG", 1)) throw new Exception("New session reset failed");
        if (!CompanionCamera.IsMirrorless("Canon EOS R8") || !CompanionCamera.IsMirrorless("Canon EOS R5 Mark II") || CompanionCamera.IsMirrorless("Canon EOS 200D")) throw new Exception("Mirrorless roles failed");
        if (!CompanionCamera.Is200D("Canon EOS 200D") || CompanionCamera.Is200D("Canon EOS 200D II")) throw new Exception("200D roles failed");
    }
}
