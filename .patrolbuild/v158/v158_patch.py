from pathlib import Path
import shutil,sys,re
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:160])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 157','versionCode 158')
rep(root/'app/build.gradle',"versionName '1.1.47'","versionName '1.1.48'")

# TRL launcher / header icon
drawable=root/'app/src/main/res/drawable'
drawable.mkdir(parents=True,exist_ok=True)
shutil.copyfile(here/'trl_patrol_icon.xml',drawable/'trl_patrol_icon.xml')

# Application label + launcher icon
p=root/'app/src/main/AndroidManifest.xml'
s=p.read_text()
s=s.replace('android:label="Ronin Patrol Link"','android:label="TRL Patrol Link"')
s=s.replace('android:icon="@drawable/ic_patrol" android:roundIcon="@drawable/ic_patrol"',
            'android:icon="@drawable/trl_patrol_icon" android:roundIcon="@drawable/trl_patrol_icon"')
p.write_text(s)

# Main dashboard branding only: keep current dark/green operational palette and add restrained TRL red accent.
p=java/'MainActivity.java'; s=p.read_text()
s=s.replace(
'private static final int BG=0xff080d12, PANEL=0xff121d27, LINE=0xff263746, WHITE=0xffeef4f9, MUTED=0xff9daebb, ACCENT=0xff70d7c4, AMBER=0xffe4b771, RED=0xffe68181;',
'private static final int BG=0xff080d12, PANEL=0xff121d27, LINE=0xff263746, WHITE=0xffeef4f9, MUTED=0xff9daebb, ACCENT=0xff70d7c4, TRL_RED=0xffc7333a, AMBER=0xffe4b771, RED=0xffe68181;'
)
old='''        TextView eyebrow=text("RONIN  /  FIELD SYSTEMS",11,ACCENT,true); eyebrow.setLetterSpacing(.14f); dashboard.addView(eyebrow); gap(dashboard,10);
        dashboard.addView(text("Patrol Link",34,WHITE,true)); gap(dashboard,5);
        dashboard.addView(text("LIVE SHIFT ACTIVITY",11,MUTED,true)); gap(dashboard,22);'''
new='''        LinearLayout brandRow=new LinearLayout(this);brandRow.setOrientation(LinearLayout.HORIZONTAL);brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon=new ImageView(this);brandIcon.setImageResource(R.drawable.trl_patrol_icon);brandIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        brandRow.addView(brandIcon,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout brandText=vertical();brandText.setPadding(dp(12),0,0,0);
        TextView eyebrow=text("TRL SECURITY  /  PATROL SYSTEMS",11,TRL_RED,true);eyebrow.setLetterSpacing(.12f);brandText.addView(eyebrow);
        brandText.addView(text("TRL Patrol Link",30,WHITE,true));
        brandRow.addView(brandText,new LinearLayout.LayoutParams(0,-2,1));dashboard.addView(brandRow);gap(dashboard,6);
        dashboard.addView(text("LIVE SHIFT ACTIVITY",11,MUTED,true)); gap(dashboard,22);'''
if old not in s: raise RuntimeError('Dashboard branding anchor missing')
s=s.replace(old,new,1)

# User-facing labels only
repls={
'Check for Patrol Link update':'Check for TRL Patrol Link update',
'Patrol Link information':'TRL Patrol Link information',
'Exit Patrol Link?':'Exit TRL Patrol Link?',
'new AlertDialog.Builder(this).setTitle("Patrol Link")':'new AlertDialog.Builder(this).setTitle("TRL Patrol Link")',
'Patrol Link diagnostics':'TRL Patrol Link diagnostics'
}
for a,b in repls.items(): s=s.replace(a,b)
p.write_text(s)

# Master feed dialog title
p=java/'MasterFeedUi.java'; s=p.read_text().replace('"Ronin Master SilverTrack feed"','"TRL Master SilverTrack feed"'); p.write_text(s)

# Foreground notifications / Android Auto titles
for name in ['LocalGpsService.java','MonitorService.java','PatrolCarService.java']:
    p=java/name; s=p.read_text()
    s=s.replace('"Ronin Patrol GPS"','"TRL Patrol GPS"')
    s=s.replace('"Ronin Patrol Link"','"TRL Patrol Link"')
    s=s.replace('"RONIN · LIVE SHIFT ACTIVITY"','"TRL · LIVE SHIFT ACTIVITY"')
    s=s.replace('"RONIN · "+engine.sourceStatus()','"TRL · "+engine.sourceStatus()')
    p.write_text(s)

# Diagnostics display name; internal identifiers stay unchanged for compatibility.
p=java/'PatrolEngine.java'; s=p.read_text().replace('return "Ronin Patrol Link " + BuildConfig.VERSION_NAME','return "TRL Patrol Link " + BuildConfig.VERSION_NAME'); p.write_text(s)

# Update UI/download name, but preserve updater mechanics.
p=java/'AppUpdateManager.java'; s=p.read_text()
s=s.replace('"Patrol Link is up to date."','"TRL Patrol Link is up to date."')
s=s.replace('"Patrol Link update available"','"TRL Patrol Link update available"')
s=s.replace('"Allow Patrol Link updates"','"Allow TRL Patrol Link updates"')
s=s.replace('"Ronin Patrol Link "+o.optString("versionName","")','"TRL Patrol Link "+o.optString("versionName","")')
s=s.replace('"Downloading Patrol Link update"','"Downloading TRL Patrol Link update"')
s=s.replace('"Ronin-Patrol-Link-update.apk"','"TRL-Patrol-Link-update.apk"')
p.write_text(s)

print('Applied v1.1.48 TRL-only rebrand with no operational logic changes.')
