#!/usr/bin/env bash
set -euo pipefail
P=/tmp/patrol-v121
mkdir -p "$P" /tmp/deliver121 /tmp/tools121 /tmp/audio121
sudo apt-get -qq update >/tmp/audio-tools-install.log 2>&1
sudo apt-get -qq install -y ffmpeg >>/tmp/audio-tools-install.log 2>&1
cat .patrolbuild/base.part0{0,1,2,3,4,5} | base64 -d >/tmp/base.zip
unzip -q /tmp/base.zip -d "$P"
cat .patrolbuild/overlay.part0{0,1,2} | base64 -d >/tmp/overlay.tgz
tar -xzf /tmp/overlay.tgz -C "$P"
J="$P/app/src/main/java/au/com/roningroup/patrollink"
T="$P/app/src/androidTest/java/au/com/roningroup/patrollink"
U="$P/app/src/test/java/au/com/roningroup/patrollink"
cp .patrolbuild/PatrolEngine.java .patrolbuild/MainActivity.java "$J/"
python3 .patrolbuild/v115-patch.py "$P"
mkdir -p "$T" "$U" "$P/app/src/androidTest/assets"
cp .patrolbuild/RefreshLifecycleTest.java "$T/"
python3 .patrolbuild/v115-finalize.py "$P"
python3 .patrolbuild/v116-patch.py "$P"
cp .patrolbuild/TlsPolicy.java .patrolbuild/VoiceManager.java "$J/"
cp .patrolbuild/TlsPolicyTest.java "$U/"
cp .patrolbuild/TlsRecoveryTest.java "$T/"
python3 .patrolbuild/v117-patch.py "$P"
python3 .patrolbuild/v120-patch.py "$P"
python3 .patrolbuild/v121-patch.py "$P"
cp .patrolbuild/VoiceLogicTest.java "$U/"
cp .patrolbuild/LocalSpeechTest.java "$T/"
keytool -J-Dkeystore.pkcs12.legacy -genkeypair -alias fixture -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "$P/app/src/androidTest/assets/tls-fixture.p12" -storepass fixture-only -keypass fixture-only -dname 'CN=localhost,OU=Instrumentation fixture only,O=Test' -ext SAN=dns:localhost,ip:127.0.0.1 -ext EKU=serverAuth -validity 30 -noprompt
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-36' 'build-tools;36.0.0'
openssl cms -encrypt -binary -aes-256-cbc -in "$P/test-signing.p12" -outform DER -out /tmp/tools121/signing.enc .patrolbuild/local-transfer.crt
cp "$ANDROID_HOME/build-tools/36.0.0/lib/apksigner.jar" /tmp/tools121/
cp "$ANDROID_HOME/build-tools/36.0.0/zipalign" "$ANDROID_HOME/build-tools/36.0.0/aapt" /tmp/tools121/
node --check "$P/app/src/main/assets/extract.js"
mkdir -p "$P/app/libs" "$P/app/src/main/assets/"{speech-model,voices}
curl -fsSL --retry 3 -o "$P/app/libs/local-speech.aar" https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
echo '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96  /tmp/patrol-v121/app/libs/local-speech.aar' | sha256sum -c -
curl -fsSL --retry 3 -o /tmp/pocket.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2
echo '2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb  /tmp/pocket.tar.bz2' | sha256sum -c -
tar -xf /tmp/pocket.tar.bz2 -C /tmp
MODEL=/tmp/sherpa-onnx-pocket-tts-int8-2026-01-26
ASSETS="$P/app/src/main/assets"
cp "$MODEL"/{lm_flow.int8.onnx,lm_main.int8.onnx,encoder.onnx,decoder.int8.onnx,text_conditioner.onnx,vocab.json,token_scores.json,LICENSE,README.md} "$ASSETS/speech-model/"
cp "$MODEL/test_wavs/bria.wav" "$P/app/src/androidTest/assets/alternate-reference.wav"
python3 - <<'PY'
import hashlib,json,pathlib
folder=pathlib.Path('/tmp/patrol-v121/app/src/main/assets/speech-model')
out={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in folder.iterdir() if p.suffix in ['.onnx','.json']}
(folder/'manifest.json').write_text(json.dumps(out,indent=2))
pathlib.Path('/tmp/deliver121/MODEL-MANIFEST.json').write_text(json.dumps(out,indent=2))
PY
# A provisional CI reference uses already-public samples. Original uploaded voice is inserted locally later.
base64 -d .patrolbuild/felicity_scan.ogg.b64 >/tmp/audio121/scan.ogg
base64 -d .patrolbuild/felicity_breach.ogg.b64 >/tmp/audio121/breach.ogg
cat .patrolbuild/felicity_incident.ogg.b64.part* | base64 -d >/tmp/audio121/incident.ogg
cat .patrolbuild/felicity_generic.ogg.b64.part* | base64 -d >/tmp/audio121/generic.ogg
ffmpeg -loglevel error -i /tmp/audio121/generic.ogg -i /tmp/audio121/scan.ogg -i /tmp/audio121/breach.ogg -i /tmp/audio121/incident.ogg -filter_complex '[0:a][1:a][2:a][3:a]concat=n=4:v=0:a=1[out]' -map '[out]' -ar 24000 -ac 1 -c:a pcm_s16le "$ASSETS/voices/felicity.wav"
curl -fsSL --retry 3 -o /tmp/tools121/sherpa-onnx-jvm.jar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-jvm-1.13.8.jar
echo '77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b  /tmp/tools121/sherpa-onnx-jvm.jar' | sha256sum -c -
curl -fsSL --retry 3 -o /tmp/tools121/sherpa-onnx-native-linux.jar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-native-lib-linux-x64-1.13.8.jar
echo '30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba  /tmp/tools121/sherpa-onnx-native-linux.jar' | sha256sum -c -
python3 - <<'PY'
from pathlib import Path
root=Path('/tmp/patrol-v121/app/src/main')
for p in (root/'java').rglob('*.java'):
 s=p.read_text()
 for bad in ['import android.speech.tts','new TextToSpeech','api.elevenlabs','xi-api-key','tts.speak(']:assert bad not in s,(p,bad)
for name in ['VoiceManager','VoiceAudio','VoiceReadout','LocalVoiceService']:
 s=(root/'java/au/com/roningroup/patrollink'/f'{name}.java').read_text()
 for bad in ['HttpURLConnection','URLConnection','R.raw.felicity','GENERIC_START','new URL(']:assert bad not in s,(name,bad)
assert not list((root/'res/raw').glob('felicity*'))
assert 'android:process=":voice"' in (root/'AndroidManifest.xml').read_text()
print('Local-only synthesis and separate speech process source checks passed.')
PY
