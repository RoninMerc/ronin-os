from pathlib import Path
root=Path(__file__).parent/'reviewed'
p=root/'app/src/androidTest/java/com/ronin/vanta/DeviceTest.java'
s=p.read_text().rstrip()
old='java.util.List<android.view.accessibility.AccessibilityNodeInfo> emma=root.findAccessibilityNodeInfosByText("Emma");'
assert s.count(old)==1
s=s.replace(old,'''java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes=new java.util.ArrayDeque<>();nodes.add(root);boolean searched=false;
            while(!nodes.isEmpty()){android.view.accessibility.AccessibilityNodeInfo node=nodes.removeFirst();if(node.isEditable()){android.os.Bundle args=new android.os.Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"British");assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args));searched=true;break;}for(int child=0;child<node.getChildCount();child++){android.view.accessibility.AccessibilityNodeInfo n=node.getChild(child);if(n!=null)nodes.add(n);}}
            assertTrue("Voice search field found",searched);InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(300);root=ui.getRootInActiveWindow();
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> emma=root.findAccessibilityNodeInfosByText("Emma");''',1)
old='assertTrue(use.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));'
assert s.count(old)==1
s=s.replace(old,'''root=ui.getRootInActiveWindow();use=root.findAccessibilityNodeInfosByText("USE VOICE");android.view.accessibility.AccessibilityNodeInfo useButton=null;
            for(android.view.accessibility.AccessibilityNodeInfo node:use)if("USE VOICE".equalsIgnoreCase(String.valueOf(node.getText()))&&node.isClickable()&&node.isEnabled()){useButton=node;break;}
            assertNotNull("The actual Use voice action button exists",useButton);assertTrue(useButton.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));''',1)
assert s.endswith('}')
s=s[:-1]+'''
    private static void qaShell(String command)throws Exception {
        android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
    }
    @org.junit.After public void preserveQaScreenshots()throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=ctx.getExternalFilesDir(null);File[] pictures=dir==null?null:dir.listFiles();
        qaShell("mkdir -p /sdcard/Download/Vanta-QA");
        if(pictures!=null)for(File picture:pictures)if(picture.getName().matches("[A-Za-z0-9_.-]+\\\\.png"))
            qaShell("cp "+picture.getAbsolutePath()+" /sdcard/Download/Vanta-QA/"+picture.getName());
    }
}
'''
p.write_text(s)

# Release Activity-owned voice dialogs on shutdown instead of leaking their windows.
p=root/'app/src/main/java/com/ronin/vanta/MainActivity.java'
s=p.read_text()
for old,new in [
 ('public class MainActivity extends Activity {','public class MainActivity extends Activity {\n    private AlertDialog voiceDialog,voiceDetailDialog;'),
 ('dialog.setOnDismissListener(d->voiceEngine.stop());','voiceDialog=dialog;dialog.setOnDismissListener(d->{voiceEngine.stop();if(voiceDialog==dialog)voiceDialog=null;});'),
 ('detail.setOnDismissListener(d->voiceEngine.stop());','voiceDetailDialog=detail;detail.setOnDismissListener(d->{voiceEngine.stop();if(voiceDetailDialog==detail)voiceDetailDialog=null;});'),
 ('@Override protected void onDestroy(){stopRequest();','@Override protected void onDestroy(){if(voiceDetailDialog!=null)voiceDetailDialog.dismiss();if(voiceDialog!=null)voiceDialog.dismiss();stopRequest();')
]:
    assert s.count(old)==1,old
    s=s.replace(old,new,1)
p.write_text(s)
p=root/'app/src/main/res/values/strings.xml'
s=p.read_text().replace('>Vanta Preview</string>','>Ronin Vanta Preview</string>')
p.write_text(s)
print('Final voice-selection regression, lifecycle cleanup, launcher label and screenshot export prepared.')
