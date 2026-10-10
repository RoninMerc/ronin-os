from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:160])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 144','versionCode 145')
rep(root/'app/build.gradle',"versionName '1.1.34'","versionName '1.1.35'")
shutil.copyfile(here/'CheckpointProximityUi.java',java/'CheckpointProximityUi.java')

main=java/'MainActivity.java'
s=main.read_text()
anchor='''    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);'''
replacement='''    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        if(checkpointUi!=null && checkpointUi.onActivityResult(requestCode,resultCode,data)) return;
        super.onActivityResult(requestCode,resultCode,data);'''
if anchor not in s: raise RuntimeError('MainActivity onActivityResult anchor missing')
s=s.replace(anchor,replacement,1)
main.write_text(s)
print('Applied v1.1.35 editable PBC checkpoint groups/order/photos and 100m MyCentsys prompts.')
