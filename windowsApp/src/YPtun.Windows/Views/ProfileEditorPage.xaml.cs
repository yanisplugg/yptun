using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using YPtun.WinUI.Models;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI.Views;

public sealed partial class ProfileEditorPage : Page
{
    private static readonly JsonSerializerOptions PrettyJson = new() { WriteIndented = true };

    private AppState? _state;
    private string? _profileId;
    private JsonObject? _location;
    private bool _syncing;

    public ProfileEditorPage()
    {
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is not ProfileEditorNavigation navigation)
        {
            ShowError("Не переданы параметры редактора");
            return;
        }

        _state = navigation.State;
        _profileId = navigation.ProfileId;
        var editor = await _state.GetProfileEditorAsync(_profileId);
        LoadingOverlay.Visibility = Visibility.Collapsed;
        if (editor is null)
        {
            return;
        }

        _profileId = editor.IsNew ? null : editor.StorageId;
        TitleText.Text = editor.IsNew ? "Новая конфигурация" : "Редактирование конфигурации";
        if (editor.IsSubscription)
        {
            MessageBar.Title = "Конфигурация из подписки";
            MessageBar.Message = "Локальные изменения могут быть заменены при следующем обновлении подписки.";
            MessageBar.Severity = InfoBarSeverity.Warning;
            MessageBar.IsOpen = true;
        }

        _location = JsonNode.Parse(editor.Location.GetRawText())?.AsObject() ?? new JsonObject();
        RefreshControls();
    }

    private void RefreshControls()
    {
        if (_location is null)
        {
            return;
        }
        _syncing = true;
        NameBox.Text = _location["name"]?.GetValue<string>() ?? string.Empty;
        var engine = _location["engine"]?.GetValue<string>() ?? "stealth";
        EngineBox.SelectedItem = EngineBox.Items
            .OfType<ComboBoxItem>()
            .FirstOrDefault(item => string.Equals(item.Tag?.ToString(), engine, StringComparison.OrdinalIgnoreCase));
        JsonBox.Text = _location.ToJsonString(PrettyJson);
        _syncing = false;
    }

    private void OnCommonFieldChanged(object sender, object e)
    {
        if (_syncing || _location is null)
        {
            return;
        }
        _location["name"] = NameBox.Text;
        _location["engine"] = (EngineBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "stealth";
        _syncing = true;
        JsonBox.Text = _location.ToJsonString(PrettyJson);
        _syncing = false;
    }

    private void OnFormat(object sender, RoutedEventArgs e)
    {
        try
        {
            _location = (JsonNode.Parse(JsonBox.Text) ?? throw new JsonException("Пустой JSON")).AsObject();
            RefreshControls();
        }
        catch (Exception exception)
        {
            ShowError(exception.Message);
        }
    }

    private async void OnSave(object sender, RoutedEventArgs e)
    {
        if (_state is null)
        {
            return;
        }
        try
        {
            _location = (JsonNode.Parse(JsonBox.Text) ?? throw new JsonException("Пустой JSON")).AsObject();
            _location["name"] = NameBox.Text.Trim();
            _location["engine"] = (EngineBox.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "stealth";
            if (await _state.SaveProfileAsync(_profileId, _location.ToJsonString()))
            {
                App.CurrentApp.MainWindow?.OpenProfiles();
            }
        }
        catch (Exception exception)
        {
            ShowError(exception.Message);
        }
    }

    private void OnCancel(object sender, RoutedEventArgs e) => App.CurrentApp.MainWindow?.OpenProfiles();

    private void ShowError(string message)
    {
        LoadingOverlay.Visibility = Visibility.Collapsed;
        MessageBar.Title = "Не удалось открыть конфигурацию";
        MessageBar.Message = message;
        MessageBar.Severity = InfoBarSeverity.Error;
        MessageBar.IsOpen = true;
    }
}
