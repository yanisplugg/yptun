using System.ComponentModel;
using System.Runtime.InteropServices;
using YPtun.WinUI.Models;

namespace YPtun.WinUI.Services;

/// <summary>Registers one optional system-wide shortcut and receives WM_HOTKEY through a safe HWND subclass.</summary>
public sealed class GlobalHotKeyService : IDisposable
{
    private const int HotKeyId = 0x5950;
    private const uint WmHotKey = 0x0312;
    private const uint ModNoRepeat = 0x4000;
    private const nuint SubclassId = 0x59505455;

    private readonly nint _window;
    private readonly Func<Task> _action;
    private readonly Action<Exception> _onError;
    private readonly SubclassProc _windowProc;
    private readonly bool _subclassed;
    private bool _disposed;

    public HotKeyBinding? Binding { get; private set; }

    public bool IsRegistered { get; private set; }

    public GlobalHotKeyService(nint window, Func<Task> action, Action<Exception> onError)
    {
        _window = window;
        _action = action;
        _onError = onError;
        _windowProc = WndProc;
        _subclassed = SetWindowSubclass(_window, _windowProc, SubclassId, 0);
    }

    public bool SetBinding(HotKeyBinding? binding, out string? error)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (IsRegistered)
        {
            UnregisterHotKey(_window, HotKeyId);
            IsRegistered = false;
        }

        Binding = binding is { IsValid: true } ? binding.Normalized() : null;
        if (Binding is null)
        {
            error = null;
            return true;
        }
        if (!_subclassed)
        {
            error = new Win32Exception(
                Marshal.GetLastWin32Error(),
                "Не удалось подключить обработчик горячей клавиши").Message;
            Binding = null;
            return false;
        }

        IsRegistered = RegisterHotKey(
            _window,
            HotKeyId,
            Binding.Modifiers | ModNoRepeat,
            Binding.VirtualKey);
        if (IsRegistered)
        {
            error = null;
            return true;
        }

        var code = Marshal.GetLastWin32Error();
        error = new Win32Exception(code).Message;
        Binding = null;
        return false;
    }

    private nint WndProc(
        nint window,
        uint message,
        nint wParam,
        nint lParam,
        nuint subclassId,
        nuint referenceData)
    {
        if (message == WmHotKey && wParam == HotKeyId)
        {
            _ = InvokeActionAsync();
            return 0;
        }
        return DefSubclassProc(window, message, wParam, lParam);
    }

    private async Task InvokeActionAsync()
    {
        try
        {
            await _action();
        }
        catch (Exception exception)
        {
            _onError(exception);
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        if (IsRegistered)
        {
            UnregisterHotKey(_window, HotKeyId);
            IsRegistered = false;
        }
        if (_subclassed)
        {
            RemoveWindowSubclass(_window, _windowProc, SubclassId);
        }
        GC.KeepAlive(_windowProc);
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate nint SubclassProc(
        nint window,
        uint message,
        nint wParam,
        nint lParam,
        nuint subclassId,
        nuint referenceData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(
        nint window,
        SubclassProc callback,
        nuint subclassId,
        nuint referenceData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(nint window, SubclassProc callback, nuint subclassId);

    [DllImport("comctl32.dll")]
    private static extern nint DefSubclassProc(nint window, uint message, nint wParam, nint lParam);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RegisterHotKey(nint window, int id, uint modifiers, uint key);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnregisterHotKey(nint window, int id);
}
