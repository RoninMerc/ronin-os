from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/build.gradle'
s=p.read_text()
if 'versionCode 123' not in s or "versionName '1.1.13'" not in s:
    raise RuntimeError('v1.1.13 version anchor missing')
s=s.replace('versionCode 123','versionCode 124',1).replace("versionName '1.1.13'","versionName '1.1.14'",1)
p.write_text(s)
