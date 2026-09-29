using System;
using System.Collections.Generic;
using System.Linq;

namespace ThreeScreen;

public sealed class SessionState
{
    public IReadOnlyList<Backdrop> Active { get; private set; } = new[] { Backgrounds.All[0], Backgrounds.All[2] };
    public DateTime Started { get; private set; } = DateTime.UtcNow;
    public void Apply(IEnumerable<Backdrop> choices, DateTime now)
    {
        var pair = choices.DistinctBy(x => x.Id).ToArray();
        if (pair.Length != 2) throw new ArgumentException("배경은 서로 다른 2개를 선택하세요.");
        Active = pair;
        Started = now;
    }
    public int Index(DateTime now) => (int)(Math.Max(0, (now - Started).TotalSeconds) / 60) % 2;
    public int Remaining(DateTime now) => 60 - (int)Math.Max(0, (now - Started).TotalSeconds) % 60;
}
