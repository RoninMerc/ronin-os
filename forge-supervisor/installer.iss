#define MyAppName "Ronin Forge Supervisor"
#define MyAppVersion "0.2.0"
#define MyAppExeName "RoninForgeSupervisor.exe"
[Setup]
AppId={{7CBB59A0-6AD2-4E99-9D67-45E82C637B45}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
DefaultDirName={autopf}\Ronin Forge Supervisor
DefaultGroupName=Ronin Forge Supervisor
OutputDir=..\artifacts
OutputBaseFilename=Ronin-Forge-Supervisor-0.2.0-Setup
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
[Files]
Source: "..\artifacts\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\Ronin Forge Supervisor"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Ronin Forge Supervisor"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"
[Run]
Filename: "{app}\{#MyAppExeName}"; Flags: nowait postinstall skipifsilent