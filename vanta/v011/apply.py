from pathlib import Path
import subprocess
root=Path(__file__).resolve().parent
project=root.parent/'personal'
for patch in sorted(root.glob('patch-*.diff')):
    print('Applying', patch.name)
    subprocess.run(['patch','--batch','--fuzz=0','-p1','-d',str(project)],input=patch.read_bytes(),check=True)
print('Applied Ronin Vanta Android 0.11.0 transparent source patches.')
