"""Wait for real stable radio state, not a transient handover; test setup only.

Android 16 gate logs show cellular network 101 connecting after a one-sample
'offline' assertion and disconnecting 2.5 seconds later. Submission-count,
remote-ID, recovery and background assertions are preserved.
"""
from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/BackgroundDeviceTest.java'
s=p.read_text()
def replace(old,new):
 global s
 if s.count(old)!=1: raise SystemExit('Expected one network-gate target: '+old[:80])
 s=s.replace(old,new,1)
replace('''      long deadline = SystemClock.elapsedRealtime() + 12000;
      while (h.engine().online() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
      assertFalse("Emulator network was disconnected", h.engine().online());''','''      awaitStableNetwork(false);
      assertFalse("Emulator network was disconnected", h.engine().online());''')
replace('''      deadline = SystemClock.elapsedRealtime() + 18000;
      while (!h.engine().online() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
      assertTrue(h.engine().online());''','''      awaitStableNetwork(true);
      assertTrue(h.engine().online());''')
replace('''      Thread.sleep(1500);
      assertEquals(
          "qa-remote-immutable"''','''      awaitStableNetwork(false);
      assertEquals(
          "qa-remote-immutable"''')
replace('''      deadline = SystemClock.elapsedRealtime() + 30000;
      long onlineSince = 0;
      while (SystemClock.elapsedRealtime() < deadline) {
        if (h.engine().online()) {
          if (onlineSince == 0) onlineSince = SystemClock.elapsedRealtime();
          if (SystemClock.elapsedRealtime() - onlineSince >= 1000) break;
        } else onlineSince = 0;
        Thread.sleep(200);
      }''','''      awaitStableNetwork(true);''')
replace('''  void waitState(String id, String status) throws Exception {''','''  private void awaitStableNetwork(boolean expected) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 45000;
    long stableSince = 0;
    while (SystemClock.elapsedRealtime() < deadline) {
      long now = SystemClock.elapsedRealtime();
      if (h.engine().online() == expected) {
        if (stableSince == 0) stableSince = now;
        // Radio shutdown and cellular handover are asynchronous on Android.
        if (now - stableSince >= 3500) return;
      } else stableSince = 0;
      Thread.sleep(150);
    }
    fail("Android never held the required network state for 3.5 seconds: " + expected);
  }

  void waitState(String id, String status) throws Exception {''')
p.write_text(s)
print('Network tests wait for stable real disconnection/reconnection. Production code unchanged.')
