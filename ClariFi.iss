; Inno Setup script for ClariFi
; Build with: "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" ClariFi.iss
; Output:     Output\ClariFi-Setup-<version>.exe

#define MyAppName       "ClariFi"
#define MyAppVersion    "0.3.2"
#define MyAppPublisher  "Federico Roldós"
#define MyAppCopyright  "Copyright (C) 2026 Federico Roldós"
#define MyAppURL        "https://github.com/federicoroldos/clarifi"
#define MyAppExeName    "ClariFi.exe"

[Setup]
AppId={{B7A3F2E1-9C4D-4F8A-9E0B-CLARIFIFINANCE}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
AppCopyright={#MyAppCopyright}
AppContact={#MyAppURL}
VersionInfoVersion={#MyAppVersion}.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoCopyright={#MyAppCopyright}
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersion}
VersionInfoDescription={#MyAppName} Setup
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputBaseFilename=ClariFi-Setup-{#MyAppVersion}
SetupIconFile=clarifi.ico
; Wizard branding images. Generated from clarifi.ico at build time (see BUILD.md and
; the release-windows CI job) so clarifi.ico stays the single source of truth.
; WizardImageFile      = left panel on the Welcome/Finished pages.
; WizardSmallImageFile = top-right corner of every inner page (this is the generic
;                        placeholder Inno shows during install when left unset).
; One image per display-scaling level so Inno picks an exact match and never
; stretches the round logo into an ellipse (see make_wizard_images.py).
WizardImageFile=WizardImage.bmp,WizardImage-125.bmp,WizardImage-150.bmp,WizardImage-175.bmp,WizardImage-200.bmp,WizardImage-225.bmp
WizardSmallImageFile=WizardSmallImage.bmp,WizardSmallImage-125.bmp,WizardSmallImage-150.bmp,WizardSmallImage-175.bmp,WizardSmallImage-200.bmp,WizardSmallImage-225.bmp,WizardSmallImage-250.bmp
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
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
; Interactive install: postinstall checkbox on the final wizard page
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
; Silent install (in-app auto-update): launch unconditionally
Filename: "{app}\{#MyAppExeName}"; Flags: nowait; Check: WizardSilent

[UninstallDelete]
; Leaves %APPDATA%\ClariFi\ alone by default so user data survives uninstall.
; If you ever want to wipe it, add:
; Type: filesandordirs; Name: "{userappdata}\ClariFi"
