[Setup]
AppId={{905F92DE-69E0-4B1B-B9F6-C29C4D946CC3}
AppName=TRL Mother Base
AppVersion=0.2.0
DefaultDirName={localappdata}\Programs\TRL Mother Base
DefaultGroupName=TRL Mother Base
PrivilegesRequired=lowest
OutputDir=installer
OutputBaseFilename=TRL-Mother-Base-Setup-0.2.0
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\TRL Mother Base"; Filename: "{app}\TRL-Mother-Base.exe"
Name: "{autodesktop}\TRL Mother Base"; Filename: "{app}\TRL-Mother-Base.exe"
[Run]
Filename: "{app}\TRL-Mother-Base.exe"; Description: "Open TRL Mother Base"; Flags: nowait postinstall skipifsilent
