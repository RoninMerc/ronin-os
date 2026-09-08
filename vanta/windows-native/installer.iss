#ifndef AppSource
#define AppSource "publish"
#endif
#ifndef Output
#define Output "installer-output"
#endif
[Setup]
AppId={{6BE5C6D8-9C73-4BA4-A6FC-4917C85FCB84}
AppName=Ronin Vanta
AppVersion=0.1.1
AppVerName=Ronin Vanta Windows 0.1.1
AppPublisher=Ronin Group Australia
DefaultDirName={localappdata}\Programs\Ronin Vanta
DefaultGroupName=Ronin Vanta
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22000
OutputDir={#Output}
OutputBaseFilename=Ronin-Vanta-Windows-0.1.1-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\RoninVanta.exe
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
VersionInfoVersion=0.1.1.0
VersionInfoDescription=Ronin Vanta Windows installer
VersionInfoProductName=Ronin Vanta
[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: unchecked
[Files]
Source: "{#AppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\Ronin Vanta"; Filename: "{app}\RoninVanta.exe"
Name: "{autodesktop}\Ronin Vanta"; Filename: "{app}\RoninVanta.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\RoninVanta.exe"; Description: "Open Ronin Vanta"; Flags: nowait postinstall skipifsilent
