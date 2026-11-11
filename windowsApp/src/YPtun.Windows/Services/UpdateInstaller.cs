using System.Diagnostics;
using System.Security.Cryptography;
using YPtun.WinUI.Models;

namespace YPtun.WinUI.Services;

public static class UpdateInstaller
{
    public static async Task DownloadAndInstallAsync(UpdateCheckPayload update, CancellationToken cancellationToken = default)
    {
        if (!update.IsUpdateAvailable)
        {
            return;
        }
        if (!Uri.TryCreate(update.DownloadUrl, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps)
        {
            throw new InvalidOperationException("Ссылка обновления не использует HTTPS");
        }

        var directory = Path.Combine(Path.GetTempPath(), "YPtun", "updates", update.Version);
        Directory.CreateDirectory(directory);
        var file = Path.Combine(directory, Path.GetFileName(update.AssetName));
        using (var client = new HttpClient { Timeout = TimeSpan.FromMinutes(15) })
        {
            client.DefaultRequestHeaders.UserAgent.ParseAdd("YPtun/4.0.0");
            await using var source = await client.GetStreamAsync(uri, cancellationToken);
            await using var destination = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.None);
            await source.CopyToAsync(destination, cancellationToken);
        }

        if (!string.IsNullOrWhiteSpace(update.Sha256))
        {
            await using var input = File.OpenRead(file);
            var actual = Convert.ToHexString(await SHA256.HashDataAsync(input, cancellationToken));
            var expected = update.Sha256.Trim().Replace("sha256:", "", StringComparison.OrdinalIgnoreCase);
            if (!actual.Equals(expected, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(file);
                throw new InvalidDataException("SHA-256 загруженного обновления не совпадает с GitHub");
            }
        }

        if (!await HasValidAuthenticodeSignatureAsync(file, cancellationToken))
        {
            File.Delete(file);
            throw new InvalidDataException("Обновление не имеет действительной подписи Authenticode");
        }

        var portable = Environment.GetEnvironmentVariable("YPTUN_PORTABLE_EXE");
        if (!string.IsNullOrWhiteSpace(portable) && File.Exists(portable))
        {
            StartPortableReplacement(file, Path.GetFullPath(portable));
        }
        else
        {
            Process.Start(new ProcessStartInfo(file)
            {
                UseShellExecute = true,
                Verb = "runas",
            });
        }
    }

    private static async Task<bool> HasValidAuthenticodeSignatureAsync(string file, CancellationToken cancellationToken)
    {
        var escaped = file.Replace("'", "''", StringComparison.Ordinal);
        using var process = Process.Start(new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            ArgumentList =
            {
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
                $"$s=Get-AuthenticodeSignature -LiteralPath '{escaped}'; if($s.Status -eq 'Valid'){{exit 0}}else{{Write-Error $s.Status; exit 1}}",
            },
        }) ?? throw new InvalidOperationException("Не удалось запустить проверку цифровой подписи");
        await process.WaitForExitAsync(cancellationToken);
        return process.ExitCode == 0;
    }

    private static void StartPortableReplacement(string downloaded, string carrier)
    {
        var script = Path.Combine(Path.GetTempPath(), $"yptun-update-{Guid.NewGuid():N}.ps1");
        var source = downloaded.Replace("'", "''", StringComparison.Ordinal);
        var target = carrier.Replace("'", "''", StringComparison.Ordinal);
        var pid = Environment.ProcessId;
        File.WriteAllText(script, $$"""
            $ErrorActionPreference = 'Stop'
            try { Wait-Process -Id {{pid}} -Timeout 120 -ErrorAction SilentlyContinue } catch {}
            Move-Item -LiteralPath '{{source}}' -Destination '{{target}}' -Force
            Start-Process -FilePath '{{target}}'
            Remove-Item -LiteralPath $PSCommandPath -Force
            """);
        Process.Start(new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            ArgumentList = { "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script },
        });
    }
}
