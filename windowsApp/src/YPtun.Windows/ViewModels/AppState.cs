using System.Collections.ObjectModel;
using System.Text.Json;
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
    private readonly SemaphoreSlim _connectionGate = new(1, 1);
    private bool _connectionTarget;

    public ObservableCollection<ProfileItem> Profiles { get; } = [];
    public ObservableCollection<SubscriptionItem> Subscriptions { get; } = [];
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

    private string _telegramProxyStatus = "stopped";
    public string TelegramProxyStatus
    {
        get => _telegramProxyStatus;
        set => SetProperty(ref _telegramProxyStatus, value);
    }

    private string _telegramProxyDetail = "Отключён";
    public string TelegramProxyDetail
    {
        get => _telegramProxyDetail;
        set => SetProperty(ref _telegramProxyDetail, value);
    }

    private string _sessionDetail = "Сеанс не запущен";
    public string SessionDetail
    {
        get => _sessionDetail;
        set => SetProperty(ref _sessionDetail, value);
    }

    private string _engineHealth = "—";
    public string EngineHealth
    {
        get => _engineHealth;
        set => SetProperty(ref _engineHealth, value);
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

    public Task ToggleConnectionAsync() =>
        SetConnectionStateAsync(IsBusy ? !_connectionTarget : !IsConnected);

    public Task EnsureConnectedAsync() => SetConnectionStateAsync(true);

    public Task EnsureDisconnectedAsync() => SetConnectionStateAsync(false);

    private async Task SetConnectionStateAsync(bool connect)
    {
        _connectionTarget = connect;
        await _connectionGate.WaitAsync();
        try
        {
            if (IsConnected == connect)
            {
                return;
            }

            if (connect && ConnectionMode == "tun" && !ElevationService.IsElevated)
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
                await SendConnectionCommandAsync(connect);
            }
            finally
            {
                IsBusy = false;
            }
        }
        finally
        {
            _connectionGate.Release();
        }
    }

    public async Task RestartConnectionAsync()
    {
        if (!IsConnected)
        {
            await EnsureConnectedAsync();
            return;
        }
        await _connectionGate.WaitAsync();
        try
        {
            IsBusy = true;
            try
            {
                if (IsConnected)
                {
                    await SendConnectionCommandAsync(false);
                }
                await SendConnectionCommandAsync(true);
            }
            finally
            {
                IsBusy = false;
            }
        }
        finally
        {
            _connectionGate.Release();
        }
    }

    private async Task<bool> SendConnectionCommandAsync(bool connect)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new
        {
            command = connect ? "start" : "stop",
            mode = ConnectionMode,
        });
        if (!response.Ok || response.Data is null)
        {
            RecordUiError(response.Error ?? "Операция завершилась ошибкой");
            return false;
        }
        ApplySnapshot(response.Data);
        return true;
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

    public async Task<LocationEditorData?> GetProfileEditorAsync(string? id)
    {
        var response = await _core.SendAsync<LocationEditorData>(new
        {
            command = "get_profile",
            id = id ?? string.Empty,
        });
        if (response.Ok && response.Data is not null)
        {
            return response.Data;
        }

        RecordUiError(response.Error ?? "Не удалось открыть редактор конфигурации");
        return null;
    }

    public async Task<bool> SaveProfileAsync(string? id, string locationJson)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new
        {
            command = "save_profile",
            id = id ?? string.Empty,
            text = locationJson,
        });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
            return true;
        }

        RecordUiError(response.Error ?? "Не удалось сохранить конфигурацию");
        return false;
    }

    public async Task<bool> DuplicateProfileAsync(ProfileItem profile)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new
        {
            command = "duplicate_profile",
            id = profile.Id,
        });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
            return true;
        }

        RecordUiError(response.Error ?? "Не удалось создать копию");
        return false;
    }

    public async Task<string?> ExportProfileAsync(ProfileItem profile)
    {
        var response = await _core.SendAsync<TextPayload>(new
        {
            command = "export_profile",
            id = profile.Id,
        });
        if (response.Ok && response.Data is not null)
        {
            return response.Data.Text;
        }

        RecordUiError(response.Error ?? "Не удалось экспортировать конфигурацию");
        return null;
    }

    public async Task<string?> ExportBundleAsync()
    {
        var response = await _core.SendAsync<TextPayload>(new { command = "export_bundle" });
        if (response.Ok && response.Data is not null)
        {
            return response.Data.Text;
        }

        RecordUiError(response.Error ?? "Не удалось экспортировать резервную копию");
        return null;
    }

    public async Task<SettingsSnapshot?> GetSettingsAsync()
    {
        var response = await _core.SendAsync<SettingsSnapshot>(new { command = "settings" });
        if (response.Ok && response.Data is not null)
        {
            return response.Data;
        }

        RecordUiError(response.Error ?? "Не удалось загрузить параметры");
        return null;
    }

    public async Task<SettingsSnapshot?> SaveSettingsAsync(string kind, string json)
    {
        var response = await _core.SendAsync<SettingsSnapshot>(new
        {
            command = "save_settings",
            id = kind,
            text = json,
        });
        if (response.Ok && response.Data is not null)
        {
            return response.Data;
        }

        RecordUiError(response.Error ?? "Не удалось сохранить параметры");
        return null;
    }

    public async Task ClearLogsAsync()
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "clear_logs" });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Не удалось очистить журнал");
        }
    }

    public async Task<UpdateCheckPayload?> CheckUpdateAsync()
    {
        var response = await _core.SendAsync<UpdateCheckPayload>(new { command = "check_update" });
        if (response.Ok && response.Data is not null)
        {
            return response.Data;
        }

        RecordUiError(response.Error ?? "Не удалось проверить обновления");
        return null;
    }

    public async Task<string?> GetExitIpAsync()
    {
        var response = await _core.SendAsync<TextPayload>(new { command = "exit_ip" });
        if (response.Ok && response.Data is not null)
        {
            return response.Data.Text;
        }

        RecordUiError(response.Error ?? "Не удалось определить внешний IP-адрес");
        return null;
    }

    public static string PrettyJson(JsonElement value) => JsonSerializer.Serialize(value, new JsonSerializerOptions
    {
        WriteIndented = true,
    });

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

    public async Task<bool> SaveSubscriptionAsync(
        SubscriptionItem subscription,
        string url,
        string customName,
        string cascadeLink,
        int updateIntervalHours,
        bool autoUpdateEnabled)
    {
        var payload = JsonSerializer.Serialize(new
        {
            url,
            customName,
            cascadeLink,
            updateIntervalHours,
            autoUpdateEnabled,
        }, CoreJson.Options);
        var response = await _core.SendAsync<CoreSnapshot>(new
        {
            command = "save_subscription",
            id = subscription.Url,
            text = payload,
        });
        if (!response.Ok || response.Data is null)
        {
            RecordUiError(response.Error ?? "Не удалось сохранить подписку");
            return false;
        }
        ApplySnapshot(response.Data);

        var newUrl = url.Trim();
        if (!string.Equals(newUrl, subscription.Url, StringComparison.Ordinal))
        {
            await RefreshSubscriptionAsync(newUrl);
        }
        if (IsConnected && !string.Equals(cascadeLink.Trim(), subscription.CascadeLink, StringComparison.Ordinal))
        {
            await RestartConnectionAsync();
        }
        return true;
    }

    public async Task RefreshSubscriptionAsync(string url)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new
        {
            command = "refresh_subscription",
            id = url,
        });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Не удалось обновить подписку");
        }
    }

    public async Task DeleteSubscriptionAsync(SubscriptionItem subscription)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new
        {
            command = "delete_subscription",
            id = subscription.Url,
        });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? "Не удалось удалить подписку");
        }
    }

    public async Task PingProfileAsync(ProfileItem profile)
    {
        var response = await _core.SendAsync<CoreSnapshot>(new { command = "ping", id = profile.Id });
        if (response.Ok && response.Data is not null)
        {
            ApplySnapshot(response.Data);
        }
        else
        {
            RecordUiError(response.Error ?? $"Не удалось проверить {profile.Name}");
        }
    }

    public async Task PingAllAsync()
    {
        if (IsBusy)
        {
            return;
        }
        IsBusy = true;
        try
        {
            foreach (var profile in Profiles.ToArray())
            {
                await PingProfileAsync(profile);
            }
        }
        finally
        {
            IsBusy = false;
        }
    }

    public async Task ConnectFastestAsync()
    {
        await PingAllAsync();
        var fastest = Profiles
            .Where(profile => profile.IsComplete && profile.PingMs is not null)
            .OrderBy(profile => profile.PingMs)
            .FirstOrDefault();
        if (fastest is not null && SelectedProfile?.Id != fastest.Id)
        {
            await SelectProfileAsync(fastest);
        }
        await EnsureConnectedAsync();
    }

    public async Task FindFreeServersAsync()
    {
        if (IsBusy)
        {
            return;
        }
        IsBusy = true;
        ConnectionDetail = "Поиск и проверка бесплатных серверов…";
        try
        {
            var response = await _core.SendAsync<CoreSnapshot>(new { command = "find_free_servers" });
            if (response.Ok && response.Data is not null)
            {
                ApplySnapshot(response.Data);
            }
            else
            {
                RecordUiError(response.Error ?? "Поиск бесплатных серверов завершился без результата");
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
        TelegramProxyStatus = snapshot.TelegramProxyStatus;
        TelegramProxyDetail = snapshot.TelegramProxyDetail;
        if (snapshot.Session.ConnectedSinceEpochMs > 0)
        {
            var since = DateTimeOffset.FromUnixTimeMilliseconds((long)snapshot.Session.ConnectedSinceEpochMs);
            var elapsed = DateTimeOffset.Now - since;
            var tun = snapshot.Session.InCoreTun ? "встроенный TUN" : snapshot.Session.ExternalTun ? "tun2socks" : snapshot.Session.ConnectionMode;
            SessionDetail = $"{snapshot.Session.ActiveEngine} · {tun} · {elapsed:hh\\:mm\\:ss} · обходных маршрутов: {snapshot.Session.BypassRouteCount}";
            EngineHealth = snapshot.Session.EngineAlive
                ? "Движок работает"
                : $"Нет ответа (ошибок watchdog: {snapshot.Session.WatchdogProbeFailures})";
        }
        else
        {
            SessionDetail = "Сеанс не запущен";
            EngineHealth = "—";
        }

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

        if (!Subscriptions.SequenceEqual(snapshot.Subscriptions))
        {
            Subscriptions.Clear();
            foreach (var subscription in snapshot.Subscriptions)
            {
                Subscriptions.Add(subscription);
            }
        }

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
        _connectionGate.Dispose();
        _core.Dispose();
        return ValueTask.CompletedTask;
    }

    private string coreError() => string.IsNullOrWhiteSpace(_core.InitializationError)
        ? "WinUI 3 shell запущен; нативное ядро не найдено."
        : $"Нативное ядро не загрузилось: {_core.InitializationError}";
}
