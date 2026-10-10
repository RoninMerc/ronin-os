from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 145','versionCode 146')
rep(root/'app/build.gradle',"versionName '1.1.35'","versionName '1.1.36'")

# Keep fleet/reporting cadence untouched, but request local position fixes much faster.
p=java/'LocalGpsService.java'; s=p.read_text()
old='''        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
            try{
                if(!manager.getAllProviders().contains(provider))continue;
                manager.requestLocationUpdates(provider,interval(this),0f,this,Looper.getMainLooper());subscribed=true;
                Location cached=manager.getLastKnownLocation(provider);if(cached!=null)saveFix(this,cached);
            }catch(SecurityException ignored){}catch(IllegalArgumentException ignored){}
        }'''
new='''        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
            try{
                if(!manager.getAllProviders().contains(provider))continue;
                // Checkpoint proximity needs fresh fixes independently of the slower fleet/reporting cadence.
                long fixInterval=LocationManager.GPS_PROVIDER.equals(provider)?1000L:2000L;
                manager.requestLocationUpdates(provider,fixInterval,0f,this,Looper.getMainLooper());subscribed=true;
                Location cached=manager.getLastKnownLocation(provider);if(cached!=null)saveFix(this,cached);
            }catch(SecurityException ignored){}catch(IllegalArgumentException ignored){}
        }'''
if old not in s: raise RuntimeError('LocalGpsService subscribe anchor not found')
p.write_text(s.replace(old,new,1))

# Nearest outstanding checkpoint is always visible. Image and Scan Complete appear at 50m.
p=java/'CheckpointProximityUi.java'; s=p.read_text()
s=s.replace('complete=button("Complete",this::completeActive);','complete=button("SCAN COMPLETE",this::completeActive);',1)

old_tick='''    public void tick(){
        if(card==null)return;
        long fix=LocalGpsService.prefs(activity).getLong("fix_time",0),age=LocalGpsService.fixAgeSeconds(activity);
        if(fix==0||age<0||age>90){showNone(age>90?"GPS position is stale":"Waiting for current GPS position");return;}
        SharedPreferences gp=LocalGpsService.prefs(activity);
        double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));

        maybeGatePrompt(lat,lon);

        Checkpoint best=null;float bestDist=Float.MAX_VALUE;
        for(Checkpoint c:activeRoute()){
            if(!c.mapped()||completed(c.id))continue;
            float d=distance(lat,lon,c.lat,c.lon);
            if(d<=Math.max(c.radius,DEFAULT_TRIGGER)&&d<bestDist){best=c;bestDist=d;}
        }
        if(best==null){
            if(!activeId.isEmpty()){
                Checkpoint old=find(activeId);
                if(old!=null&&old.mapped()&&distance(lat,lon,old.lat,old.lon)<CLEAR_RADIUS)return;
            }
            activeId="";showNone(activeRoute().stream().noneMatch(Checkpoint::mapped)?"No checkpoint locations registered for this patrol":"No outstanding checkpoint nearby");return;
        }
        activeId=best.id;
        String reached=bestDist<=25?"CHECKPOINT REACHED":"CHECKPOINT NEARBY";
        title.setText(reached);distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        hint.setText("Scan the physical SilverTrack QR code, then tap Complete. GPS proximity alone never records a scan.");
        complete.setVisibility(View.VISIBLE);showPhoto(best);
    }'''
new_tick='''    public void tick(){
        if(card==null)return;
        long fix=LocalGpsService.prefs(activity).getLong("fix_time",0),age=LocalGpsService.fixAgeSeconds(activity);
        if(fix==0||age<0||age>10){showNone(age>10?"Waiting for a fresh checkpoint GPS fix":"Waiting for current GPS position");return;}
        SharedPreferences gp=LocalGpsService.prefs(activity);
        double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));

        maybeGatePrompt(lat,lon);

        Checkpoint best=null;float bestDist=Float.MAX_VALUE;
        for(Checkpoint c:activeRoute()){
            if(!c.mapped()||completed(c.id))continue;
            float d=distance(lat,lon,c.lat,c.lon);
            if(d<bestDist){best=c;bestDist=d;}
        }
        if(best==null){
            activeId="";
            showNone(activeRoute().stream().noneMatch(Checkpoint::mapped)?"No checkpoint locations registered for this patrol":"All mapped checkpoints completed for this lap");
            return;
        }

        activeId=best.id;
        boolean scanRange=bestDist<=50f;
        title.setText(scanRange?"CHECKPOINT IN SCAN RANGE":"NEXT CHECKPOINT");
        distance.setText(best.name+"   "+arrow(bearing(lat,lon,best.lat,best.lon))+"  "+Math.round(bestDist)+" m");
        if(scanRange){
            hint.setText("Scan the physical SilverTrack QR code, then tap SCAN COMPLETE. Only the SilverTrack scan changes the tally.");
            complete.setVisibility(View.VISIBLE);
            showPhoto(best);
        }else{
            hint.setText("Live checkpoint GPS · QR/photo appears automatically at 50 metres.");
            complete.setVisibility(View.GONE);
            if(photo!=null)photo.setVisibility(View.GONE);
        }
    }'''
if old_tick not in s: raise RuntimeError('Checkpoint tick anchor not found')
s=s.replace(old_tick,new_tick,1)
p.write_text(s)

print('Applied v1.1.36 live checkpoint GPS: ~1s fixes, nearest checkpoint always visible, 50m QR/photo + SCAN COMPLETE.')
