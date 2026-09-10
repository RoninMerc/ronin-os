from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'JobEngine.java';s=p.read_text()
changes=[
('''  public synchronized void wake(boolean userInitiated, Runnable finished) {''','''  public synchronized void wake(boolean userInitiated, Runnable finished) {
    // An already-promoted user foreground service takes ownership of finite in-flight
    // transfers. A later stop callback for the fallback scheduler must not cancel them.
    if (VantaWorkService.foreground) schedulerOwned.clear();'''),
('''        if (!userInitiated) schedulerOwned.add(job.id());''','''        if (!userInitiated && !VantaWorkService.foreground) schedulerOwned.add(job.id());'''),
('''  public void schedulerStopped() {
    for (String id : schedulerOwned) {''','''  public synchronized void schedulerStopped() {
    if (VantaWorkService.foreground) {
      schedulerOwned.clear();
      schedulePending();
      return;
    }
    for (String id : schedulerOwned) {'''),
('''  private synchronized void schedule(long delay) {
    try {''','''  private synchronized void schedule(long delay) {
    // Replacing a running JobScheduler ID invokes onStopJob. Let the current batch
    // finish; its finalizers will schedule any remaining persisted work afterward.
    if (!schedulerOwned.isEmpty()) return;
    try {''')]
for before,after in changes:
 assert s.count(before)==1,(s.count(before),before[:100]);s=s.replace(before,after,1)
p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/JobPublicationDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @SuppressWarnings("unchecked")
  @Test public void staleSchedulerStopCannotCancelForegroundOwnedTransfer()throws Exception {
    JobEngine e=h.engine();java.lang.reflect.Field callsField=JobEngine.class.getDeclaredField("calls"),ownersField=JobEngine.class.getDeclaredField("schedulerOwned");
    callsField.setAccessible(true);ownersField.setAccessible(true);
    Map<String,Net.Call> calls=(Map<String,Net.Call>)callsField.get(e);Set<String> owners=(Set<String>)ownersField.get(e);
    String id="qa-lease-"+UUID.randomUUID();Net.Call call=new Net.Call();
    synchronized(e) {
      boolean foreground=VantaWorkService.foreground;
      try {
        calls.put(id,call);owners.add(id);VantaWorkService.foreground=true;
        e.schedulerStopped();assertFalse(call.isCancelled());assertFalse(owners.contains(id));
        VantaWorkService.foreground=false;e.schedulerStopped();assertFalse(call.isCancelled());
      } finally {calls.remove(id);owners.remove(id);VantaWorkService.foreground=foreground;}
    }
  }
  @SuppressWarnings("unchecked")
  @Test public void genuinelySchedulerOwnedTransferHonoursSystemStop()throws Exception {
    JobEngine e=h.engine();java.lang.reflect.Field callsField=JobEngine.class.getDeclaredField("calls"),ownersField=JobEngine.class.getDeclaredField("schedulerOwned");
    callsField.setAccessible(true);ownersField.setAccessible(true);
    Map<String,Net.Call> calls=(Map<String,Net.Call>)callsField.get(e);Set<String> owners=(Set<String>)ownersField.get(e);
    String id="qa-lease-"+UUID.randomUUID();Net.Call call=new Net.Call();
    synchronized(e) {
      boolean foreground=VantaWorkService.foreground;
      try {calls.put(id,call);owners.add(id);VantaWorkService.foreground=false;e.schedulerStopped();assertTrue(call.isCancelled());}
      finally {calls.remove(id);owners.remove(id);VantaWorkService.foreground=foreground;}
    }
  }
}
''';p.write_text(s)
print('Foreground/scheduler ownership is explicit; fallback scheduling cannot cancel an active foreground transfer.')
