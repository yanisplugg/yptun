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
OutputBaseFilename={#AppName}-{#AppVersion}-x64-installer
SetupIconFile={#SourcePath}\..\..\appIcons\WindowsIcon.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; The app bundles its own x64 JRE and x64 native cores, so it must install as 64-bit. (It still runs
; on Windows-on-ARM through the x64 emulation layer.)
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
; Always offer the picker, even when the OS language matches one we ship.
ShowLanguageDialog=yes
; Shut a running YPtun down before overwriting its files, so an upgrade doesn't need a reboot.
CloseApplications=yes
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
