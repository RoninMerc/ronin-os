from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);j=root/'app/src/main/java/au/com/roningroup/patrollink';here=Path(__file__).parent
for name in ['CheckpointSyncClient.java','MasterFeedClient.java','SilverTrackVehicleCollector.java']:shutil.copyfile(here/name,j/name)
p=root/'app/build.gradle';s=p.read_text()
assert 'versionCode 158' in s and "versionName '1.1.48'" in s
p.write_text(s.replace('versionCode 158','versionCode 160').replace("versionName '1.1.48'","versionName '1.1.50'"))
p=j/'CheckpointProximityUi.java';s=p.read_text()
start=s.index('        if(CheckpointSyncClient.master(activity)){',s.index('    private void syncTick(){'))
end=s.index('        if(!CheckpointSyncClient.subscriber(activity))return;',start)
s=s[:start]+s[end:]
s=s.replace('        if(!CheckpointSyncClient.subscriber(activity))return;','        if(!CheckpointSyncClient.subscriber(activity)&&!CheckpointSyncClient.master(activity))return;',1)
s=s.replace('if(incoming.isEmpty())return;','if(incoming.isEmpty()&&remote.length()!=0)return;')
anchor='private boolean supervisor(){return '
assert anchor in s
s=s.replace(anchor,anchor+'!CheckpointSyncClient.central(activity)&&',1)
s=s.replace('Master owns the shared checkpoint set.','Mother Base can centrally own the shared checkpoint set.')
s=s.replace('Checkpoint configuration is supplied by the Master phone.','Checkpoint configuration is supplied by Mother Base through the cloud relay.')
p.write_text(s)
assert 'CheckpointSyncClient.central(activity)' in s
assert 'MasterFeedClient.publish' in (j/'PatrolEngine.java').read_text()
print('Mother Base checkpoint sync patch applied; package identity and signing configuration preserved.')

engine=j/'PatrolEngine.java'
s=engine.read_text()
anchor='MasterFeedClient.publish(context,data.optJSONArray("allRows"));'
assert s.count(anchor)==1
engine.write_text(s.replace(anchor,anchor+'\n                    SilverTrackVehicleCollector.collect(context,web,data.optJSONArray("allRows"),zone());',1))
