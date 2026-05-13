; Inno Setup script for ClariFi
; Build with: "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" ClariFi.iss
; Output:     Output\ClariFi-Setup-<version>.exe

#define MyAppName       "ClariFi"
#define MyAppVersion    "0.1.1"
#define MyAppPublisher  "Federico Roldos"
#define MyAppURL        "https://github.com/federicoroldos/basic-personal-finances-tracker"
#define MyAppExeName    "ClariFi.exe"

[Setup]
AppId={{B7A3F2E1-9C4D-4F8A-9E0B-CLARIFIFINANCE}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputBaseFilename=ClariFi-Setup-{#MyAppVersion}
SetupIconFile=clarifi.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName} {#MyAppVersion}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Files]
Source: "dist\ClariFi\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}";           Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}";     Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Leaves %APPDATA%\ClariFi\ alone by default so user data survives uninstall.
; If you ever want to wipe it, add:
; Type: filesandordirs; Name: "{userappdata}\ClariFi"
