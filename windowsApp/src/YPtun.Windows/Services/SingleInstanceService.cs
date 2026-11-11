using System.Net;
using System.Net.Sockets;

namespace YPtun.WinUI.Services;

public sealed class SingleInstanceService : IDisposable
{
    private const int LegacySingleInstancePort = 47_638;
    private readonly Mutex _mutex = new(false, @"Local\YPtun.WinUI3");
    private TcpListener? _listener;
    private CancellationTokenSource? _listenerCancellation;

    public bool IsOwner { get; private set; }

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
        _listenerCancellation?.Dispose();
        _listenerCancellation = null;
        _listener?.Stop();
        _listener = null;
        _mutex.ReleaseMutex();
        IsOwner = false;
    }

    public void Dispose()
    {
        Release();
        _mutex.Dispose();
    }

    private static async Task DrainLegacyShowRequestsAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                using var client = await listener.AcceptTcpClientAsync(cancellationToken);
                // The Compose client sends "show" or "show <link>" here. Holding the same port is
                // enough to keep both shells from controlling one VPN at once; activation forwarding
                // is added when deep-link handling reaches parity.
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
