using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI.Views;

public sealed partial class LogsPage : Page
{
    private AppState? _state;

    public LogsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _state = (AppState)e.Parameter;
        DataContext = _state;
        base.OnNavigatedTo(e);
    }

    private void OnCopy(object sender, RoutedEventArgs e)
    {
        if (_state is null)
        {
            return;
        }

        var package = new DataPackage();
        package.SetText(string.Join(Environment.NewLine, _state.Logs));
        Clipboard.SetContent(package);
    }
}
