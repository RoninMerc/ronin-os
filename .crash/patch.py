from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]);here=Path(__file__).parent;j=root/'app/src/main/java/au/com/roningroup/patrollink'
p=root/'app/build.gradle';s=p.read_text();assert 'versionCode 169' in s;p.write_text(s.replace('versionCode 169','versionCode 170').replace("versionName '1.1.57'","versionName '1.1.58'"))
for p in here.glob('*.java'):
 if not p.stem.endswith('Test'):shutil.copyfile(p,j/p.name)
p=j/'MonitorService.java';s=p.read_text();anchor='    @Override public int onStartCommand(Intent intent, int flags, int startId) {'
assert anchor in s;s=s.replace(anchor,anchor+'\n        if(CrashReport.pending(this)){stopSelf();return START_NOT_STICKY;}');p.write_text(s)
p=j/'LocalGpsService.java';s=p.read_text();anchor='public int onStartCommand(Intent intent,int flags,int startId){'
assert anchor in s;s=s.replace(anchor,anchor+'\n        if(CrashReport.pending(this)){stopSelf();return START_NOT_STICKY;}');p.write_text(s)
p=j/'SilverTrackVehicleCollector.java';s=p.read_text();assert 'setOffscreenPreRaster(true)' in s;p.write_text(s.replace('setOffscreenPreRaster(true)','setOffscreenPreRaster(false)'))
tests=root/'app/src/androidTest/java/au/com/roningroup/patrollink';shutil.copyfile(here/'CrashReportTest.java',tests/'CrashReportTest.java')
p=tests/'TabletUpgradeTest.java';s=p.read_text();assert 'assertEquals(169,' in s;p.write_text(s.replace('assertEquals(169,','assertEquals(170,'))
print('CRASH_RECOVERY_OK: code170; local bounded diagnostics, deliberate retry, preserved private storage and release signing')
