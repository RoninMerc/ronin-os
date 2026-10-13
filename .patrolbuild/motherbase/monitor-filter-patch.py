from pathlib import Path
import shutil,sys,re
root=Path(sys.argv[1]);j=root/'app/src/main/java/au/com/roningroup/patrollink';here=Path(__file__).parent
shutil.copyfile(here/'MonitorFilterUi.java',j/'MonitorFilterUi.java')
def replace(path,old,new):
 s=path.read_text()
 if s.count(old)!=1:raise RuntimeError('Monitor filter patch anchor mismatch: '+str(path)+' '+old[:100])
 path.write_text(s.replace(old,new,1))
def rx(path,pattern,change):
 s=path.read_text();s,n=re.subn(pattern,change,s,count=0)
 if n!=1:raise RuntimeError('Monitor filter method anchor mismatch: '+str(path)+' '+pattern)
 path.write_text(s)
replace(root/'app/build.gradle','versionCode 160','versionCode 161')
replace(root/'app/build.gradle',"versionName '1.1.50'","versionName '1.1.51'")
main=j/'MainActivity.java'
replace(main,'root.addView(attachedWeb,new LinearLayout.LayoutParams(-1,0,1));',
 'root.addView(attachedWeb,new LinearLayout.LayoutParams(-1,0,1));\n        MonitorFilterUi.bind(this,attachedWeb);\n        toolbar.addView(button("Filter reported issue",false,()->MonitorFilterUi.show(this,engine.webView())));')
rx(main,r'(void\s+onResume\s*\([^)]*\)\s*\{)',lambda m:m[1]+' if(browserMode)MonitorFilterUi.resume(this); ')
rx(main,r'(void\s+onPause\s*\([^)]*\)\s*\{)',lambda m:m[1]+' MonitorFilterUi.pause(this); ')
rx(main,r'(void\s+onDestroy\s*\([^)]*\)\s*\{)',lambda m:m[1]+' MonitorFilterUi.unbind(this); ')
engine=j/'PatrolEngine.java'
rx(engine,r'(web\s*=\s*new WebView\(context\);)',lambda m:m[1]+'\n        MonitorFilterUi.prepare(web);')
rx(engine,r'if\s*\(\s*now\s*>=\s*nextCheckElapsed\s*\)',lambda m:'if(now>=nextCheckElapsed&&!MonitorFilterUi.interacting(web))')
rx(engine,r'(void\s+onPageFinished\s*\(WebView\s+(\w+),\s*String\s+\w+\)\s*\{)',lambda m:m[1]+' MonitorFilterUi.pageReady('+m[2]+'); ')
testdir=root/'app/src/androidTest/java/au/com/roningroup/patrollink';testdir.mkdir(parents=True,exist_ok=True)
shutil.copyfile(here/'MonitorFilterUiTest.java',testdir/'MonitorFilterUiTest.java')
print('MONITOR_FILTER_PATCH_OK: v1.1.51; native HTML-select bridge; fallback button; existing session and signing retained')
