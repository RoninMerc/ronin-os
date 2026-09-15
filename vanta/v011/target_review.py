from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
p=j/'TaskChoice.java';s=p.read_text();old='''      if (task.action.equals("forge")
          && ProjectConversation.plainBuild(raw)''';assert s.count(old)==1
s=s.replace(old,'''      if (task.action.equals("forge")
          && !platform.equals("Windows") && !task.platform.equals("Windows")
          && ProjectConversation.plainBuild(raw)''');p.write_text(s)
p=root/'app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java';s=p.read_text().rstrip();assert s.endswith('}')
s=s[:-1]+'''
  @Test public void explicitWindowsTargetDoesNotCompileAnOlderAndroidProject()throws Exception {
    sourceJob(source("original"));
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)) {
      compose(a,"Now take this code and create a Windows executable",null);
      a.onActivity(host->{try {
        assertEquals("Prepare Windows source",((Button)TaskChoiceDeviceTest.view(host,"task-confirm")).getText().toString());
        assertEquals(View.VISIBLE,TaskChoiceDeviceTest.view(host,"task-low-refusal").getVisibility());
        TaskChoiceDeviceTest.dialog(host).dismiss();
      }catch(Exception failure){throw new AssertionError(failure);}});
    }
  }
}
''';p.write_text(s)
print('An explicit Windows request cannot be converted into a saved Android compilation.')
