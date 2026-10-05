from pathlib import Path

root = Path('ronin-vanta-windows')

def replace(name: str, old: str, new: str) -> None:
    path = root / name
    text = path.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise RuntimeError(f'Unexpected source while stabilising {name}')
    path.write_text(text.replace(old, new), encoding='utf-8')

# Navigation changes the ContentControl immediately; its visual tree is realised
# on the dispatcher. Exercise the real Settings page only after that layout pass.
# Keep every Activity recovery assertion and screenshot from the original test.
replace(
    'tests/Vanta.Tests/CatalogueAcceptanceTests.cs',
    'window.Navigate("Settings"); ((SettingsPage)Page()).Section("About & diagnostics");',
    'window.Navigate("Settings"); await Layout(); ((SettingsPage)Page()).Section("About & diagnostics");'
)
# The loop requires a non-null URL, and Fresh always supplies a validated first
# URL. Make the invariant explicit after potentially replacing the checkpoint.
replace(
    'src/Vanta.Core/CatalogueSync.cs',
    'if (checkpoint.Page > 1000 || checkpoint.Seen.Contains(checkpoint.Url))',
    'if (checkpoint.Page > 1000 || checkpoint.Seen.Contains(checkpoint.Url!))'
)
print('Stabilised dispatcher-based acceptance navigation; catalogue assertions retained.')
