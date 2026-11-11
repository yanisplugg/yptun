using System.Text.Json;

namespace YPtun.WinUI.Models;

public sealed record SettingsSnapshot(
    JsonElement Traffic,
    JsonElement Routing,
    JsonElement RoutingProfiles,
    JsonElement SplitTunnel,
    JsonElement AppBehavior,
    JsonElement Socks,
    JsonElement Updates);
