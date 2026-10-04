$ErrorActionPreference = 'Stop'
# Пересобирает docs/latest.json (запасной источник для страницы загрузки, когда api.github.com недоступен).
# Запускать после публикации релиза, затем commit + push. $env:GITHUB_TOKEN необязателен (поднимает лимит API).
$h = @{ Accept = 'application/vnd.github+json' }
if ($env:GITHUB_TOKEN) { $h.Authorization = "Bearer $env:GITHUB_TOKEN" }
$r = Invoke-RestMethod https://api.github.com/repos/yanisplugg/olcvpn-client/releases/latest -Headers $h
$out = [ordered]@{
    name = $r.name; tag_name = $r.tag_name; html_url = $r.html_url
    assets = @($r.assets | ForEach-Object { [ordered]@{ name = $_.name; browser_download_url = $_.browser_download_url } })
}
$path = Join-Path $PSScriptRoot '..\..\docs\latest.json'
[IO.File]::WriteAllText($path, ($out | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))
"$($r.tag_name): $($out.assets.Count) assets -> $path"
