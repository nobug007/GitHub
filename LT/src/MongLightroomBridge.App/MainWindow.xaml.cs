using System.Windows;
using MongLightroomBridge.App.ViewModels;

namespace MongLightroomBridge.App;

public partial class MainWindow : Window
{
    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
    }
}
