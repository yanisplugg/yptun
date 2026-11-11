using System.Runtime.InteropServices;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using WinRT.Interop;
using Windows.System;
using YPtun.WinUI.Models;
using YPtun.WinUI.Services;
using YPtun.WinUI.ViewModels;
using YPtun.WinUI.Views;

namespace YPtun.WinUI;

public sealed partial class MainWindow : Window
{
    private const int VkShift = 0x10;
    private const int VkControl = 0x11;
    private const int VkAlt = 0x12;
    private const int VkLeftWindows = 0x5B;
    private const int VkRightWindows = 0x5C;

    private readonly AppState _state;
    private readonly AppWindow _appWindow;
    private readonly TrayService _tray;
    private readonly GlobalHotKeyService _hotKey;
    private readonly HotKeySettingsStore _hotKeySettings;
    private HotKeyBinding? _configuredHotKey;
    private string? _hotKeyError;
    private bool _exitRequested;
    private bool _backgroundNoticeShown;

    public string HotKeyLabel => _configuredHotKey is null
        ? "Не задана"
        : _hotKeyError is null ? _configuredHotKey.Label : $"{_configuredHotKey.Label} (недоступна)";

    public MainWindow(AppState state, AppPaths paths)
    {
        _state = state;
        InitializeComponent();
        _appWindow = ConfigureWindow();

        RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
        Navigate(typeof(ConnectionPage));
        _appWindow.Closing += OnWindowClosing;

        var handle = WindowNative.GetWindowHandle(this);
        _hotKeySettings = new HotKeySettingsStore(paths);
        _hotKey = new GlobalHotKeyService(handle, _state.ToggleConnectionAsync, _state.RecordUiError);
        _configuredHotKey = _hotKeySettings.Load();
        if (!_hotKey.SetBinding(_configuredHotKey, out _hotKeyError) && _hotKeyError is not null)
        {
            _state.RecordUiError($"Горячая клавиша {_configuredHotKey?.Label}: {_hotKeyError}");
        }

        _tray = new TrayService(
            _state,
            ShowFromTray,
            ShowMyIpAsync,
            FindFreeServersAsync,
            ShowHotKeyDialogAsync,
            OpenSettings,
            RequestExit);
        Closed += OnClosed;
    }

    private AppWindow ConfigureWindow()
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
        return appWindow;
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

    public void OpenProfileEditor(string? profileId) =>
        ContentFrame.Navigate(typeof(ProfileEditorPage), new ProfileEditorNavigation(_state, profileId));

    public void OpenProfiles()
    {
        RootNavigation.SelectedItem = RootNavigation.MenuItems
            .OfType<NavigationViewItem>()
            .FirstOrDefault(item => string.Equals(item.Tag?.ToString(), "profiles", StringComparison.Ordinal));
        Navigate(typeof(ProfilesPage));
    }

    public void OpenSettings()
    {
        ShowFromTray();
        RootNavigation.SelectedItem = RootNavigation.SettingsItem;
        Navigate(typeof(SettingsPage));
    }

    public void ShowFromTray()
    {
        _appWindow.Show();
        Activate();
        SetForegroundWindow(WindowNative.GetWindowHandle(this));
    }

    public async Task ShowHotKeyDialogAsync()
    {
        ShowFromTray();
        var captured = _configuredHotKey;
        var captureBox = new TextBox
        {
            Text = captured?.Label ?? "Нажмите комбинацию",
            IsReadOnly = true,
            HorizontalTextAlignment = TextAlignment.Center,
            FontSize = 18,
            SelectionHighlightColor = new Microsoft.UI.Xaml.Media.SolidColorBrush(Colors.Transparent),
        };
        captureBox.KeyDown += (_, args) =>
        {
            var binding = HotKeyBinding.Create(
                args.Key,
                IsKeyDown(VkControl),
                IsKeyDown(VkAlt),
                IsKeyDown(VkShift),
                IsKeyDown(VkLeftWindows) || IsKeyDown(VkRightWindows));
            if (binding is not null)
            {
                captured = binding;
                captureBox.Text = binding.Label;
                args.Handled = true;
            }
        };
        var content = new StackPanel { Spacing = 14 };
        content.Children.Add(new TextBlock
        {
            Text = "Нажмите комбинацию с Ctrl, Alt, Shift или Win. Она будет переключать VPN из любого приложения.",
            TextWrapping = TextWrapping.Wrap,
        });
        content.Children.Add(captureBox);

        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = "Горячая клавиша",
            Content = content,
            PrimaryButtonText = "Сохранить",
            SecondaryButtonText = "Очистить",
            CloseButtonText = "Отмена",
            DefaultButton = ContentDialogButton.Primary,
        };
        dialog.Opened += (_, _) => captureBox.Focus(FocusState.Programmatic);
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.None)
        {
            return;
        }

        var next = result == ContentDialogResult.Secondary ? null : captured;
        var previous = _configuredHotKey;
        if (!_hotKey.SetBinding(next, out var error))
        {
            _hotKey.SetBinding(previous, out _hotKeyError);
            await ShowMessageAsync(
                "Горячая клавиша уже занята",
                error ?? "Windows отклонила эту комбинацию.");
            return;
        }

        _configuredHotKey = next;
        _hotKeyError = null;
        _hotKeySettings.Save(next);
    }

    private async Task ShowMyIpAsync()
    {
        ShowFromTray();
        var result = new TextBlock
        {
            Text = "Определяем внешний адрес…",
            FontSize = 20,
            TextWrapping = TextWrapping.Wrap,
        };
        var progress = new ProgressRing { IsActive = true, Width = 30, Height = 30 };
        var panel = new StackPanel { Spacing = 14, HorizontalAlignment = HorizontalAlignment.Center };
        panel.Children.Add(progress);
        panel.Children.Add(result);
        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = "Мой IP",
            Content = panel,
            CloseButtonText = "Закрыть",
        };
        var shown = dialog.ShowAsync();
        var address = await _state.GetExitIpAsync();
        progress.IsActive = false;
        progress.Visibility = Visibility.Collapsed;
        result.Text = address ?? "Адрес не определён. Подробности записаны в журнал.";
        await shown;
    }

    private async Task FindFreeServersAsync()
    {
        ShowFromTray();
        OpenProfiles();
        await _state.FindFreeServersAsync();
    }

    private async Task ShowMessageAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = title,
            Content = message,
            CloseButtonText = "OK",
        };
        await dialog.ShowAsync();
    }

    public void RequestExit()
    {
        _exitRequested = true;
        Close();
    }

    private void OnWindowClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (_exitRequested)
        {
            return;
        }
        args.Cancel = true;
        sender.Hide();
        if (!_backgroundNoticeShown)
        {
            _backgroundNoticeShown = true;
            _tray.ShowNotification(
                "YPtun работает в фоне",
                "Откройте меню значка, чтобы подключиться или завершить приложение.");
        }
    }

    private async void OnClosed(object sender, WindowEventArgs args)
    {
        _hotKey.Dispose();
        _tray.Dispose();
        await _state.DisposeAsync();
    }

    private static bool IsKeyDown(int virtualKey) => (GetKeyState(virtualKey) & 0x8000) != 0;

    [DllImport("user32.dll")]
    private static extern short GetKeyState(int virtualKey);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(nint window);
}
