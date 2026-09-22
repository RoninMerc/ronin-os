from pathlib import Path
import sys

root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s:
        raise SystemExit(f'anchor missing in {path}: {old[:160]!r}')
    path.write_text(s.replace(old,new,1))

gradle=root/'app/build.gradle'
rep(gradle,'versionCode 117','versionCode 120')
rep(gradle,"versionName '1.1.7'","versionName '1.1.10'")

main=java/'MainActivity.java'
s=main.read_text()
old='form.addView(text("Felicity is built in and is the default. Imported voices must be generated from the exact Patrol Link training script; the recording stays on this device.",12,MUTED,false)); gap(form,14);'
new='form.addView(text("Felicity is built in and is the default. Voice packs are completely local: no ElevenLabs, no API key and no Android/system fallback voice. Whatever voice is selected is the only voice Patrol Link is allowed to play.",12,MUTED,false)); gap(form,14);'
if old not in s: raise SystemExit('voice description anchor missing')
s=s.replace(old,new,1)

old='.setMessage("Use a recording generated from the exact training script. Patrol Link will store it locally and use the recorded alert phrases from that script.")'
new='.setMessage("Use a recording generated from the exact Patrol Link training script. The recording is stored locally and becomes a selectable voice pack. No external voice service is used.")'
if old not in s: raise SystemExit('import message anchor missing')
s=s.replace(old,new,1)

old='Voice settings include the built-in Felicity voice and local import of additional training-script recordings. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.'
new='Voice settings are local-only. Felicity is built in, and additional training-script recordings can be imported as local voice packs. Patrol Link contains no external voice API integration and no Android/system TTS fallback; the selected voice is the only voice the app can play. Voice playback is isolated from the 30-second monitor loop, so an audio failure does not stop feed refreshes.'
if old not in s: raise SystemExit('help anchor missing')
s=s.replace(old,new,1)

main.write_text(s)

assert "versionCode 120" in gradle.read_text()
assert "no ElevenLabs" in main.read_text()
