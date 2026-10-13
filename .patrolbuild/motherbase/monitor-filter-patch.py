from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);j=root/'app/src/main/java/au/com/roningroup/patrollink';here=Path(__file__).parent
shutil.copyfile(here/'MonitorFilterUi.java',j/'MonitorFilterUi.java')
def replace(path,old,new):
 s=path.read_text()
 if s.count(old)!=1:raise RuntimeError('Monitor filter patch anchor mismatch: '+str(path)+' '+old[:100])
 path.write_text(s.replace(old,new,1))
replace(root/'app/build.gradle','versionCode 160','versionCode 161')
replace(root/'app/build.gradle',"versionName '1.1.50'","versionName '1.1.51'")
main=j/'MainActivity.java'
replace(main,'root.addView(attachedWeb,new LinearLayout.LayoutParams(-1,0,1));','root.addView(attachedWeb,new LinearLayout.LayoutParams(-1,0,1));\n        MonitorFilterUi.bind(this,attachedWeb);')
# Provide an explicit fallback using the same existing website option list.
replace(main,'buttonRow(toolbar,button("Dashboard",false,()->{ engine.inspectNow(); timer.postDelayed(this::showDashboard, 700); }),button("Refresh now",true,engine::refreshMonitor));',
 'buttonRow(toolbar,button("Dashboard",false,()->{ engine.inspectNow(); timer.postDelayed(this::showDashboard, 700); }),button("Refresh now",true,engine::refreshMonitor));\n        toolbar.addView(button("Filter reported issue",false,()->MonitorFilterUi.show(this,engine.webView())));')
replace(main,'super.onResume();engine.appVisible=true;engine.browserVisible=browserMode;','super.onResume();engine.appVisible=true;engine.browserVisible=browserMode;if(browserMode)MonitorFilterUi.resume(this);')
replace(main,'engine.appVisible=false;engine.browserVisible=false;timer.removeCallbacks(tick);super.onPause();','MonitorFilterUi.pause(this);engine.appVisible=false;engine.browserVisible=false;timer.removeCallbacks(tick);super.onPause();')
replace(main,'engine.remove(this);timer.removeCallbacks(tick);','MonitorFilterUi.unbind(this);engine.remove(this);timer.removeCallbacks(tick);')
engine=j/'PatrolEngine.java'
replace(engine,'web = new WebView(context);','web = new WebView(context);\n        MonitorFilterUi.prepare(web);')
replace(engine,'if (now >= nextCheckElapsed) {','if (now >= nextCheckElapsed && !MonitorFilterUi.interacting(web)) {')
replace(engine,'navigating = false; CookieManager.getInstance().flush();','navigating = false; CookieManager.getInstance().flush();\n                MonitorFilterUi.pageReady(view);')
testdir=root/'app/src/androidTest/java/au/com/roningroup/patrollink';testdir.mkdir(parents=True,exist_ok=True)
shutil.copyfile(here/'MonitorFilterUiTest.java',testdir/'MonitorFilterUiTest.java')
# Confirm the real Android runner without dumping signing settings.
print('MONITOR_FILTER_PATCH_OK: v1.1.51; native HTML-select bridge; fallback button; existing session and signing retained')
