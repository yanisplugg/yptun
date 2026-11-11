using System.Diagnostics;
using System.Security.Principal;

namespace YPtun.WinUI.Services;

public static class ElevationService
{
    public static bool IsElevated
    {
        get
        {
            using var identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
    }

    public static bool RestartElevated(params string[] arguments)
    {
        var executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable))
        {
            throw new InvalidOperationException("Путь к YPtun.exe не найден");
        }

        var start = new ProcessStartInfo(executable)
        {
            UseShellExecute = true,
            Verb = "runas",
            WorkingDirectory = AppContext.BaseDirectory,
            Arguments = string.Join(' ', arguments.Select(QuoteArgument)),
        };
        return Process.Start(start) is not null;
    }

    private static string QuoteArgument(string value) => value.Any(char.IsWhiteSpace)
        ? $"\"{value.Replace("\"", "\\\"")}\""
        : value;
}
