namespace YPtun.WinUI.Services;

public sealed class AppPaths
{
    public bool IsPortable { get; }

    public string DataDirectory { get; }

    public string NativeDirectory { get; } = Path.Combine(AppContext.BaseDirectory, "Native");

    public string LogsFile => Path.Combine(DataDirectory, "yptun-winui.log");

    public string HotKeySettingsFile => Path.Combine(DataDirectory, "windows_hotkey.json");

    public AppPaths()
    {
        var portableExecutable = Environment.GetEnvironmentVariable("YPTUN_PORTABLE_EXE");
        var portableRoot = !string.IsNullOrWhiteSpace(portableExecutable) && File.Exists(portableExecutable)
            ? Path.GetDirectoryName(Path.GetFullPath(portableExecutable))
            : null;
        IsPortable = portableRoot is not null || File.Exists(Path.Combine(AppContext.BaseDirectory, ".portable"));
        DataDirectory = IsPortable
            ? Path.Combine(portableRoot ?? AppContext.BaseDirectory, "Data")
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "YPtun");
        Directory.CreateDirectory(DataDirectory);
    }
}
