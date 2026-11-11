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
