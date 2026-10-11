from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:160])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 153','versionCode 154')
rep(root/'app/build.gradle',"versionName '1.1.43'","versionName '1.1.44'")

for name in ['SupervisorPin.java','FleetUi.java','CheckpointSyncClient.java','CheckpointProximityUi.java']:
    shutil.copyfile(here/name,java/name)

print('Applied v1.1.44: Master has no patrol assignment, supervisor PIN lock preserved, and checkpoint config/QR photos sync from Master to Patrol 1/2/3.')
