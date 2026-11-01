using System;
using System.Collections.Generic;
using System.Linq;
namespace ThreeScreen;
internal static class CameraDetection
{
    internal static string Describe(IEnumerable<string> models)
    {
        var names = models.ToArray();
        string Role(string label, Func<string,bool> match) {
            var found = names.Where(match).ToArray();
            return label + ": " + (found.Length == 0 ? "미검색 · 전원/USB 확인" : found.Length == 1 ? "검색됨 (" + found[0] + ")" : "여러 대 검색됨 · 한 대만 연결: " + string.Join(", ", found));
        }
        var other = names.Where(x => !CompanionCamera.IsMirrorless(x) && !CompanionCamera.Is200D(x)).ToArray();
        return Role("미러리스", CompanionCamera.IsMirrorless) + "\n" + Role("EOS 200D", CompanionCamera.Is200D) + (other.Length == 0 ? "" : "\n미지원 모델: " + string.Join(", ",other));
    }
    internal static void Verify()
    {
        var pair = Describe(new[] { "Canon EOS R5m2", "Canon EOS 200D" });
        if (pair.Contains("미검색") || pair.Contains("미지원")) throw new Exception("Actual SDK R5m2 model not recognized");
        if (!Describe(new[] { "Canon EOS R8" }).Contains("EOS 200D: 미검색")) throw new Exception("Missing DSLR diagnostic");
        if (!Describe(new[] { "Canon EOS 200D" }).Contains("미러리스: 미검색")) throw new Exception("Missing mirrorless diagnostic");
        if (!Describe(new[] { "Other" }).Contains("미지원 모델: Other")) throw new Exception("Unsupported model diagnostic");
        if (!Describe(new[] { "Canon EOS R8", "Canon EOS R5m2" }).Contains("여러 대")) throw new Exception("Ambiguous cameras diagnostic");
    }
}
