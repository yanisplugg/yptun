using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;
using YPtun.WinUI.Models;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI.Views;

public sealed partial class ProfilesPage : Page
{
    private AppState? _state;

    public ProfilesPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _state = (AppState)e.Parameter;
        DataContext = _state;
        base.OnNavigatedTo(e);
    }

    private async void OnProfileClick(object sender, ItemClickEventArgs e)
    {
        if (_state is not null && e.ClickedItem is ProfileItem profile)
        {
            await _state.SelectProfileAsync(profile);
        }
    }

    private async void OnPaste(object sender, RoutedEventArgs e)
    {
        if (_state is null)
        {
            return;
        }

        try
        {
            var content = Clipboard.GetContent();
            if (content.Contains(StandardDataFormats.Text))
            {
                await _state.ImportAsync(await content.GetTextAsync());
            }
        }
        catch (Exception exception)
        {
            _state.RecordUiError(exception);
        }
    }

    private async void OnRefresh(object sender, RoutedEventArgs e)
    {
        if (_state is not null)
        {
            await _state.RefreshSubscriptionsAsync();
        }
    }

    private async void OnRefreshSubscription(object sender, RoutedEventArgs e)
    {
        if (_state is not null && sender is Button { Tag: SubscriptionItem subscription })
        {
            await _state.RefreshSubscriptionAsync(subscription.Url);
        }
    }

    private async void OnEditSubscription(object sender, RoutedEventArgs e)
    {
        if (_state is null || sender is not Button { Tag: SubscriptionItem subscription })
        {
            return;
        }

        var url = new TextBox { Header = "URL подписки", Text = subscription.Url };
        var name = new TextBox { Header = "Своё название", Text = subscription.CustomName };
        var cascade = new TextBox
        {
            Header = "Каскад (ссылка второго прокси)",
            Text = subscription.CascadeLink,
            PlaceholderText = "vless://, vmess://, trojan://, ss://…",
        };
        var interval = new NumberBox
        {
            Header = "Интервал обновления, часов",
            Value = subscription.UpdateIntervalHours,
            Minimum = 1,
            Maximum = 720,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };
        var automatic = new ToggleSwitch
        {
            Header = "Автоматически обновлять подписку",
            IsOn = subscription.AutoUpdateEnabled,
        };
        var panel = new StackPanel { Spacing = 12, Width = 560 };
        panel.Children.Add(url);
        panel.Children.Add(name);
        panel.Children.Add(cascade);
        panel.Children.Add(interval);
        panel.Children.Add(automatic);
        panel.Children.Add(new TextBlock
        {
            Text = "Изменение каскада переподключит активный туннель. Новый URL будет проверен немедленно.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.62,
        });

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Параметры подписки",
            Content = panel,
            PrimaryButtonText = "Сохранить",
            CloseButtonText = "Отмена",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        if (!Uri.TryCreate(url.Text.Trim(), UriKind.Absolute, out var parsed)
            || parsed.Scheme is not ("http" or "https"))
        {
            _state.RecordUiError("Укажите корректный HTTP(S)-адрес подписки");
            return;
        }

        await _state.SaveSubscriptionAsync(
            subscription,
            url.Text,
            name.Text,
            cascade.Text,
            (int)interval.Value,
            automatic.IsOn);
    }

    private async void OnDeleteSubscription(object sender, RoutedEventArgs e)
    {
        if (_state is null || sender is not Button { Tag: SubscriptionItem subscription })
        {
            return;
        }
        var connectionNotice = _state.IsConnected
            ? " Активное подключение будет остановлено."
            : string.Empty;
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Удалить «{subscription.DisplayName}»?",
            Content = $"Будут удалены все конфигурации этой подписки ({subscription.LocationCount}).{connectionNotice}",
            PrimaryButtonText = "Удалить",
            CloseButtonText = "Отмена",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        if (_state.IsConnected)
        {
            await _state.EnsureDisconnectedAsync();
        }
        await _state.DeleteSubscriptionAsync(subscription);
    }

    private async void OnPingAll(object sender, RoutedEventArgs e)
    {
        if (_state is not null)
        {
            await _state.PingAllAsync();
        }
    }

    private async void OnPing(object sender, RoutedEventArgs e)
    {
        if (_state is not null && sender is Button { Tag: ProfileItem profile })
        {
            await _state.PingProfileAsync(profile);
        }
    }

    private async void OnFindFreeServers(object sender, RoutedEventArgs e)
    {
        if (_state is null)
        {
            return;
        }
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Поиск бесплатных серверов",
            Content = "YPtun загрузит публичные списки, проверит серверы через Xray и заменит незакреплённые результаты предыдущего поиска. Это может занять несколько минут.",
            PrimaryButtonText = "Начать",
            CloseButtonText = "Отмена",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
        {
            await _state.FindFreeServersAsync();
        }
    }

    private void OnNew(object sender, RoutedEventArgs e) => App.CurrentApp.MainWindow?.OpenProfileEditor(null);

    private void OnEdit(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ProfileItem profile })
        {
            App.CurrentApp.MainWindow?.OpenProfileEditor(profile.Id);
        }
    }

    private async void OnDuplicate(object sender, RoutedEventArgs e)
    {
        if (_state is not null && sender is Button { Tag: ProfileItem profile })
        {
            await _state.DuplicateProfileAsync(profile);
        }
    }

    private async void OnShare(object sender, RoutedEventArgs e)
    {
        if (_state is null || sender is not Button { Tag: ProfileItem profile })
        {
            return;
        }

        var text = await _state.ExportProfileAsync(profile);
        if (!string.IsNullOrWhiteSpace(text))
        {
            var package = new DataPackage();
            package.SetText(text);
            Clipboard.SetContent(package);
            Clipboard.Flush();
        }
    }

    private async void OnExportBundle(object sender, RoutedEventArgs e)
    {
        if (_state is null || App.CurrentApp.MainWindow is null)
        {
            return;
        }

        var text = await _state.ExportBundleAsync();
        if (text is null)
        {
            return;
        }

        var picker = new FileSavePicker
        {
            SuggestedFileName = $"YPtun-backup-{DateTime.Now:yyyyMMdd-HHmm}",
        };
        picker.FileTypeChoices.Add("YPtun JSON", new List<string> { ".json" });
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.CurrentApp.MainWindow));
        var file = await picker.PickSaveFileAsync();
        if (file is not null)
        {
            await FileIO.WriteTextAsync(file, text);
        }
    }

    private async void OnImportFile(object sender, RoutedEventArgs e)
    {
        if (_state is null || App.CurrentApp.MainWindow is null)
        {
            return;
        }

        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".json");
        picker.FileTypeFilter.Add(".txt");
        picker.FileTypeFilter.Add(".conf");
        picker.FileTypeFilter.Add(".ovpn");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.CurrentApp.MainWindow));

        var file = await picker.PickSingleFileAsync();
        if (file is not null)
        {
            await _state.ImportAsync(await FileIO.ReadTextAsync(file));
        }
    }

    private async void OnDelete(object sender, RoutedEventArgs e)
    {
        if (_state is not null && sender is Button { Tag: ProfileItem profile })
        {
            await _state.DeleteProfileAsync(profile);
        }
    }
}
