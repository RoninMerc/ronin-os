package com.ronin.vanta;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public class Continuity171DeviceTest {
  Context ctx(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
  @Test public void seed() throws Exception {
    new SecureVault(ctx()).putSecret("qa-upgrade-171","fixture-key-preserved-not-a-provider-key");
    new WorkspaceState(ctx()).draft("qa-upgrade-171","Unsent text retained over install");
    ThreadStore threads=new ThreadStore(new SecureVault(ctx()));
    JSONObject thread=threads.create("qa-upgrade-provider","qa-upgrade-model","chat");
    threads.append(thread,"user","Conversation retained over install");
    assertTrue(ctx().getSharedPreferences("qa-171",0).edit().putString("thread",thread.getString("id")).commit());
  }
  @Test public void verify() throws Exception {
    assertEquals("0.17.1",ctx().getPackageManager().getPackageInfo(ctx().getPackageName(),0).versionName);
    assertEquals("fixture-key-preserved-not-a-provider-key",new SecureVault(ctx()).getSecret("qa-upgrade-171"));
    assertEquals("Unsent text retained over install",new WorkspaceState(ctx()).draft("qa-upgrade-171"));
    String id=ctx().getSharedPreferences("qa-171",0).getString("thread","");
    assertEquals("Conversation retained over install",new ThreadStore(new SecureVault(ctx())).get(id).getJSONArray("messages").getJSONObject(0).getString("content"));
  }
  @Test public void reopenCatalogueWithoutNetwork() throws Exception {
    File file=new File(ctx().getExternalFilesDir(null),"live-catalogue.json");
    assertTrue(file.exists());JSONObject evidence;
    try(InputStream input=new FileInputStream(file)){evidence=new JSONObject(new String(Net.read(input,2000000,new Net.Call()),StandardCharsets.UTF_8));}
    int expected=evidence.getInt("count");
    long actual=ModelRegistry.get(ctx()).models("featherless").stream().filter(m->!"removed".equals(m.spec.optString("registry_status"))).count();
    assertEquals(expected,actual);
    assertNotNull(ModelRegistry.get(ctx()).find("featherless","huihui-ai/Huihui-Qwen3.5-27B-abliterated"));
  }
}
