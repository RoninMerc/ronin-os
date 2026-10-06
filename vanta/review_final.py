from pathlib import Path

root = Path(__file__).parent / 'reviewed'
j = root / 'app/src/main/java/com/ronin/vanta'

def patch(file, old, new):
    path = j / file
    s = path.read_text()
    if s.count(old) != 1:
        raise SystemExit(f'{file}: expected one patch target, got {s.count(old)}: {old[:90]}')
    path.write_text(s.replace(old, new, 1))

p=root/'app/build.gradle'
s=p.read_text().replace('versionCode 4','versionCode 41').replace("versionName '0.4.0-preview'", "versionName '0.4.1-preview'")
p.write_text(s)
patch('MainActivity.java', 'PREVIEW 0.4",10', 'PREVIEW 0.4.1",10')
patch('MainActivity.java', 'private boolean selection(){if(provider==null||model==null)', 'private boolean selection(){if(model!=null&&(model.id.toLowerCase(Locale.ROOT).contains("e2ee")||"e2ee".equalsIgnoreCase(model.spec.optString("privacy")))){error("This model requires an end-to-end encryption adapter not included in this preview. No plaintext prompt was sent. Choose a compatible model.");return false;}if(provider==null||model==null)')
patch('SecureVault.java', 'public void putSecret(String alias, String secret) throws Exception {', 'public synchronized void putSecret(String alias, String secret) throws Exception {')
patch('SecureVault.java', 'prefs.edit().putString(alias + ".iv", Base64.encodeToString(iv, Base64.NO_WRAP))\n                .putString(alias + ".ct", Base64.encodeToString(enc, Base64.NO_WRAP)).apply();', 'boolean saved=prefs.edit().putString(alias + ".iv", Base64.encodeToString(iv, Base64.NO_WRAP))\n                .putString(alias + ".ct", Base64.encodeToString(enc, Base64.NO_WRAP)).commit();\n        if(!saved)throw new java.io.IOException("Encrypted data could not be saved.");')
patch('ModelInfo.java', 'heuristicUncensored=s.contains("uncensored")||s.contains("abliterated")||s.contains("unfiltered");', 'heuristicUncensored=(s.contains("uncensored")||s.contains("abliterated")||s.contains("unfiltered"))&&!s.matches(".*not[- ]+(uncensored|unfiltered).*" );')
patch('MediaCheck.java', 'max-min<=3&&sum/visible<8', 'max-min<=3&&(sum/visible<8||sum/visible>252)')
patch('MainActivity.java', 'uniformly near-black or transparent', 'uniformly near-black, near-white or transparent')
patch('Net.java', 'u.getUserInfo()!=null)', 'u.getUserInfo()!=null || u.getRef()!=null)')

# Align exactly with the private build worker's accepted project transport.
f=j/'ForgeClient.java'
s=f.read_text();a=s.index('    public static void validateProject(');b=s.index('    private static Map<String,String> headers(',a)
s=s[:a]+'''    public static void validateProject(JSONObject project)throws Exception {
        JSONArray files=project.optJSONArray("files");
        if(files==null||files.length()<5||files.length()>100)throw new IOException("Project must contain 5 to 100 complete text files.");
        Set<String> seen=new HashSet<>();long total=0;boolean settings=false,rootBuild=false,app=false,manifest=false,source=false;
        Set<String> top=new HashSet<>(Arrays.asList("settings.gradle","settings.gradle.kts","build.gradle","build.gradle.kts","gradle.properties","app/build.gradle","app/build.gradle.kts","app/proguard-rules.pro"));
        for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);String path=f.getString("path");Object value=f.opt("content");
            if(!(value instanceof String)||path.length()>240||path.isEmpty()||path.startsWith("/")||path.indexOf('\\\\')>=0||path.indexOf(':')>=0||path.chars().anyMatch(c->c<32))throw new IOException("Invalid project file: "+path);
            for(String part:path.split("/",-1))if(part.isEmpty()||part.startsWith("."))throw new IOException("Unsafe project path: "+path);
            if(!seen.add(path.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate project file: "+path);
            boolean supported=top.contains(path)||(path.startsWith("app/src/")&&path.matches(".*\\\\.(java|kt|xml|json|txt|html|css|js|svg)"));
            if(!supported)throw new IOException("File type is not accepted by this preview worker: "+path);
            int size=((String)value).getBytes(StandardCharsets.UTF_8).length;total+=size;
            if(size==0||size>200000||total>800000)throw new IOException("Project limit: 200 KB per file, 800 KB total; empty files are not accepted.");
            settings|=path.equals("settings.gradle")||path.equals("settings.gradle.kts");rootBuild|=path.equals("build.gradle")||path.equals("build.gradle.kts");app|=path.equals("app/build.gradle")||path.equals("app/build.gradle.kts");manifest|=path.equals("app/src/main/AndroidManifest.xml");source|=path.startsWith("app/src/main/")&&(path.endsWith(".java")||path.endsWith(".kt"));
        }
        if(!settings||!rootBuild||!app||!manifest||!source)throw new IOException("Missing root/app build scripts, settings, manifest or Android source.");
    }
''' + s[b:]
s=s.replace('if(!sha.equals(run.optString("head_sha"))||!run.optString("path")', 'if(!sha.equals(run.optString("head_sha"))||!branch.equals(run.optString("head_branch"))||!run.optString("path")')
# Explicitly bound decompression when checking every entry, not only the target entry.
s=s.replace('ZipEntry e;int count=0;while((e=z.getNextEntry())!=null){if(++count>20000)', 'ZipEntry e;int count=0;long expanded=0;byte[] scan=new byte[16384];while((e=z.getNextEntry())!=null){int n;while((n=z.read(scan))!=-1){call.check();expanded+=n;if(expanded>256000000L)throw new IOException("Expanded APK exceeds inspection limit.");}if(++count>20000)')
a=s.index('    private static byte[] extractZip(')
s=s[:a]+'''    static byte[] extractZip(byte[] zip,String suffix,int limit,Net.Call call)throws Exception{
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(zip))){ZipEntry e;int count=0;long total=0;byte[] buf=new byte[16384];while((e=z.getNextEntry())!=null){if(++count>100)throw new IOException("Artifact contains too many files.");boolean wanted=!e.isDirectory()&&e.getName().endsWith(suffix);ByteArrayOutputStream b=wanted?new ByteArrayOutputStream():null;int n;while((n=z.read(buf))!=-1){call.check();total+=n;if(total>limit)throw new IOException("Expanded artifact exceeds limit.");if(wanted)b.write(buf,0,n);}if(wanted)return b.toByteArray();}}return null;
    }
}
'''
f.write_text(s)

# Preview stays in the voice detail dialog; progress and failures are visible there.
f=j/'MainActivity.java';s=f.read_text()
a=s.index('        list.setOnItemClickListener((parent,view,pos,id)->{VoiceChoice v=visible.get(pos);')
b=s.index('    private float voiceSpeed()',a)
s=s[:a]+'''        list.setOnItemClickListener((parent,view,pos,id)->{
            VoiceChoice v=visible.get(pos);TextView previewStatus=text("Tap Preview to listen. Then choose Use voice.",13,MUTED,false);previewStatus.setPadding(dp(20),dp(8),dp(20),dp(8));
            AlertDialog detail=new AlertDialog.Builder(this).setTitle(v.label).setMessage(v.provider.isEmpty()?"Uses your Android speech engine. Some device voices need its network service.":"Voice: "+v.voice+"\\nModel: "+v.model+"\\nCloud previews and replies consume provider credits and send text to that voice provider.")
                .setView(previewStatus).setPositiveButton("USE VOICE",(d,w)->{prefs.edit().putString("voice_provider",v.provider).putString("voice_model",v.model).putString("voice_id",v.voice).putString("voice_label",v.label).apply();voiceEngine.stop();dialog.dismiss();notice("Voice selected: "+v.label);})
                .setNeutralButton("PREVIEW",null).setNegativeButton("CANCEL",null).create();
            detail.setOnDismissListener(d->voiceEngine.stop());detail.show();
            detail.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->previewVoice(v,previewStatus));
        });dialog.show();
    }
''' + s[b:]
s=s.replace('private void previewVoice(VoiceChoice v){', 'private void previewVoice(VoiceChoice v,TextView visibleStatus){')
a=s.index('    private void previewVoice(');b=s.index('    private void say(',a)
segment=s[a:b].replace('status.setText(', 'visibleStatus.setText(').replace('MainActivity.this.error(t);','visibleStatus.setText(t);')
s=s[:a]+segment+s[b:];f.write_text(s)

# Protocol and worker-contract regression tests.
f=root/'app/src/test/java/com/ronin/vanta/ProtocolTest.java'
s=f.read_text().replace('new String[]{"settings.gradle","app/build.gradle"', 'new String[]{"settings.gradle","build.gradle","app/build.gradle"')
s=s.rstrip()[:-1]+'''
    @Test public void negatedModelNameNotUncensored(){assertFalse(new ModelInfo("not-uncensored","Not uncensored","text","").isUncensored());}
    @Test(expected=IOException.class)public void emptySourceRejected()throws Exception{JSONObject p=project();p.getJSONArray("files").getJSONObject(0).put("content","");ForgeClient.validateProject(p);}
    @Test(expected=IOException.class)public void workerFileLimitEnforced()throws Exception{JSONObject p=project();for(int i=0;i<100;i++)p.getJSONArray("files").put(new JSONObject().put("path","app/src/main/assets/a"+i+".txt").put("content","x"));ForgeClient.validateProject(p);}
    @Test(expected=IOException.class)public void caseCollisionRejected()throws Exception{JSONObject p=project();p.getJSONArray("files").put(new JSONObject().put("path","Settings.gradle").put("content","x"));ForgeClient.validateProject(p);}
    @Test(expected=IOException.class)public void fragmentBaseUrlRejected()throws Exception{Net.requireHttps("https://example.com/v1#wrong");}
    @Test(expected=IOException.class)public void skippedZipEntryAlsoBounded()throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(b)){z.putNextEntry(new java.util.zip.ZipEntry("other.txt"));z.write(new byte[200]);z.closeEntry();z.putNextEntry(new java.util.zip.ZipEntry("target.log"));z.write(1);z.closeEntry();}ForgeClient.extractZip(b.toByteArray(),".log",100,new Net.Call());}
}
''';f.write_text(s)
f=root/'app/src/androidTest/java/com/ronin/vanta/DeviceTest.java';s=f.read_text().rstrip()[:-1]+'''
    @Test public void whiteImageIsWarned()throws Exception{Bitmap b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);assertTrue(MediaCheck.image(png(b)).suspicious);}
    @Test public void voicePickerOpensAndKeepsSelection()throws Exception{
        Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();SecureVault vault=new SecureVault(ctx);vault.putSecret("venice","qa-placeholder-never-used-for-network");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{try{a.showMode("setup");java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("voiceStudio");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new AssertionError(e);}});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(500);
            android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();android.view.accessibility.AccessibilityNodeInfo root=ui.getRootInActiveWindow();assertNotNull(root);assertFalse(root.findAccessibilityNodeInfosByText("Voice Studio").isEmpty());
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> emma=root.findAccessibilityNodeInfosByText("Emma");assertFalse(emma.isEmpty());android.view.accessibility.AccessibilityNodeInfo item=emma.get(0);while(item!=null&&!item.isClickable())item=item.getParent();assertNotNull(item);assertTrue(item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(300);root=ui.getRootInActiveWindow();java.util.List<android.view.accessibility.AccessibilityNodeInfo> use=root.findAccessibilityNodeInfosByText("USE VOICE");assertFalse(use.isEmpty());
            Bitmap shot=ui.takeScreenshot();if(shot!=null){try(FileOutputStream out=new FileOutputStream(new File(ctx.getExternalFilesDir(null),"voice-picker-preview.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}}
            assertTrue(use.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertEquals("bf_emma",ctx.getSharedPreferences("vanta_state",0).getString("voice_id",""));
        }finally{vault.deleteSecret("venice");ctx.getSharedPreferences("vanta_state",0).edit().remove("voice_provider").remove("voice_model").remove("voice_id").remove("voice_label").apply();}
    }
}
''';f.write_text(s)
print('Applied final reviewed corrections: 0.4.1, worker contract, bounded archives, durable vault, voice preview UX and regression tests.')
