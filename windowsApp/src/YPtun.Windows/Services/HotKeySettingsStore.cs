using System.Text.Json;
using Microsoft.Win32;
using YPtun.WinUI.Models;

namespace YPtun.WinUI.Services;

public sealed class HotKeySettingsStore
{
    private const string LegacyRegistryPath = @"Software\JavaSoft\Prefs\org\olcbox\yptun";
    private readonly string _file;

    public HotKeySettingsStore(AppPaths paths)
    {
        _file = paths.HotKeySettingsFile;
    }

    public HotKeyBinding? Load()
    {
        if (File.Exists(_file))
        {
            try
            {
                var state = JsonSerializer.Deserialize<State>(File.ReadAllText(_file), CoreJson.Options);
                return state?.Binding is { IsValid: true } binding ? binding.Normalized() : null;
            }
            catch
            {
                return null;
            }
        }

        var migrated = LoadLegacyBinding();
        Save(migrated);
        return migrated;
    }

    public void Save(HotKeyBinding? binding)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
        var temporary = $"{_file}.{Environment.ProcessId}.tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(new State(1, binding), CoreJson.Options));
        File.Move(temporary, _file, true);
    }

    private static HotKeyBinding? LoadLegacyBinding()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(LegacyRegistryPath);
            if (key is null)
            {
                return null;
            }

            var virtualKey = ReadUInt32(key, "hotkey_vk");
            var modifiers = ReadUInt32(key, "hotkey_mod");
            if (virtualKey == 0 || modifiers == 0)
            {
                return null;
            }
            var label = ReadValue(key, "hotkey_label")?.ToString() ?? string.Empty;
            var binding = new HotKeyBinding(modifiers, virtualKey, label).Normalized();
            return binding.IsValid ? binding : null;
        }
        catch
        {
            return null;
        }
    }

    private static uint ReadUInt32(RegistryKey key, string name)
    {
        var value = ReadValue(key, name);
        return value switch
        {
            int number when number > 0 => (uint)number,
            long number when number > 0 && number <= uint.MaxValue => (uint)number,
            string text when uint.TryParse(text, out var number) => number,
            _ => 0,
        };
    }

    private static object? ReadValue(RegistryKey key, string name) =>
        key.GetValue(name) ?? key.GetValue($"/{name}");

    private sealed record State(int Version, HotKeyBinding? Binding);
}
