from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(s,old,new):
    if old not in s:raise RuntimeError('v136 missing anchor: '+old[:130])
    return s.replace(old,new,1)

p=root/'app/build.gradle'
if p.exists():
    s=p.read_text();s=rep(s,'versionCode 135','versionCode 136');s=rep(s,"versionName '1.1.25'","versionName '1.1.26'");p.write_text(s)

# DOM extraction must not return a partial table success before trying other rows.
p=root/'app/src/main/assets/extract.js';s=p.read_text()
s=rep(s,".replace(/\\s+/g, ' ').trim();", ".replace(/[\\u200B-\\u200D\\uFEFF]/g, '').replace(/\\s+/g, ' ').trim();")
s=rep(s,"const allowed = new Set((guardIds || []).map(key));", """const allowed = new Set((guardIds || []).map(key));
    const guardKey = x => key(x).replace(/[. _-]/g, '');
    const canonical = new Map((guardIds || []).map(g => [guardKey(g),norm(g)]));
    const creator = value => canonical.get(guardKey(value)) || norm(value);""")
s=rep(s,"schema: 4", "schema: 5")
s=rep(s,"selectedRows: [], mode:", "selectedRows: [], allRows: [], unreadRows: 0, mode:")
s=rep(s,"const seen = new Set();", """const seen = new Set();
    const handledIds = new Set();
    const idFrom = e => {
        const full=norm(e.innerText || e.textContent);
        if(issueIdRe.test(full))return full;
        const candidates=Array.from(e.querySelectorAll('a,span,label')).filter(notHidden)
            .map(n=>norm(n.innerText||n.textContent)).filter(t=>issueIdRe.test(t));
        const unique=Array.from(new Set(candidates));
        return unique.length===1?unique[0]:'';
    };
    const creatorFrom = e => {
        const full=norm(e.innerText || e.textContent);
        if(canonical.has(guardKey(full)))return creator(full);
        const values=Array.from(e.querySelectorAll('a,span,label')).filter(notHidden)
            .map(n=>norm(n.innerText||n.textContent)).filter(t=>canonical.has(guardKey(t)));
        const unique=Array.from(new Set(values.map(creator)));
        return unique.length===1?unique[0]:creator(full);
    };""")
s=rep(s,"""        if (!issueIdRe.test(id) || seen.has(id)) return false;
        seen.add(id); out.totalRows++;
        if (allowed.has(key(guard))) out.selectedRows.push({id, guard, property: property.slice(0,240), issue: issue.slice(0,8000), date: date.slice(0,120)});""", """        guard=creator(guard);
        const identity=[id,guard,property,issue,date].join('|');
        if (!issueIdRe.test(id) || seen.has(identity)) return false;
        seen.add(identity); handledIds.add(id); out.totalRows++;
        const record={id,guard,property:property.slice(0,240),issue:issue.slice(0,8000),date:date.slice(0,120)};
        out.allRows.push(record);
        if (allowed.has(key(guard))) out.selectedRows.push(record);""")
s=rep(s,"!issueIdRe.test(norm(cells[0].innerText || cells[0].textContent))", "!issueIdRe.test(idFrom(cells[0]))")
s=rep(s,"pushRow(val('id'), val('guard'), val('property'), val('issue'), val('date'));", "pushRow(idFrom(cells[idx.id]), creatorFrom(cells[idx.guard]), val('property'), val('issue'), val('date'));")
s=rep(s,"if (out.totalRows) { out.recognized = true; out.mode = 'table'; return JSON.stringify(out); }", "if (out.totalRows) { out.recognized = true; out.mode = 'table'; }")
s=rep(s,"if (out.totalRows) { out.recognized = true; out.mode = 'generic-row'; return JSON.stringify(out); }", "if (out.totalRows && out.mode==='none') { out.recognized = true; out.mode = 'generic-row'; }")
s=s.replace("seen.has(id)","handledIds.has(id)")
s=rep(s,"""        if (!issueIdRe.test(t)) return false;
        // Prefer leaf-ish""", """        if (!notHidden(e) || !issueIdRe.test(t)) return false;
        // Prefer leaf-ish""")
s=rep(s,"""            if (e.children && e.children.length) continue;""", """            if (!notHidden(e) || (e.children && e.children.length)) continue;""")
s=rep(s,"""        let guard = '';
        for (const g of allowed) if (joinedVals.toUpperCase().includes(g)) { guard = g; break; }
        if (!guard) {""", """        let guard = '';
        // Created By follows the timestamp. Never choose Assigned To or a badge
        // before the timestamp just because it matches a selected guard.
        if (!guard) {""")
s=rep(s,"{ guard = v; break; }", "{ guard = creator(v); break; }")
s=s.replace(r"\d{1,2}:\d{2}\s*(?:AM|PM)",r"\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)")
s=rep(s,"""            let guard = '';
            for (const g of allowed) if (joined.toUpperCase().includes(g)) { guard = g; break; }
            const dateLine = block.findIndex(v => date && v.includes(date));""", """            let guard = '';
            const dateLine = block.findIndex(v => date && v.includes(date));
            if(dateLine>=0 && dateLine+1<block.length)guard=creator(block[dateLine+1]);""")
s=rep(s,"""    if (out.totalRows) { out.recognized = true; out.mode = 'body-text'; }
    else out.recognized = hasMonitor;
    return JSON.stringify(out);""", """    if (out.totalRows) { out.recognized = true; if(out.mode==='none')out.mode='body-text'; }
    else out.recognized = hasMonitor;
    const visibleIds=new Set(Array.from(document.querySelectorAll('a,td,span,label'))
        .filter(notHidden).map(e=>norm(e.innerText||e.textContent)).filter(t=>issueIdRe.test(t)));
    out.unreadRows=Array.from(visibleIds).filter(id=>!handledIds.has(id)).length;
    return JSON.stringify(out);""")
p.write_text(s)

# Preserve stored keys and totals; expose per-row decisions for auditing.
p=java/'CheckpointTracker.java';s=p.read_text()
anchor='    public synchronized int lastPageAdded(String guard){'
methods='''    public static String eventIdentity(Observation o){
        Area area=areaFor(o);
        return area==null?"":guardKey(o.guard)+"|"+area.key+"|"+o.recordedAt;
    }
    public synchronized boolean containsHit(Observation o){return seenEvents.contains(eventIdentity(o));}
    public synchronized String exclusion(Observation o){
        if(o==null)return "Unreadable row";
        if(guardKey(o.guard).isEmpty())return "Created By missing";
        if(o.recordedAt<=0)return "Timestamp could not be read";
        if(o.recordedAt<shiftStart)return "Before shift start";
        if(areaFor(o)==null)return "Not a checkpoint/patrol scan";
        return "";
    }

    public synchronized int lastPageAdded(String guard){'''
s=rep(s,anchor,methods)
s=rep(s,'        changed|=prune(seenEvents);','        // Identities are retained for the entire active shift; no mid-shift eviction.')
p.write_text(s)
for name in ('HistoryPageAudit.java','HistoryScanGate.java'):shutil.copyfile(here/name,java/name)

p=java/'PatrolEngine.java';s=p.read_text()
s=rep(s,'    public final CheckpointTracker checkpoints;', '''    public final CheckpointTracker checkpoints;
    public final HistoryPageAudit historyAudit=new HistoryPageAudit();
    private final HistoryScanGate historyGate=new HistoryScanGate();
    private long nextHistoryRead;
    public String historyReadStatus="Waiting for the visible page";''')
s=rep(s,'    private void resetRenderer() {','    private void resetRenderer() {\n        historyGate.reset();')
s=rep(s,'            if (consecutiveFailures >= 2) {','            if (consecutiveFailures >= 2 && !browserVisible) {')
s=rep(s,'                documentState = "document loading";','''                documentState = "document loading";
                historyGate.reset();
                if(browserVisible)historyReadStatus="Waiting for Silvertracker page to load";''')
s=rep(s,'            if (!refreshActive) nextCheckElapsed = now + INTERVAL;','''            if (!refreshActive) {
                nextCheckElapsed = now + INTERVAL;
                if(web!=null && monitorPage(web.getUrl()) && !navigating && now>=nextHistoryRead){
                    nextHistoryRead=now+1800L;
                    scanVisiblePage();
                }
            }''')
s=rep(s,'        long now=SystemClock.elapsedRealtime();\n        refreshAttempt++; navigationToken++;', '''        if(refreshActive){
            historyReadStatus="Reading / waiting for page changes; repeated taps are combined";
            notifyChanged();return;
        }
        long now=SystemClock.elapsedRealtime();
        refreshAttempt++; navigationToken++;''')
s=rep(s,'        documentState="manual visible-page scan";','''        documentState="manual visible-page scan";
        historyReadStatus="Reading visible page";
        notifyChanged();''')
s=rep(s,'        browserVisible=false;\n        if(!running)return;', '        browserVisible=false;\n        historyGate.reset();\n        if(!running)return;')
s=rep(s,'                JSONArray entries = data.optJSONArray("selectedRows");','''                JSONArray entries = data.optJSONArray(browserVisible?"allRows":"selectedRows");
                if(entries==null)entries=data.optJSONArray("selectedRows");''')
s=rep(s,'                    inspectAgain(token);\n                    return;\n                }\n                List<Observation> observations', '''                    if(browserVisible){historyGate.reset();historyReadStatus="Page is still loading; waiting for rows";notifyChanged();}
                    inspectAgain(token);
                    return;
                }
                String historyFingerprint=entries==null?"[]":entries.toString();
                if(browserVisible && !historyGate.ready(historyFingerprint,false,SystemClock.elapsedRealtime())){
                    historyReadStatus="Checking that the new page has finished changing";notifyChanged();
                    inspectAgain(token);return;
                }
                List<Observation> observations''')
s=rep(s,'                checkpoints.observe(selected);\n\n                if(browserVisible) {', '''                if(!browserVisible)checkpoints.observe(selected);

                if(browserVisible) {
                    historyAudit.accept(historyFingerprint,observations,guards(),checkpoints,
                            rowsFound,data.optInt("unreadRows",0),now);
                    historyReadStatus=historyAudit.needsCheck()?"Page read — some rows need checking":"Scan complete — all readable rows checked";
                    nextHistoryRead=SystemClock.elapsedRealtime()+1800L;''')
s=rep(s,'status("HISTORY_READ", "This Silvertracker page was added to the 18:00 shift checkpoint tally. Automatic polling is paused while this screen is open.");', 'status("HISTORY_READ", historyReadStatus);')
p.write_text(s)

p=java/'MainActivity.java';s=p.read_text()
a=s.index('        TextView note=text("Automatic polling is paused while this screen is open.')
b=s.index('        attachedWeb=engine.webView();',a)
s=s[:a]+'''        TextView note=text("View this page’s counted / skipped entries",12,ACCENT,true);
        note.setPadding(dp(14),dp(5),dp(14),dp(10));
        note.setOnClickListener(v->{
            TextView report=text(engine.historyReadStatus+"\\n\\n"+engine.historyAudit.details(),13,WHITE,false);
            report.setPadding(dp(16),dp(12),dp(16),dp(12));
            ScrollView scroll=new ScrollView(this);scroll.addView(report);
            new AlertDialog.Builder(this).setTitle("Page scan details").setView(scroll)
                .setPositiveButton("Close",null).show();
        });
        root.addView(note);

'''+s[b:]
a=s.index('        StringBuilder totals=new StringBuilder("SHIFT FROM ").append(start);',s.index('    private void refreshBrowserTally()'))
b=s.index('    private void detachWeb()',a)
s=s[:a]+'''        StringBuilder totals=new StringBuilder("SHIFT FROM ").append(start);
        for(String guard:engine.guards()){
            totals.append("\\n").append(guard).append(" · ").append(engine.checkpoints.total(guard))
                    .append(" total | ").append(engine.historyAudit.counts(guard));
        }
        totals.append("\\n").append(engine.historyReadStatus);
        if(engine.historyAudit.readAt()>0)totals.append(" · ").append(DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(Instant.ofEpochMilli(engine.historyAudit.readAt()).atZone(engine.zone())));
        browserTallyText.setText(totals.toString());
        browserTallyText.setTextColor(engine.historyAudit.needsCheck()?AMBER:ACCENT);
    }

'''+s[b:]
p.write_text(s)

for name in ('HistoryReaderRegressionTest.java',):
    if (here/name).exists():shutil.copyfile(here/name,root/'app/src/androidTest/java/au/com/roningroup/patrollink'/name)
if (here/'test_history_dom.py').exists():shutil.copyfile(here/'test_history_dom.py',root/'tests/test_history_dom.py')
