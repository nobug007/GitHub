using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;
using MongLightroomBridge.Core.Interfaces;
using MongLightroomBridge.Core.Models;
using MongLightroomBridge.Core.Profiles;
using MongLightroomBridge.Infrastructure.Settings;

namespace MongLightroomBridge.App.ViewModels;

/// <summary>
/// Backs MainWindow. Owns the current job form (folders/profile/sliders), submits jobs either to the
/// real Lightroom plugin (file IPC via IJobFileService) or to the local IDryRunProcessor, and polls for
/// progress. All EDSDK-style "never touch the original" principles carry over here as "never touch the
/// input folder" - RunAsync only ever reads from InputFolder and writes to OutputFolder/jobs folder.
/// </summary>
public sealed partial class MainViewModel : ObservableObject, IDisposable
{
    private readonly IJobFileService _jobFileService;
    private readonly IDryRunProcessor _dryRunProcessor;
    private readonly IPhotoQualityFilter _qualityFilter;
    private readonly BridgeSettings _settings;
    private readonly BridgeSettingsStore _settingsStore;
    private readonly ILogger<MainViewModel> _logger;
    private readonly DispatcherTimer _heartbeatTimer;

    private AutoCorrectionProfileName _lastAppliedProfile;
    private bool _convertToGrayscale;
    private CancellationTokenSource? _runCts;

    [ObservableProperty] private string inputFolder = string.Empty;
    [ObservableProperty] private string outputFolder = string.Empty;

    [ObservableProperty] private bool isLightroomConnected;
    [ObservableProperty] private string connectionStatusText = "Lightroom 연결 확인 중...";

    [ObservableProperty] private AutoCorrectionProfileName selectedProfile = AutoCorrectionProfileName.NaturalPortrait;

    [ObservableProperty] private double exposure;
    [ObservableProperty] private double contrast;
    [ObservableProperty] private double highlights;
    [ObservableProperty] private double shadows;
    [ObservableProperty] private double whites;
    [ObservableProperty] private double blacks;
    [ObservableProperty] private double temperature;
    [ObservableProperty] private double tint;
    [ObservableProperty] private double vibrance;
    [ObservableProperty] private double saturation;

    [ObservableProperty] private string? selectedCropAspect;

    [ObservableProperty] private ExportFormat selectedFormat = ExportFormat.Jpg;
    [ObservableProperty] private int quality = 90;

    [ObservableProperty] private bool isDryRun = true;

    /// <summary>Windows-app-only pre-filter option - see IPhotoQualityFilter. Off by default so
    /// behavior is unchanged unless the user opts in.</summary>
    [ObservableProperty] private bool excludeBlurryPhotos;

    /// <summary>Windows-app-only pre-filter option - see EyeOpennessResult's doc comment for why
    /// this is "couldn't confirm an eye is open", not a closed-eye detector.</summary>
    [ObservableProperty] private bool excludeUncertainEyePhotos;

    [ObservableProperty] private bool isRunning;
    [ObservableProperty] private int progressPercent;
    [ObservableProperty] private string statusMessage = "입력/출력 폴더를 선택하고 자동 보정을 실행하세요.";
    [ObservableProperty] private string logText = string.Empty;

    public ObservableCollection<AutoCorrectionProfileName> Profiles { get; } =
        new(BuiltInProfiles.All);

    public ObservableCollection<string?> CropAspectOptions { get; } =
        new(new string?[] { null, "3:2", "4:5", "1:1", "16:9" });

    public ObservableCollection<ExportFormat> Formats { get; } = new(Enum.GetValues<ExportFormat>());

    public ObservableCollection<JobFileResult> Results { get; } = new();

    public MainViewModel(
        IJobFileService jobFileService,
        IDryRunProcessor dryRunProcessor,
        IPhotoQualityFilter qualityFilter,
        BridgeSettings settings,
        BridgeSettingsStore settingsStore,
        ILogger<MainViewModel> logger)
    {
        _jobFileService = jobFileService;
        _dryRunProcessor = dryRunProcessor;
        _qualityFilter = qualityFilter;
        _settings = settings;
        _settingsStore = settingsStore;
        _logger = logger;

        ApplyProfileDefaults(SelectedProfile);

        _heartbeatTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(3),
        };
        _heartbeatTimer.Tick += async (_, _) => await CheckHeartbeatAsync().ConfigureAwait(true);
        _heartbeatTimer.Start();
        _ = CheckHeartbeatAsync();
    }

    partial void OnSelectedProfileChanged(AutoCorrectionProfileName value) => ApplyProfileDefaults(value);

    private void ApplyProfileDefaults(AutoCorrectionProfileName profile)
    {
        var defaults = BuiltInProfiles.GetDefaults(profile);
        Exposure = defaults.Exposure;
        Contrast = defaults.Contrast;
        Highlights = defaults.Highlights;
        Shadows = defaults.Shadows;
        Whites = defaults.Whites;
        Blacks = defaults.Blacks;
        Temperature = defaults.Temperature;
        Tint = defaults.Tint;
        Vibrance = defaults.Vibrance;
        Saturation = defaults.Saturation;
        SelectedCropAspect = defaults.CropAspect;
        _convertToGrayscale = defaults.ConvertToGrayscale;
        _lastAppliedProfile = profile;
    }

    [RelayCommand]
    private void BrowseInputFolder()
    {
        var dialog = new OpenFolderDialog { Title = "입력 폴더 선택" };
        if (dialog.ShowDialog() == true)
        {
            InputFolder = dialog.FolderName;
        }
    }

    [RelayCommand]
    private void BrowseOutputFolder()
    {
        var dialog = new OpenFolderDialog { Title = "출력 폴더 선택" };
        if (dialog.ShowDialog() == true)
        {
            OutputFolder = dialog.FolderName;
        }
    }

    [RelayCommand]
    private void OpenJobsFolder()
    {
        Directory.CreateDirectory(_jobFileService.JobsFolder);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_jobFileService.JobsFolder}\"") { UseShellExecute = true });
    }

    private bool CanRun() => !IsRunning;

    [RelayCommand(CanExecute = nameof(CanRun))]
    private async Task RunAsync()
    {
        if (string.IsNullOrWhiteSpace(OutputFolder))
        {
            StatusMessage = "출력 폴더를 먼저 선택해주세요.";
            return;
        }

        if (!IsDryRun && string.IsNullOrWhiteSpace(InputFolder))
        {
            StatusMessage = "입력 폴더를 먼저 선택해주세요.";
            return;
        }

        IsRunning = true;
        RunCommand.NotifyCanExecuteChanged();
        Results.Clear();
        ProgressPercent = 0;
        LogText = string.Empty;
        _runCts?.Dispose();
        _runCts = new CancellationTokenSource();

        JobRequest? request = null;

        try
        {
            var excludedFileNames = await ComputeExcludedFileNamesAsync(_runCts.Token).ConfigureAwait(true);
            request = BuildJobRequest(excludedFileNames);

            if (IsDryRun)
            {
                StatusMessage = "로컬 드라이런을 실행 중...";
                AppendLog($"[dry-run] job {request.JobId} 시작 (Lightroom 미사용)");
                var progress = new Progress<JobStatus>(ApplyStatus);
                var status = await _dryRunProcessor.RunAsync(request, progress, _runCts.Token).ConfigureAwait(true);
                ApplyStatus(status);
            }
            else
            {
                StatusMessage = $"작업 제출됨: {request.JobId}. Lightroom 플러그인이 처리하기를 기다리는 중...";
                await _jobFileService.SubmitJobAsync(request, _runCts.Token).ConfigureAwait(true);
                AppendLog($"job 파일 작성됨: pending-{request.JobId}.json");
                await PollStatusUntilDoneAsync(request.JobId, _runCts.Token).ConfigureAwait(true);
            }
        }
        catch (OperationCanceledException)
        {
            StatusMessage = "취소됨.";
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Run failed for job {JobId}", request?.JobId);
            StatusMessage = $"실행 중 오류: {ex.Message}";
            AppendLog($"[error] {ex}");
        }
        finally
        {
            IsRunning = false;
            RunCommand.NotifyCanExecuteChanged();
        }
    }

    /// <summary>
    /// Runs IPhotoQualityFilter over InputFolder off the UI thread when either exclude option is
    /// checked, logs a line per excluded file so the user can see why, and returns the file names
    /// to carry into JobRequest.ExcludedFileNames. Returns an empty list (never null) when both
    /// options are off, InputFolder is blank, or InputFolder doesn't exist yet - so this is always
    /// safe to call unconditionally from RunAsync.
    /// </summary>
    private async Task<IReadOnlyList<string>> ComputeExcludedFileNamesAsync(CancellationToken cancellationToken)
    {
        if (!ExcludeBlurryPhotos && !ExcludeUncertainEyePhotos)
        {
            return Array.Empty<string>();
        }

        if (string.IsNullOrWhiteSpace(InputFolder) || !Directory.Exists(InputFolder))
        {
            return Array.Empty<string>();
        }

        StatusMessage = "사진 품질 확인 중 (흐림/눈 감김 여부)...";

        var options = new PhotoQualityFilterOptions
        {
            ExcludeBlurry = ExcludeBlurryPhotos,
            ExcludeUncertainEyes = ExcludeUncertainEyePhotos,
        };

        var assessments = await Task.Run(
            () => _qualityFilter.Assess(InputFolder, options),
            cancellationToken).ConfigureAwait(true);

        var excluded = assessments.Where(a => a.Excluded).ToList();
        foreach (var a in excluded)
        {
            AppendLog($"[quality-filter] {a.FileName} 제외됨: {a.ExclusionReason}");
        }

        if (excluded.Count > 0)
        {
            AppendLog($"[quality-filter] 총 {excluded.Count}장 제외 (전체 {assessments.Count}장 중)");
        }

        return excluded.Select(a => a.FileName).ToList();
    }

    private async Task PollStatusUntilDoneAsync(string jobId, CancellationToken cancellationToken)
    {
        var deadline = DateTimeOffset.UtcNow.AddSeconds(_settings.PluginResponseTimeoutSeconds);
        JobStatus? lastStatus = null;

        while (!cancellationToken.IsCancellationRequested)
        {
            var status = await _jobFileService.TryReadStatusAsync(jobId, cancellationToken).ConfigureAwait(true);
            var log = await _jobFileService.ReadLogAsync(jobId, cancellationToken).ConfigureAwait(true);
            if (!string.IsNullOrEmpty(log) && log != LogText)
            {
                LogText = log;
            }

            if (status is not null)
            {
                ApplyStatus(status);
                lastStatus = status;
                if (status.Status is JobStatusState.Completed or JobStatusState.Failed)
                {
                    return;
                }

                // Once the plugin has responded at all, stop enforcing the "not responding" deadline -
                // a big batch can legitimately run far longer than PluginResponseTimeoutSeconds.
                deadline = DateTimeOffset.MaxValue;
            }
            else if (DateTimeOffset.UtcNow > deadline)
            {
                StatusMessage = "Lightroom 플러그인이 응답하지 않습니다. Lightroom이 실행 중이고 Plug-in Manager에서 플러그인이 활성화되어 있는지 확인해주세요.";
                return;
            }

            await Task.Delay(_settings.PollIntervalMilliseconds, cancellationToken).ConfigureAwait(true);
        }

        cancellationToken.ThrowIfCancellationRequested();
        _ = lastStatus;
    }

    private void ApplyStatus(JobStatus status)
    {
        ProgressPercent = status.TotalFiles > 0
            ? (int)Math.Round(100.0 * status.ProcessedFiles / status.TotalFiles)
            : 100;

        Results.Clear();
        foreach (var result in status.Results)
        {
            Results.Add(result);
        }

        StatusMessage = status.Status switch
        {
            JobStatusState.Running => $"처리 중... ({status.ProcessedFiles}/{status.TotalFiles})",
            JobStatusState.Completed => $"완료: {status.Results.Count(r => r.Success)}/{status.TotalFiles} 장 성공",
            JobStatusState.Failed => $"실패: {status.ErrorMessage}",
            _ => StatusMessage,
        };
    }

    private void AppendLog(string line)
    {
        LogText = string.IsNullOrEmpty(LogText)
            ? line
            : LogText + Environment.NewLine + line;
    }

    private async Task CheckHeartbeatAsync()
    {
        try
        {
            var heartbeat = await _jobFileService.TryReadHeartbeatAsync().ConfigureAwait(true);
            var isFresh = heartbeat is not null
                && DateTimeOffset.UtcNow - heartbeat.LastSeenUtc < TimeSpan.FromSeconds(15);

            IsLightroomConnected = isFresh;
            ConnectionStatusText = isFresh
                ? $"CONNECTED (Lightroom {heartbeat!.LightroomVersion ?? "?"}, plugin {heartbeat.PluginVersion ?? "?"})"
                : "DISCONNECTED (Lightroom에서 Plug-in Manager를 통해 플러그인을 활성화하고 실행해주세요)";
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Heartbeat check failed");
        }
    }

    private JobRequest BuildJobRequest(IReadOnlyList<string>? excludedFileNames = null) => new()
    {
        JobId = DateTime.UtcNow.ToString("yyyyMMdd-HHmmssfff"),
        InputFolder = InputFolder,
        OutputFolder = OutputFolder,
        Profile = SelectedProfile,
        Format = SelectedFormat,
        Quality = Quality,
        DryRun = IsDryRun,
        ExcludedFileNames = excludedFileNames is { Count: > 0 } ? excludedFileNames : null,
        Adjustments = new AdjustmentValues
        {
            Exposure = Exposure,
            Contrast = Contrast,
            Highlights = Highlights,
            Shadows = Shadows,
            Whites = Whites,
            Blacks = Blacks,
            Temperature = Temperature,
            Tint = Tint,
            Vibrance = Vibrance,
            Saturation = Saturation,
            CropAspect = SelectedCropAspect,
            ConvertToGrayscale = _convertToGrayscale,
        },
    };

    public void Dispose()
    {
        _heartbeatTimer.Stop();
        _runCts?.Cancel();
        _runCts?.Dispose();
    }
}
