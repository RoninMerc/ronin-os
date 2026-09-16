from pathlib import Path
import os, runpy, subprocess

# Keep every reviewed 0.12 correction exactly as it was, then promote that known-good
# source into the 0.13 Vanta Orchestrator build. The 0.13 patch was authored against
# the formatted 0.12 release shape, so format before applying it. The workflow formats
# once more afterwards; that second pass is deterministic and harmless.
runpy.run_path('vanta/v012/review_base.py', run_name='__main__')
root = Path(os.environ.get('VANTA_PROJECT', 'vanta/personal'))
java_files = [str(p) for p in root.joinpath('app/src').rglob('*.java')]
subprocess.run(
    ['java', '-jar', 'baseline/reference/format.jar', '--replace', *java_files],
    check=True,
)
subprocess.run(['python3', 'vanta/v013/apply.py'], check=True)

# The retained upgrade probe must now validate the installed 0.13 package.
p = root / 'app/src/androidTest/java/com/ronin/vanta/UnifiedUpgradeProbe.java'
s = p.read_text()
old = '''    assertEquals(
        120, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
new = '''    assertEquals(
        130, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
if old not in s:
    raise SystemExit('Expected 0.12 upgrade-probe assertion before 0.13 promotion')
p.write_text(s.replace(old, new, 1))

print('Promoted reviewed 0.12 source to Ronin Vanta Android 0.13.0 with adaptive Vanta Orchestrator routing.')
