from pathlib import Path

root=Path('ronin-vanta-windows')
replacements={
    root/'src/Vanta.Windows/SettingsPage.cs': [('0.1.1','0.2.0')],
    root/'src/Vanta.Windows/app.manifest': [('version="0.1.1.0"','version="0.2.0.0"')],
    root/'src/Vanta.Core/ProviderApi.cs': [('RoninVantaWindows/0.1.1','RoninVantaWindows/0.2.0')],
    root/'installer.iss': [
        ('AppVersion=0.1.1','AppVersion=0.2.0'),
        ('AppVerName=Ronin Vanta Windows 0.1.1','AppVerName=Ronin Vanta Windows 0.2.0'),
        ('OutputBaseFilename=Ronin-Vanta-Windows-0.1.1-Setup','OutputBaseFilename=Ronin-Vanta-Windows-0.2.0-Setup'),
        ('VersionInfoVersion=0.1.1.0','VersionInfoVersion=0.2.0.0')
    ]
}
for path,pairs in replacements.items():
    text=path.read_text(encoding='utf-8')
    for old,new in pairs:
        if old not in text: raise SystemExit(f'Missing expected release marker {old!r} in {path}')
        text=text.replace(old,new)
    path.write_text(text,encoding='utf-8')
print('Applied Ronin Vanta Windows 0.2 release identity.')
