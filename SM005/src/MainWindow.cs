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
    private readonly TextBlock centralName = Text("이름을 입력해 주세요", 36);
    private readonly TextBlock countdown = Text("20:00", 40, "#BCE9D5");
    private readonly UniformGrid thumbnails = new() { Columns = 5 };
    private readonly StackPanel setupPage = new();
    private readonly DockPanel capturePage = new();
    private readonly Button nextSession;
    private readonly DriveBackup? backup;
    private BackupJob? backupJob;
    private bool endingSession;
    private bool cameraConnected, nameRequested, exitRequested, driveConnecting;
    private Window? nameDialog, connectionDialog;
    private readonly TextBlock connectionMessage = Text("카메라 전원을 켜고 USB 케이블을 연결해 주세요.", 20);
    private readonly System.Threading.CancellationTokenSource connectionCancellation = new();
    private readonly System.Threading.CancellationTokenSource backupCancellation = new();
    private DateTime nextBackupRetry = DateTime.UtcNow.AddMinutes(2);
    private System.Threading.Tasks.Task backupTask = System.Threading.Tasks.Task.CompletedTask;
    private readonly TextBlock backupStatus = Text("CR3 백업 대기", 14, "#BCE9D5");
    private readonly ScrollViewer gallery = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalContentAlignment = VerticalAlignment.Top };
    private readonly TextBlock galleryCount = Text("촬영 사진 · 0장", 16);
    private readonly StackPanel selectedPreviews = new() { Orientation = Orientation.Horizontal };
    private System.Threading.Tasks.Task decodeQueue = System.Threading.Tasks.Task.CompletedTask;
    private int shotCount;
    private readonly List<SessionPhoto> sessionPhotos = new();
    private readonly Button deleteBlurred, recommendPhotos, printPhotos;
    private SessionPhoto[] printSelection = Array.Empty<SessionPhoto>();
    private bool printing;
    private readonly TextBlock analysisStatus = Text("선명도·노출을 분석하고 비슷한 사진을 묶어 추천합니다", 14, "#A5B6C9");
    private bool processingPhotos, recommendedView;
    private string? displayedPhotoPath;
    private int photoGeneration;
    private Action<string>? verificationRecycle;
    private readonly Settings settings;
    private readonly bool verify;
    private readonly SessionState session = new();
    private CaptureLease previewLease = new(() => { }, () => { });
    private bool captureStarting;
    private CapturePhase CapturePhaseNow => verify ? previewLease.Phase : camera.Phase;
    private readonly List<string> draft = new();
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
    private bool refreshing, shuttingDown, closed, shutdownTimedOut;
    private ImageSource? lastPhoto;

    public MainWindow(bool verification = false)
    {
        verify = verification;
        cameraConnected = verification;
        previewLease.Lock();
        settings = verify ? new Settings() : Settings.Load();
        if (!verify) RcloneDrive.ImportPreviousConnection();
        settings.DogName = "";
        if (!verify) {
            backup = new DriveBackup(Path.Combine(Path.GetDirectoryName(Settings.FilePath)!, "backup-jobs"), new RcloneDrive());
            backup.Status += message => Dispatcher.BeginInvoke(new Action(() => backupStatus.Text = message));
        }
        if (!new[] { "흑색", "갈색", "흰색" }.Contains(settings.Coat)) settings.Coat = "흑색";
        camera = new CanonCamera(settings.PhotoFolder) { DogName = settings.DogName };
        camera.FileSaved += path => { if (backupJob != null) backup?.Record(backupJob, path); };
        Title = "샤인멍 · SM005";
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        Background = Brush("#10151D");
        Foreground = Brushes.White;
        FontFamily = new FontFamily("Malgun Gothic");
        Width = 1440; Height = 960;

        var root = new DockPanel { Width = 1440, Height = 960, Margin = new Thickness(24, 16, 24, 16) };
        var header = new DockPanel { Margin = new Thickness(0, 0, 0, 10) };
        var admin = Button("관리자 메뉴 ⚙", ShowAdmin);
        DockPanel.SetDock(admin, Dock.Right); header.Children.Add(admin);
        var retryCamera = Button("카메라 다시 연결", async () => { if (!verify) await camera.ReconnectAsync(); });
        DockPanel.SetDock(retryCamera, Dock.Right); header.Children.Add(retryCamera);
        header.Children.Add(cameraState); DockPanel.SetDock(header, Dock.Top); root.Children.Add(header);
        var footer = new StackPanel(); footer.Children.Add(status); footer.Children.Add(displayStatus);
        footer.Children.Add(backupStatus);
        footer.Children.Add(Button("백업 재시도", async () => await ResumeBackupAsync()));
        status.TextWrapping = TextWrapping.NoWrap; status.TextTrimming = TextTrimming.CharacterEllipsis;
        DockPanel.SetDock(footer, Dock.Bottom); root.Children.Add(footer);
        centralName.HorizontalAlignment = countdown.HorizontalAlignment = HorizontalAlignment.Center;
        DockPanel.SetDock(centralName, Dock.Top); root.Children.Add(centralName);
        var pages = new Grid(); pages.Children.Add(setupPage); pages.Children.Add(capturePage); root.Children.Add(pages);
        var changeName = Button("이름 입력 / 변경", ShowNameDialog);
        changeName.HorizontalAlignment = HorizontalAlignment.Center; setupPage.Children.Add(changeName);
        dogInput.MaxLength = 60;
        dogInput.TextChanged += (_, _) => { settings.DogName = dogInput.Text.Trim(); camera.DogName = settings.DogName; UpdateClock(); };
        mainMonitor.SelectionChanged += (_, _) => {
            if (refreshing || mainMonitor.SelectedItem is not MonitorChoice choice) return;
            settings.MainScreen = choice.Id; Save(); LayoutDisplays();
        };
        swap = Button("화면 바꾸기 ⇄", () => { settings.Swapped = !settings.Swapped; Save(); LayoutDisplays(); });
        orientation = Button("", () => { settings.PhotoPortrait = !settings.PhotoPortrait; UpdateOrientation(); Save(); });
        setupPage.Children.Add(Text("자연 배경 선택 · 12가지 풍경", 22));
        var coatRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 8) };
        foreach (string coat in new[] { "흑색", "갈색", "흰색" }) {
            var button = Button(coat + " 추천", () => SelectCoat(coat)); coats[coat] = button; coatRow.Children.Add(button);
        }
        coatRow.Children.Add(Text("하나씩 선택하세요 · 최근 선택한 2개를 순서대로 1분마다 교대합니다", 14));
        setupPage.Children.Add(coatRow); setupPage.Children.Add(cards);
        apply = Button("선택한 배경 적용", ApplyBackgrounds, true); // retained for interaction validation; selections apply automatically
        setupPage.Children.Add(selection);
        var selected = new StackPanel(); selected.Children.Add(Text("선택한 배경 · 1 → 2", 18)); selectedPreviews.HorizontalAlignment = HorizontalAlignment.Center;
        selected.Children.Add(selectedPreviews); setupPage.Children.Add(Panel(selected));
        start = Button("촬영 시작", async () => await StartSessionAsync(), true);
        start.Height = 64; start.FontSize = 26; start.Margin = new Thickness(0, 16, 12, 4); setupPage.Children.Add(start);

        var liveBackground = new DockPanel { Margin = new Thickness(0, 0, 0, 4) };
        backgroundPreview.Height = 65; backgroundPreview.Width = 120; backgroundPreview.Margin = new Thickness(0, 0, 16, 0);
        DockPanel.SetDock(backgroundPreview, Dock.Left); liveBackground.Children.Add(backgroundPreview);
        liveBackground.Children.Add(rotation); DockPanel.SetDock(liveBackground, Dock.Top); capturePage.Children.Add(liveBackground);
        DockPanel.SetDock(countdown, Dock.Top); capturePage.Children.Add(countdown);
        nextSession = Button("종료 · 다음 사용자", async () => await EndSessionAsync());
        nextSession.HorizontalAlignment = HorizontalAlignment.Right;
        DockPanel.SetDock(nextSession, Dock.Top); capturePage.Children.Add(nextSession);
        var photoActions = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 6) };
        deleteBlurred = Button("흔들림·초점 불량 사진 삭제", async () => await AnalyzePhotosAsync(true));
        deleteBlurred.ToolTip = "이번 촬영의 흐림이 뚜렷한 JPEG 사진을 휴지통으로 이동합니다. RAW 원본과 테스트로 불러온 파일은 삭제하지 않습니다.";
        recommendPhotos = Button("추천 사진 8장", async () => { if (recommendedView) RenderGallery(false); else await AnalyzePhotosAsync(false); }, true);
        printPhotos = Button("Print · 4컷 × 2장", async () => await PrintRecommendationsAsync(), true);
        printPhotos.IsEnabled = false; printPhotos.ToolTip = "추천 사진 8장을 DS-RX1HS/DS-RX1의 4×6인치 인화지 2장으로 출력합니다.";
        photoActions.Children.Add(deleteBlurred); photoActions.Children.Add(recommendPhotos); photoActions.Children.Add(printPhotos);
        DockPanel.SetDock(photoActions, Dock.Top); capturePage.Children.Add(photoActions);
        DockPanel.SetDock(analysisStatus, Dock.Top); capturePage.Children.Add(analysisStatus);
        DockPanel.SetDock(galleryCount, Dock.Top); capturePage.Children.Add(galleryCount);
        gallery.Content = thumbnails; capturePage.Children.Add(gallery);
        ShowPage(false);
        Content = new Viewbox { Stretch = Stretch.Uniform, Child = root };

        camera.ConnectionState += (label, connected) => Dispatcher.BeginInvoke(new Action(() =>
        {
            UpdateCameraConnection(connected, label);
            cameraState.Text = "●  " + label;
            cameraState.Foreground = Brush(connected ? "#BCE9D5" : "#F1CC83");
        }));
        camera.CaptureChanged += _ => Dispatcher.BeginInvoke(new Action(() => { if (!shuttingDown) { RefreshStartButton(); UpdateClock(); } }));
        camera.Status += message => Dispatcher.BeginInvoke(new Action(() => { if (!closed) status.Text = message; }));
        camera.Photo += (bytes, path, capturedDog) =>
        {
            // Decode serially off the SDK thread; keep only small gallery images.
            decodeQueue = decodeQueue.ContinueWith(_ => {
                try {
                    var bitmap = Decode(bytes);
                    Dispatcher.Invoke(() => {
                        if (shuttingDown) return;
                        ShowPhoto(bitmap, Path.GetFileName(path)); displayedPhotoPath = path;
                        AddThumbnail(bitmap, path, true);
                    });
                } catch (Exception ex) { Dispatcher.BeginInvoke(new Action(() => status.Text = "사진 표시 실패: " + ex.Message)); }
            }, System.Threading.Tasks.TaskScheduler.Default);
        };
        clock = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background, (_, _) => UpdateClock(), Dispatcher);
        UpdateOrientation(); RenderCards(); UpdateClock();
        Loaded += (_, _) =>
        {
            if (verify) { mainMonitor.Items.Add(new MonitorChoice("preview", "메인: 노트북 화면 (미리보기)")); refreshing = true; mainMonitor.SelectedIndex = 0; refreshing = false; status.Text = "미리보기 모드 · Canon 연결 대기"; displayStatus.Text = "메인 컨트롤 고정 · 외부 화면 연결 시 자동 배치"; return; }
            Awake.Enable(); camera.Start(); RefreshMonitors(); Dispatcher.BeginInvoke(new Action(ShowNameDialog)); SystemEvents.DisplaySettingsChanged += OnDisplayChanged;
            _ = ResumeBackupAsync();
        };
        Closing += OnClosing;
        System.Windows.Input.InputManager.Current.PreProcessInput += OnGlobalInput;
        Closed += (_, _) => System.Windows.Input.InputManager.Current.PreProcessInput -= OnGlobalInput;
    }
    private void OnGlobalInput(object sender, System.Windows.Input.PreProcessInputEventArgs e)
    {
        if (e.StagingItem.Input is System.Windows.Input.KeyEventArgs key && key.RoutedEvent == System.Windows.Input.Keyboard.PreviewKeyDownEvent && key.Key == System.Windows.Input.Key.Escape && !key.IsRepeat) {
            key.Handled = true;
            _ = ExitFromEscapeAsync();
        }
    }
    private async System.Threading.Tasks.Task ExitFromEscapeAsync()
    {
        if (verify) { exitRequested = true; return; }
        if (shutdownTimedOut) { backupCancellation.Cancel(); connectionCancellation.Cancel(); Awake.Disable(); Environment.Exit(1); }
        if (exitRequested) return;
        exitRequested = true; nameRequested = false;
        foreach (Window window in Application.Current.Windows.Cast<Window>().ToArray())
            if (window != this && window != projector && window != photoScreen) window.Close();
        connectionCancellation.Cancel();
        status.Text = "종료 중 · 진행 중인 사진 처리를 마무리합니다…";
        var deadline = DateTime.UtcNow.AddSeconds(15);
        while (printing || processingPhotos || endingSession || captureStarting) {
            if (DateTime.UtcNow >= deadline) {
                shutdownTimedOut = true; exitRequested = false;
                status.Text = "진행 중인 작업이 응답하지 않습니다. ESC를 다시 누르면 강제 종료됩니다(수신 중 사진 유실 가능).";
                return;
            }
            await System.Threading.Tasks.Task.Delay(100);
        }
        await Shutdown();
        if (!closed) exitRequested = false;
    }
    private bool CanEnterName => !exitRequested && !shuttingDown && cameraConnected;
    private void UpdateCameraConnection(bool connected, string label)
    {
        cameraConnected = connected;
        connectionMessage.Text = label + "\n\n카메라 전원을 켜고 USB를 연결해 주세요. EOS Utility가 실행 중이면 종료해 주세요.\n연결되면 이름 입력창이 열립니다.";
        if (shuttingDown || exitRequested) return;
        if (!connected && nameDialog != null) { nameRequested = true; nameDialog.Close(); }
        if (nameRequested && connected) {
            connectionDialog?.Close();
            Dispatcher.BeginInvoke(new Action(ShowNameDialog));
        } else if (nameRequested && !connected) ShowConnectionDialog();
    }
    private void ShowConnectionDialog()
    {
        if (connectionDialog != null || exitRequested || shuttingDown) return;
        var dialog = new Window { Owner = this, Title = "카메라를 먼저 연결해 주세요", Width = 540, Height = 310, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brush("#1A222E") };
        var panel = new StackPanel { Margin = new Thickness(24) }; panel.Children.Add(connectionMessage);
        panel.Children.Add(Button("연결 다시 확인", async () => { if (!verify) await camera.ReconnectAsync(); }));
        panel.Children.Add(Text("ESC · 프로그램 종료", 14)); dialog.Content = panel;
        connectionDialog = dialog;
        dialog.Closed += (_, _) => { panel.Children.Clear(); connectionDialog = null; };
        dialog.Show();
    }
    private void ShowNameDialog()
    {
        if (exitRequested || shuttingDown || nameDialog != null || CapturePhaseNow == CapturePhase.Active || captureStarting) return;
        nameRequested = true;
        if (!CanEnterName) { ShowConnectionDialog(); return; }
        connectionDialog?.Close(); nameRequested = false;
        var dialog = new Window { Title = "오늘의 주인공", Owner = this, Width = 480, Height = 240, ResizeMode = ResizeMode.NoResize, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brush("#1A222E"), Foreground = Brushes.White };
        nameDialog = dialog;
        var content = new StackPanel { Margin = new Thickness(24) };
        content.Children.Add(Text("강아지 이름을 입력해 주세요", 24)); content.Children.Add(dogInput);
        var ok = Button("확인", () => { if (CanEnterName && !string.IsNullOrWhiteSpace(dogInput.Text)) { Save(); dialog.Close(); } }, true);
        ok.IsDefault = true; ok.Margin = new Thickness(0, 16, 0, 0); content.Children.Add(ok); dialog.Content = content;
        dialog.Loaded += (_, _) => dogInput.Focus(); dialog.ShowDialog(); content.Children.Remove(dogInput); nameDialog = null;
    }
    internal static bool AcceptAdminPassword(string password) => password == "3327";
    private bool RequestAdminPassword()
    {
        var dialog = new Window { Title = "관리자 인증", Owner = this, Width = 420, Height = 255, ResizeMode = ResizeMode.NoResize, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brush("#1A222E") };
        var content = new StackPanel { Margin = new Thickness(24) };
        content.Children.Add(Text("관리자 비밀번호", 23));
        var password = new PasswordBox { FontSize = 24, Padding = new Thickness(10), MaxLength = 32 };
        content.Children.Add(password);
        var error = Text("", 14, "#F1CC83"); content.Children.Add(error);
        var enter = Button("확인", () => {
            if (AcceptAdminPassword(password.Password)) dialog.DialogResult = true;
            else { error.Text = "비밀번호가 올바르지 않습니다."; password.Clear(); password.Focus(); }
        }, true); enter.IsDefault = true; content.Children.Add(enter);
        dialog.Content = content; dialog.Loaded += (_, _) => password.Focus();
        dialog.PreviewKeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Escape) dialog.Close(); };
        return dialog.ShowDialog() == true;
    }
    private void ShowPage(bool capture)
    {
        setupPage.Visibility = capture ? Visibility.Collapsed : Visibility.Visible;
        capturePage.Visibility = capture ? Visibility.Visible : Visibility.Collapsed;
    }
    private void ShowAdmin()
    {
        if (!RequestAdminPassword()) return;
        var dialog = new Window { Title = "관리자 메뉴", Owner = this, Width = 600, Height = 480, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brush("#1A222E") };
        var content = new StackPanel { Margin = new Thickness(24) };
        content.Children.Add(Text("화면 배치", 24)); content.Children.Add(mainMonitor); content.Children.Add(swap); content.Children.Add(orientation);
        content.Children.Add(Button("카메라 다시 연결", async () => { if (!verify) await camera.ReconnectAsync(); }));
        content.Children.Add(Button("사진 불러오기 테스트", LoadPhoto));
        content.Children.Add(Button("Google Drive 연결 / 상태 확인", () => ShowDriveConnection(dialog)));
        content.Children.Add(Button("프로그램 종료", async () => { dialog.Close(); await Shutdown(); }));
        if (shutdownTimedOut) {
            content.Children.Add(Text("SDK 응답 없음 · 강제 종료 시 아직 수신 중인 사진은 유실될 수 있습니다.", 12));
            content.Children.Add(Button("응답 없는 프로그램 강제 종료", () => {
                backupCancellation.Cancel();
                Awake.Disable();
                Environment.Exit(1);
            }));
            dialog.Height = 460;
        }
        dialog.Content = content; dialog.ShowDialog(); content.Children.Clear();
    }
    private void ShowDriveConnection(Window owner)
    {
        var dialog = new Window { Owner = owner, Title = "Google Drive 연결", Width = 620, Height = 390, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brush("#1A222E") };
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(Text("Google Drive 백업 연결", 24));
        panel.Children.Add(Text(RcloneDrive.Account + "\nPhoto_mong / 날짜 / DOG_이름", 17));
        var message = Text(File.Exists(RcloneDrive.ConfigPath) ? "저장된 인증이 있습니다. 연결 상태 확인을 눌러 주세요." : "Google 계정 연결이 필요합니다.", 15); panel.Children.Add(message);
        using var cancel = System.Threading.CancellationTokenSource.CreateLinkedTokenSource(connectionCancellation.Token);
        Button? connect = null, check = null;
        async System.Threading.Tasks.Task Run(bool authenticate)
        {
            if (driveConnecting) return;
            if (!backupTask.IsCompleted) { message.Text = "현재 백업 중입니다. 백업이 끝난 후 연결 설정을 변경해 주세요."; return; }
            driveConnecting = true; connect!.IsEnabled = check!.IsEnabled = false;
            try {
                message.Text = authenticate ? "브라우저에서 nobug007@gmail.com으로 로그인하고 연결을 허용해 주세요." : "Google 계정과 연결을 확인하고 있습니다…";
                using var timeout = System.Threading.CancellationTokenSource.CreateLinkedTokenSource(cancel.Token);
                timeout.CancelAfter(TimeSpan.FromMinutes(authenticate ? 5 : 2));
                if (!verify) {
                    if (authenticate) await RcloneDrive.ConnectAsync(timeout.Token);
                    else await new RcloneDrive().CheckAccountAsync(timeout.Token);
                }
                message.Text = verify ? "검증 모드 · 실제 인증은 실행하지 않았습니다." : "연결 완료 · " + RcloneDrive.Account;
                backupStatus.Text = message.Text;
            } catch (OperationCanceledException) { message.Text = "연결 확인을 취소했습니다. 기존 인증은 유지됩니다."; }
            catch (Exception ex) { message.Text = "연결 실패 · " + ex.Message; }
            finally { driveConnecting = false; connect!.IsEnabled = check!.IsEnabled = true; }
        }
        connect = Button("Google 계정 연결 / 다시 연결", async () => await Run(true), true);
        check = Button("연결 상태 확인", async () => await Run(false));
        panel.Children.Add(connect); panel.Children.Add(check);
        panel.Children.Add(Button("닫기", () => dialog.Close()));
        dialog.Content = panel; dialog.Closed += (_, _) => cancel.Cancel(); dialog.ShowDialog();
    }
    private void AddThumbnail(BitmapSource source, string path, bool captured = false)
    {
        double scale = Math.Min(1, 240.0 / Math.Max(source.PixelWidth, source.PixelHeight));
        var small = new TransformedBitmap(source, new ScaleTransform(scale, scale));
        // Render into a standalone bitmap so the full original is not retained by TransformedBitmap.
        var visual = new DrawingVisual(); using (var dc = visual.RenderOpen()) dc.DrawImage(small, new Rect(0, 0, small.PixelWidth, small.PixelHeight));
        var thumb = new RenderTargetBitmap(small.PixelWidth, small.PixelHeight, 96, 96, PixelFormats.Pbgra32); thumb.Render(visual); thumb.Freeze();
        sessionPhotos.Add(new SessionPhoto(++shotCount, path, thumb, captured)); photoGeneration++;
        if (recommendedView) analysisStatus.Text = "새 사진이 추가되어 전체 보기로 전환했습니다. 다시 추천할 수 있습니다.";
        RenderGallery(false);
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, new Action(() => gallery.ScrollToEnd()));
    }
    private void RenderGallery(bool recommendations, IReadOnlyList<SessionPhoto>? selected = null)
    {
        recommendedView = recommendations;
        printSelection = recommendations ? (selected?.ToArray() ?? Array.Empty<SessionPhoto>()) : Array.Empty<SessionPhoto>();
        thumbnails.Children.Clear();
        var shown = selected ?? sessionPhotos.TakeLast(100).ToArray();
        foreach (var photo in shown) {
            var panel = new StackPanel { Margin = new Thickness(4), ToolTip = "클릭하여 크게 보기" };
            panel.Children.Add(new Image { Source = photo.Thumbnail, Height = 155, Stretch = Stretch.Uniform });
            panel.Children.Add(Text((recommendations ? "추천 · " : "") + $"{photo.Number:000}", 13));
            var item = new Button { Content = panel, Background = Brush("#1A222E"), BorderThickness = new Thickness(0), Margin = new Thickness(4), HorizontalContentAlignment = HorizontalAlignment.Stretch, Cursor = System.Windows.Input.Cursors.Hand };
            item.Click += (_, _) => ShowPhotoPopup(photo.Thumbnail, photo.Path); thumbnails.Children.Add(item);
        }
        recommendPhotos.Content = recommendations ? "전체 사진 보기" : "추천 사진 8장";
        galleryCount.Text = recommendations ? $"추천 사진 · {shown.Count}장 / 보관 {sessionPhotos.Count}장" : $"촬영 사진 · 총 {shotCount}장 / 보관 {sessionPhotos.Count}장 / 최근 {shown.Count}장 표시";
        deleteBlurred.IsEnabled = recommendPhotos.IsEnabled = !processingPhotos && !printing && sessionPhotos.Count > 0;
        RefreshPrintButton();
    }
    private async System.Threading.Tasks.Task AnalyzePhotosAsync(bool delete)
    {
        if (endingSession || processingPhotos || printing || sessionPhotos.Count == 0) return;
        processingPhotos = true; RefreshPrintButton(); deleteBlurred.IsEnabled = recommendPhotos.IsEnabled = false; nextSession.IsEnabled = false;
        var snapshot = sessionPhotos.ToArray(); int generation = photoGeneration;
        try {
            var assessed = new List<AssessedPhoto>(); int skipped = 0;
            analysisStatus.Text = "사진 분석 중… 촬영은 계속할 수 있습니다.";
            await System.Threading.Tasks.Task.Run(() => {
                for (int i = 0; i < snapshot.Length; i++) {
                    var photo = snapshot[i];
                    try {
                        if (!PhotoSelection.Supports(photo.Path)) { skipped++; continue; }
                        assessed.Add(new AssessedPhoto(photo, PhotoSelection.Analyze(Decode(File.ReadAllBytes(photo.Path)))));
                    } catch { skipped++; }
                    int done = i + 1;
                    if (done % 5 == 0) Dispatcher.BeginInvoke(new Action(() => analysisStatus.Text = $"사진 분석 중 · {done}/{snapshot.Length}장"));
                }
            });
            if (shuttingDown) return;
            if (delete) {
                var targets = PhotoSelection.Blurred(assessed).Where(p => p.Photo.Captured).ToArray();
                var removed = new List<SessionPhoto>(); var failures = new List<string>();
                await System.Threading.Tasks.Task.Run(() => {
                    foreach (var target in targets) {
                        try { PhotoSelection.Recycle(target.Photo, settings.PhotoFolder, verify ? verificationRecycle : null); removed.Add(target.Photo); }
                        catch (Exception ex) { failures.Add(Path.GetFileName(target.Photo.Path) + ": " + ex.Message); }
                    }
                });
                foreach (var photo in removed) sessionPhotos.Remove(photo);
                if (removed.Count > 0) photoGeneration++;
                RenderGallery(false);
                if (removed.Any(p => p.Path == displayedPhotoPath)) {
                    ResetPhoto(); displayedPhotoPath = null;
                    var latest = sessionPhotos.LastOrDefault();
                    if (latest != null) {
                        displayedPhotoPath = latest.Path; ShowPhoto(latest.Thumbnail, "최근 보관 사진");
                        try {
                            var full = await System.Threading.Tasks.Task.Run(() => Decode(File.ReadAllBytes(latest.Path)));
                            if (displayedPhotoPath == latest.Path && !shuttingDown) ShowPhoto(full, Path.GetFileName(latest.Path));
                        } catch { }
                    }
                }
                analysisStatus.Text = targets.Length == 0
                    ? $"분석 {assessed.Count}장 · 삭제 대상 없음 · 분석 제외 {skipped}장 (RAW/읽기 실패)"
                    : $"불량 판정 {targets.Length}장 · 휴지통 이동 {removed.Count}장 · 실패 {failures.Count}장 · 분석 제외 {skipped}장";
                if (failures.Count > 0) analysisStatus.ToolTip = string.Join(Environment.NewLine, failures);
                WriteAnalysisLog("delete", assessed, removed.Select(p => p.Path), failures);
            } else {
                if (generation != photoGeneration) { analysisStatus.Text = "분석 중 새 사진이 추가되었습니다. 추천 버튼을 다시 눌러 최신 사진을 포함하세요."; return; }
                var chosen = PhotoSelection.Recommend(assessed);
                RenderGallery(true, chosen.Select(p => p.Photo).ToArray()); gallery.ScrollToTop();
                analysisStatus.Text = $"비슷한 사진을 제외한 추천 {chosen.Count}장" + (chosen.Count < 8 ? " · 중복/흐림을 제외해 8장보다 적습니다." : "") + $" · 분석 제외 {skipped}장 (RAW/읽기 실패)";
                WriteAnalysisLog("recommend", assessed, chosen.Select(p => p.Photo.Path), Array.Empty<string>());
            }
        } catch (Exception ex) { analysisStatus.Text = "사진 처리 실패 · " + ex.Message; }
        finally {
            processingPhotos = false; nextSession.IsEnabled = true;
            deleteBlurred.IsEnabled = recommendPhotos.IsEnabled = !printing && sessionPhotos.Count > 0; RefreshPrintButton();
        }
    }
    private void RefreshPrintButton()
    {
        printPhotos.IsEnabled = !endingSession && !printing && !processingPhotos && recommendedView && printSelection.Length == 8;
        printPhotos.Content = printing ? "프린터 전송 중…" : "Print · 4컷 × 2장";
    }
    private async System.Threading.Tasks.Task PrintRecommendationsAsync()
    {
        if (verify || endingSession || printing || processingPhotos || !recommendedView || printSelection.Length != 8) return;
        var selected = printSelection.ToArray();
        printing = true; RefreshPrintButton(); nextSession.IsEnabled = false; deleteBlurred.IsEnabled = recommendPhotos.IsEnabled = false;
        analysisStatus.Text = "추천 8장 인쇄 준비 · 4×6인치에 4장씩, 총 2매";
        try { analysisStatus.Text = await PhotoPrinter.PrintAsync(selected); }
        catch (Exception ex) { analysisStatus.Text = ex.Message; }
        finally { printing = false; nextSession.IsEnabled = true; deleteBlurred.IsEnabled = recommendPhotos.IsEnabled = sessionPhotos.Count > 0; RefreshPrintButton(); }
    }
    private void WriteAnalysisLog(string operation, IEnumerable<AssessedPhoto> photos, IEnumerable<string> results, IEnumerable<string> errors)
    {
        if (verify) return;
        try {
            var folder = Path.Combine(Path.GetDirectoryName(Settings.FilePath)!, "analysis"); Directory.CreateDirectory(folder);
            var report = new { operation, time = DateTime.Now, results, errors, scores = photos.Select(p => new { p.Photo.Path, p.Quality.Sharpness, p.Quality.CenterSharpness, p.Quality.CenterContrast, p.Quality.Contrast, p.Quality.Exposure }) };
            File.WriteAllText(Path.Combine(folder, DateTime.Now.ToString("yyyyMMdd_HHmmss_fff") + ".json"), System.Text.Json.JsonSerializer.Serialize(report, new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
        } catch { }
    }
    private Window CreatePhotoPopup(ImageSource source)
    {
        var dialog = new Window { Title = "촬영 사진 크게 보기", Owner = this, WindowStyle = WindowStyle.None, ResizeMode = ResizeMode.NoResize, Width = ActualWidth * 0.7, Height = ActualHeight * 0.7, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brushes.Black };
        var surface = new Grid();
        surface.Children.Add(new Image { Source = source, Stretch = Stretch.Uniform, Margin = new Thickness(12) });
        var close = Button("✕", dialog.Close); close.FontSize = 24; close.Width = 58; close.Height = 52;
        close.HorizontalAlignment = HorizontalAlignment.Right; close.VerticalAlignment = VerticalAlignment.Top;
        surface.Children.Add(close); dialog.Content = surface;
        dialog.PreviewKeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Escape) dialog.Close(); };
        return dialog;
    }
    private void ShowPhotoPopup(BitmapSource thumbnail, string path)
    {
        var dialog = CreatePhotoPopup(thumbnail);
        // Load only this chosen original. The popup remains fixed when new shots arrive.
        dialog.Loaded += async (_, _) => {
            if (Path.GetExtension(path).ToLowerInvariant() is not (".jpg" or ".jpeg" or ".png" or ".bmp")) return;
            try {
                var full = await System.Threading.Tasks.Task.Run(() => Decode(File.ReadAllBytes(path)));
                if (dialog.IsVisible) ((Image)((Grid)dialog.Content).Children[0]).Source = full;
            } catch (Exception ex) { status.Text = "원본 열기 실패 · " + ex.Message; }
        };
        dialog.ShowDialog();
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
        settings.Coat = coat;
        // Recommendations highlight options, never silently select a background.
        RenderCards();
    }
    private IEnumerable<Backdrop> SelectedBackgrounds => draft.Select(id => Backgrounds.All.First(x => x.Id == id));
    private void Toggle(string id)
    {
        draft.Remove(id);
        if (draft.Count == 2) draft.RemoveAt(0);
        draft.Add(id);
        RenderCards();
        if (draft.Count == 2) ApplyBackgrounds();
    }
    private void RenderCards()
    {
        cards.Children.Clear();
        cards.Columns = 6;
        foreach (var pair in coats) pair.Value.Background = Brush(pair.Key == settings.Coat ? "#426C60" : "#293646");
        foreach (var backdrop in Backgrounds.All)
        {
            bool selected = draft.Contains(backdrop.Id);
            var content = new StackPanel();
            content.Children.Add(new Image { Source = backdrop.Image, Height = 100, Stretch = Stretch.UniformToFill });
            content.Children.Add(Text((selected ? $"{draft.IndexOf(backdrop.Id) + 1}  ✓  " : "") + backdrop.Name + (Backgrounds.Recommend(settings.Coat).Take(2).Contains(backdrop) ? " · 추천" : ""), 15));
            var card = new Button { Content = content, ToolTip = backdrop.Description, HorizontalContentAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(8), Margin = new Thickness(0, 0, 10, 8), Background = Brush("#1A222E"), BorderBrush = Brush(selected ? "#BCE9D5" : "#2B3747"), BorderThickness = new Thickness(selected ? 2 : 1), Cursor = System.Windows.Input.Cursors.Hand };
            card.Click += (_, _) => Toggle(backdrop.Id); cards.Children.Add(card);
        }
        selectedPreviews.Children.Clear();
        for (int i = 0; i < 2; i++) {
            var slot = new StackPanel { Width = 400, Margin = new Thickness(12, 0, 12, 0) };
            var bg = SelectedBackgrounds.ElementAtOrDefault(i);
            slot.Children.Add(new Image { Source = bg?.Image, Height = 145, Stretch = Stretch.Uniform });
            slot.Children.Add(Text(bg == null ? $"{i + 1}번째 배경을 선택하세요" : $"{i + 1}  {bg.Name}", 16));
            selectedPreviews.Children.Add(slot);
        }
        selection.Text = $"{draft.Count} / 2 선택  ·  " + string.Join(" + ", SelectedBackgrounds.Select(x => x.Name));
        apply.IsEnabled = draft.Count == 2; RefreshStartButton();
    }
    private void ApplyBackgrounds()
    {
        if (draft.Count != 2) return;
        session.Apply(SelectedBackgrounds, DateTime.UtcNow);
        settings.Selected = session.Active.Select(x => x.Id).ToArray(); Save(); UpdateClock();
        selection.Text = "적용 완료 · " + string.Join(" + ", session.Active.Select(x => x.Name));
    }
    private void UpdateClock()
    {
        var now = DateTime.UtcNow;
        if (!verify && !shuttingDown && now >= nextBackupRetry) { nextBackupRetry = now.AddMinutes(2); _ = ResumeBackupAsync(); }
        var backdrop = session.Active[session.Index(now)];
        backgroundPreview.Source = backdrop.Image; projector.Picture.Source = draft.Count == 2 || capturePage.Visibility == Visibility.Visible ? backdrop.Image : null;
        rotation.Text = $"{backdrop.Name}  ·  다음 배경까지 {session.Remaining(now)}초";
        string name = settings.DogName;
        int seconds = verify ? previewLease.RemainingSeconds : camera.RemainingSeconds;
        string remaining = $"{seconds / 60:00}:{seconds % 60:00}";
        centralName.Text = string.IsNullOrWhiteSpace(name) ? "이름을 입력해 주세요" : name; countdown.Text = remaining;
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
        orientation.Content = settings.PhotoPortrait ? "외부 화면 모두: 세로 ↻" : "외부 화면 모두: 가로 ↻";
        projector.SetPortrait(settings.PhotoPortrait);
        photoScreen.SetPortrait(settings.PhotoPortrait);
        photoPreview.SetOrientation(settings.PhotoPortrait, false);
    }
    private void RefreshStartButton()
    {
        if (start == null) return;
        bool active = CapturePhaseNow == CapturePhase.Active;
        dogInput.IsEnabled = cameraConnected && !exitRequested && !active && !captureStarting;
        start.IsEnabled = cameraConnected && !exitRequested && !shuttingDown && !endingSession && !captureStarting && !active && CapturePhaseNow == CapturePhase.Locked && (verify || camera.CanStart) && !string.IsNullOrWhiteSpace(dogInput.Text) && draft.Count == 2;
        nextSession.Visibility = Visibility.Visible;
        nextSession.IsEnabled = !endingSession && !captureStarting && !processingPhotos && !printing;
        nextSession.Content = endingSession ? "촬영 파일 수신 마무리 중…" : "종료 · 다음 사용자";
        if (printPhotos != null) {
            deleteBlurred.IsEnabled = recommendPhotos.IsEnabled = !endingSession && !printing && !processingPhotos && sessionPhotos.Count > 0;
            RefreshPrintButton();
        }
        start.Content = captureStarting ? "카메라 촬영 허용 설정 중…" : active ? "촬영 중 · 20분 종료 시 촬영 종료" : (!verify && !camera.CanStart) ? "카메라 연결 안내를 확인해 주세요" : CapturePhaseNow == CapturePhase.Locked ? (draft.Count == 2 ? "촬영 시작" : "배경 2개를 선택해 주세요") : "카메라 연결 및 촬영 준비 확인 중";
    }
    private async System.Threading.Tasks.Task StartSessionAsync()
    {
        if (exitRequested || !cameraConnected || endingSession || captureStarting || CapturePhaseNow == CapturePhase.Active || string.IsNullOrWhiteSpace(dogInput.Text) || draft.Count != 2) return;
        captureStarting = true; RefreshStartButton();
        try
        {
            bool success;
            backupJob = backup?.Begin(settings.DogName, settings.PhotoFolder);
            if (verify) { previewLease.Start(); success = true; }
            else success = await camera.StartCaptureAsync();
            if (!success || shuttingDown) return;
            sessionPhotos.Clear(); photoGeneration++; recommendedView = false;
            thumbnails.Children.Clear(); shotCount = 0; galleryCount.Text = "촬영 사진 · 0장";
            displayedPhotoPath = null; RenderGallery(false); analysisStatus.Text = "촬영 후 불량 사진 삭제 또는 추천 사진 8장을 선택하세요.";
            ResetPhoto(); ApplyBackgrounds(); ShowPage(true);
            status.Text = "촬영 허용 완료 · PC 저장(Host) · 20분 후 자동 잠금";
        }
        catch (Exception ex) { status.Text = "촬영 시작 실패 · " + ex.Message; }
        finally { captureStarting = false; RefreshStartButton(); UpdateClock(); }
    }
    private async System.Threading.Tasks.Task ResumeBackupAsync()
    {
        if (shuttingDown || driveConnecting || !backupTask.IsCompleted) return;
        try { if (backup != null) { backupTask = System.Threading.Tasks.Task.Run(() => backup.RunPendingAsync(backupCancellation.Token)); await backupTask; } }
        catch (OperationCanceledException) when (backupCancellation.IsCancellationRequested) { }
        catch (Exception ex) { backupStatus.Text = "백업 기록 저장 실패 · " + ex.Message; }
    }
    private async System.Threading.Tasks.Task EndSessionAsync(bool showName = true)
    {
        if (endingSession || captureStarting || printing || processingPhotos) return;
        endingSession = true; RefreshStartButton();
        try {
            bool locked = verify;
            if (verify) previewLease.Lock();
            else {
                var deadline = DateTime.UtcNow.AddSeconds(30);
                do {
                    locked = await camera.LockCaptureAsync();
                    if (!locked) await System.Threading.Tasks.Task.Delay(250);
                } while (!locked && DateTime.UtcNow < deadline);
            }
            if (!locked) { status.Text = "촬영 파일 수신 또는 카메라 잠금 확인 중입니다. 잠시 후 종료를 다시 눌러 주세요."; return; }
            await decodeQueue;
            if (backupJob != null) backup?.Seal(backupJob);
            backupJob = null;
            _ = ResumeBackupAsync();
            sessionPhotos.Clear(); thumbnails.Children.Clear(); shotCount = 0; photoGeneration++;
            displayedPhotoPath = null; recommendedView = false; RenderGallery(false);
            draft.Clear(); dogInput.Text = ""; RenderCards(); ShowPage(false); ResetPhoto();
            status.Text = "새 사용자 준비 · 이전 촬영 CR3는 백그라운드에서 백업합니다.";
        } catch (Exception ex) { status.Text = "촬영 종료 실패 · 원본 보존 · " + ex.Message; return; }
        finally { endingSession = false; RefreshStartButton(); }
        if (showName) ShowNameDialog();
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
        try { var photo = Decode(File.ReadAllBytes(picker.FileName)); ShowPhoto(photo, "테스트 · " + Path.GetFileName(picker.FileName)); AddThumbnail(photo, picker.FileName); }
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
        if (printing) { status.Text = "인쇄 전송이 끝난 뒤 종료해 주세요."; return; }
        if (processingPhotos) { status.Text = "사진 처리가 끝난 뒤 종료해 주세요."; return; }
        if (endingSession) return;
        shuttingDown = true; IsEnabled = false; clock.Stop(); Save();
        connectionCancellation.Cancel();
        try {
            status.Text = "카메라 잠금 복원 및 다운로드 마무리 중…";
            if (!verify) {
                var deadline = DateTime.UtcNow.AddSeconds(15);
                while (!await camera.LockCaptureAsync().WaitAsync(TimeSpan.FromSeconds(15))) {
                    if (DateTime.UtcNow >= deadline) throw new TimeoutException("카메라 잠금 대기 시간 초과");
                    await System.Threading.Tasks.Task.Delay(250);
                }
            }
            // SDK disposal drains outstanding cardless transfers before sealing the job.
            if (!verify) await camera.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(30));
            if (backupJob != null) { backup?.Seal(backupJob); backupJob = null; }
            status.Text = "백업 일시 중지 · 미완료 CR3는 다음 실행 시 재개합니다…";
            backupCancellation.Cancel();
            try { await backupTask.WaitAsync(TimeSpan.FromSeconds(8)); } catch (OperationCanceledException) { }
            SystemEvents.DisplaySettingsChanged -= OnDisplayChanged;
            Awake.Disable();
            closed = true; projector.AllowClose = photoScreen.AllowClose = true;
            projector.Close(); photoScreen.Close(); Application.Current.Shutdown();
        } catch (Exception ex) {
            shuttingDown = false; IsEnabled = true; clock.Start(); RefreshStartButton();
            shutdownTimedOut = ex is TimeoutException;
            status.Text = shutdownTimedOut ? "카메라 SDK 응답 시간 초과 · ESC를 다시 누르면 강제 종료됩니다(수신 중 사진 유실 가능)" : "종료 처리 실패 · 원본 보존 · " + ex.Message;
            Directory.CreateDirectory(Path.GetDirectoryName(Settings.FilePath)!);
            File.AppendAllText(Path.Combine(Path.GetDirectoryName(Settings.FilePath)!, "shutdown.log"),
                DateTimeOffset.Now.ToString("O") + " " + ex + Environment.NewLine);
        }
    }
    internal async System.Threading.Tasks.Task VerifyPhotoWorkflowAsync()
    {
        if (!verify) throw new InvalidOperationException("Verification mode only");
        string previousRoot = settings.PhotoFolder;
        settings.PhotoFolder = Path.Combine(AppContext.BaseDirectory, "verification", "analysis-fixtures");
        Directory.CreateDirectory(settings.PhotoFolder);
        var data = new byte[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) data[y * 256 + x] = (byte)(120 + 55 * Math.Sin(x / 28.0) * Math.Cos(y / 28.0));
        var blur = BitmapSource.Create(256, 256, 96, 96, PixelFormats.Gray8, null, data, 256); blur.Freeze();
        string SaveFixture(string name, BitmapSource image) {
            string path = Path.Combine(settings.PhotoFolder, name + ".png"); var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(image));
            using var file = File.Create(path); png.Save(file); return path;
        }
        var badPath = SaveFixture("blur", blur);
        var importedPath = SaveFixture("imported", blur);
        var goodPath = SaveFixture("sharp", Backgrounds.All[1].Image);
        sessionPhotos.Clear(); thumbnails.Children.Clear(); shotCount = 0; ShowPage(true);
        AddThumbnail(blur, badPath, true); AddThumbnail(blur, importedPath, false); AddThumbnail(Backgrounds.All[1].Image, goodPath, true);
        verificationRecycle = _ => throw new IOException("simulated recycle failure");
        await AnalyzePhotosAsync(true);
        if (sessionPhotos.Count != 3) throw new Exception("Failed recycle must retain gallery entry");
        int recycled = 0; verificationRecycle = _ => recycled++;
        await AnalyzePhotosAsync(true);
        if (recycled != 1 || sessionPhotos.Count != 2 || sessionPhotos.Any(p => p.Path == badPath) || !sessionPhotos.Any(p => p.Path == importedPath)) throw new Exception("Deletion workflow/import protection failed");
        await AnalyzePhotosAsync(false);
        if (!recommendedView || thumbnails.Children.Count != 1) throw new Exception("Recommendation workflow failed");
        AddThumbnail(Backgrounds.All[2].Image, goodPath, false);
        if (recommendedView || thumbnails.Children.Count != 3) throw new Exception("New capture must leave recommendation view");
        // Exercise the actual shell recycle operation on our disposable fixture, off the UI thread.
        verificationRecycle = null;
        var nativeFixture = new SessionPhoto(9999, badPath, blur, true);
        await System.Threading.Tasks.Task.Run(() => PhotoSelection.Recycle(nativeFixture, settings.PhotoFolder));
        if (File.Exists(badPath)) throw new Exception("Native recycle did not remove fixture");
        settings.PhotoFolder = previousRoot;
        sessionPhotos.Clear(); thumbnails.Children.Clear(); shotCount = 0; RenderGallery(false); ShowPage(false);
    }
    internal async System.Threading.Tasks.Task VerifyEndSessionAsync()
    {
        dogInput.Text = "이전 사용자"; Toggle("snow"); Toggle("forest");
        await StartSessionAsync();
        if (!nextSession.IsEnabled || nextSession.Visibility != Visibility.Visible) throw new Exception("End button unavailable during capture");
        await EndSessionAsync(false);
        if (previewLease.Phase != CapturePhase.Locked || dogInput.Text != "" || draft.Count != 0 || setupPage.Visibility != Visibility.Visible || capturePage.Visibility != Visibility.Collapsed || sessionPhotos.Count != 0 || photoScreen.Picture.Source != null) throw new Exception("End session did not reset user/camera/gallery");
        dogInput.Text = "새 사용자"; Toggle("ocean"); Toggle("forest");
        await StartSessionAsync();
        if (!previewLease.Active) throw new Exception("New session cannot start after ending previous session");
        await EndSessionAsync(false);
    }
    internal async System.Threading.Tasks.Task VerifySm005Async()
    {
        cameraConnected = false;
        ShowNameDialog();
        if (nameDialog != null || connectionDialog?.IsVisible != true || CanEnterName) throw new Exception("Disconnected camera must show connection prompt only");
        UpdateCameraConnection(true, "검증 카메라 연결");
        bool entered = false;
        _ = Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => {
            entered = nameDialog?.IsVisible == true && connectionDialog == null;
            nameDialog?.Close();
        }));
        await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
        if (!entered || !CanEnterName) throw new Exception("Camera connection must open name entry");
        settings.PhotoPortrait = true; UpdateOrientation();
        if (!projector.Portrait || !photoScreen.Portrait || LayoutTransform is RotateTransform) throw new Exception("External screens must rotate together, excluding main window");
        settings.PhotoPortrait = false; UpdateOrientation();
        if (projector.Portrait || photoScreen.Portrait) throw new Exception("Both outputs must restore landscape");
        var popup = CreatePhotoPopup(Backgrounds.All[0].Image); popup.Show();
        var key = new System.Windows.Input.KeyEventArgs(System.Windows.Input.Keyboard.PrimaryDevice, PresentationSource.FromVisual(popup), Environment.TickCount, System.Windows.Input.Key.Escape) { RoutedEvent = System.Windows.Input.Keyboard.PreviewKeyDownEvent, Source = popup };
        System.Windows.Input.InputManager.Current.ProcessInput(key);
        if (!exitRequested || !key.Handled) throw new Exception("ESC in child window must request app exit");
        popup.Close(); exitRequested = false;
    }
    private void SaveVerificationView(string filename)
    {
        UpdateLayout();
        var bitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32); bitmap.Render(this);
        var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(bitmap));
        var folder = Path.Combine(AppContext.BaseDirectory, "verification"); Directory.CreateDirectory(folder);
        using var file = File.Create(Path.Combine(folder, filename)); png.Save(file);
    }
    internal void VerifyInteractions()
    {
        if (draft.Count != 0 || setupPage.Visibility != Visibility.Visible || capturePage.Visibility != Visibility.Collapsed || start.IsEnabled) throw new Exception("Initial setup must have no selection or timer");
        if (cards.Children.Count != 12 || cards.Columns != 6) throw new Exception("12 background grid failed");
        if (AcceptAdminPassword("") || AcceptAdminPassword("3326") || !AcceptAdminPassword("3327")) throw new Exception("Admin password failed");
        SelectCoat("흰색"); if (draft.Count != 0) throw new Exception("Recommendation must not select");
        Toggle("snow"); Toggle("bamboo"); Toggle("forest");
        if (!draft.SequenceEqual(new[] { "bamboo", "forest" }) || !session.Active.Select(x => x.Id).SequenceEqual(draft)) throw new Exception("FIFO selection/order failed");
        Toggle("forest"); if (draft.Count != 2) throw new Exception("Repeated selection duplicated");
        dogInput.Text = "테스트 강아지";
        SaveVerificationView("setup.png");
        StartSessionAsync().GetAwaiter().GetResult();
        if (!previewLease.Active || start.IsEnabled || dogInput.IsEnabled || setupPage.Visibility != Visibility.Collapsed || capturePage.Visibility != Visibility.Visible) throw new Exception("Capture navigation/gating failed");
        ShowPhoto(Backgrounds.All[0].Image, "검증 사진");
        if (photoScreen.Picture.Source == null) throw new Exception("Photo display failed");
        settings.PhotoPortrait = true; UpdateOrientation(); if (!photoPreview.Portrait) throw new Exception("Portrait failed");
        settings.PhotoPortrait = false; UpdateOrientation();
        for (int i = 0; i < 105; i++) AddThumbnail(Backgrounds.All[i % 12].Image, "test");
        if (thumbnails.Children.Count != 100 || shotCount != 105 || thumbnails.Columns != 5) throw new Exception("5-column bounded gallery failed");
        UpdateLayout(); gallery.ScrollToEnd(); UpdateLayout();
        if (gallery.ScrollableHeight <= 0 || Math.Abs(gallery.VerticalOffset - gallery.ScrollableHeight) > 1) throw new Exception("Gallery auto scroll failed");
        SaveVerificationView("capture.png");
        RenderGallery(true, sessionPhotos.Take(8).ToArray());
        if (thumbnails.Children.Count != 8 || !recommendedView || !printPhotos.IsEnabled) throw new Exception("Recommendation view failed");
        SaveVerificationView("recommendations.png"); RenderGallery(false);
        if (thumbnails.Children.Count != 100 || printPhotos.IsEnabled) throw new Exception("Return to full gallery failed");
        var popup = CreatePhotoPopup(Backgrounds.All[0].Image); popup.Show();
        if (Math.Abs(popup.Width / ActualWidth - 0.7) > .001 || Math.Abs(popup.Height / ActualHeight - 0.7) > .001) throw new Exception("Popup size failed");
        ((Button)((Grid)popup.Content).Children[1]).RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
        if (popup.IsVisible) throw new Exception("Popup X failed");
        previewLease.Lock(); previewLease = new CaptureLease(() => { }, () => { }); previewLease.Lock();
        sessionPhotos.Clear();
        thumbnails.Children.Clear(); shotCount = 0; galleryCount.Text = "촬영 사진 · 0장";
        ShowPage(false); dogInput.Text = ""; draft.Clear(); RenderCards(); ResetPhoto(); UpdateClock(); UpdateLayout();
    }
}



