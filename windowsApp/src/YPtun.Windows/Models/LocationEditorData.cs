using System.Text.Json;

namespace YPtun.WinUI.Models;

public sealed record LocationEditorData(
    string StorageId,
    JsonElement Location,
    bool IsNew,
    bool IsSubscription);
