from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
p=root/'app/src/androidTest/java/com/ronin/vanta/BackgroundDeviceTest.java'
s=p.read_text()
# A just-booted emulator may register a cellular subscription after svc data disable.
# Disable the radio globally, then observe sustained loss before testing offline behaviour.
s=s.replace('MasterDeviceTest.shell("svc wifi disable");','MasterDeviceTest.shell("cmd connectivity airplane-mode enable");\n      MasterDeviceTest.shell("svc wifi disable");')
s=s.replace('MasterDeviceTest.shell("svc wifi enable");','MasterDeviceTest.shell("cmd connectivity airplane-mode disable");\n      MasterDeviceTest.shell("svc wifi enable");')
old='''      long deadline = SystemClock.elapsedRealtime() + 12000;
      while (h.engine().online() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
      assertFalse("Emulator network was disconnected", h.engine().online());'''
assert s.count(old)==1
s=s.replace(old,'      waitForStableNetwork(false);\n      long deadline;')
old='''      Thread.sleep(1500);
      assertEquals(
          "qa-remote-immutable", h.engine().store.document(job.id(), "remote").getString("id"));'''
assert s.count(old)==1
s=s.replace(old,'''      waitForStableNetwork(false);
      assertEquals(
          "qa-remote-immutable", h.engine().store.document(job.id(), "remote").getString("id"));''')
s=s.rstrip();assert s.endswith('}')
s=s[:-1]+'''
  private void waitForStableNetwork(boolean online) throws Exception {
    long deadline=SystemClock.elapsedRealtime()+30000;
    long stable=0;
    do {
      if(h.engine().online()==online) {
        if(stable==0)stable=SystemClock.elapsedRealtime();
        if(SystemClock.elapsedRealtime()-stable>=1500)return;
      } else stable=0;
      Thread.sleep(150);
    } while(SystemClock.elapsedRealtime()<deadline);
    throw new AssertionError("Emulator did not reach a stable "+(online?"online":"offline")+" state");
  }
}
'''
p.write_text(s)
print('Network test waits for real stable loss with the cellular radio disabled, not a transient Wi-Fi/default-network handover.')
