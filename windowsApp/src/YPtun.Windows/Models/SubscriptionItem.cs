namespace YPtun.WinUI.Models;

public sealed record SubscriptionItem(
    string Url,
    string Name,
    string CustomName,
    string CascadeLink,
    int UpdateIntervalHours,
    bool AutoUpdateEnabled,
    int LocationCount,
    long? ExpiresAtEpochMs)
{
    public string DisplayName => string.IsNullOrWhiteSpace(CustomName)
        ? string.IsNullOrWhiteSpace(Name) ? Url : Name
        : CustomName;

    public string Detail
    {
        get
        {
            var servers = LocationCount switch
            {
                1 => "1 сервер",
                >= 2 and <= 4 => $"{LocationCount} сервера",
                _ => $"{LocationCount} серверов",
            };
            var schedule = AutoUpdateEnabled
                ? $"обновление каждые {UpdateIntervalHours} ч"
                : "автообновление выключено";
            var expiry = ExpiresAtEpochMs is > 0
                ? $" · до {DateTimeOffset.FromUnixTimeMilliseconds(ExpiresAtEpochMs.Value):d}"
                : string.Empty;
            return $"{servers} · {schedule}{expiry}";
        }
    }
}
