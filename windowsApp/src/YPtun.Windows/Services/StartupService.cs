using Microsoft.Win32;

namespace YPtun.WinUI.Services;

public static class StartupService
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "YPtun";

    public static void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true)
            ?? throw new InvalidOperationException("Не удалось открыть автозапуск Windows");
        if (enabled)
        {
            var executable = Environment.GetEnvironmentVariable("YPTUN_PORTABLE_EXE");
            if (string.IsNullOrWhiteSpace(executable) || !File.Exists(executable))
            {
                executable = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "YPtun.exe");
            }
            key.SetValue(ValueName, $"\"{executable}\" --connect", RegistryValueKind.String);
        }
        else
        {
            key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }
}
