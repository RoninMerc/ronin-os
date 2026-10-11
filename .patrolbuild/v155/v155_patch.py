from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; here=Path(__file__).parent

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:160])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 154','versionCode 155')
rep(root/'app/build.gradle',"versionName '1.1.44'","versionName '1.1.45'")

shutil.copyfile(here/'FeatherlessCatalog.java',java/'FeatherlessCatalog.java')
shutil.copyfile(here/'FeatherlessVoiceUi.java',java/'FeatherlessVoiceUi.java')

# Make every supervisor-side Featherless entry point clearly open the same model/voice picker.
p=java/'MainActivity.java'; s=p.read_text()
s=s.replace('button("Featherless AI voice",false,()->new FeatherlessVoiceUi(this,engine.featherlessVoice).show())',
            'button("Featherless AI · Models & voices",false,()->new FeatherlessVoiceUi(this,engine.featherlessVoice).show())')
s=s.replace('button("Configure Featherless AI voice",false,()->new FeatherlessVoiceUi(this,engine.featherlessVoice).show())',
            'button("Featherless AI · Models & voices",false,()->new FeatherlessVoiceUi(this,engine.featherlessVoice).show())')
p.write_text(s)

# Put the canonical picker inside Speech controls too, so there is no ambiguous old one-model settings route.
p=java/'SpeechSettingsUi.java'; s=p.read_text()
anchor='''        f.addView(text("These settings change spoken announcements only. Silvertracker records, usernames used for matching, and the dashboard stay unchanged. They also work with any other selected local voice.",13,MUTED));'''
insert='''        f.addView(text("These settings change spoken announcements only. Silvertracker records, usernames used for matching, and the dashboard stay unchanged. They also work with any other selected local voice.",13,MUTED));
        f.addView(button("Featherless AI · Models & voices","featherless_models_voices",()->new FeatherlessVoiceUi(activity,engine.featherlessVoice).show()));'''
if anchor not in s: raise RuntimeError('Speech controls anchor missing')
s=s.replace(anchor,insert,1)
p.write_text(s)

print('Applied v1.1.45: restored canonical four-model Featherless picker with voice dropdowns and unified all supervisor entry points.')
