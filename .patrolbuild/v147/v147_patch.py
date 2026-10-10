from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s: raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 146','versionCode 147')
rep(root/'app/build.gradle',"versionName '1.1.36'","versionName '1.1.37'")

# Add static operational alert access to active exact voice manager.
p=java/'VoiceManager.java'; s=p.read_text()
import re
ctor=re.search(r'\n    public VoiceManager\(Context context,SharedPreferences prefs\)\{',s)
if not ctor: raise RuntimeError('VoiceManager constructor not found')
insert='''\n    private static volatile VoiceManager ACTIVE_INSTANCE;\n\n    public static void operationalAlert(String text){\n        VoiceManager v=ACTIVE_INSTANCE;\n        if(v==null||text==null||text.trim().isEmpty())return;\n        v.main.post(()->{\n            if(!v.prefs.getBoolean("voice",true))return;\n            v.enqueue(v.activeProfile().id,SpeechRules.clean(text),v.speechSettings.speed());\n        });\n    }\n'''
s=s[:ctor.start()]+insert+s[ctor.start():]
s=s.replace('public VoiceManager(Context context,SharedPreferences prefs){','public VoiceManager(Context context,SharedPreferences prefs){\n        ACTIVE_INSTANCE=this;',1)
p.write_text(s)

# Add operational proximity voice alerts.
p=java/'CheckpointProximityUi.java'; s=p.read_text()
s=s.replace('private String activeId="",pendingPhotoId="",gateSuppressedId="";',
            'private String activeId="",pendingPhotoId="",gateSuppressedId="",checkpointVoiceId="",gateVoiceId="";',1)

needle='''        activeId=best.id;
        boolean scanRange=bestDist<=50f;'''
repl='''        activeId=best.id;
        if(bestDist<=150f && !best.id.equals(checkpointVoiceId)){
            checkpointVoiceId=best.id;
            VoiceManager.operationalAlert(best.name+" checkpoint ahead.");
        }else if(bestDist>220f && best.id.equals(checkpointVoiceId)){
            checkpointVoiceId="";
        }
        boolean scanRange=bestDist<=50f;'''
if needle not in s: raise RuntimeError('checkpoint alert anchor missing')
s=s.replace(needle,repl,1)

needle='''        if(nearest==null||gateDialogOpen)return;
        gateDialogOpen=true;Checkpoint gate=nearest;int metres=Math.round(best);'''
repl='''        if(nearest==null||gateDialogOpen)return;
        if(!nearest.id.equals(gateVoiceId)){
            gateVoiceId=nearest.id;
            VoiceManager.operationalAlert(nearest.name+" gate ahead.");
        }
        gateDialogOpen=true;Checkpoint gate=nearest;int metres=Math.round(best);'''
if needle not in s: raise RuntimeError('gate alert anchor missing')
s=s.replace(needle,repl,1)

needle='if(id.equals(gateSuppressedId)&&d>GATE_CLEAR)gateSuppressedId="";'
repl='if(id.equals(gateSuppressedId)&&d>GATE_CLEAR){gateSuppressedId="";if(id.equals(gateVoiceId))gateVoiceId="";}'
if needle not in s: raise RuntimeError('gate reset anchor missing')
s=s.replace(needle,repl,1)
p.write_text(s)

# Extend the full canonical script so new voice profiles/full rebuilds include all current operational phrases.
asset=root/'app/src/main/assets/exact/canonical_phrases.txt'
existing=asset.read_text()
additions=[
"Silver Track update",
"Checkpoint ahead",
"Scan point ahead",
"Checkpoint in scan range",
"Scan complete",
"Seal Circuit checkpoint ahead",
"Rec Centre checkpoint ahead",
"Slipstream checkpoint ahead",
"Bobsled Lane checkpoint ahead",
"Christina checkpoint ahead",
"Solo Place checkpoint ahead",
"Impeccable checkpoint ahead",
"Quest checkpoint ahead",
"Eolo checkpoint ahead",
"Elusive checkpoint ahead",
"Serenade checkpoint ahead",
"Gate ahead",
"Solo Place gate ahead",
"Impeccable gate ahead",
"Quest gate ahead",
"MyCentsys Remote",
"Open gate"
]
seen={x.strip().lower() for x in existing.splitlines() if x.strip()}
lines=existing.rstrip('\n').splitlines()
for phrase in additions:
    if phrase.lower() not in seen:
        lines.append(phrase);seen.add(phrase.lower())
asset.write_text('\n'.join(lines)+'\n')

print('Applied v1.1.37 Andy operational checkpoint/gate alerts and updated full canonical voice script.')
