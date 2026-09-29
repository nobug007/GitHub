using System;
using System.ComponentModel;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Screen = System.Windows.Forms.Screen;

namespace ThreeScreen;

public sealed class OutputWindow : Window
{
    public Image Picture { get; }
    private readonly PhotoView? photoView;
    private bool portrait;
    public bool AllowClose;
    public OutputWindow(bool projector)
    {
        Title = projector ? "샤인멍 · 배경 프로젝터" : "샤인멍 · 사진 디스플레이";
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        Background = new SolidColorBrush(Color.FromRgb(18, 24, 33));
        ShowInTaskbar = true;
        ShowActivated = false;
        Topmost = true;
        var grid = new Grid();
        if (projector)
        {
            Picture = new Image { Stretch = Stretch.Fill };
            grid.Children.Add(Picture);
        }
        else
        {
            photoView = new PhotoView();
            Picture = photoView.Picture;
            grid.Children.Add(photoView);
        }
        Content = grid;
        SizeChanged += (_, _) => ApplyPhotoOrientation();
        Closing += (_, e) => { if (!AllowClose) e.Cancel = true; };
    }
    public void UpdateWelcome(string name, string remaining) => photoView?.UpdateInfo(name, remaining);
    public void SetPhoto(ImageSource? source) => photoView?.SetPhoto(source);
    public void SetPortrait(bool portrait) { this.portrait = portrait; ApplyPhotoOrientation(); }
    private void ApplyPhotoOrientation() => photoView?.SetOrientation(portrait, ActualWidth >= ActualHeight);
}

public static class Displays
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hwnd, out NativeRect rect);
    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect { public int Left, Top, Right, Bottom; }
    public static void Place(Window window, Screen screen)
    {
        window.WindowState = WindowState.Normal;
        var b = screen.Bounds;
        if (!window.IsVisible) window.Show();
        var hwnd = new WindowInteropHelper(window).EnsureHandle();
        void Position()
        {
            if (!window.IsVisible) return;
            var after = window is OutputWindow ? new IntPtr(-1) : IntPtr.Zero;
            uint flags = 0x0010 | 0x0040; // no activation, show window; allow z-order change
            if (!SetWindowPos(hwnd, after, b.X, b.Y, b.Width, b.Height, flags))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "출력 화면 배치 실패");
            GetWindowRect(hwnd, out var actual);
            var folder = System.IO.Path.GetDirectoryName(Settings.FilePath)!;
            System.IO.Directory.CreateDirectory(folder);
            System.IO.File.AppendAllText(System.IO.Path.Combine(folder, "displays.log"),
                $"{DateTime.Now:O} {window.Title} => {screen.DeviceName} expected={b.X},{b.Y},{b.Width},{b.Height} actual={actual.Left},{actual.Top},{actual.Right-actual.Left},{actual.Bottom-actual.Top} visible={window.IsVisible} topmost={window.Topmost}\n");
        }
        Position();
        // Reapply after WPF's initial layout / per-monitor DPI transition.
        window.Dispatcher.BeginInvoke(DispatcherPriority.Loaded, new Action(Position));
    }
}
