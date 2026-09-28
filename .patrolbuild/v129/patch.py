from pathlib import Path
import shutil,sys,re
root=Path(sys.argv[1]);java=root/'app/src/main/java/au/com/roningroup/patrollink';payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v129 patch anchor missing: '+old[:220])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 128','versionCode 129');s=rep(s,"versionName '1.1.18'","versionName '1.1.19'");p.write_text(s)

shutil.copyfile(payload/'CheckpointTracker.java',java/'CheckpointTracker.java')

p=java/'PatrolEngine.java';s=p.read_text()
s=rep(s,'public static final long INTERVAL = 30_000L;','''public static final long INTERVAL = 10_000L;
    public static final long BACKOFF_INTERVAL = 15_000L;
    public static final long FAILURE_INTERVAL = 30_000L;''')
s=rep(s,'    public final SharedPreferences prefs;\n    public final VoiceManager voices;',
      '    public final SharedPreferences prefs;\n    public final VoiceManager voices;\n    public final CheckpointTracker checkpoints;')
s=rep(s,'        this.voices = new VoiceManager(this.context, this.prefs);',
      '        this.voices = new VoiceManager(this.context, this.prefs);\n        this.checkpoints = new CheckpointTracker(this.context);')
s=rep(s,'                List<Observation> selected = FeedReducer.selected(observations, guards());\n                voices.speechSettings.remember(selected);',
      '                List<Observation> selected = FeedReducer.selected(observations, guards());\n                voices.speechSettings.remember(selected);\n                checkpoints.observe(selected);')

# After every good read, wait a full 10 seconds from success before the next request.
s=rep(s,'completedRefreshes++; consecutiveFailures = 0; rebuildBeforeRetry = false;',
      'completedRefreshes++; consecutiveFailures = 0; rebuildBeforeRetry = false;\n                nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;')

# Timeout: first slow/failure backs off to 15s; repeat to 30s. Never overlaps an active request.
old='''            status("REFRESH_TIMEOUT", "No usable monitor rows arrived within 25 seconds. Keeping last known activity and retrying.");
            if (consecutiveFailures >= 2) {
                consecutiveFailures = 0;
                resetRenderer();
            }
            nextCheckElapsed = now + 5000L;'''
new='''            status("REFRESH_TIMEOUT", "No usable monitor rows arrived within 25 seconds. Keeping last known activity and backing off safely.");
            long retryDelay = consecutiveFailures >= 2 ? FAILURE_INTERVAL : BACKOFF_INTERVAL;
            if (consecutiveFailures >= 2) {
                consecutiveFailures = 0;
                resetRenderer();
            }
            nextCheckElapsed = now + retryDelay;'''
s=rep(s,old,new)

old='''        if (consecutiveFailures >= 2) rebuildBeforeRetry = true;
        nextCheckElapsed = SystemClock.elapsedRealtime() + ("TLS_ERROR".equals(code) ? INTERVAL : 5000L);
        status(code, message);'''
new='''        long retryDelay = "TLS_ERROR".equals(code) ? FAILURE_INTERVAL
                : (consecutiveFailures >= 2 ? FAILURE_INTERVAL : BACKOFF_INTERVAL);
        if (consecutiveFailures >= 2) rebuildBeforeRetry = true;
        nextCheckElapsed = SystemClock.elapsedRealtime() + retryDelay;
        status(code, message);'''
s=rep(s,old,new)

# Offline state should not hammer the network every 10 seconds.
s=rep(s,'if (!online()) { status("OFFLINE", "No network connection. Keeping the session and retrying."); return; }',
      'if (!online()) { status("OFFLINE", "No network connection. Keeping the session and retrying."); nextCheckElapsed = now + BACKOFF_INTERVAL; return; }')
s=rep(s,'nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;\n            return;\n        }\n        WebView view = webView();',
      'nextCheckElapsed = SystemClock.elapsedRealtime() + BACKOFF_INTERVAL;\n            return;\n        }\n        WebView view = webView();')

s=rep(s,'"\\nRefresh method: attached browser + uncached GET + row-readiness deadline" +',
      '"\\nRefresh method: attached browser + uncached GET + row-readiness deadline" +\n                "\\nRefresh cadence: adaptive 10s normal / 15s backoff / 30s repeated failure" +')

p.write_text(s)

p=java/'MainActivity.java';s=p.read_text()
s=s.replace('showGuardHistory(index)','showCheckpointTally(index)')
start=s.index('    private void showGuardHistory(int index) {') if '    private void showGuardHistory(int index) {' in s else s.index('    private void showCheckpointTally(int index) {')
end=s.index('    private void showBrowser() {',start)
method=r'''    private void showCheckpointTally(int index) {
        String guard=engine.guards().get(index);
        String nickname=engine.voices.speechSettings.nickname(guard);
        String shownName=nickname==null||nickname.trim().isEmpty()?guard:nickname.trim()+"  ·  "+guard;

        LinearLayout list=vertical();list.setPadding(dp(16),dp(8),dp(16),dp(8));
        long started=engine.checkpoints.shiftStart();
        String since=started>0?DateTimeFormatter.ofPattern("HH:mm · d MMM").format(Instant.ofEpochMilli(started).atZone(engine.zone())):"first patrol scan";
        list.addView(text("SHIFT CHECKPOINT TALLY",11,ACCENT,true));gap(list,5);
        list.addView(text(shownName,22,WHITE,true));gap(list,5);
        list.addView(text("Counting unique patrol/scan entries since "+since+". The tally persists if Patrol Link is closed.",12,MUTED,false));gap(list,16);

        for(String group:new String[]{"PBC 2","PBC 3"}) {
            int total=engine.checkpoints.groupTotal(guard,group);
            LinearLayout groupCard=card();
            groupCard.addView(text(group+"  ·  "+total+" hits",17,WHITE,true));gap(groupCard,10);
            for(CheckpointTracker.Point point:CheckpointTracker.POINTS) {
                if(!group.equals(point.group))continue;
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
                TextView label=text(point.label,14,MUTED,false);
                TextView count=text(String.valueOf(engine.checkpoints.count(guard,point.id)),18,ACCENT,true);
                count.setGravity(Gravity.END);
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                row.addView(count,new LinearLayout.LayoutParams(dp(54),-2));
                groupCard.addView(row);gap(groupCard,7);
            }
            addCard(list,groupCard);
        }

        LinearLayout totalCard=card();
        totalCard.addView(text("TOTAL CHECKPOINT HITS  ·  "+engine.checkpoints.total(guard),16,ACCENT,true));
        addCard(list,totalCard);

        final AlertDialog[] holder=new AlertDialog[1];
        addCard(list,dangerButton("Reset shift tally",()->new AlertDialog.Builder(this)
            .setTitle("Reset shift tally?")
            .setMessage("This clears the checkpoint counts for all patrol guards and starts a new shift tally now.")
            .setPositiveButton("Reset",(d,w)->{
                engine.checkpoints.reset();
                toast("Shift checkpoint tally reset.");
                if(holder[0]!=null)holder[0].dismiss();
                showCheckpointTally(index);
            }).setNegativeButton("Cancel",null).show()));

        ScrollView scroll=new ScrollView(this);scroll.addView(list);
        holder[0]=new AlertDialog.Builder(this).setTitle("Checkpoint tally").setView(scroll).setPositiveButton("Close",null).create();
        holder[0].show();
    }

'''
s=s[:start]+method+s[end:]

# Dashboard and help wording.
s=s.replace("Tap for this guard's recent activity","Tap for this guard's PBC checkpoint tally")
s=s.replace("Tap a guard card to view that guard's recent entries.","Tap a guard card to view that guard's PBC 2 / PBC 3 checkpoint tally.")
s=s.replace('countdownText.setText("SIGN_IN_REQUIRED".equals(engine.state)?"Automatic refresh paused until manual sign-in":engine.running?"Automatic refresh: 30 seconds · next check in "+wait+"s":"Monitor stopped");',
            'countdownText.setText("SIGN_IN_REQUIRED".equals(engine.state)?"Automatic refresh paused until manual sign-in":engine.running?"Adaptive refresh: 10s normal · 15/30s backoff · next check in "+wait+"s":"Monitor stopped");')
s=s.replace("requests fresh monitor rows every 30 seconds.","normally requests fresh monitor rows every 10 seconds, with 15/30-second automatic backoff if Silvertracker is slow or a request fails.")
s=s.replace("Every 30 seconds Patrol Link requests an uncached monitor page and waits for readable rows.","Patrol Link normally requests an uncached monitor page every 10 seconds and waits for readable rows. Requests never overlap; slow/error states back off to 15 or 30 seconds.")
p.write_text(s)

# Add instrumentation coverage.
shutil.copyfile(payload/'CheckpointTrackerTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/CheckpointTrackerTest.java')
shutil.copyfile(payload/'AdaptiveRefreshTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/AdaptiveRefreshTest.java')

assert 'versionCode 129' in (root/'app/build.gradle').read_text()
assert 'INTERVAL = 10_000L' in (java/'PatrolEngine.java').read_text()
assert 'BACKOFF_INTERVAL = 15_000L' in (java/'PatrolEngine.java').read_text()
assert 'FAILURE_INTERVAL = 30_000L' in (java/'PatrolEngine.java').read_text()
assert 'checkpoints.observe(selected)' in (java/'PatrolEngine.java').read_text()
assert 'PBC 2' in (java/'MainActivity.java').read_text()
assert 'showGuardHistory' not in (java/'MainActivity.java').read_text()
