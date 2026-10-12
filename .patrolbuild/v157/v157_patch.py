from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 156','versionCode 157')
rep(root/'app/build.gradle',"versionName '1.1.46'","versionName '1.1.47'")

shutil.copyfile(here/'FleetRelay.java',java/'FleetRelay.java')

p=java/'PatrolEngine.java'; s=p.read_text()

old='''        if(MasterFeedClient.subscriber(context)){
            if(!masterPollBusy && now>=nextCheckElapsed){
                masterPollBusy=true;nextCheckElapsed=now+INTERVAL;
                MasterFeedClient.poll(context,(snapshot,error)->{'''
new='''        if(MasterFeedClient.subscriber(context)){
            if(!masterPollBusy && now>=nextCheckElapsed){
                FleetRelay.syncGuards(context,(changed,guardError)->{
                    if(changed){
                        latest.clear();present.clear();lastRead=lastReadElapsed=0;
                        status("GUARDS_SYNCED","Active guards updated automatically from the Master phone.");
                    }
                });
                masterPollBusy=true;nextCheckElapsed=now+INTERVAL;
                MasterFeedClient.poll(context,(snapshot,error)->{'''
if old not in s: raise RuntimeError('subscriber cycle anchor missing')
s=s.replace(old,new,1)

old='''                GuardDirectory.remember(prefs, data.optJSONArray("allRows"));
                if(!browserVisible && MasterFeedClient.master(context))MasterFeedClient.publish(context,data.optJSONArray("allRows"));'''
new='''                GuardDirectory.remember(prefs, data.optJSONArray("allRows"));
                if(!browserVisible && MasterFeedClient.master(context)){
                    MasterFeedClient.publish(context,data.optJSONArray("allRows"));
                    FleetRelay.publishGuards(context,guards());
                }'''
if old not in s: raise RuntimeError('Master publish anchor missing')
s=s.replace(old,new,1)

old='''        }else if(MasterFeedClient.master(context)){
            status("MASTER_MODE","Master reads SilverTrack and relays only authorised activity.");
            if(running)openMonitor();'''
new='''        }else if(MasterFeedClient.master(context)){
            FleetRelay.publishGuards(context,guards());
            status("MASTER_MODE","Master reads SilverTrack and relays authorised activity plus the active guard list.");
            if(running)openMonitor();'''
if old not in s: raise RuntimeError('changeFeedMode master anchor missing')
s=s.replace(old,new,1)

p.write_text(s)

p=java/'MainActivity.java'; s=p.read_text()
old='''                SharedPreferences.Editor edit=engine.prefs.edit();for(int i=0;i<3;i++)edit.putString("guard"+i,chosen.get(i));
                edit.putString("zone",zoneText).putInt("oldMinutes",minutes).putBoolean("patrolOnly",false).putBoolean("voice",voice.isChecked()).apply();
                engine.latest.clear();engine.present.clear();engine.lastRead=engine.lastReadElapsed=0;'''
new='''                SharedPreferences.Editor edit=engine.prefs.edit();for(int i=0;i<3;i++)edit.putString("guard"+i,chosen.get(i));
                edit.putString("zone",zoneText).putInt("oldMinutes",minutes).putBoolean("patrolOnly",false).putBoolean("voice",voice.isChecked()).apply();
                if(FleetRelay.master(this))FleetRelay.publishGuards(this,chosen);
                engine.latest.clear();engine.present.clear();engine.lastRead=engine.lastReadElapsed=0;'''
if old not in s: raise RuntimeError('guard save anchor missing')
s=s.replace(old,new,1)
p.write_text(s)

print('Applied v1.1.47: repaired authenticated fleet relay, compatible patrol GPS payloads, and automatic Master-to-Patrol guard shift sync.')
