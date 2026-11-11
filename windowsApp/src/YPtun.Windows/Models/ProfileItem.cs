namespace YPtun.WinUI.Models;

public sealed record ProfileItem(
    string Id,
    string Name,
    string Subtitle,
    string Engine,
    bool IsComplete,
    bool IsSubscription,
    string? SubscriptionName);
