namespace YPtun.WinUI.Models;

public sealed record CoreSnapshot(
    string Status,
    string StatusLabel,
    string StatusDetail,
    bool IsConnected,
    bool RuntimeAvailable,
    string ConnectionMode,
    string? ActiveProfileId,
    IReadOnlyList<ProfileItem> Profiles,
    IReadOnlyList<SubscriptionItem> Subscriptions,
    SessionDiagnostics Session,
    string TelegramProxyStatus,
    string TelegramProxyDetail,
    IReadOnlyList<string> Logs);
