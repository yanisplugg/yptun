#!/usr/bin/env python3
"""Пакует расширение в один файл: zip + самораспаковывающийся установщик .bat.

    python chrome-extension/pack.py [куда]        # по умолчанию: chrome-extension/dist

.crx смысла не имеет: Chrome с 73-й версии ставит только пакеты, подписанные Web Store
(«CRX_REQUIRED_PROOF_MISSING»), а флаг --load-extension вырезан из Chrome 137. Бесплатный
способ без магазина и без сайта остался один — «Загрузить распакованное», и .bat сводит его
к трём кликам: сам находит браузер, сам выбирает куда распаковать, кладёт путь в буфер.
"""
import base64, io, json, os, sys, zipfile

SRC = os.path.dirname(os.path.abspath(__file__))
SKIP = {"pack.py", "link.test.mjs", "dist", ".gitignore"}

# Тело установщика. Едет внутри .bat как base64, путь к самому .bat приходит через YPTUN_BAT.
INSTALLER_PS = r'''
$ErrorActionPreference = 'Stop'
$bat = $env:YPTUN_BAT

function Find-Browser {
  # Порядок = приоритет. У каждого браузера своя страница расширений.
  $list = @(
    @{ n = 'Google Chrome';    e = 'chrome.exe';  u = 'chrome://extensions';
       p = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
             "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
             "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe") },
    @{ n = 'Microsoft Edge';   e = 'msedge.exe';  u = 'edge://extensions';
       p = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
             "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") },
    @{ n = 'Yandex Browser';   e = 'browser.exe'; u = 'browser://extensions';
       p = @("$env:LOCALAPPDATA\Yandex\YandexBrowser\Application\browser.exe",
             "$env:ProgramFiles\Yandex\YandexBrowser\Application\browser.exe") },
    @{ n = 'Brave';            e = 'brave.exe';   u = 'brave://extensions';
       p = @("$env:ProgramFiles\BraveSoftware\Brave-Browser\Application\brave.exe",
             "$env:LOCALAPPDATA\BraveSoftware\Brave-Browser\Application\brave.exe") },
    @{ n = 'Vivaldi';          e = 'vivaldi.exe'; u = 'vivaldi://extensions';
       p = @("$env:LOCALAPPDATA\Vivaldi\Application\vivaldi.exe",
             "$env:ProgramFiles\Vivaldi\Application\vivaldi.exe") },
    @{ n = 'Opera';            e = 'opera.exe';   u = 'opera://extensions';
       p = @("$env:LOCALAPPDATA\Programs\Opera\opera.exe",
             "$env:ProgramFiles\Opera\opera.exe") },
    @{ n = 'Chromium';         e = 'chrome.exe';  u = 'chrome://extensions';
       p = @("$env:LOCALAPPDATA\Chromium\Application\chrome.exe") }
  )
  $appPaths = @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths',
                'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths',
                'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths')
  foreach ($b in $list) {
    foreach ($path in $b.p) { if ($path -and (Test-Path -LiteralPath $path)) { return ($b + @{ exe = $path }) } }
    foreach ($root in $appPaths) {                    # запасной путь: где Windows сама помнит браузер
      $v = (Get-ItemProperty -Path "$root\$($b.e)" -EA SilentlyContinue).'(default)'
      if ($v -and (Test-Path -LiteralPath $v)) { return ($b + @{ exe = $v }) }
    }
  }
  return $null
}

function Pick-Dir {
  # Первая папка, в которую реально удаётся писать: профиль -> рядом с .bat -> временная.
  foreach ($d in @("$env:LOCALAPPDATA\YPtun\chrome-extension",
                   (Join-Path (Split-Path -Parent $bat) 'YPtun-chrome-extension'),
                   "$env:TEMP\YPtun\chrome-extension")) {
    try {
      New-Item -ItemType Directory -Force -Path $d | Out-Null
      $probe = Join-Path $d '.write-test'
      Set-Content -LiteralPath $probe -Value 1
      Remove-Item -LiteralPath $probe -Force
      return $d
    } catch { }
  }
  throw 'не нашёл ни одной папки с правом записи'
}

$lines = Get-Content -LiteralPath $bat
$i = ($lines | Select-String -SimpleMatch (':::PAY' + 'LOAD:::') | Select-Object -First 1).LineNumber
$b64 = ($lines[$i..($lines.Count - 1)] | Where-Object { $_ -match '\S' }) -join ''
$zip = Join-Path $env:TEMP 'yptun-ext.zip'
[IO.File]::WriteAllBytes($zip, [Convert]::FromBase64String($b64))

$dest = Pick-Dir
Get-ChildItem -LiteralPath $dest -Force | Remove-Item -Recurse -Force -EA SilentlyContinue
Expand-Archive -LiteralPath $zip -DestinationPath $dest -Force
Remove-Item -LiteralPath $zip -Force
try { Set-Clipboard -Value $dest } catch { }

Write-Host ''
Write-Host "  Распаковано: $dest"
Write-Host '  Путь скопирован в буфер обмена.'
$b = Find-Browser
if ($b) {
  Write-Host "  Браузер: $($b.n)"
  Write-Host ''
  Write-Host '  Осталось три клика в открывшейся вкладке:'
  Write-Host '    1. Включить "Режим разработчика" (тумблер справа сверху)'
  Write-Host '    2. Нажать "Загрузить распакованное"'
  Write-Host '    3. В окне выбора папки нажать Ctrl+V, затем Enter'
  Start-Process -FilePath $b.exe -ArgumentList $b.u
} else {
  Write-Host '  Браузер на базе Chromium не найден.'
  Write-Host '  Откройте его страницу расширений вручную, включите "Режим разработчика",'
  Write-Host '  нажмите "Загрузить распакованное" и вставьте путь (Ctrl+V).'
}
'''

BAT = """@echo off
chcp 65001 >nul
title YPtun VPN - установка расширения Chrome
set "YPTUN_BAT=%~f0"
echo.
echo   YPtun VPN - расширение для браузера, версия {version}
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{script}')))"
if errorlevel 1 (
  echo.
  echo   Не удалось установить.
)
echo.
pause
exit /b
:::PAYLOAD:::
"""


def files():
    for root, dirs, names in os.walk(SRC):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for n in names:
            path = os.path.join(root, n)
            rel = os.path.relpath(path, SRC).replace("\\", "/")
            if rel.split("/")[0] not in SKIP:
                yield path, rel


def main(out_dir):
    version = json.load(io.open(os.path.join(SRC, "manifest.json"), encoding="utf-8"))["version"]
    os.makedirs(out_dir, exist_ok=True)
    zip_path = os.path.join(out_dir, "yptun-chrome-%s.zip" % version)

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for path, rel in files():
            z.write(path, rel)

    script = base64.b64encode(INSTALLER_PS.encode("utf-8")).decode()
    if len(script) > 7500:  # cmd не переваривает строку длиннее ~8191 символа
        raise SystemExit("установщик разросся: %d символов base64" % len(script))

    payload = base64.b64encode(io.open(zip_path, "rb").read()).decode()
    payload = "\n".join(payload[i:i + 120] for i in range(0, len(payload), 120))
    bat_path = os.path.join(out_dir, "YPtun-chrome-install.bat")
    io.open(bat_path, "w", encoding="utf-8", newline="\r\n").write(
        BAT.format(version=version, script=script) + payload + "\n")

    for p in (zip_path, bat_path):
        print("%s  %d bytes" % (p, os.path.getsize(p)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(SRC, "dist"))
