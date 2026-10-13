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
 if n!=1:
  for line in path.read_text().splitlines():
   if any(x in line for x in ['Runnable','nextCheckElapsed','onPageFinished','public void run']):print('FILTER_FLOW: '+line[:240])
  raise RuntimeError('Monitor filter method anchor mismatch: '+str(path)+' '+pattern)
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
rx(engine,r'(Runnable\s+cycle\s*=\s*new Runnable\s*\(\s*\)\s*\{\s*(?:@Override\s*)?public\s+void\s+run\s*\(\s*\)\s*\{)',lambda m:m[1]+' if(MonitorFilterUi.interacting(web)){handler.postDelayed(this,1000);return;} ')
rx(engine,r'(void\s+onPageFinished\s*\(WebView\s+(\w+),\s*String\s+\w+\)\s*\{)',lambda m:m[1]+' MonitorFilterUi.pageReady('+m[2]+'); ')
testdir=root/'app/src/androidTest/java/au/com/roningroup/patrollink';testdir.mkdir(parents=True,exist_ok=True)
shutil.copyfile(here/'MonitorFilterUiTest.java',testdir/'MonitorFilterUiTest.java')
print('MONITOR_FILTER_PATCH_OK: v1.1.51; native HTML-select bridge; fallback button; existing session and signing retained')

# Use AndroidJUnitRunner for the new real-WebView checks, including older fixture hooks.
gradle=root/'app/build.gradle';s=gradle.read_text()
runners=re.findall(r'''testInstrumentationRunner\s+["']([^"']+)["']''',s)
for runner in runners:s=s.replace(runner,'androidx.test.runner.AndroidJUnitRunner')
if not runners:
 s,n=re.subn(r'(defaultConfig\s*\{)',lambda m:m[1]+'\n        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"',s,count=1)
 if n!=1:raise RuntimeError('defaultConfig instrumentation anchor missing')
gradle.write_text(s)
manifest=root/'app/src/androidTest/AndroidManifest.xml'
if manifest.exists():
 xml=manifest.read_text()
 xml=re.sub(r'''(<instrumentation\b[^>]*?android:name=)["'][^"']+["']''',lambda m:m[1]+'"androidx.test.runner.AndroidJUnitRunner"',xml)
 manifest.write_text(xml)
print('FILTER_RUNNER: AndroidJUnitRunner; previous runner names: '+','.join(runners))
