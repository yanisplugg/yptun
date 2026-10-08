; Multilingual Windows installer for the YPtun desktop client.
;
; jpackage (which Compose's packageExe drives) builds a WiX/MSI wrapper whose UI is fixed to ONE
; language per build, so a four-language installer would have meant four separate files. Inno Setup
; shows a language picker at launch and keeps everything in a single .exe, so the installer is built
; from here instead. It packages the SAME app image Compose produces
; (desktopApp/build/compose/binaries/main/app/YPtun), so nothing about the app itself changes.
;
; Build (see packaging/windows/build-installer.ps1):
;   ISCC.exe /DAppVersion=3.1.1 /DAppDir=<app image> /DOutDir=<out> yptun.iss

#ifndef AppVersion
  #define AppVersion "3.1.1"
#endif
#ifndef AppDir
  #define AppDir "..\..\build\compose\binaries\main\app\YPtun"
#endif
#ifndef OutDir
  #define OutDir "..\..\build\compose\binaries\main\exe"
#endif
; Chinese and Persian are not in the Inno Setup installer's bundled set, so they are vendored here
; (from jrsoftware/issrc: Files/Languages/ChineseSimplified.isl and Languages/Unofficial/Farsi.isl)
; to keep the build reproducible without a network fetch.
#ifndef IslDir
  #define IslDir "lang"
#endif
; "amd64" or "arm64" - the architecture of the app image being packaged. The bundled JRE and the
; native cores are arch-specific, so the installer must refuse to install the wrong one.
#ifndef AppArch
  #define AppArch "amd64"
#endif

#define AppName "YPtun"
#define AppExe "YPtun.exe"
#define AppPublisher "YPtun"

[Setup]
; Stable AppId: keeps upgrades in place instead of stacking second installations. Distinct from the
; jpackage upgradeUuid because this is a different installer technology with its own registry entry.
AppId={{9C4D1F2E-6B7A-4F58-9E31-2A8D5C0B7E44}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExe}
OutputDir={#OutDir}
#if AppArch == "arm64"
  #define ArchSuffix "arm64"
  ; arm64 build: native ARM64 only, no emulation fallback.
  #define ArchAllowed "arm64"
  #define ArchIn64Bit "arm64"
#else
  #define ArchSuffix "x64"
  ; x64 build: also allowed on Windows-on-ARM, where it runs under the x64 emulation layer.
  #define ArchAllowed "x64compatible"
  #define ArchIn64Bit "x64compatible"
#endif

OutputBaseFilename={#AppName}-{#AppVersion}-{#ArchSuffix}-installer
SetupIconFile={#SourcePath}\..\..\appIcons\WindowsIcon.ico
Compression=lzma2/max
SolidCompression=yes
; "dynamic" follows the OS theme: a light or dark wizard, switching with Windows' "app mode" setting.
; The side/corner art comes in a light and a dark variant (art/gen_art.py), each at several scales so
; it stays sharp at 100/125/150/200 % display scaling. Image back colours are BGR ($BBGGRR).
WizardStyle=modern dynamic
WizardImageFile={#SourcePath}art\wizard-light-100.bmp,{#SourcePath}art\wizard-light-125.bmp,{#SourcePath}art\wizard-light-150.bmp,{#SourcePath}art\wizard-light-200.bmp
WizardImageFileDynamicDark={#SourcePath}art\wizard-dark-100.bmp,{#SourcePath}art\wizard-dark-125.bmp,{#SourcePath}art\wizard-dark-150.bmp,{#SourcePath}art\wizard-dark-200.bmp
WizardSmallImageFile={#SourcePath}art\small-light-100.bmp,{#SourcePath}art\small-light-125.bmp,{#SourcePath}art\small-light-150.bmp,{#SourcePath}art\small-light-200.bmp
WizardSmallImageFileDynamicDark={#SourcePath}art\small-dark-100.bmp,{#SourcePath}art\small-dark-125.bmp,{#SourcePath}art\small-dark-150.bmp,{#SourcePath}art\small-dark-200.bmp
WizardImageBackColor=$F2E8E3
WizardImageBackColorDynamicDark=$14100E
WizardSmallImageBackColor=$F2E8E3
WizardSmallImageBackColorDynamicDark=$14100E
; The app bundles its own JRE and native cores, so the installer must match the machine.
ArchitecturesAllowed={#ArchAllowed}
ArchitecturesInstallIn64BitMode={#ArchIn64Bit}
PrivilegesRequired=admin
; Always offer the picker, even when the OS language matches one we ship.
ShowLanguageDialog=yes
; Restart Manager is OFF on purpose: it runs BEFORE our code, sees the running YPtun (it cannot close one
; parked in the tray, nor in silent mode - how the in-app updater runs this installer) and shows
; "could not close applications" even when everything is about to be closed. StopRunningApp (below)
; closes the app after the user clicks Install, so an upgrade over a running YPtun needs neither a
; manual exit nor a reboot.
CloseApplications=no
RestartApplications=no

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "zh"; MessagesFile: "{#IslDir}\ChineseSimplified.isl"
Name: "fa"; MessagesFile: "{#IslDir}\Farsi.isl"

[CustomMessages]
en.CreateDesktopIcon=Create a &desktop shortcut
en.LaunchApp=Launch {#AppName}
en.AdditionalIcons=Additional shortcuts:
ru.CreateDesktopIcon=Создать значок на &рабочем столе
ru.LaunchApp=Запустить {#AppName}
ru.AdditionalIcons=Дополнительные значки:
zh.CreateDesktopIcon=创建桌面快捷方式(&D)
zh.LaunchApp=启动 {#AppName}
zh.AdditionalIcons=其他快捷方式：
fa.CreateDesktopIcon=ایجاد میان‌بر در &دسکتاپ
fa.LaunchApp=اجرای {#AppName}
fa.AdditionalIcons=میان‌برهای بیشتر:

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; The whole Compose app image: launcher + app\ (jars incl. the bundled native cores) + runtime\ (JRE).
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchApp}"; Flags: nowait postinstall skipifsilent

[Code]
// Closes every process that runs from THIS install dir (a portable copy elsewhere is left alone): first a
// polite close so the app can restore the system proxy / drop its TUN, then, after 8 s, a hard stop.
procedure StopRunningApp(AppDir: String);
var
  ResultCode: Integer;
  Script: String;
begin
  StringChange(AppDir, '''', '''''');
  Script :=
    '$d = ''' + AppDir + '\''; ' +
    '$p = @(Get-Process -ErrorAction SilentlyContinue | ' +
      'Where-Object { $_.Path -and $_.Path.StartsWith($d, [StringComparison]::OrdinalIgnoreCase) }); ' +
    'if ($p.Count) { ' +
      'foreach ($x in $p) { [void]$x.CloseMainWindow() }; ' +
      '$p | Wait-Process -Timeout 8 -ErrorAction SilentlyContinue; ' +
      '$p | Where-Object { -not $_.HasExited } | Stop-Process -Force -ErrorAction SilentlyContinue; ' +
      'Start-Sleep -Milliseconds 800 }';
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + Script + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopRunningApp(ExpandConstant('{app}'));
  Result := '';
end;

function InitializeUninstall(): Boolean;
begin
  StopRunningApp(ExpandConstant('{app}'));
  Result := True;
end;
