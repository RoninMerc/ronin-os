from pathlib import Path
import base64,gzip,hashlib,json,sys
root=Path(sys.argv[1]).resolve()
parts=Path(__file__).parent
payload=''.join((parts/f'patch-{i}.b64').read_text().strip() for i in range(4))
assert hashlib.sha256(payload.encode()).hexdigest()=='1674c73308b4e20c04f2ba9986c1c0e66e33fc9806f0c1baaf170501f761a083','Patch transport hash mismatch'
records=json.loads(gzip.decompress(base64.b64decode(payload,validate=True)))
for r in records:
    p=(root/r['path']).resolve()
    assert p.is_relative_to(root),'Unsafe patch path'
    old=p.read_bytes() if p.exists() else b''
    assert hashlib.sha256(old).hexdigest()==r['before'],'Baseline mismatch: '+r['path']
    lines=old.decode('utf-8').splitlines(keepends=True)
    for start,end,new in reversed(r['ops']):lines[start:end]=new.splitlines(keepends=True)
    updated=''.join(lines).encode('utf-8')
    assert hashlib.sha256(updated).hexdigest()==r['after'],'Patch result mismatch: '+r['path']
    p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(updated)
    print('PATCHED',r['path'])
print('Applied',len(records),'hash-verified source changes; retained all other baseline files.')

# Follow-up compile and regression corrections, each applied exactly once.
def fix(name, before, after):
    p=root/name; text=p.read_text()
    assert text.count(before)==1, 'Follow-up baseline mismatch: '+name
    p.write_text(text.replace(before,after))
fix('app/src/main/java/com/ronin/vanta/ManagedBuildFiles.java','static String selectGate(JSONObject source, Map<String, JSONObject> files) {','static String selectGate(JSONObject source, Map<String, JSONObject> files) throws Exception {')
fix('app/src/test/java/com/ronin/vanta/BuildFoundationTest.java','assertTrue(expected.getMessage().contains("context is too small"));','assertTrue(expected.getMessage().contains("LOCAL_CONTEXT_BUDGET"));\n      assertTrue(expected.getMessage().contains("No request for this file was sent"));')
fix('app/src/main/java/com/ronin/vanta/JobOperations.java','// not a fabricated tokenizer. Explicit dependencies that do not fit stop before modification.','// not a fabricated tokenizer. Targets stay complete; oversized dependencies are labelled excerpts.')
fix('app/src/main/java/com/ronin/vanta/JobOperations.java','    if (forge) {\n      ForgeRecoveryController.checking(e, j, in, p, m, inputChars, images, outputTokens, c);\n      ensureInference(e, j, p, key, m, c);\n      ForgeRecoveryController.reserve(e, j, in, p, m, phase, inputChars, images, outputTokens, c);\n    } else e.markRequest(j, phase);\n    String[] partial = {""};\n    c.inferenceOptions = forge ? ForgeRequestPolicy.options(p, m, phase) : null;\n    GenerationWatchdog watchdog = forge ? new GenerationWatchdog(c) : null;\n    try {\n      String answer =','    String[] partial = {""};\n    GenerationWatchdog watchdog = forge ? new GenerationWatchdog(c) : null;\n    try {\n      if (forge) {\n        ForgeRecoveryController.checking(e, j, in, p, m, inputChars, images, outputTokens, c);\n        ensureInference(e, j, p, key, m, c);\n        ForgeRecoveryController.reserve(e, j, in, p, m, phase, inputChars, images, outputTokens, c);\n      } else e.markRequest(j, phase);\n      c.inferenceOptions = forge ? ForgeRequestPolicy.options(p, m, phase) : null;\n      String answer =')
p=root/'README.md'
p.write_text('# Ronin Vanta Android 0.17.5 — Forge reliability\n\nThis release updates Vanta itself while retaining the matching package ID, encrypted workspace, failed tasks and device-held signing identity. It protects managed build scripts, provides exact task-source recovery, bounds repair context and generation time, and corrects deterministic source-preparation faults without regenerating an app. See `docs/FORGE_0175.md` and the separately issued verification report for scope and actual test results.\n\nEarlier release notes are retained below. An APK compiled from this source must be signed with the matching retained owner key to update an existing installation; a source ZIP is not an installed update.\n\n'+p.read_text())
