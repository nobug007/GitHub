using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using Screen = System.Windows.Forms.Screen;

namespace ThreeScreen;

public sealed class MainWindow : Window
{
    private readonly Settings settings;
    private readonly bool verify;
    private readonly SessionState session = new();
    private CaptureLease previewLease = new(() => { }, () => { });
    private bool captureStarting;
    private CapturePhase CapturePhaseNow => verify ? previewLease.Phase : camera.Phase;
    private readonly HashSet<string> draft = new();
    private readonly OutputWindow projector = new(true), photoScreen = new(false);
    private readonly CanonCamera camera;
    private readonly DispatcherTimer clock;
    private readonly UniformGrid cards = new() { Columns = 4 };
    private readonly TextBlock status = Text("프로그램 시작 · Canon 카메라 연결을 준비합니다", 14, "#A5B6C9");
    private readonly TextBlock cameraState = Text("●  카메라 연결 중", 21, "#F1CC83");
    private readonly TextBlock displayStatus = Text("", 13, "#A5B6C9");
    private readonly TextBlock selection = Text("", 14, "#A5B6C9");
    private readonly TextBlock rotation = Text("", 14, "#A5B6C9");
    private readonly TextBlock photoCaption = Text("촬영하면 여기에 바로 표시됩니다", 13, "#A5B6C9");
    private readonly Image backgroundPreview = new() { Height = 110, Stretch = Stretch.Uniform };
    private readonly PhotoView photoPreview = new() { Height = 110 };
    private readonly ComboBox mainMonitor = new() { Width = 220, FontSize = 12 };
    private readonly TextBox dogInput = new() { FontSize = 20, Padding = new Thickness(12), MinWidth = 220 };
    private readonly Button apply;
    private readonly Button swap;
    private readonly Button start;
    private readonly Button orientation;
    private readonly Dictionary<string, Button> coats = new();
    private bool refreshing, shuttingDown, closed;
    private ImageSource? lastPhoto;

    public MainWindow(bool verification = false)
    {
        verify = verification;
        previewLease.Lock();
        settings = verify ? new Settings() : Settings.Load();
        settings.DogName = "";
        if (!new[] { "흑색", "갈색", "흰색" }.Contains(settings.Coat)) settings.Coat = "흑색";
        foreach (var id in settings.Selected ?? Array.Empty<string>()) if (Backgrounds.All.Any(x => x.Id == id)) draft.Add(id);
        if (draft.Count != 2) { draft.Clear(); foreach (var b in Backgrounds.Recommend(settings.Coat).Take(2)) draft.Add(b.Id); }
        session.Apply(Backgrounds.All.Where(x => draft.Contains(x.Id)), DateTime.UtcNow);
        camera = new CanonCamera(settings.PhotoFolder) { DogName = settings.DogName };
        Title = "샤인멍 · 3 Screen Studio";
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        Background = Brush("#10151D");
        Foreground = Brushes.White;
        FontFamily = new FontFamily("Malgun Gothic");
        Width = 1440; Height = 960;

        var root = new DockPanel { Width = 1440, Height = 960, Margin = new Thickness(24, 16, 24, 16) };
        var header = new DockPanel { Height = 52, Margin = new Thickness(0, 0, 0, 8) };
        var exit = Button("종료", async () => await Shutdown());
        DockPanel.SetDock(exit, Dock.Right); header.Children.Add(exit);
        var brand = new StackPanel();
        brand.Children.Add(Text("샤인멍  /  3 SCREEN STUDIO", 27, "#F2F5F8"));
        header.Children.Add(brand); DockPanel.SetDock(header, Dock.Top); root.Children.Add(header);

        var footer = new StackPanel { Margin = new Thickness(0, 6, 0, 0) };
        footer.Children.Add(displayStatus);
        DockPanel.SetDock(footer, Dock.Bottom); root.Children.Add(footer);
        var cameraBar = new DockPanel();
        var reconnectButton = Button("카메라 다시 연결", async () => { if (!verify) await camera.ReconnectAsync(); });
        DockPanel.SetDock(reconnectButton, Dock.Right); cameraBar.Children.Add(reconnectButton);
        var cameraText = new StackPanel(); cameraText.Children.Add(cameraState); cameraText.Children.Add(status); cameraBar.Children.Add(cameraText);
        status.TextWrapping = TextWrapping.NoWrap; status.TextTrimming = TextTrimming.CharacterEllipsis;
        var cameraPanel = Panel(cameraBar); cameraPanel.Margin = new Thickness(0, 0, 0, 10);
        DockPanel.SetDock(cameraPanel, Dock.Top); root.Children.Add(cameraPanel);
        var body = new StackPanel(); root.Children.Add(body);

        var controls = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var dogPanel = new StackPanel();
        dogPanel.Children.Add(Text("01  오늘의 주인공", 17));
        var dogRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 0) };
        dogInput.Text = settings.DogName;
        dogInput.MaxLength = 60;
        dogInput.TextChanged += (_, _) => { settings.DogName = dogInput.Text.Trim(); camera.DogName = settings.DogName; RefreshStartButton(); UpdateClock(); };
        dogInput.LostFocus += (_, _) => Save();
        dogInput.ToolTip = "강아지 이름을 입력하세요";
        dogRow.Children.Add(dogInput); dogRow.Children.Add(Text("  강아지 이름을 입력하세요", 13, "#91A2B6"));
        dogPanel.Children.Add(dogRow); controls.Children.Add(Panel(dogPanel));
        var screens = new StackPanel();
        screens.Children.Add(Text("02  화면 배치", 17));
        var screenRow = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
        mainMonitor.SelectionChanged += (_, _) =>
        {
            if (refreshing || mainMonitor.SelectedItem is not MonitorChoice choice) return;
            settings.MainScreen = choice.Id; Save(); LayoutDisplays();
        };
        screenRow.Children.Add(mainMonitor);
        swap = Button("화면 바꾸기 ⇄", () => { settings.Swapped = !settings.Swapped; Save(); LayoutDisplays(); });
        screenRow.Children.Add(swap);
        orientation = Button("", () => { settings.PhotoPortrait = !settings.PhotoPortrait; UpdateOrientation(); Save(); });
        screenRow.Children.Add(orientation); screens.Children.Add(screenRow);
        screens.Children.Add(Text("사진 가로/세로: 화면 내용 90° 회전 · 이름과 남은 시간 함께 표시", 12, "#91A2B6"));
        var screenPanel = Panel(screens); Grid.SetColumn(screenPanel, 1); controls.Children.Add(screenPanel);
        body.Children.Add(controls);

        var heading = new DockPanel();
        heading.Children.Add(Text("03  자연 배경 · 10가지 풍경", 22)); body.Children.Add(heading);
        var coatRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 6) };
        foreach (string coat in new[] { "흑색", "갈색", "흰색" })
        {
            var button = Button(coat, () => SelectCoat(coat)); coats[coat] = button; coatRow.Children.Add(button);
        }
        coatRow.Children.Add(Text("  털 색별 추천 선택 · 10종 중 2개 선택 · 1분마다 전환", 14, "#A5B6C9"));
        body.Children.Add(coatRow); body.Children.Add(cards);
        var actionRow = new DockPanel { Margin = new Thickness(0, 4, 0, 10) };
        apply = Button("선택한 2개 배경 적용", ApplyBackgrounds, true);
        DockPanel.SetDock(apply, Dock.Right); actionRow.Children.Add(apply); actionRow.Children.Add(selection); body.Children.Add(actionRow);

        var previews = new Grid();
        previews.ColumnDefinitions.Add(new ColumnDefinition()); previews.ColumnDefinitions.Add(new ColumnDefinition());
        var bg = new StackPanel(); bg.Children.Add(Text("PROJECTOR  ·  배경 화면", 14)); bg.Children.Add(backgroundPreview); bg.Children.Add(rotation);
        previews.Children.Add(Panel(bg));
        var ph = new StackPanel(); var phHead = new DockPanel();
        var test = Button("사진 불러오기 테스트", LoadPhoto);
        DockPanel.SetDock(test, Dock.Right); phHead.Children.Add(test); phHead.Children.Add(Text("DISPLAY  ·  촬영 사진", 14)); ph.Children.Add(phHead);
        ph.Children.Add(photoPreview); ph.Children.Add(photoCaption);
        var phBorder = Panel(ph); Grid.SetColumn(phBorder, 1); previews.Children.Add(phBorder); body.Children.Add(previews);
        start = Button("촬영 시작  ·  20분", async () => await StartSessionAsync(), true);
        start.Height = 66; start.FontSize = 25; start.FontWeight = FontWeights.SemiBold;
        start.Margin = new Thickness(0, 12, 12, 0); start.IsEnabled = false;
        body.Children.Add(start);
        Content = new Viewbox { Stretch = Stretch.Uniform, Child = root, HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Stretch };

        camera.ConnectionState += (label, connected) => Dispatcher.BeginInvoke(new Action(() =>
        {
            cameraState.Text = "●  " + label;
            cameraState.Foreground = Brush(connected ? "#BCE9D5" : "#F1CC83");
        }));
        camera.CaptureChanged += _ => Dispatcher.BeginInvoke(new Action(() => { if (!shuttingDown) { RefreshStartButton(); UpdateClock(); } }));
        camera.Status += message => Dispatcher.BeginInvoke(new Action(() => { if (!closed) status.Text = message; }));
        camera.Photo += (bytes, path, capturedDog) =>
        {
            try
            {
                var bitmap = Decode(bytes);
                Dispatcher.BeginInvoke(DispatcherPriority.Normal, new Action(() =>
                {
                    if (!shuttingDown)
                    {
                        ShowPhoto(bitmap, Path.GetFileName(path));
                        status.Text = "사진 화면 갱신 완료 · " + Path.GetFileName(path);
                    }
                }));
            }
            catch (Exception ex) { Dispatcher.BeginInvoke(new Action(() => status.Text = "사진 표시 실패: " + ex.Message)); }
        };
        clock = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background, (_, _) => UpdateClock(), Dispatcher);
        UpdateOrientation(); RenderCards(); UpdateClock();
        Loaded += (_, _) =>
        {
            if (verify) { mainMonitor.Items.Add(new MonitorChoice("preview", "메인: 노트북 화면 (미리보기)")); refreshing = true; mainMonitor.SelectedIndex = 0; refreshing = false; status.Text = "미리보기 모드 · Canon 연결 대기"; displayStatus.Text = "메인 컨트롤 고정 · 외부 화면 연결 시 자동 배치"; return; }
            camera.Start(); RefreshMonitors(); SystemEvents.DisplaySettingsChanged += OnDisplayChanged;
        };
        Closing += OnClosing;
    }
    private static SolidColorBrush Brush(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    private static TextBlock Text(string value, double size, string color = "#E7EDF4") => new() { Text = value, FontSize = size, Foreground = Brush(color), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 4, 0, 4), TextWrapping = TextWrapping.Wrap };
    private static Border Panel(UIElement content) => new() { Child = content, Background = Brush("#1A222E"), CornerRadius = new CornerRadius(12), Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(0, 0, 12, 0) };
    private static Button Button(string label, Action action, bool accent = false)
    {
        var button = new Button { Content = label, Padding = new Thickness(18, 10, 18, 10), Margin = new Thickness(0, 0, 10, 0), FontSize = 14, Background = Brush(accent ? "#BCE9D5" : "#293646"), Foreground = accent ? Brush("#122B24") : Brushes.White, BorderThickness = new Thickness(0), Cursor = System.Windows.Input.Cursors.Hand };
        button.Click += (_, _) => action(); return button;
    }
    private void SelectCoat(string coat)
    {
        settings.Coat = coat; draft.Clear();
        foreach (var backdrop in Backgrounds.Recommend(coat).Take(2)) draft.Add(backdrop.Id);
        RenderCards();
    }
    private void Toggle(string id)
    {
        if (!draft.Remove(id))
        {
            if (draft.Count == 2) { selection.Text = "2개가 선택되어 있습니다. 하나를 해제한 뒤 다른 배경을 선택하세요."; return; }
            draft.Add(id);
        }
        RenderCards();
    }
    private void RenderCards()
    {
        cards.Children.Clear();
        cards.Columns = 5;
        foreach (var pair in coats) pair.Value.Background = Brush(pair.Key == settings.Coat ? "#426C60" : "#293646");
        foreach (var backdrop in Backgrounds.All)
        {
            bool selected = draft.Contains(backdrop.Id);
            var content = new StackPanel();
            content.Children.Add(new Image { Source = backdrop.Image, Height = 70, Stretch = Stretch.UniformToFill });
            content.Children.Add(Text((selected ? "✓  " : "") + backdrop.Name, 15));
            var card = new Button { Content = content, ToolTip = backdrop.Description, HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(8), Margin = new Thickness(0, 0, 10, 8), Background = Brush("#1A222E"), BorderBrush = Brush(selected ? "#BCE9D5" : "#2B3747"), BorderThickness = new Thickness(selected ? 2 : 1), Cursor = System.Windows.Input.Cursors.Hand };
            card.Click += (_, _) => Toggle(backdrop.Id); cards.Children.Add(card);
        }
        selection.Text = $"{draft.Count} / 2 선택  ·  " + string.Join(" + ", Backgrounds.All.Where(x => draft.Contains(x.Id)).Select(x => x.Name));
        apply.IsEnabled = draft.Count == 2;
    }
    private void ApplyBackgrounds()
    {
        if (draft.Count != 2) return;
        session.Apply(Backgrounds.All.Where(x => draft.Contains(x.Id)), DateTime.UtcNow);
        settings.Selected = session.Active.Select(x => x.Id).ToArray(); Save(); UpdateClock();
        selection.Text = "적용 완료 · " + string.Join(" + ", session.Active.Select(x => x.Name));
    }
    private void UpdateClock()
    {
        var now = DateTime.UtcNow; var backdrop = session.Active[session.Index(now)];
        backgroundPreview.Source = backdrop.Image; projector.Picture.Source = backdrop.Image;
        rotation.Text = $"{backdrop.Name}  ·  다음 배경까지 {session.Remaining(now)}초";
        string name = settings.DogName;
        int seconds = verify ? previewLease.RemainingSeconds : camera.RemainingSeconds;
        string remaining = $"{seconds / 60:00}:{seconds % 60:00}";
        photoScreen.UpdateWelcome(name, remaining); photoPreview.UpdateInfo(name, remaining);
        RefreshStartButton();
    }
    private void ShowPhoto(ImageSource source, string caption)
    {
        lastPhoto = source; photoPreview.SetPhoto(source); photoScreen.SetPhoto(source);
        photoCaption.Text = caption + " · 다음 촬영까지 유지";
    }
    private void UpdateOrientation()
    {
        orientation.Content = settings.PhotoPortrait ? "사진: 세로 ↻" : "사진: 가로 ↻";
        photoScreen.SetPortrait(settings.PhotoPortrait);
        photoPreview.SetOrientation(settings.PhotoPortrait, false);
    }
    private void RefreshStartButton()
    {
        if (start == null) return;
        bool active = CapturePhaseNow == CapturePhase.Active;
        dogInput.IsEnabled = !active && !captureStarting;
        start.IsEnabled = !shuttingDown && !captureStarting && !active && CapturePhaseNow == CapturePhase.Locked && !string.IsNullOrWhiteSpace(dogInput.Text);
        start.Content = captureStarting ? "카메라 촬영 허용 설정 중…" : active ? "촬영 중 · 20분 종료 시 자동 잠금" : CapturePhaseNow == CapturePhase.Locked ? "촬영 시작  ·  20분" : "카메라 잠금 상태 확인 중 · 촬영 불가";
    }
    private async System.Threading.Tasks.Task StartSessionAsync()
    {
        if (captureStarting || CapturePhaseNow == CapturePhase.Active || string.IsNullOrWhiteSpace(dogInput.Text)) return;
        captureStarting = true; RefreshStartButton();
        try
        {
            bool success;
            if (verify) { previewLease.Start(); success = true; }
            else success = await camera.StartCaptureAsync();
            if (!success || shuttingDown) return;
            ResetPhoto(); ApplyBackgrounds();
            status.Text = "촬영 허용 완료 · PC 저장(Host) · 20분 후 자동 잠금";
        }
        finally { captureStarting = false; RefreshStartButton(); UpdateClock(); }
    }
    private void ResetPhoto()
    {
        lastPhoto = null; photoPreview.SetPhoto(null); photoScreen.SetPhoto(null);
        photoCaption.Text = "촬영 준비 · 이름 입력 후 촬영 시작을 누르세요"; Save(); UpdateClock();
    }
    private static BitmapSource Decode(byte[] bytes) => PhotoDecoder.Decode(bytes);
    private void LoadPhoto()
    {
        var picker = new OpenFileDialog { Filter = "사진|*.jpg;*.jpeg;*.png;*.bmp", Title = "디스플레이 테스트용 사진 선택" };
        if (picker.ShowDialog(this) != true) return;
        try { ShowPhoto(Decode(File.ReadAllBytes(picker.FileName)), "테스트 · " + Path.GetFileName(picker.FileName)); }
        catch (Exception ex) { status.Text = "사진 열기 실패: " + ex.Message; }
    }
    private sealed record MonitorChoice(string Id, string Label) { public override string ToString() => Label; }
    private void OnDisplayChanged(object? sender, EventArgs e) => Dispatcher.BeginInvoke(new Action(() => { if (!shuttingDown) RefreshMonitors(); }));
    private void RefreshMonitors()
    {
        refreshing = true;
        var monitors = Screen.AllScreens;
        var selected = monitors.FirstOrDefault(x => x.DeviceName == settings.MainScreen) ?? Screen.PrimaryScreen ?? monitors[0];
        mainMonitor.ItemsSource = monitors.Select((s, i) => new MonitorChoice(s.DeviceName, $"메인: 화면 {i + 1} ({s.Bounds.Width}×{s.Bounds.Height}){(s.Primary ? " · 주 화면" : "")}")).ToArray();
        mainMonitor.SelectedIndex = Array.IndexOf(monitors, selected);
        // Keep the saved laptop identity when temporarily disconnected.
        if (string.IsNullOrEmpty(settings.MainScreen)) { settings.MainScreen = selected.DeviceName; Save(); }
        refreshing = false; LayoutDisplays();
    }
    private void LayoutDisplays()
    {
        if (verify) return;
        var monitors = Screen.AllScreens;
        var main = monitors.FirstOrDefault(x => x.DeviceName == settings.MainScreen) ?? Screen.PrimaryScreen ?? monitors[0];
        Displays.Place(this, main);
        var external = monitors.Where(x => x.DeviceName != main.DeviceName).OrderBy(x => x.DeviceName).Take(2).ToArray();
        swap.IsEnabled = external.Length == 2;
        if (external.Length == 2)
        {
            Displays.Place(projector, external[settings.Swapped ? 1 : 0]);
            Displays.Place(photoScreen, external[settings.Swapped ? 0 : 1]);
            displayStatus.Text = $"메인 {main.DeviceName} 고정  |  배경 {external[settings.Swapped ? 1 : 0].DeviceName}  |  사진 {external[settings.Swapped ? 0 : 1].DeviceName}";
        }
        else
        {
            photoScreen.Hide();
            if (external.Length == 1) Displays.Place(projector, external[0]); else projector.Hide();
            displayStatus.Text = $"현재 {monitors.Length}개 화면 · 3화면 운영은 Windows 디스플레이 설정에서 ‘확장’을 선택하세요. 부족한 화면은 위 미리보기로 확인할 수 있습니다.";
        }
    }
    private void Save()
    {
        if (verify) return;
        try { settings.Save(); } catch (Exception ex) { status.Text = "설정 저장 실패: " + ex.Message; }
    }
    private async void OnClosing(object? sender, CancelEventArgs e)
    {
        if (closed || verify) return;
        e.Cancel = true; await Shutdown();
    }
    private async System.Threading.Tasks.Task Shutdown()
    {
        if (shuttingDown) return;
        shuttingDown = true; IsEnabled = false; clock.Stop(); Save();
        status.Text = "카메라 잠금 복원 및 다운로드 마무리 중…";
        if (!verify && !await camera.LockCaptureAsync())
        {
            shuttingDown = false; IsEnabled = true; clock.Start(); RefreshStartButton();
            status.Text = "카메라 잠금이 확인되지 않아 종료하지 않았습니다. 다운로드 완료 / 카메라 재연결 후 다시 종료하세요.";
            return;
        }
        SystemEvents.DisplaySettingsChanged -= OnDisplayChanged;
        if (!verify) await camera.DisposeAsync();
        closed = true; projector.AllowClose = photoScreen.AllowClose = true;
        projector.Close(); photoScreen.Close(); Application.Current.Shutdown();
    }
    internal void VerifyInteractions()
    {
        foreach (string coat in new[] { "흑색", "갈색", "흰색" })
        {
            SelectCoat(coat);
            if (cards.Children.Count != 10 || cards.Columns != 5 || draft.Count != 2) throw new Exception("Nature background grid failed");
            var first = draft.First(); Toggle(first);
            if (apply.IsEnabled) throw new Exception("Apply must require two selections");
            Toggle(first); ApplyBackgrounds();
            if (session.Active.Count != 2) throw new Exception("Background apply failed");
        }
        ShowPhoto(Backgrounds.All[0].Image, "검증 사진");
        if (photoScreen.Picture.Source == null || photoPreview.Picture.Source == null) throw new Exception("Photo display failed");
        ResetPhoto();
        if (photoScreen.Picture.Source != null || previewLease.Active) throw new Exception("Welcome reset failed");
        if (dogInput.Text != "" || start.IsEnabled) throw new Exception("Name must start empty");
        dogInput.Text = "테스트 강아지"; StartSessionAsync().GetAwaiter().GetResult();
        if (!previewLease.Active || start.IsEnabled || dogInput.IsEnabled) throw new Exception("Start must open exactly one timed capture window");
        settings.PhotoPortrait = true; UpdateOrientation();
        if (!photoPreview.Portrait) throw new Exception("Portrait failed");
        settings.PhotoPortrait = false; UpdateOrientation();
        previewLease.Lock(); previewLease = new CaptureLease(() => { }, () => { }); previewLease.Lock();
        RefreshStartButton(); dogInput.Text = ""; ResetPhoto();
        SelectCoat("흑색"); ApplyBackgrounds(); UpdateLayout();
    }
}
