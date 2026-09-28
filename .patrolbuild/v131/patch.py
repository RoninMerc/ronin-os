from pathlib import Path
import shutil,sys
root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'
payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s:
        raise RuntimeError('v131 patch anchor missing: '+old[:240])
    return s.replace(old,new,1)

# Version.
p=root/'app/build.gradle'
s=p.read_text()
s=rep(s,'versionCode 130','versionCode 131')
s=rep(s,"versionName '1.1.20'","versionName '1.1.21'")
p.write_text(s)

# Correct the two recreation-centre checkpoint names while preserving the
# existing rec1/rec2 IDs so current shift counts survive the in-place update.
p=java/'CheckpointTracker.java'
s=p.read_text()
old='''        new Point("rec1","Recreation Centre 1","PBC 2","recreational facility 1","recreation centre 1","rec centre 1"),
        new Point("rec2","Recreation Centre 2","PBC 2","recreational facility 2","recreation centre 2","rec centre 2","recreational facility gym","recreation centre gym"),'''
new='''        new Point("rec2","Rec Center 2 Gym","PBC 2",
                "recreational facility gym","recreation centre gym","recreation center gym",
                "rec centre gym","rec center gym","rec center 2 gym","recreation center 2 gym",
                "recreational facility 2"),
        new Point("rec1","Rec Center 2 Barbecue Area","PBC 2",
                "recreational facility 1","recreational facility bbq","recreational facility barbecue",
                "recreation centre bbq","recreation center bbq","rec centre bbq","rec center bbq",
                "barbecue area","bbq area","rec center 2 barbecue area","recreation center 2 barbecue area"),'''
s=rep(s,old,new)
p.write_text(s)

# The screenshot showed a healthy, current feed rendered at desktop-fit scale:
# this is a WebView viewport/display problem, not a failed network load.
# Keep the desktop layout, but start it at a readable scale instead of shrinking
# the whole Silvertracker page to the phone width.
p=java/'PatrolEngine.java'
s=p.read_text()
old='''        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(false);
        s.setTextZoom(115);'''
new='''        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        web.setInitialScale(135);
        web.setHorizontalScrollBarEnabled(true);
        web.setVerticalScrollBarEnabled(true);'''
s=rep(s,old,new)

# Add native page controls so the user no longer has to hit Silvertracker's
# microscopic pager inside the legacy desktop page.
anchor='''    public void scanVisiblePage() {
        if(!running)return;'''
method=r'''    public void pageHistory(boolean right) {
        if(!running || web==null || !browserVisible)return;
        if(!monitorPage(web.getUrl())){
            status("SIGN_IN_REQUIRED","Open Silvertracker and sign in once.");
            return;
        }
        if(navigating){
            status("CHECKING","Waiting for the current Silvertracker page to finish loading.");
            return;
        }

        final String wanted = right
                ? "['>','›','»','next','next page']"
                : "['<','‹','«','previous','prev','previous page']";
        String script =
                "(function(){"+
                "var wanted="+wanted+";"+
                "var els=[].slice.call(document.querySelectorAll('a,button,input[type=button],input[type=submit],span'));"+
                "for(var i=els.length-1;i>=0;i--){"+
                " var e=els[i];"+
                " var cs=window.getComputedStyle(e);"+
                " if(!cs||cs.display==='none'||cs.visibility==='hidden'||e.offsetParent===null)continue;"+
                " if(e.disabled||e.getAttribute('aria-disabled')==='true'||((' '+e.className+' ').toLowerCase().indexOf(' disabled ')>=0))continue;"+
                " var t=((e.innerText||e.value||e.getAttribute('aria-label')||e.title||'')+'').replace(/\\s+/g,' ').trim().toLowerCase();"+
                " if(wanted.indexOf(t)>=0){e.click();return 'CLICKED:'+t;}"+
                "}"+
                "return 'NOT_FOUND';"+
                "})()";

        status("HISTORY_NAV","Moving to another Silvertracker page…");
        web.evaluateJavascript(script,result->{
            if(result!=null && result.contains("NOT_FOUND")){
                status("HISTORY_NAV","Silvertracker's page arrow was not found on this page. You can still use its own < and > controls below.");
                return;
            }
            handler.postDelayed(()->{
                if(browserVisible && running && !navigating && !refreshActive)scanVisiblePage();
            },1800L);
        });
    }

    public void scanVisiblePage() {
        if(!running)return;'''
s=rep(s,anchor,method)
p.write_text(s)

# Browser toolbar: native page buttons mirror the legacy site's < and > controls.
p=java/'MainActivity.java'
s=p.read_text()
old='''        buttonRow(toolbar,
            button("Dashboard",false,()->{showDashboard();engine.resumeLiveFeed();}),
            button("Scan this page",true,engine::scanVisiblePage));
        root.addView(toolbar);'''
new='''        buttonRow(toolbar,
            button("Dashboard",false,()->{showDashboard();engine.resumeLiveFeed();}),
            button("Scan this page",true,engine::scanVisiblePage));
        buttonRow(toolbar,
            button("‹  Silvertracker page",false,()->engine.pageHistory(false)),
            button("Silvertracker page  ›",false,()->engine.pageHistory(true)));
        root.addView(toolbar);'''
s=rep(s,old,new)

old='''        TextView note=text("Automatic polling is paused while this screen is open so Silvertracker's own page arrows are not interrupted. Use its < and > controls to go back through the shift. Every page that loads is added to the 18:00 checkpoint tally; duplicate Issue IDs and duplicate checkpoint+time rows are ignored. Pinch to zoom if needed.",11,MUTED,false);'''
new='''        TextView note=text("Automatic polling is paused while this screen is open. Use the large Silvertracker page buttons above instead of trying to hit the tiny website arrows. Every page that loads is added to the 18:00 checkpoint tally; duplicate Issue IDs and duplicate checkpoint+time rows are ignored. The website is shown at a readable desktop zoom and can be panned or pinched.",11,MUTED,false);'''
s=rep(s,old,new)
p.write_text(s)

# Regression coverage.
shutil.copyfile(payload/'RecCentreLabelsTest.java',
                root/'app/src/androidTest/java/au/com/roningroup/patrollink/RecCentreLabelsTest.java')

assert "versionName '1.1.21'" in (root/'app/build.gradle').read_text()
tracker=(java/'CheckpointTracker.java').read_text()
assert 'Rec Center 2 Gym' in tracker
assert 'Rec Center 2 Barbecue Area' in tracker
assert 'Recreation Centre 1' not in tracker
assert 'Recreation Centre 2' not in tracker
engine=(java/'PatrolEngine.java').read_text()
assert 'setInitialScale(135)' in engine
assert 'setUseWideViewPort(true)' in engine
assert 'pageHistory(boolean right)' in engine
main=(java/'MainActivity.java').read_text()
assert 'Silvertracker page  ›' in main
assert '‹  Silvertracker page' in main
