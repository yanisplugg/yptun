#ifndef SourceDir
  #error SourceDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
#ifndef AppVersion
  #define AppVersion "4.0.0"
#endif
#ifndef Architecture
  #define Architecture "x64"
#endif

#if Architecture == "arm64"
  #define AllowedArchitecture "arm64"
#else
  #define AllowedArchitecture "x64compatible"
#endif

[Setup]
AppId={{E558B9DF-39A5-40E1-A347-092E54B350F4}
AppName=YPtun
AppVersion={#AppVersion}
AppPublisher=YPtun
AppPublisherURL=https://github.com/yanisplugg/yptun
AppSupportURL=https://github.com/yanisplugg/yptun/issues
DefaultDirName={autopf}\YPtun
DefaultGroupName=YPtun
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\YPtun.exe
OutputDir={#OutputDir}
OutputBaseFilename=YPtun-{#AppVersion}-{#Architecture}-installer
SetupIconFile=..\src\YPtun.Windows\Assets\WindowsIcon.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed={#AllowedArchitecture}
ArchitecturesInstallIn64BitMode={#AllowedArchitecture}
CloseApplications=yes
RestartApplications=no
MinVersion=10.0.17763

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\YPtun"; Filename: "{app}\YPtun.exe"
Name: "{autodesktop}\YPtun"; Filename: "{app}\YPtun.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\YPtun.exe"; Description: "{cm:LaunchProgram,YPtun}"; Flags: nowait postinstall skipifsilent
