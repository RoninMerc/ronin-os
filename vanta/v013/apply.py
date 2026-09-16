from pathlib import Path
import base64, hashlib, lzma, subprocess

root = Path(__file__).resolve().parent
parts = sorted(root.glob('patch_*.txt'))
if not parts:
    raise SystemExit('Missing Android 0.13 patch payload')
encoded = ''.join(p.read_text(encoding='utf-8').strip() for p in parts)
packed = base64.b64decode(encoded, validate=True)
expected = '1c1f5860b9dfde696e517736ebec8464d8f0b4dac7e69018208b1b6220551614'
actual = hashlib.sha256(packed).hexdigest()
if actual != expected:
    raise SystemExit(f'Android 0.13 patch checksum mismatch: {actual}')
# Python's liblzma can require substantially more memory than the compressed payload
# size when a high-compression dictionary was used. 512 MiB is a bounded CI-safe cap.
patch = lzma.decompress(packed, memlimit=512 * 1024 * 1024)
subprocess.run(
    ['patch', '--batch', '--fuzz=0', '-p1', '-d', 'vanta/personal'],
    input=patch,
    check=True,
)

# A terminal job can become visible before an older UI refresh clears the in-memory
# running flag on slower Android releases. Reconcile the durable job record at the
# user's next Send press so a completed turn can never silently swallow a follow-up.
main = Path('vanta/personal/app/src/main/java/com/ronin/vanta/MainActivity.java')
text = main.read_text(encoding='utf-8')
old = '''  private void startUnifiedProject(
      ComposerIntent action, String raw, String selectedPlatform, JSONObject explicitProject) {
    if (running || launchingJob || taskChoice != null) return;
    final int epoch = screenRevision;'''
new = '''  private void startUnifiedProject(
      ComposerIntent action, String raw, String selectedPlatform, JSONObject explicitProject) {
    if (running && jobs != null && currentJob != null && !currentJob.isEmpty()) {
      try {
        VantaJob durable = jobs.store.get(currentJob);
        if (durable != null && !durable.active()) {
          running = false;
          refreshSend();
        }
      } catch (Exception ignored) {
        // Keep the conservative in-memory state when durable status cannot be confirmed.
      }
    }
    if (running || launchingJob || taskChoice != null) return;
    final int epoch = screenRevision;'''
if old not in text:
    raise SystemExit('Android 0.13 stale-running reconciliation insertion point not found')
main.write_text(text.replace(old, new, 1), encoding='utf-8')

print(f'Applied Android 0.13 Vanta Orchestrator patch ({len(patch)} bytes) plus durable follow-up reconciliation.')
