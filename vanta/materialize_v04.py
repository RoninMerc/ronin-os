from pathlib import Path
import base64, hashlib, lzma, json
root=Path(__file__).resolve().parent
raw=base64.b64decode(''.join((root/f'review-v04-{i}.b64').read_text().strip() for i in range(3)),validate=True)
assert hashlib.sha256(raw).hexdigest()=='38170a640f711c8d79fd2b419835c08a2fea0c434b0ebaad9d08b1fab059dc74', 'source checksum mismatch'
files=json.loads(lzma.decompress(raw))
assert len(files)==21
for name,content in files.items():
    p=Path(name)
    assert not p.is_absolute() and '..' not in p.parts
    if name=='app/src/main/java/com/ronin/vanta/MainActivity.java':
        content=content.replace('import android.content.*;','import android.content.*;\nimport android.content.ClipboardManager;')
    target=root/'reviewed'/p
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(content,encoding='utf-8')
print('Materialized 21 reviewed source files. No credentials included.')
