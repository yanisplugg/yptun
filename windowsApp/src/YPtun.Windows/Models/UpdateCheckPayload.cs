namespace YPtun.WinUI.Models;

public sealed record UpdateCheckPayload(
    string Version,
    string HtmlUrl,
    string AssetName,
    string DownloadUrl,
    long? SizeBytes,
    string? Sha256,
    bool IsUpdateAvailable);

public sealed record TextPayload(string Text);
