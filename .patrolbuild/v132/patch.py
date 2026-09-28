from pathlib import Path
import shutil,sys
root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'
payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s:
        raise RuntimeError('v132 patch anchor missing: '+old[:240])
    return s.replace(old,new,1)

# Version.
p=root/'app/build.gradle'
s=p.read_text()
s=rep(s,'versionCode 131','versionCode 132')
s=rep(s,"versionName '1.1.21'","versionName '1.1.22'")
p.write_text(s)

# Replace the fixed checkpoint whitelist/two-key duplicate handling with the
# dynamic guard + area + Silvertracker timestamp tracker.
shutil.copyfile(payload/'CheckpointTracker.java',java/'CheckpointTracker.java')

# Replace fixed tally UI with dynamic areas learned from Silvertracker.
p=java/'MainActivity.java'
s=p.read_text()
start=s.index('    private void showCheckpointTally(int index) {')
end=s.index('    private void showBrowser() {',start)
method=r'''    private void showCheckpointTally(int index) {
        String guard=engine.guards().get(index);
        String nickname=engine.voices.speechSettings.nickname(guard);
        String shownName=nickname==null||nickname.trim().isEmpty()?guard:nickname.trim()+"  ·  "+guard;

        LinearLayout list=vertical();list.setPadding(dp(16),dp(8),dp(16),dp(8));
        long started=engine.checkpoints.shiftStart();
        String since=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(Instant.ofEpochMilli(started).atZone(engine.zone()));

        list.addView(text("SHIFT CHECKPOINT TALLY",11,ACCENT,true));gap(list,5);
        list.addView(text(shownName,22,WHITE,true));gap(list,5);
        list.addView(text("Unique hit = area + Silvertracker recorded time + guard. The same page can be scanned again without increasing the count. The same area/time hit by a different guard counts separately.",12,MUTED,false));gap(list,8);
        list.addView(text("Shift starts "+since+". New General Patrol/scan areas are learned automatically; they do not need to be pre-programmed.",12,MUTED,false));gap(list,16);

        final AlertDialog[] holder=new AlertDialog[1];
        addCard(list,button("Open Silvertracker · scan older pages",true,()->{
            if(holder[0]!=null)holder[0].dismiss();
            showBrowser();
        }));

        java.util.List<CheckpointTracker.AreaCount> areas=engine.checkpoints.areas(guard);
        if(areas.isEmpty()){
            LinearLayout empty=card();
            empty.addView(text("No checkpoint hits recorded for this guard yet.",15,MUTED,false));
            addCard(list,empty);
        } else {
            for(CheckpointTracker.AreaCount area:areas){
                LinearLayout c=card();
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
                TextView label=text(area.label,15,WHITE,true);
                TextView count=text(String.valueOf(area.count),20,ACCENT,true);
                count.setGravity(Gravity.END);
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                row.addView(count,new LinearLayout.LayoutParams(dp(58),-2));
                c.addView(row);
                if(area.lastAt>0){
                    gap(c,5);
                    String when=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(Instant.ofEpochMilli(area.lastAt).atZone(engine.zone()));
                    c.addView(text("Latest counted hit: "+when,11,MUTED,false));
                }
                addCard(list,c);
            }
        }

        LinearLayout totalCard=card();
        totalCard.addView(text("TOTAL CHECKPOINT HITS  ·  "+engine.checkpoints.total(guard),16,ACCENT,true));
        addCard(list,totalCard);

        addCard(list,dangerButton("Clear & restart current 18:00 shift",()->new AlertDialog.Builder(this)
            .setTitle("Clear shift tally?")
            .setMessage("This clears checkpoint counts for all three guards and restarts the tally from the current 18:00 shift boundary.")
            .setPositiveButton("Clear",(d,w)->{
                engine.checkpoints.reset();
                toast("Shift tally cleared. Counting again from 18:00.");
                if(holder[0]!=null)holder[0].dismiss();
                showCheckpointTally(index);
            }).setNegativeButton("Cancel",null).show()));

        ScrollView scroll=new ScrollView(this);scroll.addView(list);
        holder[0]=new AlertDialog.Builder(this).setTitle("Checkpoint tally").setView(scroll).setPositiveButton("Close",null).create();
        holder[0].show();
    }

'''
s=s[:start]+method+s[end:]

# Make the history screen show exactly what the most recently scanned page
# contributed for each guard, so failures are immediately visible.
start=s.index('    private void refreshBrowserTally() {')
end=s.index('    private void detachWeb()',start)
refresh=r'''    private void refreshBrowserTally() {
        if(browserTallyText==null)return;
        String start=DateTimeFormatter.ofPattern("HH:mm · d MMM").format(
                Instant.ofEpochMilli(engine.checkpoints.shiftStart()).atZone(engine.zone()));
        StringBuilder totals=new StringBuilder("SHIFT FROM ").append(start);
        StringBuilder added=new StringBuilder("LAST PAGE ADDED");
        for(String guard:engine.guards()){
            totals.append("   ·   ").append(guard).append(" ").append(engine.checkpoints.total(guard));
            added.append("   ·   ").append(guard).append(" +").append(engine.checkpoints.lastPageAdded(guard));
        }
        browserTallyText.setText(totals.append("\n").append(added).toString());
    }

'''
s=s[:start]+refresh+s[end:]
p.write_text(s)

# Older fixed-list tests intentionally target the retired implementation.
for name in ['CheckpointTrackerTest.java','ShiftCheckpointHistoryTest.java','RecCentreLabelsTest.java']:
    old=root/'app/src/androidTest/java/au/com/roningroup/patrollink'/name
    if old.exists(): old.unlink()

shutil.copyfile(payload/'DynamicCheckpointKeyTest.java',
                root/'app/src/androidTest/java/au/com/roningroup/patrollink/DynamicCheckpointKeyTest.java')

# Sanity checks.
assert "versionName '1.1.22'" in (root/'app/build.gradle').read_text()
tracker=(java/'CheckpointTracker.java').read_text()
assert 'guard+"|"+area.key+"|"+o.recordedAt' in tracker
assert 'Issue ID is deliberately NOT part' in tracker
assert 'any new valid patrol/scan area' in tracker
main=(java/'MainActivity.java').read_text()
assert 'Unique hit = area + Silvertracker recorded time + guard.' in main
assert 'LAST PAGE ADDED' in main
assert 'CheckpointTracker.POINTS' not in main
