from pathlib import Path
import subprocess,os,json,shutil,zipfile,hashlib,xml.etree.ElementTree as ET
root=Path('vanta/personal').resolve();evidence=Path('acceptance-evidence').resolve();evidence.mkdir(exist_ok=True)
classes=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
references=Path('baseline/reference').resolve()
# The real JSON implementation must precede android.jar's host-side API stubs.
json_jars=sorted(references.glob('json-*.jar'))
assert json_jars,'Public host JSON implementation missing'
cp=os.pathsep.join([str(classes)]+[str(p) for p in json_jars]+[str(p) for p in sorted(references.glob('*.jar')) if p not in json_jars])
helper=evidence/'helper-classes';helper.mkdir(exist_ok=True)
subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(helper),str(root/'ci/ContinuityAcceptance.java')],check=True)
command=['java','-cp',str(helper)+os.pathsep+cp,'com.ronin.vanta.ContinuityAcceptance',str(root/'app/src/main/assets/forge-examples/field-report.json'),str(evidence/'source')]
with (evidence/'extraction.log').open('w') as log: subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
assets=root/'app/src/androidTest/assets/continuity-acceptance';assets.mkdir(parents=True,exist_ok=True)
shutil.copy(evidence/'source/conversation.json',assets/'conversation.json')
shutil.copy(evidence/'source/source.zip',assets/'source.zip')
results=[]
for track in ('original','archive'):
 project=evidence/'source'/track/'project';logpath=evidence/(track+'-build.log')
 cmd=['gradle','-p',str(project),'--no-daemon','--max-workers=2','--console=plain','testReleaseUnitTest','lintRelease','assembleRelease','--stacktrace']
 with logpath.open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=600)
 text=logpath.read_text();reports=list(project.glob('app/build/test-results/testReleaseUnitTest/TEST-*.xml'))
 parsed=[ET.parse(p).getroot().attrib for p in reports]
 total=sum(int(p.get('tests',0)) for p in parsed);failures=sum(int(p.get('failures',0))+int(p.get('errors',0)) for p in parsed);skips=sum(int(p.get('skipped',0)) for p in parsed)
 apks=list(project.glob('app/build/outputs/apk/release/*.apk'))
 detail={'track':track,'exit_code':r.returncode,'tests':total,'failures':failures,'skipped':skips,'apk_count':len(apks)};results.append(detail)
 (evidence/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(detail),flush=True)
 if r.returncode or total!=14 or failures or skips or len(apks)!=1:raise SystemExit('Real conversation/ZIP compiler acceptance failed; read '+str(logpath))
 if 'VANTA_TEST_REPORT|v1|:app:testReleaseUnitTest|14|14|0|0' not in text:raise SystemExit('Actual managed test counts absent')
 with zipfile.ZipFile(apks[0]) as z:
  if z.testzip() is not None or not {'AndroidManifest.xml','classes.dex'}.issubset(z.namelist()):raise SystemExit('Real compiler output is invalid')
 detail['apk_sha256']=hashlib.sha256(apks[0].read_bytes()).hexdigest()
 (assets/track).mkdir(exist_ok=True)
 shutil.copy(apks[0],assets/track/'app-built.apk');shutil.copy(logpath,assets/track/'build.log');shutil.copy(evidence/'source'/track/'project.json',assets/track/'project.json')
 for n,report in enumerate(reports):shutil.copy(report,evidence/(track+f'-test-{n}.xml'))
 for rel in ('.gradle','app/build'):
  path=project/rel
  if path.exists():shutil.rmtree(path)
(evidence/'results.json').write_text(json.dumps(results,indent=2))
print('Real conversation source and ZIP reimport builds both passed with 14 actual unit tests each. These are not live AI-provider generation tests.')
