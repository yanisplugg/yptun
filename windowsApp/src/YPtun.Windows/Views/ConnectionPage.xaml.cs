using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI.Views;

public sealed partial class ConnectionPage : Page
{
    private AppState? _state;

    public ConnectionPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _state = (AppState)e.Parameter;
        DataContext = _state;
        base.OnNavigatedTo(e);
    }

    private async void OnToggleConnection(object sender, RoutedEventArgs e)
    {
        if (_state is not null)
        {
            await _state.ToggleConnectionAsync();
        }
    }
}
