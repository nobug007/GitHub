using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows.Media.Imaging;

namespace ThreeScreen;

public sealed record Backdrop(string Id, string Name, string Description)
{
    public BitmapImage Image { get; } = Load(Id);
    private static BitmapImage Load(string id)
    {
        var image = new BitmapImage(); image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.UriSource = new Uri(Path.Combine(AppContext.BaseDirectory, "assets", "backgrounds", id + ".png"));
        image.EndInit(); image.Freeze(); return image;
    }
}
public static class Backgrounds
{
    public static readonly Backdrop[] All =
    {
        new("flowers", "봄꽃 정원", "봄꽃과 부드러운 햇살"),
        new("forest", "햇살 숲", "초록 숲의 아침"),
        new("mountains", "푸른 산", "산과 드넓은 계곡"),
        new("space", "별빛 우주", "별과 보랏빛 성운"),
        new("river", "맑은 강", "나무 사이로 흐르는 강"),
        new("ocean", "푸른 바다", "잔잔한 바다와 모래사장"),
        new("lavender", "라벤더 들판", "보랏빛 꽃과 여명"),
        new("autumn", "가을 숲", "황금빛 단풍"),
        new("waterfall", "숲속 폭포", "청록빛 물과 이끼 숲"),
        new("sunset", "노을 호수", "노을이 비치는 호수"),
        new("snow", "아침 설원", "눈 덮인 산과 따뜻한 일출"),
        new("bamboo", "대나무 숲", "초록 대나무 사이의 아침 햇살")
    };
    public static IReadOnlyList<Backdrop> Recommend(string coat) =>
        (coat switch
        {
            "흰색" => new[] { 3, 8, 1, 6, 2, 4, 7, 9 },
            "갈색" => new[] { 5, 1, 4, 2, 3, 6, 8, 0 },
            _ => new[] { 0, 5, 9, 6, 2, 4, 7, 1 }
        }).Select(i => All[i]).ToArray();
}
