using System.Text.Json;

namespace YPtun.WinUI.Services;

/// <summary>
/// Managed entry point for the native Rust core. Until the native bridge is staged, the client
/// remains available in shell mode so the WinUI project can be developed and diagnosed by itself.
/// </summary>
public sealed class CoreClient : IDisposable
{
    private readonly AppPaths _paths;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly bool _nativeAvailable;
    private bool _disposed;

    public bool IsNativeAvailable => _nativeAvailable;

    public string? InitializationError { get; }

    public CoreClient(AppPaths paths)
    {
        _paths = paths;
        if (!File.Exists(Path.Combine(_paths.NativeDirectory, NativeCore.LibraryFileName)))
        {
            return;
        }

        try
        {
            NativeCore.Initialize(paths);
            _nativeAvailable = true;
        }
        catch (Exception exception)
        {
            InitializationError = exception.Message;
        }
    }

    public async Task<CoreResponse<T>> SendAsync<T>(object request, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!IsNativeAvailable)
            {
                return CoreResponse<T>.Failure("Нативное ядро ещё не собрано. Запустите windowsApp/build.ps1.");
            }

            var json = JsonSerializer.Serialize(request, CoreJson.Options);
            var response = await Task.Run(() => NativeCore.Send(json), cancellationToken).ConfigureAwait(false);
            return JsonSerializer.Deserialize<CoreResponse<T>>(response, CoreJson.Options)
                ?? CoreResponse<T>.Failure("Пустой ответ нативного ядра");
        }
        catch (Exception exception)
        {
            return CoreResponse<T>.Failure(exception.Message);
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _gate.Wait();
        try
        {
            NativeCore.Dispose();
        }
        finally
        {
            _gate.Release();
        }
        _gate.Dispose();
    }
}

public sealed record CoreResponse<T>(bool Ok, T? Data, string? Error)
{
    public static CoreResponse<T> Failure(string error) => new(false, default, error);
}

internal static class CoreJson
{
    public static JsonSerializerOptions Options { get; } = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true,
        WriteIndented = false,
    };
}
