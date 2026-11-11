using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using YPtun.WinUI.Models;
using YPtun.WinUI.ViewModels;
using YPtun.WinUI.Services;

namespace YPtun.WinUI.Views;

public sealed partial class SettingsPage : Page
{
    private AppState? _state;
    private SettingsSnapshot? _settings;
    private bool _loaded;

    public SettingsPage()
    {
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        _state = (AppState)e.Parameter;
        DataContext = _state;
        ConnectionModeButtons.SelectedIndex = _state.ConnectionMode == "proxy" ? 1 : 0;
        RefreshHotKeyStatus();
        _settings = await _state.GetSettingsAsync();
        if (_settings is not null)
        {
            ApplySettings(_settings);
        }
        _loaded = true;
        base.OnNavigatedTo(e);
    }

    private void ApplySettings(SettingsSnapshot settings)
    {
        var app = Node(settings.AppBehavior);
        AutoConnectSwitch.IsOn = Bool(app, "autoConnectOnLaunch");
        SystemProxySwitch.IsOn = Bool(app, "setSystemProxy", true);
        TelegramSwitch.IsOn = Bool(app, "telegramProxyEnabled");
        EnergySaverSwitch.IsOn = Bool(app, "energySaver");
        SpeedSwitch.IsOn = Bool(app, "showSpeedOnHome");
        EditingSwitch.IsOn = Bool(app, "allowConfigEditing");
        TelemostSwitch.IsOn = Bool(app, "telemostCookiesEnabled");
        TelemostCookiesBox.Text = String(app, "telemostCookies");
        PingUrlBox.Text = String(app, "pingUrl", "https://www.gstatic.com/generate_204");
        PingTimeoutBox.Value = Int(app, "pingTimeoutSec", 7);
        SelectTag(PingModeBox, String(app, "pingMode", "proxy_head"));

        var socks = Node(settings.Socks);
        SocksHostBox.Text = String(socks, "host", "127.0.0.1");
        SocksPortBox.Value = Int(socks, "port", 10808);
        SocksUserBox.Text = String(socks, "username");
        SocksPasswordBox.Password = String(socks, "password");

        var traffic = Node(settings.Traffic);
        RemoteDnsBox.Text = String(traffic, "remoteDns", "8.8.8.8");
        RemoteDns2Box.Text = String(traffic, "remoteDns2");
        DirectDnsBox.Text = String(traffic, "directDns", "77.88.8.8");
        DomainStrategyBox.SelectedItem = String(traffic, "domainStrategy", "ipv4_only");
        MtuBox.Value = Int(traffic, "mtu", 1500);
        MuxSwitch.IsOn = Bool(traffic, "muxEnabled");
        FragmentSwitch.IsOn = Bool(traffic, "fragmentEnabled");
        BlockRuSwitch.IsOn = Bool(traffic, "blockRuDomains");

        var routing = Node(settings.Routing);
        BypassLanSwitch.IsOn = Bool(routing, "bypassLan", true);
        BlockAdsSwitch.IsOn = Bool(routing, "blockAds");
        BypassRussiaSwitch.IsOn = Bool(routing, "bypassRussia");
        DirectDomainsBox.Text = Lines(routing["directDomains"]);
        BlockDomainsBox.Text = Lines(routing["blockDomains"]);

        var split = Node(settings.SplitTunnel);
        SelectTag(SplitModeBox, String(split, "mode", "all_apps"));
        ProxyProcessesBox.Text = Lines(split["proxyProcesses"]);
        BypassProcessesBox.Text = Lines(split["bypassProcesses"]);

        AdvancedKindBox.SelectedIndex = AdvancedKindBox.SelectedIndex < 0 ? 0 : AdvancedKindBox.SelectedIndex;
        RefreshAdvancedEditor();
    }

    private async void OnSaveAll(object sender, RoutedEventArgs e)
    {
        if (_state is null || _settings is null)
        {
            return;
        }

        try
        {
            var app = Node(_settings.AppBehavior);
            app["autoConnectOnLaunch"] = AutoConnectSwitch.IsOn;
            app["setSystemProxy"] = SystemProxySwitch.IsOn;
            app["telegramProxyEnabled"] = TelegramSwitch.IsOn;
            app["energySaver"] = EnergySaverSwitch.IsOn;
            app["showSpeedOnHome"] = SpeedSwitch.IsOn;
            app["allowConfigEditing"] = EditingSwitch.IsOn;
            app["telemostCookiesEnabled"] = TelemostSwitch.IsOn;
            app["telemostCookies"] = TelemostCookiesBox.Text;
            app["pingMode"] = SelectedTag(PingModeBox, "proxy_head");
            app["pingUrl"] = PingUrlBox.Text.Trim();
            app["pingTimeoutSec"] = (int)PingTimeoutBox.Value;

            var socks = Node(_settings.Socks);
            socks["host"] = SocksHostBox.Text.Trim();
            socks["port"] = (int)SocksPortBox.Value;
            socks["username"] = SocksUserBox.Text;
            socks["password"] = SocksPasswordBox.Password;

            var traffic = Node(_settings.Traffic);
            traffic["remoteDns"] = RemoteDnsBox.Text.Trim();
            traffic["remoteDns2"] = RemoteDns2Box.Text.Trim();
            traffic["directDns"] = DirectDnsBox.Text.Trim();
            traffic["domainStrategy"] = DomainStrategyBox.SelectedItem?.ToString() ?? "ipv4_only";
            traffic["mtu"] = (int)MtuBox.Value;
            traffic["muxEnabled"] = MuxSwitch.IsOn;
            traffic["fragmentEnabled"] = FragmentSwitch.IsOn;
            traffic["blockRuDomains"] = BlockRuSwitch.IsOn;

            var routing = Node(_settings.Routing);
            routing["bypassLan"] = BypassLanSwitch.IsOn;
            routing["blockAds"] = BlockAdsSwitch.IsOn;
            routing["bypassRussia"] = BypassRussiaSwitch.IsOn;
            routing["directDomains"] = ArrayFromLines(DirectDomainsBox.Text);
            routing["blockDomains"] = ArrayFromLines(BlockDomainsBox.Text);

            var split = Node(_settings.SplitTunnel);
            split["mode"] = SelectedTag(SplitModeBox, "all_apps");
            split["proxyProcesses"] = ArrayFromLines(ProxyProcessesBox.Text);
            split["bypassProcesses"] = ArrayFromLines(BypassProcessesBox.Text);

            foreach (var item in new[]
            {
                (Kind: "app_behavior", Node: app),
                (Kind: "socks", Node: socks),
                (Kind: "traffic", Node: traffic),
                (Kind: "routing", Node: routing),
                (Kind: "split_tunnel", Node: split),
            })
            {
                var saved = await _state.SaveSettingsAsync(item.Kind, item.Node.ToJsonString());
                if (saved is null)
                {
                    return;
                }
                _settings = saved;
            }

            StartupService.SetEnabled(AutoConnectSwitch.IsOn);

            ShowMessage("Параметры сохранены", InfoBarSeverity.Success);
            ApplySettings(_settings);
        }
        catch (Exception exception)
        {
            ShowMessage(exception.Message, InfoBarSeverity.Error);
        }
    }

    private async void OnModeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loaded && _state is not null)
        {
            await _state.SetConnectionModeAsync(ConnectionModeButtons.SelectedIndex == 1 ? "proxy" : "tun");
        }
    }

    private async void OnCheckUpdate(object sender, RoutedEventArgs e)
    {
        if (_state is null)
        {
            return;
        }
        var update = await _state.CheckUpdateAsync();
        if (update is null)
        {
            return;
        }
        if (!update.IsUpdateAvailable)
        {
            ShowMessage($"Установлена актуальная версия ({update.Version})", InfoBarSeverity.Success);
            return;
        }

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Доступна YPtun {update.Version}",
            Content = $"Загрузить и установить {update.AssetName}? Файл будет проверен по SHA-256 и цифровой подписи.",
            PrimaryButtonText = "Установить",
            CloseButtonText = "Позже",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        try
        {
            ShowMessage("Загрузка обновления…", InfoBarSeverity.Informational);
            await UpdateInstaller.DownloadAndInstallAsync(update);
            App.CurrentApp.MainWindow?.RequestExit();
        }
        catch (Exception exception)
        {
            ShowMessage($"Обновление отклонено: {exception.Message}", InfoBarSeverity.Error);
        }
    }

    private async void OnConfigureHotKey(object sender, RoutedEventArgs e)
    {
        if (App.CurrentApp.MainWindow is not { } window)
        {
            return;
        }
        await window.ShowHotKeyDialogAsync();
        RefreshHotKeyStatus();
    }

    private void RefreshHotKeyStatus()
    {
        HotKeyStatusText.Text = App.CurrentApp.MainWindow?.HotKeyLabel ?? "Не задана";
    }

    private void OnAdvancedKindChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_settings is not null)
        {
            RefreshAdvancedEditor();
        }
    }

    private void RefreshAdvancedEditor()
    {
        if (_settings is null)
        {
            return;
        }
        AdvancedJsonBox.Text = AppState.PrettyJson(AdvancedKind() switch
        {
            "traffic" => _settings.Traffic,
            "routing" => _settings.Routing,
            "routing_profiles" => _settings.RoutingProfiles,
            "split_tunnel" => _settings.SplitTunnel,
            "socks" => _settings.Socks,
            "updates" => _settings.Updates,
            _ => _settings.AppBehavior,
        });
    }

    private async void OnSaveAdvanced(object sender, RoutedEventArgs e)
    {
        if (_state is null)
        {
            return;
        }
        try
        {
            var parsed = JsonNode.Parse(AdvancedJsonBox.Text)
                ?? throw new JsonException("Пустой JSON");
            AdvancedJsonBox.Text = parsed.ToJsonString(PrettyOptions);
            var saved = await _state.SaveSettingsAsync(AdvancedKind(), AdvancedJsonBox.Text);
            if (saved is not null)
            {
                _settings = saved;
                ApplySettings(saved);
                ShowMessage("Раздел сохранён", InfoBarSeverity.Success);
            }
        }
        catch (Exception exception)
        {
            ShowMessage(exception.Message, InfoBarSeverity.Error);
        }
    }

    private void OnFormatAdvanced(object sender, RoutedEventArgs e)
    {
        try
        {
            AdvancedJsonBox.Text = (JsonNode.Parse(AdvancedJsonBox.Text)
                ?? throw new JsonException("Пустой JSON")).ToJsonString(PrettyOptions);
        }
        catch (Exception exception)
        {
            ShowMessage(exception.Message, InfoBarSeverity.Error);
        }
    }

    private string AdvancedKind() => SelectedTag(AdvancedKindBox, "app_behavior");

    private void ShowMessage(string message, InfoBarSeverity severity)
    {
        MessageBar.Message = message;
        MessageBar.Severity = severity;
        MessageBar.IsOpen = true;
    }

    private static JsonObject Node(JsonElement value) => JsonNode.Parse(value.GetRawText())!.AsObject();
    private static string String(JsonObject node, string key, string fallback = "") => node[key]?.GetValue<string>() ?? fallback;
    private static bool Bool(JsonObject node, string key, bool fallback = false) => node[key]?.GetValue<bool>() ?? fallback;
    private static int Int(JsonObject node, string key, int fallback) => node[key]?.GetValue<int>() ?? fallback;
    private static string Lines(JsonNode? node) => node is JsonArray array
        ? string.Join(Environment.NewLine, array.Select(value => value?.GetValue<string>()).Where(value => !string.IsNullOrWhiteSpace(value)))
        : string.Empty;
    private static JsonArray ArrayFromLines(string text) => new(text
        .Split(new[] { '\r', '\n', ',', ';' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .Select(value => JsonValue.Create(value))
        .ToArray());
    private static void SelectTag(ComboBox box, string tag) => box.SelectedItem = box.Items
        .OfType<ComboBoxItem>()
        .FirstOrDefault(item => string.Equals(item.Tag?.ToString(), tag, StringComparison.OrdinalIgnoreCase));
    private static string SelectedTag(ComboBox box, string fallback) => (box.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? fallback;
    private static JsonSerializerOptions PrettyOptions { get; } = new() { WriteIndented = true };
}
