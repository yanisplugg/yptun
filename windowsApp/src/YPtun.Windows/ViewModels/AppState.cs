using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using YPtun.WinUI.Models;
using YPtun.WinUI.Services;

namespace YPtun.WinUI.ViewModels;

public sealed partial class AppState : ObservableObject, IAsyncDisposable
{
    private readonly CoreClient _core;
    private readonly AppPaths _paths;
    private readonly PeriodicTimer _pollTimer = new(TimeSpan.FromMilliseconds(750));
    private readonly CancellationTokenSource _pollCancellation = new();
    private readonly DispatcherQueue _dispatcher = DispatcherQueue.GetForCurrentThread();

    public ObservableCollection<ProfileItem> Profiles { get; } = [];
    public ObservableCollection<string> Logs { get; } = [];

    private string _connectionStatus = "Отключено";
    public string ConnectionStatus
    {
        get => _connectionStatus;
        set => SetProperty(ref _connectionStatus, value);
    }

    private string _connectionDetail = "Выберите конфигурацию и подключитесь";
    public string ConnectionDetail
    {
        get => _connectionDetail;
        set => SetProperty(ref _connectionDetail, value);
    }

    private bool _isConnected;
    public bool IsConnected
    {
        get => _isConnected;
        set => SetProperty(ref _isConnected, value);
    }

    private bool _isBusy;
    public bool IsBusy
    {
        get => _isBusy;
        set => SetProperty(ref _isBusy, value);
    }

    private bool _nativeCoreAvailable;
    public bool NativeCoreAvailable
    {
        get => _nativeCoreAvailable;
        set => SetProperty(ref _nativeCoreAvailable, value);
    }

    private ProfileItem? _selectedProfile;
    public ProfileItem? SelectedProfile
    {
        get => _selectedProfile;
        set => SetProperty(ref _selectedProfile, value);
    }

    private string _connectionMode = "tun";
    public string ConnectionMode
    {
        get => _connectionMode;
        set => SetProperty(ref _connectionMode, value);
    }

    public string DataDirectory => _paths.DataDirectory;

    public Task Initialization { get; }

    public AppState(CoreClient core, AppPaths paths)
    {
        _core = core;
        _paths = paths;
        NativeCoreAvailable = core.IsNativeAvailable;
        Initialization = InitializeAsync();
    }

    public async Task InitializeAsync()
    {
        if (!NativeCoreAvailable)
        {
            Logs.Add(coreError());
            return;
        }

        await RefreshAsync();
        _ = PollAsync(_pollCancellation.Token);
    }

    public async Task RefreshAsync()
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "snapshot" });
        if (!response.Ok || response.Data is null)
        {
            RecordUiError(response.Error ?? "Не удалось получить состояние ядра");
            return;
        }

        ApplySnapshot(response.Data);
    }

    public async Task ToggleConnectionAsync()
    {
        if (IsBusy)
        {
            return;
        }

        if (!IsConnected && ConnectionMode == "tun" && !ElevationService.IsElevated)
        {
            App.CurrentApp.ReleaseSingleInstanceForRestart();
            try
            {
                if (ElevationService.RestartElevated("--connect"))
                {
                    Application.Current.Exit();
                    return;
                }
                App.CurrentApp.ReacquireSingleInstance();
            }
            catch (Exception exception)
            {
                App.CurrentApp.ReacquireSingleInstance();
                RecordUiError($"Не удалось запросить права администратора: {exception.Message}");
            }
            return;
        }

        IsBusy = true;
        try
        {
            var command = IsConnected ? "stop" : "start";
            var response = await _core.SendAsync<CoreSnapshot>(new { command, mode = ConnectionMode });
            if (!response.Ok || response.Data is null)
            {
                RecordUiError(response.Error ?? "Операция завершилась ошибкой");
                return;
            }

            ApplySnapshot(response.Data);
        }
        finally
        {
            IsBusy = false;
        }
    }

    public async Task SelectProfileAsync(ProfileItem profile)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "select", id = profile.Id });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Не удалось выбрать конфигурацию");
        }
    }

    public async Task ImportAsync(string text)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "import", text });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Конфигурация не распознана");
        }
    }

    public async Task DeleteProfileAsync(ProfileItem profile)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "delete", id = profile.Id });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Не удалось удалить конфигурацию");
        }
    }

    public async Task RefreshSubscriptionsAsync()
    {
        IsBusy = true;
        try
        {
            var response = await _core.SendAsync<CoreSnapshot>(new { command = "refresh_subscriptions" });
            if (response.Ok && response.Data is not null)
            {
                ApplySnapshot(response.Data);
            }
            else
            {
                RecordUiError(response.Error ?? "Не удалось обновить подписки");
            }
        }
        finally
        {
            IsBusy = false;
        }
    }

    public async Task SetConnectionModeAsync(string mode)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "set_mode", mode });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Не удалось изменить режим");
        }
    }

    public void RecordUiError(Exception exception) => RecordUiError(exception.Message);

    public void RecordUiError(string message)
    {
        if (!_dispatcher.HasThreadAccess)
        {
            _dispatcher.TryEnqueue(() => RecordUiError(message));
            return;
        }

        Logs.Add($"UI: {message}");
        ConnectionDetail = message;

        try
        {
            Directory.CreateDirectory(_paths.DataDirectory);
            File.AppendAllText(_paths.LogsFile, $"{DateTimeOffset.Now:O} {message}{Environment.NewLine}");
        }
        catch
        {
            // Diagnostics must never take down the UI.
        }
    }

    private async Task PollAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (await _pollTimer.WaitForNextTickAsync(cancellationToken))
            {
                await RefreshAsync();
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }

    private void ApplySnapshot(CoreSnapshot snapshot)
    {
        if (!_dispatcher.HasThreadAccess)
        {
            _dispatcher.TryEnqueue(() => ApplySnapshot(snapshot));
            return;
        }

        ConnectionStatus = snapshot.StatusLabel;
        ConnectionDetail = snapshot.StatusDetail;
        IsConnected = snapshot.IsConnected;
        NativeCoreAvailable = snapshot.RuntimeAvailable;
        ConnectionMode = snapshot.ConnectionMode;

        var selectedId = snapshot.ActiveProfileId;
        if (!Profiles.SequenceEqual(snapshot.Profiles))
        {
            Profiles.Clear();
            foreach (var profile in snapshot.Profiles)
            {
                Profiles.Add(profile);
            }
        }

        SelectedProfile = Profiles.FirstOrDefault(profile => profile.Id == selectedId);

        if (!Logs.SequenceEqual(snapshot.Logs))
        {
            Logs.Clear();
            foreach (var line in snapshot.Logs)
            {
                Logs.Add(line);
            }
        }
    }

    public ValueTask DisposeAsync()
    {
        _pollCancellation.Cancel();
        _pollTimer.Dispose();
        _pollCancellation.Dispose();
        _core.Dispose();
        return ValueTask.CompletedTask;
    }

    private string coreError() => string.IsNullOrWhiteSpace(_core.InitializationError)
        ? "WinUI 3 shell запущен; нативное ядро не найдено."
        : $"Нативное ядро не загрузилось: {_core.InitializationError}";
}
