from pathlib import Path
import base64, hashlib, os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
assets=root/'app/src/androidTest/assets'
expected={
 'worker-success':'b9cb220c624404435f5e3be1f324208c5fdb5fa7c468361f8322300385c8ef80',
 'worker-built':'2d4c78d57fa6f91547cae900c20f0712633f296ae5aa9a0671e8eae7c40af206',
 'worker-failure':'469fb4406563f738827b44b7c0b5dc3492d5fd6fdab4edefafc29d773d206b2f',
}
for name,digest in expected.items():
    p=assets/(name+'.zip.b64')
    raw=base64.b64decode(p.read_text().strip(),validate=True)
    assert hashlib.sha256(raw).hexdigest()==digest, 'Actual-worker fixture checksum mismatch: '+name
p=root/'app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java'
s=p.read_text()
old='Base64.getDecoder().decode(new String(asset(name + ".zip.b64"), StandardCharsets.UTF_8))'
assert s.count(old)==1
s=s.replace(old,'Base64.getDecoder().decode(new String(asset(name + ".zip.b64"), StandardCharsets.UTF_8).trim())')
p.write_text(s)
print('Worker ZIP checksums verified. Removed representation-only edge whitespace before strict test-fixture decoding; no compiler/result assertion weakened.')
