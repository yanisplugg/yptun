namespace YPtun.WinUI.Models;

public sealed record SessionDiagnostics(
    string ActiveEngine,
    string ConnectionMode,
    bool EngineAlive,
    ulong ConnectedSinceEpochMs,
    bool InCoreTun,
    bool ExternalTun,
    ulong BypassRouteCount,
    uint WatchdogProbeFailures);
