from pathlib import Path
import sys

root = Path(sys.argv[1])
java = root / 'app/src/main/java/au/com/roningroup/patrollink'

def replace_once(path, old, new):
    s = path.read_text()
    if old not in s:
        raise SystemExit(f'patch anchor missing in {path}: {old[:140]!r}')
    path.write_text(s.replace(old, new, 1))

gradle = root / 'app/build.gradle'
replace_once(gradle, 'versionCode 117', 'versionCode 118')
replace_once(gradle, "versionName '1.1.7'", "versionName '1.1.8'")

engine = java / 'PatrolEngine.java'
s = engine.read_text()
old = '"\\nCar host connected: " + carConnected + "\\nActive voice: " + voices.activeName() + "\\nOffline voice ready: " + voices.ready() +'
new = '"\\nCar host connected: " + carConnected + "\\nAlert voice: " + voices.activeName() + "\\nFull readout voice: " + voices.dynamicVoiceName() + "\\nOffline full readout ready: " + voices.ready() +'
if old not in s:
    raise SystemExit('PatrolEngine diagnostics voice anchor missing')
engine.write_text(s.replace(old, new, 1))

main = java / 'MainActivity.java'
s = main.read_text()
old = 'CheckBox voice=new CheckBox(this);voice.setText("Speak new activity with "+engine.voices.activeName());voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);'
new = 'CheckBox voice=new CheckBox(this);voice.setText("Speak full activity + location ("+engine.voices.activeName()+" alert + offline readout)");voice.setTextColor(WHITE);voice.setChecked(engine.prefs.getBoolean("voice",true));form.addView(voice);'
if old not in s:
    raise SystemExit('settings voice checkbox anchor missing')
s = s.replace(old, new, 1)

old = 'form.addView(text("Felicity is built in and is the default. Imported voices must be generated from the exact Patrol Link training script; the recording stays on this device.",12,MUTED,false)); gap(form,14);'
new = 'form.addView(text("Felicity is built in and is the default alert voice. For every new event Patrol Link now reads the complete Issue Monitor matter text, property/location and guard after the alert. Arbitrary live text is spoken with the best offline English voice installed on this phone. Imported alert voices stay on this device.",12,MUTED,false)); gap(form,14);'
if old not in s:
    raise SystemExit('voice library description anchor missing')
s = s.replace(old, new, 1)

old = '.setMessage("Use a recording generated from the exact training script. Patrol Link will store it locally and use the recorded alert phrases from that script.")'
new = '.setMessage("Use a recording generated from the exact training script. Patrol Link stores it locally for the event alert. The complete live matter, location and guard are then read aloud automatically.")'
if old not in s:
    raise SystemExit('import dialog anchor missing')
s = s.replace(old, new, 1)

old = 'Voice settings include the built-in Felicity voice and local import of additional training-script recordings. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.'
new = 'Voice settings include the built-in Felicity alert voice and local import of additional training-script recordings. Every newly detected Issue Monitor row is announced with its matter/activity text, property or location, and guard. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.'
if old not in s:
    raise SystemExit('help text anchor missing')
s = s.replace(old, new, 1)

main.write_text(s)

assert "versionCode 118" in gradle.read_text()
assert "full activity + location" in main.read_text()
assert "Full readout voice" in engine.read_text()
