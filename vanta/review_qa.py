from pathlib import Path
p=Path(__file__).parent/'reviewed/app/src/androidTest/java/com/ronin/vanta/DeviceTest.java'
s=p.read_text().rstrip()
old='java.util.List<android.view.accessibility.AccessibilityNodeInfo> emma=root.findAccessibilityNodeInfosByText("Emma");'
assert s.count(old)==1
s=s.replace(old,'''java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes=new java.util.ArrayDeque<>();nodes.add(root);boolean searched=false;
            while(!nodes.isEmpty()){android.view.accessibility.AccessibilityNodeInfo node=nodes.removeFirst();if(node.isEditable()){android.os.Bundle args=new android.os.Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"British");assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args));searched=true;break;}for(int child=0;child<node.getChildCount();child++){android.view.accessibility.AccessibilityNodeInfo n=node.getChild(child);if(n!=null)nodes.add(n);}}
            assertTrue("Voice search field found",searched);InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(300);root=ui.getRootInActiveWindow();
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> emma=root.findAccessibilityNodeInfosByText("Emma");''',1)
assert s.endswith('}')
s=s[:-1]+'''
    @org.junit.After public void preserveQaScreenshots()throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String source=ctx.getExternalFilesDir(null).getAbsolutePath();
        String command="mkdir -p /sdcard/Download/Vanta-QA; cp -f '"+source+"'/*.png /sdcard/Download/Vanta-QA/ 2>/dev/null || true";
        android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
    }
}
'''
p.write_text(s)
print('QA search test and screenshot export prepared before app uninstall.')
