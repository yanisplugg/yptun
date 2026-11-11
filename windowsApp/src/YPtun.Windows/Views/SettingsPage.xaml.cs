using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI.Views;

public sealed partial class SettingsPage : Page
{
    private AppState? _state;
    private bool _loaded;

    public SettingsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _state = (AppState)e.Parameter;
        DataContext = _state;
        ConnectionModeButtons.SelectedIndex = _state.ConnectionMode == "proxy" ? 1 : 0;
        _loaded = true;
        base.OnNavigatedTo(e);
    }

    private async void OnModeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loaded && _state is not null)
        {
            await _state.SetConnectionModeAsync(ConnectionModeButtons.SelectedIndex == 1 ? "proxy" : "tun");
        }
    }
}
