using WinVirtualKey = Windows.System.VirtualKey;

namespace YPtun.WinUI.Models;

public sealed record HotKeyBinding(uint Modifiers, uint VirtualKey, string Label)
{
    public const uint Alt = 0x0001;
    public const uint Control = 0x0002;
    public const uint Shift = 0x0004;
    public const uint Windows = 0x0008;

    public bool IsValid => VirtualKey != 0
        && (Modifiers & (Alt | Control | Shift | Windows)) != 0
        && (Modifiers & ~(Alt | Control | Shift | Windows)) == 0
        && !IsModifierKey((WinVirtualKey)VirtualKey);

    public static HotKeyBinding? Create(WinVirtualKey key, bool control, bool alt, bool shift, bool windows)
    {
        if (IsModifierKey(key))
        {
            return null;
        }

        uint modifiers = 0;
        if (control) modifiers |= Control;
        if (alt) modifiers |= Alt;
        if (shift) modifiers |= Shift;
        if (windows) modifiers |= Windows;
        if (modifiers == 0)
        {
            return null;
        }

        return new HotKeyBinding(modifiers, (uint)key, Format(modifiers, key));
    }

    public HotKeyBinding Normalized() => this with
    {
        Label = string.IsNullOrWhiteSpace(Label) ? Format(Modifiers, (WinVirtualKey)VirtualKey) : Label.Trim(),
    };

    public static string Format(uint modifiers, WinVirtualKey key)
    {
        var parts = new List<string>(5);
        if ((modifiers & Control) != 0) parts.Add("Ctrl");
        if ((modifiers & Alt) != 0) parts.Add("Alt");
        if ((modifiers & Shift) != 0) parts.Add("Shift");
        if ((modifiers & Windows) != 0) parts.Add("Win");
        parts.Add(KeyLabel(key));
        return string.Join('+', parts);
    }

    private static string KeyLabel(WinVirtualKey key) => key switch
    {
        >= WinVirtualKey.Number0 and <= WinVirtualKey.Number9 =>
            ((char)('0' + (int)key - (int)WinVirtualKey.Number0)).ToString(),
        >= WinVirtualKey.A and <= WinVirtualKey.Z => key.ToString(),
        WinVirtualKey.Space => "Space",
        WinVirtualKey.Enter => "Enter",
        WinVirtualKey.Tab => "Tab",
        WinVirtualKey.Escape => "Esc",
        WinVirtualKey.Delete => "Delete",
        WinVirtualKey.Insert => "Insert",
        WinVirtualKey.Home => "Home",
        WinVirtualKey.End => "End",
        WinVirtualKey.PageUp => "Page Up",
        WinVirtualKey.PageDown => "Page Down",
        WinVirtualKey.Left => "Left",
        WinVirtualKey.Right => "Right",
        WinVirtualKey.Up => "Up",
        WinVirtualKey.Down => "Down",
        _ => key.ToString(),
    };

    private static bool IsModifierKey(WinVirtualKey key) => key is
        WinVirtualKey.Control or WinVirtualKey.LeftControl or WinVirtualKey.RightControl or
        WinVirtualKey.Menu or WinVirtualKey.LeftMenu or WinVirtualKey.RightMenu or
        WinVirtualKey.Shift or WinVirtualKey.LeftShift or WinVirtualKey.RightShift or
        WinVirtualKey.LeftWindows or WinVirtualKey.RightWindows or WinVirtualKey.None;
}
