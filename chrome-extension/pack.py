#!/usr/bin/env python3
"""Пакует расширение в один файл: zip + самораспаковывающийся установщик .bat.

    python chrome-extension/pack.py [куда]        # по умолчанию: chrome-extension/dist

.crx смысла не имеет: Chrome с 73-й версии ставит только пакеты, подписанные Web Store
(«CRX_REQUIRED_PROOF_MISSING»), а флаг --load-extension вырезан из Chrome 137. Бесплатный
способ без магазина и без сайта остался один — «Загрузить распакованное», и .bat сводит его
к трём кликам: распаковывает в %LOCALAPPDATA%\\YPtun, кладёт путь в буфер, открывает вкладку.
"""
import base64, io, json, os, sys, zipfile

SRC = os.path.dirname(os.path.abspath(__file__))
SKIP = {"pack.py", "link.test.mjs", "dist"}

PS = (
    "$p='%~f0'; $l=Get-Content -LiteralPath $p; "
    "$i=($l | Select-String -SimpleMatch (':::PAY'+'LOAD:::') | Select-Object -First 1).LineNumber; "
    "$z=Join-Path $env:TEMP 'yptun-ext.zip'; "
    "[IO.File]::WriteAllBytes($z,[Convert]::FromBase64String((($l[$i..($l.Count-1)] | "
    "Where-Object {$_ -match '\\S'}) -join ''))); "
    "$d='%DEST%'; if(Test-Path $d){Remove-Item $d -Recurse -Force}; "
    "New-Item -ItemType Directory -Force -Path $d | Out-Null; "
    "Expand-Archive -LiteralPath $z -DestinationPath $d -Force; Remove-Item $z; Set-Clipboard $d"
)

BAT = """@echo off
chcp 65001 >nul
title YPtun VPN - установка расширения Chrome
set "DEST=%LOCALAPPDATA%\\YPtun\\chrome-extension"
echo.
echo   YPtun VPN - расширение для Chrome {version}
echo   ---------------------------------------
echo   Распаковываю в: %DEST%
powershell -NoProfile -ExecutionPolicy Bypass -Command "{ps}"
if errorlevel 1 goto fail
echo   Готово. Путь скопирован в буфер обмена.
echo.
echo   Осталось три клика в открывшейся вкладке:
echo     1. Включить "Режим разработчика" (тумблер справа сверху)
echo     2. Нажать "Загрузить распакованное"
echo     3. В окне выбора папки нажать Ctrl+V, затем Enter
echo.
start "" chrome.exe "chrome://extensions"
pause
exit /b 0
:fail
echo   Не удалось распаковать.
pause
exit /b 1
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

    b64 = base64.b64encode(io.open(zip_path, "rb").read()).decode()
    payload = "\n".join(b64[i:i + 120] for i in range(0, len(b64), 120))
    bat_path = os.path.join(out_dir, "YPtun-chrome-install.bat")
    io.open(bat_path, "w", encoding="utf-8", newline="\r\n").write(
        BAT.format(version=version, ps=PS) + payload + "\n")

    for p in (zip_path, bat_path):
        print("%s  %d bytes" % (p, os.path.getsize(p)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(SRC, "dist"))
