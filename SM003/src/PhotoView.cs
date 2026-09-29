using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace ThreeScreen;

// Shared by the control preview and the actual photo display.
public sealed class PhotoView : Viewbox
{
    public Image Picture { get; } = new() { Stretch = Stretch.Uniform };
    private readonly Grid scene = new() { Background = Brushes.Black };
    private readonly TextBlock name = new() { Foreground = Brushes.White, FontSize = 36, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock remaining = new() { Foreground = Brushes.White, FontSize = 40, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock welcome = new() { Text = "샤인멍", Foreground = Brushes.White, FontSize = 76, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    public bool Portrait { get; private set; }
    public PhotoView()
    {
        Stretch = Stretch.Uniform;
        scene.Children.Add(Picture);
        scene.Children.Add(welcome);
        var row = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(remaining, Dock.Right); row.Children.Add(remaining); row.Children.Add(name);
        scene.Children.Add(new Border { Background = new SolidColorBrush(Color.FromArgb(190, 12, 20, 28)), Padding = new Thickness(32, 16, 32, 16), VerticalAlignment = VerticalAlignment.Top, Child = row });
        Child = scene;
        SetOrientation(false, false);
    }
    public void SetOrientation(bool portrait, bool rotateForMonitor)
    {
        Portrait = portrait;
        scene.Width = portrait ? 900 : 1600;
        scene.Height = portrait ? 1600 : 900;
        scene.LayoutTransform = new RotateTransform(portrait && rotateForMonitor ? 90 : 0);
    }
    public void UpdateInfo(string dogName, string time)
    {
        name.Text = string.IsNullOrWhiteSpace(dogName) ? "샤인멍" : dogName;
        remaining.Text = "남은 시간  " + time;
    }
    public void SetPhoto(ImageSource? image)
    {
        Picture.Source = image;
        welcome.Visibility = image == null ? Visibility.Visible : Visibility.Collapsed;
    }
}
