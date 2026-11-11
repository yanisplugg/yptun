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

    public AppPaths? Paths { get; }

    public App()
    {
        InitializeComponent();
        UnhandledException += OnUnhandledException;
        _singleInstance.ActivationReceived += OnActivationReceived;

        if (_singleInstance.IsOwner)
        {
            Paths = new AppPaths();
            var core = new CoreClient(Paths);
            State = new AppState(core, Paths);
        }
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var arguments = Environment.GetCommandLineArgs()
            .Skip(1)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .ToList();
        if (arguments.Count == 0 && !string.IsNullOrWhiteSpace(args.Arguments))
        {
            arguments.Add(args.Arguments.Trim());
        }

        var state = State;
        var paths = Paths;
        if (!_singleInstance.IsOwner || state is null || paths is null)
        {
            await _singleInstance.ForwardActivationAsync(arguments);
            Exit();
            return;
        }

        MainWindow = new MainWindow(state, paths);
        MainWindow.Activate();

        await state.Initialization;
        var hasExplicitConnectionCommand = false;
        foreach (var argument in arguments)
        {
            hasExplicitConnectionCommand |= IsConnectionCommand(argument);
            await HandleActivationAsync(argument);
        }

        if (!hasExplicitConnectionCommand)
        {
            var settings = await state.GetSettingsAsync();
            var autoConnect = settings?.AppBehavior.ValueKind == System.Text.Json.JsonValueKind.Object
                && settings.AppBehavior.TryGetProperty("autoConnectOnLaunch", out var value)
                && value.ValueKind is System.Text.Json.JsonValueKind.True;
            if (autoConnect)
            {
                await state.EnsureConnectedAsync();
            }
        }
    }

    public void ReleaseSingleInstanceForRestart() => _singleInstance.Release();

    public void ReacquireSingleInstance() => _singleInstance.TryAcquire();

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        State?.RecordUiError(e.Exception);
        e.Handled = true;
    }

    private void OnActivationReceived(object? sender, IReadOnlyList<string> arguments)
    {
        var window = MainWindow;
        if (window is null)
        {
            return;
        }
        window.DispatcherQueue.TryEnqueue(async () =>
        {
            window.ShowFromTray();
            foreach (var argument in arguments)
            {
                await HandleActivationAsync(argument);
            }
        });
    }

    private async Task HandleActivationAsync(string activation)
    {
        var state = State;
        if (state is null || string.IsNullOrWhiteSpace(activation))
        {
            return;
        }

        activation = activation.Trim().Trim('"');
        switch (activation.ToLowerInvariant())
        {
            case "--connect":
                await state.EnsureConnectedAsync();
                return;
            case "--disconnect":
                await state.EnsureDisconnectedAsync();
                return;
            case "--restart":
                await state.RestartConnectionAsync();
                return;
        }

        if (File.Exists(activation))
        {
            try
            {
                var info = new FileInfo(activation);
                if (info.Length > 20 * 1024 * 1024)
                {
                    throw new InvalidDataException("Файл конфигурации больше 20 МБ");
                }
                await state.ImportAsync(await File.ReadAllTextAsync(activation));
                MainWindow?.OpenProfiles();
            }
            catch (Exception exception)
            {
                state.RecordUiError($"Не удалось открыть файл: {exception.Message}");
            }
            return;
        }

        if (!Uri.TryCreate(activation, UriKind.Absolute, out var uri))
        {
            return;
        }

        if (uri.Scheme.Equals("yptun", StringComparison.OrdinalIgnoreCase))
        {
            if (uri.Host.Equals("control", StringComparison.OrdinalIgnoreCase))
            {
                await HandleControlActivationAsync(uri);
                return;
            }

            if (uri.Host.Equals("import", StringComparison.OrdinalIgnoreCase))
            {
                var marker = "yptun://import/";
                var payload = activation.StartsWith(marker, StringComparison.OrdinalIgnoreCase)
                    ? activation[marker.Length..]
                    : uri.AbsolutePath.TrimStart('/');
                if (!string.IsNullOrWhiteSpace(payload))
                {
                    await state.ImportAsync(Uri.UnescapeDataString(payload));
                    MainWindow?.OpenProfiles();
                }
                return;
            }

            var queryPayload = QueryValue(uri, "url") ?? QueryValue(uri, "link");
            if (!string.IsNullOrWhiteSpace(queryPayload))
            {
                activation = queryPayload;
            }
        }

        if (activation.Contains("://", StringComparison.Ordinal))
        {
            await state.ImportAsync(activation);
            MainWindow?.OpenProfiles();
        }
    }

    private async Task HandleControlActivationAsync(Uri uri)
    {
        var state = State;
        if (state is null)
        {
            return;
        }
        var verb = uri.AbsolutePath.Trim('/').Split('/', StringSplitOptions.RemoveEmptyEntries).LastOrDefault();
        switch (verb?.ToLowerInvariant())
        {
            case "start":
                await state.EnsureConnectedAsync();
                break;
            case "stop":
                await state.EnsureDisconnectedAsync();
                break;
            case "restart":
                await state.RestartConnectionAsync();
                break;
            case "auto":
                await state.ConnectFastestAsync();
                break;
        }
    }

    private static bool IsConnectionCommand(string value)
    {
        value = value.Trim().Trim('"');
        if (value is "--connect" or "--disconnect" or "--restart")
        {
            return true;
        }
        return Uri.TryCreate(value, UriKind.Absolute, out var uri)
            && uri.Scheme.Equals("yptun", StringComparison.OrdinalIgnoreCase)
            && uri.Host.Equals("control", StringComparison.OrdinalIgnoreCase);
    }

    private static string? QueryValue(Uri uri, string name)
    {
        foreach (var pair in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = pair.Split('=', 2);
            if (parts.Length == 2 && parts[0].Equals(name, StringComparison.OrdinalIgnoreCase))
            {
                return Uri.UnescapeDataString(parts[1]);
            }
        }
        return null;
    }
}
