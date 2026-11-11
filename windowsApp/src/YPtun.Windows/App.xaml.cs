using Microsoft.UI.Xaml;
using YPtun.WinUI.Services;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI;

public partial class App : Application
{
    private readonly SingleInstanceService _singleInstance = new();

    public static App CurrentApp => (App)Current;

    public AppState? State { get; }

    public MainWindow? MainWindow { get; private set; }

    public App()
    {
        InitializeComponent();
        UnhandledException += OnUnhandledException;

        if (_singleInstance.IsOwner)
        {
            var paths = new AppPaths();
            var core = new CoreClient(paths);
            State = new AppState(core, paths);
        }
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var state = State;
        if (!_singleInstance.IsOwner || state is null)
        {
            Exit();
            return;
        }

        MainWindow = new MainWindow(state);
        MainWindow.Activate();

        var commandLineArguments = Environment.GetCommandLineArgs().Skip(1);
        var activationArguments = args.Arguments.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (commandLineArguments.Concat(activationArguments).Contains("--connect"))
        {
            await state.Initialization;
            await state.ToggleConnectionAsync();
        }
    }

    public void ReleaseSingleInstanceForRestart() => _singleInstance.Release();

    public void ReacquireSingleInstance() => _singleInstance.TryAcquire();

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        State?.RecordUiError(e.Exception);
        e.Handled = true;
    }
}
