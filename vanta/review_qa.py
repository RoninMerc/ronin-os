from pathlib import Path
p=Path(__file__).parent/'reviewed/app/src/androidTest/java/com/ronin/vanta/DeviceTest.java'
s=p.read_text().rstrip()
assert s.endswith('}')
s=s[:-1]+'''
    @After public void preserveQaScreenshots()throws Exception {
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String source=ctx.getExternalFilesDir(null).getAbsolutePath();
        String command="mkdir -p /sdcard/Download/Vanta-QA; cp -f '"+source+"'/*.png /sdcard/Download/Vanta-QA/ 2>/dev/null || true";
        android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
    }
}
'''
p.write_text(s)
print('QA screenshot export prepared before app uninstall.')
