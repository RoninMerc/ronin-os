from pathlib import Path
import base64, hashlib, json, os, shutil, subprocess
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root=Path('/tmp/patrol-v121');out=Path('/tmp/deliver121');tools=out/'tools';tools.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
apk=out/'Patrol-Link-v121-CI-tested.apk'
shutil.copyfile(root/'app/build/outputs/apk/release/app-release.apk',apk)
report=subprocess.check_output([str(sdk/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
assert 'Signer #1 certificate SHA-256 digest: ff91d55288f1a7160e13c1affcdfef9bba89d24b2a74b0e4d7f7e923569e9332' in report
(out/'APK-VERIFICATION.txt').write_text(report)
(out/'PACKAGE.txt').write_bytes(subprocess.check_output([str(sdk/'aapt'),'dump','badging',str(apk)]))
shutil.copyfile(sdk/'lib/apksigner.jar',tools/'apksigner.jar');shutil.copyfile(sdk/'zipalign',tools/'zipalign')
if (sdk/'lib64').exists():shutil.copytree(sdk/'lib64',tools/'lib64',dirs_exist_ok=True)
for f in ['LocalVoiceProbe.java','VoiceReference.java']:shutil.copyfile(Path(__file__).parent/f,tools/f)
for name in ['sherpa-onnx-jvm-1.13.8.jar','sherpa-onnx-native-lib-linux-x64-1.13.8.jar']:
    dest=tools/('sherpa-jvm.jar' if 'jvm' in name else 'sherpa-linux.jar')
    subprocess.run(['curl','--fail','--location','--retry','3','-o',str(dest),'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/'+name],check=True)
# No plaintext compatibility signing material is exported in an artifact or APK.
pub=b'''-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtOZzCGg7oLvLoPgFrij5
Dt8FEDEMlZhXWUN4yek4gU4l/JDtSy22Az5NC0XzrC3bDI0rD4OoeRXGlLsvA0mL
xic0nkwfZI9L64IifRElKtbFML+EIKaEnLKWAgfsHVjyktuWDoX+icdRGOGULNR9
zzc2yh2OcpKomnCMC2NHtUDyvBSIRl2EXkdz2liF7TMEzmkKaE0s0QdJXFA6kg6K
hbnelcvOKsk8HIZZ5L6lzTHqccCxCekS0Qa3J++XCDXeSIN/OGS3sE21VixNYxoM
1EmBLgP2hYyzH4OIN5hvr97yY1vdYJPsCM+KrThul3uDWKiXe0QeK2Kg421bjGeo
NQIDAQAB
-----END PUBLIC KEY-----'''
key=AESGCM.generate_key(256);nonce=os.urandom(12)
wrapped=serialization.load_pem_public_key(pub).encrypt(key,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
encrypted=AESGCM(key).encrypt(nonce,(root/'test-signing.p12').read_bytes(),b'patrol-v121-private-assembly')
enc=lambda b:base64.b64encode(b).decode()
(tools/'compatibility-key.encrypted.json').write_text(json.dumps({'key':enc(wrapped),'nonce':enc(nonce),'ciphertext':enc(encrypted)}))
(out/'BUILD-STAGE.json').write_text(json.dumps({'commit':os.environ['GITHUB_SHA'],'run':os.environ['GITHUB_RUN_ID'],'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'stage':'Compiled and signed; do not infer completed emulator verification from this early artifact.'},indent=2))
