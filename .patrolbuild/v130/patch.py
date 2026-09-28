from pathlib import Path
import shutil,sys,re
root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'
payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s:
        raise RuntimeError('v130 patch anchor missing: '+old[:220])
    return s.replace(old,new,1)

# Version.
p=root/'app/build.gradle'
s=p.read_text()
s=rep(s,'versionCode 129','versionCode 130')
s=rep(s,"versionName '1.1.19'","versionName '1.1.20'")
p.write_text(s)

# Reliability primitives.
shutil.copyfile(payload/'AnnouncementLedger.java',java/'AnnouncementLedger.java')
shutil.copyfile(payload/'CheckpointTracker.java',java/'CheckpointTracker.java')

# Canonical exact recording coverage. Keep the old truncated source phrase for
# backwards source matching, but add the correctly spoken user-facing wording.
canonical=root/'app/src/main/assets/exact/canonical_phrases.txt'
lines=canonical.read_text().splitlines()
if 'General Patrol - Ceil Circuit' not in lines:
    try:
        final_warning=lines.index('Parking Breach - 3rd Warning')
    except ValueError:
        final_warning=len(lines)
    lines.insert(final_warning,'General Patrol - Ceil Circuit')
canonical.write_text('\n'.join(lines)+'\n')

# Preview must describe the exact sentence the live exact-recording path will use.
p=java/'SpeechPreferences.java'
s=p.read_text()
old='''    public synchronized String preview(Observation row,String type,String original,String replacement) {
        Map<String,String> alerts=overrides(ALERT),places=overrides(PLACE),phrases=overrides(PHRASE);
        Map<String,String> target=type.equals(ALERT)?alerts:type.equals(PLACE)?places:phrases;
        if(replacement.trim().isEmpty())target.remove(SpeechRules.key(original));else target.put(SpeechRules.key(original),replacement.trim());
        return SpeechRules.format(row,prefix(),nicknames(),alerts,places,phrases);
    }'''
new='''    public synchronized String preview(Observation row,String type,String original,String replacement) {
        Map<String,String> alerts=overrides(ALERT),places=overrides(PLACE),phrases=overrides(PHRASE);
        Map<String,String> target=type.equals(ALERT)?alerts:type.equals(PLACE)?places:phrases;
        if(replacement.trim().isEmpty())target.remove(SpeechRules.key(original));else target.put(SpeechRules.key(original),replacement.trim());
        if(type.equals(ALERT)) {
            String exact=SpeechRules.clean(alerts.get(SpeechRules.key(row.issue)));
            if(!exact.isEmpty()) return SpeechRules.phrases(exact,phrases);
        }
        return SpeechRules.format(row,prefix(),nicknames(),alerts,places,phrases);
    }'''
s=rep(s,old,new)
p.write_text(s)

# Exact voice diagnostics: never silently claim a custom sentence is recorded.
p=java/'VoiceManager.java'
s=p.read_text()
anchor='''    public void speechSettingsChanged(){
        stop();status="Speech wording changed";lastError="The single canonical WAV is portable across phones. Custom wording can only be spoken when that exact phrase is already in the canonical pack.";
    }
'''
insert='''    public String recordingIssue(String text){
        Profile p=activeProfile();
        if(!ExactPhrasePack.installed(context,p.id))
            return "Import the single "+p.name+" WAV first.";
        try{
            List<File> resolved=resolve(p.id,SpeechRules.clean(text));
            return resolved.isEmpty()?"No exact recording matches this wording.":"";
        }catch(Exception e){return safe(e);}
    }

    public void speechSettingsChanged(){
        stop();status="Speech wording changed";lastError="The single canonical WAV is portable across phones. Custom wording can only be spoken when that exact phrase is already in the canonical pack.";
    }
'''
s=rep(s,anchor,insert)
old='''    private void refreshStatus(){
        Profile p=activeProfile();status=ready()?p.name+" exact single-WAV pack ready":p.name+" single WAV required";
    }'''
new='''    private void refreshStatus(){
        Profile p=activeProfile();
        if(!ExactPhrasePack.installed(context,p.id)){status=p.name+" single WAV required";return;}
        try{
            int have=ExactPhrasePack.clips(context,p.id).size(), need=canonicalPhrases().size();
            status=have>=need?p.name+" exact single-WAV pack ready"
                    :p.name+" exact pack "+have+"/"+need+" phrases · update WAV for missing wording";
        }catch(Exception e){status=p.name+" exact pack needs checking";}
    }'''
s=rep(s,old,new)
p.write_text(s)

# Speech editor: show exact-recording readiness before the user relies on an edit.
p=java/'SpeechSettingsUi.java'
s=p.read_text()
s=rep(s,'private static final int WHITE=0xffeff5fa,MUTED=0xffa7b7c3,ACCENT=0xff80d5c5;',
      'private static final int WHITE=0xffeff5fa,MUTED=0xffa7b7c3,ACCENT=0xff80d5c5,AMBER=0xffe4b771;')
old='''        Runnable update=()->{
            String source=original.getText().toString();Observation row=editorSample(type,source,entry);
            preview.setText("Will say:\\n"+settings.preview(row,type,source,spoken.getText().toString()));
        };'''
new='''        Runnable update=()->{
            String source=original.getText().toString();Observation row=editorSample(type,source,entry);
            String will=settings.preview(row,type,source,spoken.getText().toString());
            String missing=engine.voices.recordingIssue(will);
            preview.setText("Will say:\\n"+will+(missing.isEmpty()?"\\n\\nExact recording: ready":"\\n\\nExact recording missing: "+missing));
            preview.setTextColor(missing.isEmpty()?WHITE:AMBER);
        };'''
s=rep(s,old,new)
old='''        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{settings.save(type,original.getText().toString(),spoken.getText().toString());saved();showCatalog(type);}catch(Exception ex){error(ex);}});'''
new='''        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            String source=original.getText().toString();
            String replacement=spoken.getText().toString();
            String will=settings.preview(editorSample(type,source,entry),type,source,replacement);
            settings.save(type,source,replacement);
            engine.voices.stop();
            String missing=engine.voices.recordingIssue(will);
            Toast.makeText(activity,missing.isEmpty()?"Speech settings saved. New updates use the new wording.":"Wording saved, but "+missing,Toast.LENGTH_LONG).show();
            showCatalog(type);
        }catch(Exception ex){error(ex);}});'''
s=rep(s,old,new)
p.write_text(s)

# Monitor: do not age new alerts out just because Silvertracker was delayed;
# do not let manual historical paging contaminate live cards or speech.
p=java/'PatrolEngine.java'
s=p.read_text()
s=rep(s,'        this.voices = new VoiceManager(this.context, this.prefs);\n        this.checkpoints = new CheckpointTracker(this.context);',
      '''        this.voices = new VoiceManager(this.context, this.prefs);
        this.checkpoints = new CheckpointTracker(this.context);
        this.lastRead = prefs.getLong("last_live_read_at",0L);
        if(this.lastRead < this.checkpoints.shiftStart()) this.lastRead = 0L;''')

s=rep(s,'        if (!refreshActive && now >= nextCheckElapsed) refreshMonitor();',
      '''        if (browserVisible) {
            // Do not fight the operator while they use Silvertracker's own pager.
            if (!refreshActive) nextCheckElapsed = now + INTERVAL;
        } else if (!refreshActive && now >= nextCheckElapsed) refreshMonitor();''')

s=rep(s,'        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);',
      '''        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(false);
        s.setTextZoom(115);''')

old='''                List<Observation> selected = FeedReducer.selected(observations, guards());
                voices.speechSettings.remember(selected);
                checkpoints.observe(selected);
                Map<String, Observation> batch = FeedReducer.latest(selected, guards(), false);
                boolean hadBaseline = lastRead > 0;
                List<Observation> announce = speechLedger.collect(selected, hadBaseline, now);'''
new='''                List<Observation> selected = FeedReducer.selected(observations, guards());
                voices.speechSettings.remember(selected);
                checkpoints.observe(selected);

                if(browserVisible) {
                    selectedCount=selected.size();
                    acceptedThisNavigation=true; refreshActive=false; navigating=false; inspectionPending=false;
                    completedRefreshes++; consecutiveFailures=0; rebuildBeforeRetry=false;
                    CookieManager.getInstance().flush();
                    documentState="history page counted into shift tally";
                    nextCheckElapsed=SystemClock.elapsedRealtime()+INTERVAL;
                    status("HISTORY_READ", "This Silvertracker page was added to the 18:00 shift checkpoint tally. Automatic polling is paused while this screen is open.");
                    return;
                }

                Map<String, Observation> batch = FeedReducer.latest(selected, guards(), false);
                boolean hadBaseline = lastRead > 0;
                List<Observation> announce = speechLedger.collect(selected, hadBaseline, lastRead, now);'''
s=rep(s,old,new)

s=rep(s,'selectedCount = batch.size(); lastRead = now; lastReadElapsed = SystemClock.elapsedRealtime();',
      '''selectedCount = batch.size(); lastRead = now; lastReadElapsed = SystemClock.elapsedRealtime();
                prefs.edit().putLong("last_live_read_at",now).apply();''')

anchor='''    public void inspectNow() {
        if (refreshActive && web != null && !navigating) inspect(navigationToken, false);
        else if (!refreshActive) refreshMonitor();
    }'''
replacement='''    public void scanVisiblePage() {
        if(!running)return;
        if(web==null){openMonitor();return;}
        if(!monitorPage(web.getUrl())){
            status("SIGN_IN_REQUIRED","Open Silvertracker and sign in once.");
            return;
        }
        if(navigating){status("CHECKING","Waiting for this Silvertracker page to finish loading.");return;}
        long now=SystemClock.elapsedRealtime();
        refreshAttempt++; navigationToken++;
        refreshActive=true; inspectionPending=false; acceptedThisNavigation=false; lastPageFailed=false; navigating=false;
        startedElapsed=now; refreshDeadline=now+10_000L;
        documentState="manual visible-page scan";
        final long token=navigationToken;
        handler.post(()->inspect(token,false));
    }

    public void resumeLiveFeed() {
        browserVisible=false;
        if(!running)return;
        refreshActive=false; inspectionPending=false; navigating=false; acceptedThisNavigation=false; lastPageFailed=false;
        navigationToken++; signedMonitorUrl=MONITOR;
        nextCheckElapsed=SystemClock.elapsedRealtime();
        handler.post(this::refreshMonitor);
    }

    public void inspectNow() {
        if(browserVisible){scanVisiblePage();return;}
        if (refreshActive && web != null && !navigating) inspect(navigationToken, false);
        else if (!refreshActive) refreshMonitor();
    }'''
s=rep(s,anchor,replacement)

exit_anchor='''latest.clear(); present.clear(); recentById.clear(); speechLedger.clear(); welcomeSpokenThisSession = false; lastRead = lastReadElapsed = 0;'''
exit_new='''latest.clear(); present.clear(); recentById.clear(); speechLedger.clear(); welcomeSpokenThisSession = false; lastRead = lastReadElapsed = 0;
        prefs.edit().remove("last_live_read_at").apply();'''
s=rep(s,exit_anchor,exit_new)

s=s.replace('"\\nRefresh cadence: adaptive 10s normal / 15s backoff / 30s repeated failure" +',
            '"\\nRefresh cadence: adaptive 10s normal / 15s backoff / 30s repeated failure; paused during manual Silvertracker paging" +')
p.write_text(s)

# UI: compact/useable Silvertracker surface, visible shift tally, and clear manual paging instructions.
p=java/'MainActivity.java'
s=p.read_text()
s=rep(s,'private TextView stateText, detailText, readText, countdownText, voiceStatusText;',
      'private TextView stateText, detailText, readText, countdownText, voiceStatusText, browserTallyText;')

# Tally dialog guidance and reset semantics.
s=s.replace('Counting unique patrol/scan entries since "+since+". The tally persists if Patrol Link is closed.',
            'Counting unique patrol/scan entries from "+since+". Open Silvertracker and use its own page arrows to scan older pages back to 18:00; every page you load is added once.')
s=s.replace('final AlertDialog[] holder=new AlertDialog[1];',
'''final AlertDialog[] holder=new AlertDialog[1];
        addCard(list,button("Open Silvertracker · scan older pages",true,()->{
            if(holder[0]!=null)holder[0].dismiss();
            showBrowser();
        }));''',1)
s=s.replace('dangerButton("Reset shift tally"', 'dangerButton("Clear & restart current 18:00 shift"')
s=s.replace('This clears the checkpoint counts for all patrol guards and starts a new shift tally now.',
            'This clears the checkpoint counts for all patrol guards and restarts the tally from the current 18:00 shift boundary.')
s=s.replace('toast("Shift checkpoint tally reset.");','toast("Shift tally cleared. Counting again from 18:00.");')

start=s.index('    private void showBrowser() {')
end=s.index('    private void detachWeb()',start)
browser=r'''    private void showBrowser() {
        browserMode=true; engine.browserVisible=true; base();
        LinearLayout toolbar=vertical(); toolbar.setPadding(dp(12),dp(8),dp(12),0);
        toolbar.addView(text("SILVERTRACKER · SHIFT HISTORY",11,ACCENT,true)); gap(toolbar,6);
        buttonRow(toolbar,
            button("Dashboard",false,()->{showDashboard();engine.resumeLiveFeed();}),
            button("Scan this page",true,engine::scanVisiblePage));
        root.addView(toolbar);

        browserTallyText=text("",11,ACCENT,true);
        browserTallyText.setPadding(dp(14),0,dp(14),dp(7));
        root.addView(browserTallyText);
        refreshBrowserTally();

        TextView note=text("Automatic polling is paused while this screen is open so Silvertracker's own page arrows are not interrupted. Use its < and > controls to go back through the shift. Every page that loads is added to the 18:00 checkpoint tally; duplicate Issue IDs and duplicate checkpoint+time rows are ignored. Pinch to zoom if needed.",11,MUTED,false);
        note.setPadding(dp(14),0,dp(14),dp(8)); root.addView(note);

        attachedWeb=engine.webView();
        if(attachedWeb.getParent() instanceof ViewGroup) ((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb);
        root.addView(attachedWeb,new LinearLayout.LayoutParams(-1,0,1));
        if(attachedWeb.getUrl()==null || "about:blank".equals(attachedWeb.getUrl())) engine.openMonitor();
        else engine.scanVisiblePage();
    }

    private void refreshBrowserTally() {
        if(browserTallyText==null)return;
        String start=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(
                Instant.ofEpochMilli(engine.checkpoints.shiftStart()).atZone(engine.zone()));
        StringBuilder b=new StringBuilder("SHIFT FROM ").append(start);
        for(String guard:engine.guards()) b.append("   ·   ").append(guard).append(" ").append(engine.checkpoints.total(guard));
        browserTallyText.setText(b.toString());
    }

'''
s=s[:start]+browser+s[end:]

s=rep(s,'        if(browserMode || stateText==null) return;',
      '''        if(browserMode){refreshBrowserTally();return;}
        if(stateText==null)return;''')
s=rep(s,'    @Override public void onBackPressed() { if(browserMode)showDashboard();else moveTaskToBack(true); }',
      '''    @Override public void onBackPressed() {
        if(browserMode){showDashboard();engine.resumeLiveFeed();}
        else moveTaskToBack(true);
    }''')

# Help copy no longer tells users the browser will refresh while they are paging.
s=s.replace('Patrol Link normally requests an uncached monitor page every 10 seconds and waits for readable rows. Requests never overlap; slow/error states back off to 15 or 30 seconds.',
            'Patrol Link normally requests an uncached monitor page every 10 seconds and waits for readable rows. Requests never overlap; slow/error states back off to 15 or 30 seconds. Automatic polling pauses while the Silvertracker screen is open so manual page navigation is not interrupted.')
p.write_text(s)

# Existing canonical regression now expects the corrected phrase too.
p=root/'app/src/androidTest/java/au/com/roningroup/patrollink/SingleWavCanonicalTest.java'
s=p.read_text()
s=rep(s,'assertEquals(359,p.size());','assertEquals(360,p.size());')
s=rep(s,'assertTrue(p.contains("Welfare Check"));',
      '''assertTrue(p.contains("Welfare Check"));
        assertTrue(p.contains("Door Found Open"));
        assertTrue(p.contains("General Patrol - Balmara Place"));
        assertTrue(p.contains("General Patrol - Ceil Circuit"));''')
p.write_text(s)

# Additional regression coverage.
unit=root/'app/src/test/java/au/com/roningroup/patrollink'
instrument=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
shutil.copyfile(payload/'AnnouncementLedgerReliabilityTest.java',unit/'AnnouncementLedgerReliabilityTest.java')
shutil.copyfile(payload/'ShiftCheckpointHistoryTest.java',instrument/'ShiftCheckpointHistoryTest.java')
shutil.copyfile(payload/'SpeechPreviewParityTest.java',instrument/'SpeechPreviewParityTest.java')

assert "versionName '1.1.20'" in (root/'app/build.gradle').read_text()
assert 'General Patrol - Ceil Circuit' in canonical.read_text()
assert 'last_live_read_at' in (java/'PatrolEngine.java').read_text()
assert 'browserVisible' in (java/'PatrolEngine.java').read_text()
assert 'scanVisiblePage' in (java/'PatrolEngine.java').read_text()
assert 'setLoadWithOverviewMode(false)' in (java/'PatrolEngine.java').read_text()
assert 'checkpoint+time' in (java/'MainActivity.java').read_text()
