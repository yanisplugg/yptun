using System.IO.Pipes;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace YPtun.WinUI.Services;

public sealed class SingleInstanceService : IDisposable
{
    private const int LegacySingleInstancePort = 47_638;
    private const string ActivationPipeName = "YPtun.WinUI3.Activation";
    private const int MaximumActivationLength = 64 * 1024;
    private readonly Mutex _mutex = new(false, @"Local\YPtun.WinUI3");
    private TcpListener? _listener;
    private CancellationTokenSource? _listenerCancellation;

    public bool IsOwner { get; private set; }

    public event EventHandler<IReadOnlyList<string>>? ActivationReceived;

    public SingleInstanceService()
    {
        TryAcquire();
    }

    public bool TryAcquire()
    {
        if (IsOwner)
        {
            return true;
        }

        try
        {
            IsOwner = _mutex.WaitOne(0);
        }
        catch (AbandonedMutexException)
        {
            IsOwner = true;
        }
        catch (UnauthorizedAccessException)
        {
            IsOwner = false;
        }
        if (!IsOwner)
        {
            return false;
        }

        try
        {
            _listener = new TcpListener(IPAddress.Loopback, LegacySingleInstancePort);
            _listener.Start();
            _listenerCancellation = new CancellationTokenSource();
            _ = DrainLegacyShowRequestsAsync(_listener, _listenerCancellation.Token);
            _ = DrainActivationPipeAsync(_listenerCancellation.Token);
            return true;
        }
        catch (SocketException)
        {
            _listener = null;
            _mutex.ReleaseMutex();
            IsOwner = false;
            return false;
        }
    }

    public void Release()
    {
        if (!IsOwner)
        {
            return;
        }

        _listenerCancellation?.Cancel();
        _listener?.Stop();
        _listener = null;
        _listenerCancellation?.Dispose();
        _listenerCancellation = null;
        _mutex.ReleaseMutex();
        IsOwner = false;
    }

    public void Dispose()
    {
        Release();
        _mutex.Dispose();
    }

    public async Task ForwardActivationAsync(
        IEnumerable<string> activationArguments,
        CancellationToken cancellationToken = default)
    {
        var arguments = activationArguments
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Take(32)
            .Select(value => value.Trim())
            .ToArray();
        if (IsOwner)
        {
            ActivationReceived?.Invoke(this, arguments);
            return;
        }

        if (await TryForwardToPipeAsync(arguments, cancellationToken))
        {
            return;
        }

        await ForwardToLegacyOwnerAsync(arguments, cancellationToken);
    }

    private static async Task<bool> TryForwardToPipeAsync(
        IReadOnlyList<string> arguments,
        CancellationToken cancellationToken)
    {
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(3));
            await using var client = new NamedPipeClientStream(
                ".",
                ActivationPipeName,
                PipeDirection.Out,
                PipeOptions.Asynchronous);
            await client.ConnectAsync(timeout.Token);
            await using var writer = new StreamWriter(client, new UTF8Encoding(false), leaveOpen: true)
            {
                AutoFlush = true,
            };
            var payload = JsonSerializer.Serialize(arguments, CoreJson.Options);
            if (payload.Length > MaximumActivationLength)
            {
                return false;
            }
            await writer.WriteLineAsync(payload.AsMemory(), timeout.Token);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static async Task ForwardToLegacyOwnerAsync(
        IReadOnlyList<string> arguments,
        CancellationToken cancellationToken)
    {
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(3));
            using var client = new TcpClient();
            await client.ConnectAsync(IPAddress.Loopback, LegacySingleInstancePort, timeout.Token);
            await using var writer = new StreamWriter(client.GetStream(), new UTF8Encoding(false))
            {
                AutoFlush = true,
            };
            var link = arguments.FirstOrDefault(value =>
                value.Contains("://", StringComparison.Ordinal) && !value.Any(char.IsWhiteSpace));
            await writer.WriteLineAsync(link is null ? "show" : $"show {link}");
        }
        catch
        {
            // The owner may be shutting down. A secondary instance still exits rather than racing it.
        }
    }

    private async Task DrainActivationPipeAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await using var server = new NamedPipeServerStream(
                    ActivationPipeName,
                    PipeDirection.In,
                    1,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(cancellationToken);
                using var reader = new StreamReader(server, Encoding.UTF8, leaveOpen: true);
                var payload = await reader.ReadLineAsync(cancellationToken);
                if (payload is null || payload.Length > MaximumActivationLength)
                {
                    continue;
                }
                try
                {
                    var arguments = JsonSerializer.Deserialize<string[]>(payload, CoreJson.Options);
                    if (arguments is not null && arguments.Length <= 32 && arguments.All(value => value is not null))
                    {
                        ActivationReceived?.Invoke(this, arguments);
                    }
                }
                catch (JsonException)
                {
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
        catch (IOException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private async Task DrainLegacyShowRequestsAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                using var client = await listener.AcceptTcpClientAsync(cancellationToken);
                try
                {
                    using var reader = new StreamReader(client.GetStream(), Encoding.UTF8);
                    var line = (await reader.ReadLineAsync(cancellationToken))?.Trim();
                    if (line == "show")
                    {
                        ActivationReceived?.Invoke(this, Array.Empty<string>());
                        continue;
                    }
                    if (line?.StartsWith("show ", StringComparison.Ordinal) == true)
                    {
                        var link = line[5..];
                        if (link.Length <= MaximumActivationLength
                            && link.Contains("://", StringComparison.Ordinal)
                            && !link.Any(char.IsWhiteSpace))
                        {
                            ActivationReceived?.Invoke(this, new[] { link });
                        }
                    }
                }
                catch (IOException)
                {
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (SocketException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (ObjectDisposedException)
        {
        }
    }
}
