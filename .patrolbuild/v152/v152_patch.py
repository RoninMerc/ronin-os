from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 151','versionCode 152')
rep(root/'app/build.gradle',"versionName '1.1.41'","versionName '1.1.42'")

for name in ['SilverFeedRelay.java','SupervisorPin.java','FleetUi.java']:
    shutil.copyfile(here/name,java/name)

# Ensure existing fleet relay authenticates to the secured Render relay too.
p=java/'FleetRelay.java'; s=p.read_text()
old='h.setRequestProperty("Accept","application/json");h.setRequestProperty("User-Agent","Ronin-Patrol-Link/1.1.31");'
new='h.setRequestProperty("Accept","application/json");h.setRequestProperty("User-Agent","Ronin-Patrol-Link/1.1.42");h.setRequestProperty("X-Ronin-Key","ronin-patrol-link-2026");'
if old not in s: raise RuntimeError('FleetRelay request header anchor missing')
p.write_text(s.replace(old,new,1))

# PatrolEngine: Master reads SilverTrack directly; Patrol phones consume only the Master relay.
p=java/'PatrolEngine.java'; s=p.read_text()

anchor='''    public WebView peekWebView() { return web; }'''
insert='''    public WebView peekWebView() { return web; }
    private boolean relayUser(){ return SilverFeedRelay.relayUser(context); }'''
if anchor not in s: raise RuntimeError('PatrolEngine peek anchor missing')
s=s.replace(anchor,insert,1)

old='''    private final Runnable cycle = new Runnable() { public void run() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();'''
new='''    private final Runnable cycle = new Runnable() { public void run() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if(relayUser()){
            if(now>=nextCheckElapsed){nextCheckElapsed=now+3000L;pollMasterFeed();}
            handler.postDelayed(this,1000L);
            return;
        }'''
if old not in s: raise RuntimeError('cycle anchor missing')
s=s.replace(old,new,1)

# Master publishes every accepted live snapshot.
old='''                List<Observation> selected = FeedReducer.selected(observations, guards());
                voices.speechSettings.remember(selected);
                if(!browserVisible)checkpoints.observe(selected);'''
new='''                List<Observation> selected = FeedReducer.selected(observations, guards());
                voices.speechSettings.remember(selected);
                if(!browserVisible)checkpoints.observe(selected);
                if(!browserVisible && FleetRelay.master(context)) SilverFeedRelay.publish(context,selected);'''
if old not in s: raise RuntimeError('selected observation anchor missing')
s=s.replace(old,new,1)

# Relay consumer applies the same feed reduction and voice formatting locally.
anchor='''    private void trimRecent() {'''
relay_method='''    private void pollMasterFeed(){
        if(!running||!relayUser())return;
        SilverFeedRelay.poll(context,(events,masterSeenAt,error)->{
            if(!running||!relayUser())return;
            long now=System.currentTimeMillis();
            if(error!=null){
                state="RELAY_OFFLINE";detail="Unable to reach the Master SilverTrack feed. Keeping last known activity.";
                notifyChanged();return;
            }
            List<Observation> selected=FeedReducer.selected(events,guards());
            voices.speechSettings.remember(selected);
            checkpoints.observe(selected);
            Map<String,Observation> batch=FeedReducer.latest(selected,guards(),false);
            boolean hadBaseline=lastRead>0;
            List<Observation> announce=speechLedger.collect(selected,hadBaseline,lastRead,now);
            present.clear();present.addAll(batch.keySet());
            for(Observation o:selected)recentById.put(o.id,o);
            trimRecent();
            for(Map.Entry<String,Observation> e:batch.entrySet()){
                Observation previous=latest.get(e.getKey());
                if(previous==null||previous.id.equals(e.getValue().id)||FeedReducer.newer(e.getValue(),previous))latest.put(e.getKey(),e.getValue());
            }
            selectedCount=batch.size();rowsFound=selected.size();lastRead=now;lastReadElapsed=SystemClock.elapsedRealtime();
            prefs.edit().putLong("last_live_read_at",now).apply();
            long age=masterSeenAt<=0?Long.MAX_VALUE:Math.max(0,now-masterSeenAt);
            state=age<=30_000L?"READ_OK":age<=120_000L?"RELAY_DELAYED":"RELAY_OFFLINE";
            detail=age<=30_000L?"SilverTrack activity received from the Master device."
                    :age<=120_000L?"Master feed is delayed; showing last known activity."
                    :"Master device has not checked in recently; showing last known activity.";
            if(prefs.getBoolean("voice",true))for(Observation o:announce){
                if(featherlessVoice.selected())featherlessVoice.speak(voices.speechSettings.format(o));else voices.speak(o);
            }
            notifyChanged();
        });
    }

    private void trimRecent() {'''
if anchor not in s: raise RuntimeError('trimRecent anchor missing')
s=s.replace(anchor,relay_method,1)

old='''    public void start() {
        if (running) return;
        running = true; handler.removeCallbacks(cycle);
        nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
        if (web != null && monitorPage(web.getUrl())) refreshMonitor(); else openMonitor();
        handler.post(cycle);
    }'''
new='''    public void start() {
        if (running) return;
        running = true; handler.removeCallbacks(cycle);
        if(relayUser()){
            state="RELAY_CONNECTING";detail="Connecting to the Master SilverTrack feed…";
            nextCheckElapsed=0;handler.post(cycle);notifyChanged();return;
        }
        nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
        if (web != null && monitorPage(web.getUrl())) refreshMonitor(); else openMonitor();
        handler.post(cycle);
    }'''
if old not in s: raise RuntimeError('start anchor missing')
s=s.replace(old,new,1)

s=s.replace('''    public void openMonitor() {
        if (!running || refreshActive) return;''','''    public void openMonitor() {
        if (relayUser()) { status("RELAY_MODE","SilverTrack is supplied by the Master device on this patrol phone."); return; }
        if (!running || refreshActive) return;''',1)
s=s.replace('''    public void refreshMonitor() {
        if (!running) return;''','''    public void refreshMonitor() {
        if (relayUser()) return;
        if (!running) return;''',1)

old='''    public String sourceStatus() {
        if (!running) return state.equals("EXITED") ? "SIGNED OUT" : "MONITOR RESTARTING";'''
new='''    public String sourceStatus() {
        if(relayUser())return SilverFeedRelay.status(context);
        if (!running) return state.equals("EXITED") ? "SIGNED OUT" : "MONITOR RESTARTING";'''
if old not in s: raise RuntimeError('sourceStatus anchor missing')
s=s.replace(old,new,1)

old='''    public boolean healthy() { return running && online() && state.equals("READ_OK") && lastReadElapsed > 0 && SystemClock.elapsedRealtime() - lastReadElapsed <= 75_000L; }'''
new='''    public boolean healthy() {
        if(relayUser()){
            long seen=prefs.getLong("silver_master_seen_at",0),age=seen<=0?Long.MAX_VALUE:System.currentTimeMillis()-seen;
            return running&&online()&&age<=75_000L;
        }
        return running && online() && state.equals("READ_OK") && lastReadElapsed > 0 && SystemClock.elapsedRealtime() - lastReadElapsed <= 75_000L;
    }'''
if old not in s: raise RuntimeError('healthy anchor missing')
s=s.replace(old,new,1)

p.write_text(s)

# User-mode dashboard: no SilverTrack browser/login; show Master-feed status instead.
p=java/'MainActivity.java'; s=p.read_text()
old='''        if(userMode){
            buttonRow(dashboard,button("Silvertracker",false,this::showBrowser),button("Diagnostics",false,this::diagnostics));
        }else{'''
new='''        if(userMode){
            buttonRow(dashboard,button("Master feed status",false,this::masterFeedStatus),button("Diagnostics",false,this::diagnostics));
        }else{'''
if old not in s: raise RuntimeError('user mode button anchor missing')
s=s.replace(old,new,1)

old='''    private void showBrowser() {
        browserMode=true; engine.browserVisible=true; base();'''
new='''    private void showBrowser() {
        if(SilverFeedRelay.relayUser(this)){masterFeedStatus();return;}
        browserMode=true; engine.browserVisible=true; base();'''
if old not in s: raise RuntimeError('showBrowser anchor missing')
s=s.replace(old,new,1)

anchor='''    private void diagnostics() {'''
method='''    private void masterFeedStatus(){
        String msg=SilverFeedRelay.status(this)+"\n\nThis patrol phone does not sign in to SilverTrack. The Master / Supervisor phone is the only SilverTrack collector and relays read-only activity to Patrol 1, 2 and 3.\n\nIf the Master feed goes offline, Patrol Link keeps the last known activity and shows a stale/offline status.";
        new AlertDialog.Builder(this).setTitle("Master SilverTrack feed").setMessage(msg).setPositiveButton("Close",null).show();
    }

    private void diagnostics() {'''
if anchor not in s: raise RuntimeError('diagnostics anchor missing')
s=s.replace(anchor,method,1)
p.write_text(s)

print('Applied v1.1.42: PIN-locked supervisor elevation and Master-only SilverTrack collector relay to Patrol 1/2/3.')
