from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);j=root/'app/src/main/java/au/com/roningroup/patrollink';here=Path(__file__).parent
def replace(path,old,new):
 s=path.read_text()
 if s.count(old)!=1:raise RuntimeError('Vehicle capture patch anchor mismatch: '+str(path)+' '+old[:100])
 path.write_text(s.replace(old,new,1))
replace(root/'app/build.gradle','versionCode 161','versionCode 162')
replace(root/'app/build.gradle',"versionName '1.1.51'","versionName '1.1.52'")
shutil.copyfile(here/'VehicleCaptureUi.java',j/'VehicleCaptureUi.java')
engine=j/'PatrolEngine.java'
capture='SilverTrackVehicleCollector.collect(context,web,data.optJSONArray("allRows"),zone());'
s=engine.read_text()
assert s.count(capture)==1
s=s.replace('                    '+capture+'\n','',1)
anchor='GuardDirectory.remember(prefs, data.optJSONArray("allRows"));'
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'\n                '+capture,1)
# Preserve the live publishing guard. Historical browser results are capture-only.
assert s.index(capture)<s.index('if(!browserVisible && MasterFeedClient.master(context)){')
assert 'if(!browserVisible && MasterFeedClient.master(context)){' in s
engine.write_text(s)
main=j/'MainActivity.java'
anchor='toolbar.addView(button("Filter reported issue",false,()->MonitorFilterUi.show(this,engine.webView())));'
replace(main,anchor,anchor+'\n        toolbar.addView(button("Capture parking reports",false,()->VehicleCaptureUi.show(this,engine)));')
print('VEHICLE_CAPTURE_PATCH_OK: capture works with visible browser; live feed publishing guard preserved; v1.1.52')
