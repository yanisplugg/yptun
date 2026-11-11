using System.ComponentModel;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using Microsoft.UI.Dispatching;
using YPtun.WinUI.ViewModels;

namespace YPtun.WinUI.Services;

public sealed class TrayService : IDisposable
{
    private readonly AppState _state;
    private readonly DispatcherQueue _dispatcher;
    private readonly System.Windows.Forms.NotifyIcon _icon;
    private readonly System.Windows.Forms.ContextMenuStrip _menu;
    private readonly System.Windows.Forms.ToolStripMenuItem _statusItem;
    private readonly System.Windows.Forms.ToolStripMenuItem _connectItem;
    private readonly System.Drawing.Icon _baseIcon;
    private System.Drawing.Icon? _statusIcon;
    private int _iconState = -1;
    private bool _disposed;

    public TrayService(
        AppState state,
        Action showWindow,
        Func<Task> showMyIp,
        Func<Task> findFreeServers,
        Func<Task> configureHotKey,
        Action showSettings,
        Action exit)
    {
        _state = state;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _baseIcon = new System.Drawing.Icon(
            Path.Combine(AppContext.BaseDirectory, "Assets", "WindowsIcon.ico"));

        _statusItem = new System.Windows.Forms.ToolStripMenuItem { Enabled = false };
        _connectItem = Item(string.Empty, () => _state.ToggleConnectionAsync());
        var showItem = Item("Открыть YPtun", () =>
        {
            showWindow();
            return Task.CompletedTask;
        });
        var myIpItem = Item("Мой IP", showMyIp);
        var freeServersItem = Item("Бесплатные серверы", findFreeServers);
        var hotKeyItem = Item("Горячая клавиша", configureHotKey);
        var settingsItem = Item("Настройки", () =>
        {
            showSettings();
            return Task.CompletedTask;
        });
        var exitItem = Item("Выход", () =>
        {
            exit();
            return Task.CompletedTask;
        });

        _menu = new System.Windows.Forms.ContextMenuStrip();
        _menu.Items.AddRange([
            _statusItem,
            new System.Windows.Forms.ToolStripSeparator(),
            showItem,
            _connectItem,
            myIpItem,
            freeServersItem,
            hotKeyItem,
            settingsItem,
            new System.Windows.Forms.ToolStripSeparator(),
            exitItem,
        ]);
        _menu.Opening += (_, _) => RefreshText();

        _icon = new System.Windows.Forms.NotifyIcon
        {
            Text = "YPtun",
            Icon = _baseIcon,
            ContextMenuStrip = _menu,
            Visible = true,
        };
        _icon.DoubleClick += (_, _) => Dispatch(() =>
        {
            showWindow();
            return Task.CompletedTask;
        });
        _state.PropertyChanged += OnStatePropertyChanged;
        RefreshText();
    }

    public void RefreshText()
    {
        if (_disposed)
        {
            return;
        }

        var selectedName = _state.SelectedProfile?.Name;
        var status = _state.IsBusy
            ? (_state.IsConnected ? "Отключение…" : "Подключение…")
            : _state.ConnectionStatus;
        _statusItem.Text = _state.IsConnected && !string.IsNullOrWhiteSpace(selectedName)
            ? $"{status} · {Sanitize(selectedName)}"
            : status;
        _connectItem.Text = _state.IsConnected || _state.IsBusy ? "Отключить" : "Подключить";
        _connectItem.Enabled = _state.IsBusy || _state.IsConnected
            || (!_state.IsBusy && _state.NativeCoreAvailable && _state.SelectedProfile?.IsComplete == true);
        var tooltip = $"YPtun — {_statusItem.Text}";
        _icon.Text = tooltip[..Math.Min(63, tooltip.Length)];

        var iconState = _state.IsBusy ? 1 : _state.IsConnected ? 2 : 0;
        if (_iconState != iconState)
        {
            _iconState = iconState;
            var color = iconState switch
            {
                2 => System.Drawing.Color.FromArgb(52, 199, 89),
                1 => System.Drawing.Color.FromArgb(255, 159, 10),
                _ => System.Drawing.Color.FromArgb(142, 142, 147),
            };
            var next = CreateStatusIcon(color);
            _icon.Icon = next;
            _statusIcon?.Dispose();
            _statusIcon = next;
        }
    }

    public void ShowNotification(string title, string message)
    {
        _icon.BalloonTipTitle = title;
        _icon.BalloonTipText = message;
        _icon.ShowBalloonTip(4000);
    }

    private System.Windows.Forms.ToolStripMenuItem Item(string text, Func<Task> action)
    {
        var item = new System.Windows.Forms.ToolStripMenuItem(text);
        item.Click += (_, _) => Dispatch(action);
        return item;
    }

    private void OnStatePropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        _dispatcher.TryEnqueue(RefreshText);
    }

    private void Dispatch(Func<Task> action)
    {
        _dispatcher.TryEnqueue(async () =>
        {
            try
            {
                await action();
                RefreshText();
            }
            catch (Exception exception)
            {
                _state.RecordUiError(exception);
            }
        });
    }

    private System.Drawing.Icon CreateStatusIcon(System.Drawing.Color statusColor)
    {
        using var source = _baseIcon.ToBitmap();
        using var bitmap = new System.Drawing.Bitmap(32, 32, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using (var graphics = System.Drawing.Graphics.FromImage(bitmap))
        {
            graphics.Clear(System.Drawing.Color.Transparent);
            graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.DrawImage(source, 0, 0, 32, 32);
            graphics.FillEllipse(System.Drawing.Brushes.Black, 19, 19, 13, 13);
            using var brush = new System.Drawing.SolidBrush(statusColor);
            graphics.FillEllipse(brush, 21, 21, 9, 9);
        }

        var handle = bitmap.GetHicon();
        try
        {
            return (System.Drawing.Icon)System.Drawing.Icon.FromHandle(handle).Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    private static string Sanitize(string value) => new(value
        .Where(character => char.IsLetterOrDigit(character) || " -_.·()[]/".Contains(character))
        .Take(36)
        .ToArray());

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        _state.PropertyChanged -= OnStatePropertyChanged;
        _icon.Visible = false;
        _icon.Dispose();
        _menu.Dispose();
        _statusIcon?.Dispose();
        _baseIcon.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(nint icon);
}
