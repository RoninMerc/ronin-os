from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'));j=root/'app/src/main/java/com/ronin/vanta'
def edit(path,old,new):
 s=path.read_text();assert s.count(old)==1,(path.name,s.count(old),old[:80]);path.write_text(s.replace(old,new))
edit(j/'MainActivity.java','"Ronin Vanta 0.7 · personal API client"','"Ronin Vanta " + installedVersion() + " · personal API client"')
edit(j/'MainActivity.java','"RONIN VANTA 0.6\\nAndroid API "','"RONIN VANTA " + installedVersion() + "\\nAndroid API "')
edit(j/'MainActivity.java','  private void diagnostics() {','''  String installedVersion() {
    try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
    catch(android.content.pm.PackageManager.NameNotFoundException error) { return "Version unavailable"; }
  }

  private void diagnostics() {''')
# Real update/process probes preserve private records and generated-APK signing identity.
# Fixture values are never real credentials.
p=root/'app/src/androidTest/java/com/ronin/vanta/ProcessProbe.java'
edit(p,'    assertTrue(\n        ctx()','''    SecureVault vault=new SecureVault(ctx());
    vault.putSecret("qa-upgrade-credential","fixture-value-not-an-api-key");
    ThreadStore threads=new ThreadStore(vault);
    JSONObject chat=threads.create("qa-upgrade","fixture-model","chat");
    threads.append(chat,"user","Saved conversation before update");
    new WorkspaceState(ctx()).draft("qa-upgrade","Unsent draft before update");
    byte[] apk=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(ctx().getApplicationInfo().sourceDir));
    ArtifactSigner.Result signed=ArtifactSigner.sign(ctx(),apk,new Net.Call());
    assertTrue(
        ctx()''')
edit(p,'            .putInt("pid", android.os.Process.myPid())','''            .putString("chat",chat.getString("id"))
            .putString("generated_certificate",signed.certificateSha256)
            .putInt("pid", android.os.Process.myPid())''')
edit(p,'    e.cancel(remote.id());','''    SecureVault vault=new SecureVault(ctx());
    assertEquals("fixture-value-not-an-api-key",vault.getSecret("qa-upgrade-credential"));
    ThreadStore threads=new ThreadStore(vault);
    JSONObject chat=threads.get(p.getString("chat",""));
    assertEquals("Saved conversation before update",chat.getJSONArray("messages").getJSONObject(0).getString("content"));
    assertEquals("Unsent draft before update",new WorkspaceState(ctx()).draft("qa-upgrade"));
    byte[] apk=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(ctx().getApplicationInfo().sourceDir));
    assertEquals(p.getString("generated_certificate",""),ArtifactSigner.sign(ctx(),apk,new Net.Call()).certificateSha256);
    vault.deleteSecret("qa-upgrade-credential");threads.delete(chat.getString("id"));new WorkspaceState(ctx()).draft("qa-upgrade","");
    e.cancel(remote.id());''')
p=root/'app/src/androidTest/java/com/ronin/vanta/ForgeReleaseTest.java';s=p.read_text().rstrip();assert s.endswith('}');s=s[:-1]+'''
  @Test public void aboutVersionMatchesInstalledPackage()throws Exception{
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)){
      a.onActivity(host->{try{assertEquals(host.getPackageManager().getPackageInfo(host.getPackageName(),0).versionName,host.installedVersion());}catch(Exception e){throw new AssertionError(e);}});
    }
  }
  @Test public void twentyTwoThousandModelsRemainSearchableWithoutBlockingUi()throws Exception{
    ProviderConfig p=h.seed("featherless");
    List<ModelInfo> models=new ArrayList<>();
    for(int n=0;n<22000;n++)models.add(new ModelInfo("scale-"+n,String.format(Locale.ROOT,"Scale model %05d",n),"text","Catalogue stress fixture"));
    try(ActivityScenario<MainActivity> a=ActivityScenario.launch(MainActivity.class)){
      AtomicReference<Dialog> dialog=new AtomicReference<>();
      AtomicReference<ListView> list=new AtomicReference<>();
      CountDownLatch responsive=new CountDownLatch(1);
      long started=SystemClock.elapsedRealtime();
      a.onActivity(host->{
        Map<String,List<ModelInfo>> catalog=new HashMap<>();catalog.put(p.id,models);
        dialog.set(new ModelPicker(host,h.ctx().getSharedPreferences("vanta_state",0),Arrays.asList(p),catalog,new HashSet<>(Arrays.asList(p.id)),"all","qa-scale",null,false,new ModelPicker.Listener(){
          public void select(ProviderConfig provider,ModelInfo model){}
          public void auto(){}
          public void connect(ProviderConfig provider){}
          public void changed(ProviderConfig provider){}
        }).show());
        list.set(dialog.get().findViewById(android.R.id.content).findViewWithTag("model-list"));
        new Handler(Looper.getMainLooper()).post(responsive::countDown);
      });
      assertTrue("UI queue must remain responsive while preparing 22,000 models",responsive.await(2,TimeUnit.SECONDS));
      awaitCount(a,list.get(),22000);
      long loaded=SystemClock.elapsedRealtime();
      a.onActivity(host->{assertTrue("Do not inflate every model row",list.get().getChildCount()<40);
        ((EditText)dialog.get().findViewById(android.R.id.content).findViewWithTag("model-search")).setText("Scale model 21999");});
      awaitCount(a,list.get(),1);
      a.onActivity(host->{VantaRouter.Candidate item=(VantaRouter.Candidate)list.get().getItemAtPosition(list.get().getHeaderViewsCount());assertEquals("scale-21999",item.model.id);});
      try(java.io.PrintWriter out=new java.io.PrintWriter(new java.io.File(h.ctx().getExternalFilesDir(null),"catalogue-scale.txt"))){
        out.println("22000 fixture records; main-loop responsive; virtualized rows; exact last-record search passed.");
        out.println("Catalogue ready elapsed ms="+(loaded-started));out.println("Last-record search elapsed ms="+(SystemClock.elapsedRealtime()-loaded));
      }
      h.shot("081-catalogue-scale");a.onActivity(host->dialog.get().dismiss());
    }
  }
  private static void awaitCount(ActivityScenario<MainActivity> a,ListView list,int expected)throws Exception{
    AtomicInteger count=new AtomicInteger(-1);long deadline=SystemClock.elapsedRealtime()+30000;
    do{Thread.sleep(80);a.onActivity(host->count.set(list.getCount()-list.getHeaderViewsCount()-list.getFooterViewsCount()));}while(count.get()!=expected&&SystemClock.elapsedRealtime()<deadline);
    assertEquals(expected,count.get());
  }
}
''';p.write_text(s)
# Network-enable shell commands return before Android finishes reconnecting. Observe the real
# restored network instead of assuming a fixed 2.5-second delay, and respect 30-second backoff.
p=root/'app/src/androidTest/java/com/ronin/vanta/BackgroundDeviceTest.java'
edit(p,'      Thread.sleep(2500);','''      deadline = SystemClock.elapsedRealtime() + 30000;
      long onlineSince=0;
      while(SystemClock.elapsedRealtime()<deadline){
        if(h.engine().online()) {if(onlineSince==0)onlineSince=SystemClock.elapsedRealtime();if(SystemClock.elapsedRealtime()-onlineSince>=1000)break;}
        else onlineSince=0;
        Thread.sleep(200);
      }
      assertTrue("Android actually reconnected after the second network loss",h.engine().online());''')
edit(p,'h.waitDone(job.id(), 12000)','h.waitDone(job.id(), 45000)')
print('Package version, secure upgrade continuity, 22k catalogue device stress and observed network restoration.')
