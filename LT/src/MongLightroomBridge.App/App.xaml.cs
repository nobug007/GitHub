using System.IO;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Serilog;
using MongLightroomBridge.App.ViewModels;
using MongLightroomBridge.Core.Interfaces;
using MongLightroomBridge.Infrastructure.DryRun;
using MongLightroomBridge.Infrastructure.Jobs;
using MongLightroomBridge.Infrastructure.Quality;
using MongLightroomBridge.Infrastructure.Settings;

namespace MongLightroomBridge.App;

/// <summary>Composition root: Serilog + DI, same shape as ShineMung/MongCafe's App.xaml.cs.</summary>
public partial class App : System.Windows.Application
{
    private IHost? _host;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnAppDomainUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;

        ConfigureSerilog();
        Log.Information("=== MongLightroomBridge starting up ===");

        try
        {
            _host = Host.CreateDefaultBuilder()
                .UseSerilog()
                .ConfigureServices(ConfigureServices)
                .Build();

            var mainWindow = _host.Services.GetRequiredService<MainWindow>();
            mainWindow.Show();
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "MongLightroomBridge failed to start");
            MessageBox.Show($"프로그램을 시작할 수 없습니다:\n{ex.Message}", "MongLightroomBridge", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(-1);
        }
    }

    private static void ConfigureSerilog()
    {
        var logDir = Path.Combine(AppContext.BaseDirectory, "logs");
        Directory.CreateDirectory(logDir);

        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Debug()
            .Enrich.FromLogContext()
            .WriteTo.Debug()
            .WriteTo.File(
                Path.Combine(logDir, "monglightroombridge-.log"),
                rollingInterval: RollingInterval.Day,
                retainedFileCountLimit: 30,
                outputTemplate: "{Timestamp:yyyy-MM-dd HH:mm:ss.fff zzz} [{Level:u3}] {SourceContext}: {Message:lj}{NewLine}{Exception}")
            .CreateLogger();
    }

    private static void ConfigureServices(HostBuilderContext context, IServiceCollection services)
    {
        services.AddSingleton<BridgeSettingsStore>();
        services.AddSingleton(sp => sp.GetRequiredService<BridgeSettingsStore>().LoadOrCreateDefault());

        services.AddSingleton<IJobFileService, JobFileService>();
        services.AddSingleton<IDryRunProcessor, DryRunProcessor>();
        services.AddSingleton<IPhotoQualityFilter, OpenCvPhotoQualityFilter>();

        services.AddSingleton<MainViewModel>();
        services.AddSingleton<MainWindow>();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Log.Information("=== MongLightroomBridge shutting down ===");
        if (_host?.Services.GetService<MainViewModel>() is IDisposable disposableVm)
        {
            disposableVm.Dispose();
        }

        _host?.Dispose();
        Log.CloseAndFlush();
        base.OnExit(e);
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log.Error(e.Exception, "Unhandled UI thread exception");
        MessageBox.Show(
            $"예상치 못한 오류가 발생했습니다:\n{e.Exception.Message}\n\n프로그램은 계속 실행됩니다.",
            "MongLightroomBridge",
            MessageBoxButton.OK,
            MessageBoxImage.Warning);
        e.Handled = true;
    }

    private static void OnAppDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception ex)
        {
            Log.Fatal(ex, "Unhandled AppDomain exception (IsTerminating={IsTerminating})", e.IsTerminating);
        }
    }

    private static void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        Log.Error(e.Exception, "Unobserved task exception");
        e.SetObserved();
    }
}
