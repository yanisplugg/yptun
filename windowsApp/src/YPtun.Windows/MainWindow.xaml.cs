using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using WinRT.Interop;
using YPtun.WinUI.Services;
using YPtun.WinUI.ViewModels;
using YPtun.WinUI.Views;

namespace YPtun.WinUI;

public sealed partial class MainWindow : Window
{
    private readonly AppState _state;

    public MainWindow(AppState state)
    {
        _state = state;
        InitializeComponent();
        ConfigureWindow();

        RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
        Navigate(typeof(ConnectionPage));
        Closed += OnClosed;
    }

    private void ConfigureWindow()
    {
        var windowHandle = WindowNative.GetWindowHandle(this);
        var windowId = Win32Interop.GetWindowIdFromWindow(windowHandle);
        var appWindow = AppWindow.GetFromWindowId(windowId);
        appWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "WindowsIcon.ico"));
        appWindow.Resize(new Windows.Graphics.SizeInt32(1120, 760));

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(null);
        appWindow.TitleBar.ButtonBackgroundColor = Colors.Transparent;
        appWindow.TitleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
    }

    private void OnNavigationSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            Navigate(typeof(SettingsPage));
            return;
        }

        if (args.SelectedItemContainer?.Tag is not string tag)
        {
            return;
        }

        Navigate(tag switch
        {
            "profiles" => typeof(ProfilesPage),
            "logs" => typeof(LogsPage),
            _ => typeof(ConnectionPage),
        });
    }

    private void Navigate(Type pageType)
    {
        if (ContentFrame.CurrentSourcePageType != pageType)
        {
            ContentFrame.Navigate(pageType, _state);
        }
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        await _state.DisposeAsync();
    }
}
