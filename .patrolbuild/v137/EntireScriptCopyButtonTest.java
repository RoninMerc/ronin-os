package au.com.roningroup.patrollink;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EntireScriptCopyButtonTest {
    @Rule public GrantPermissionRule notifications=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    private Context c;private SharedPreferences prefs;private String profile;private PatrolEngine engine;
    private final List<String> created=new ArrayList<>();
    private static final List<String> BASE=Arrays.asList("Silvertracker update","General Patrol","Guard Patrol");
    private static final List<String> FULL=Arrays.asList("Silvertracker update","General Patrol","Guard Patrol","Bobsled Lane","Tradition Place");
    @Before public void setup(){
        c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=c.getSharedPreferences("patrol_settings",0);
        profile="copy137-"+UUID.randomUUID();created.add(profile);
        prefs.edit().putBoolean("voice",false).putBoolean("voice_profiles_single_wav_v128",true)
                .putString("voice_profiles_v5","[{\"id\":\""+profile+"\",\"name\":\"Evelyn test\"}]")
                .putString("voice_profile_active",profile)
                .remove("voice_complete_library_template_v1").remove("voice_complete_library_template_source_v1").commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            engine=((PatrolApp)c.getApplicationContext()).engine();engine.stopRuntime("TEST","Offline UI fixture");
            engine.webView().setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                    return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream("<html>Offline fixture</html>".getBytes(StandardCharsets.UTF_8)));
                }
            });
        });
    }
    @After public void cleanup(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            c.stopService(new Intent(c,MonitorService.class));engine.stopRuntime("TEST_DONE","Complete");
        });
        for(String id:created)remove(new File(c.getFilesDir(),"exact_voice_packs/"+id));
        prefs.edit().remove("voice_profiles_v5").remove("voice_profile_active")
                .remove("voice_complete_library_template_v1").remove("voice_complete_library_template_source_v1").commit();
    }
    private static void remove(File f){if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)remove(x);}f.delete();}
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private File wav(int count,int hz)throws Exception{
        File f=new File(c.getCacheDir(),profile+"-"+UUID.randomUUID()+".wav");
        int rate=16000,voiceFrames=8800,silenceFrames=48000,frames=count*voiceFrames+(count-1)*silenceFrames;
        byte[] voice=new byte[voiceFrames*2],silence=new byte[silenceFrames*2];
        for(int i=0;i<voiceFrames;i++){short s=(short)(Math.sin(2*Math.PI*hz*i/rate)*9500);voice[2*i]=(byte)s;voice[2*i+1]=(byte)(s>>8);}
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            d.writeBytes("RIFF");le32(d,36+frames*2);d.writeBytes("WAVEfmt ");le32(d,16);le16(d,1);le16(d,1);
            le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);d.writeBytes("data");le32(d,frames*2);
            for(int i=0;i<count;i++){d.write(voice);if(i<count-1)d.write(silence);}
        }
        return f;
    }
    private void installSmall()throws Exception{
        ExactPhrasePack.importPack(c,Uri.fromFile(wav(3,220)),profile,BASE);
        ExactPhrasePack.importAdditions(c,Uri.fromFile(wav(2,300)),profile,Arrays.asList("Bobsled Lane","Tradition Place"));
        ExactPhrasePack.importAdditions(c,Uri.fromFile(wav(1,420)),profile,Collections.singletonList("Bobsled Lane"));
    }
    private Map<String,String> hashes()throws Exception{
        Map<String,String> out=new LinkedHashMap<>();
        for(Map.Entry<String,File> e:ExactPhrasePack.clips(c,profile).entrySet()){
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(e.getValue().toPath()));
            out.put(e.getKey(),Arrays.toString(hash));
        }
        return out;
    }
    private static void show(MainActivity a){
        try{Method m=MainActivity.class.getDeclaredMethod("voiceLibrary");m.setAccessible(true);m.invoke(a);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static AlertDialog dialog(MainActivity a){
        try{Field f=MainActivity.class.getDeclaredField("voiceDialog");f.setAccessible(true);return (AlertDialog)f.get(a);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static View panelRoot(MainActivity a,boolean inDialog){return inDialog?dialog(a).getWindow().getDecorView():a.getWindow().getDecorView();}
    private String copyViaUi(ActivityScenario<MainActivity> scenario,boolean inDialog)throws Exception{
        scenario.onActivity(a->{
            a.stopService(new Intent(a,MonitorService.class));engine.stopRuntime("TEST","Offline fixture");
            if(inDialog)show(a);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(a->{
            View root=panelRoot(a,inDialog);Button b=root.findViewWithTag(EntireScriptCopyUi.BUTTON_TAG);
            assertNotNull("Actual Copy entire voice script button",b);
            assertEquals("Copy entire voice script",b.getText().toString());
            if(inDialog){Rect visible=new Rect();assertTrue(b.getGlobalVisibleRect(visible));assertTrue("Button fully visible without scrolling",visible.height()>=b.getHeight()-2);}
            assertTrue(b.performClick());
        });
        long end=SystemClock.elapsedRealtime()+10000;AtomicReference<String> status=new AtomicReference<>("");
        while(SystemClock.elapsedRealtime()<end){
            scenario.onActivity(a->{TextView v=panelRoot(a,inDialog).findViewWithTag(EntireScriptCopyUi.RESULT_TAG);status.set(v.getText().toString());});
            if(status.get().startsWith("Copied ")||status.get().startsWith("Not copied:"))break;
            Thread.sleep(100);
        }
        assertTrue(status.get(),status.get().startsWith("Copied "));
        AtomicReference<String> text=new AtomicReference<>();
        scenario.onActivity(a->{
            ClipData clip=((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
            assertNotNull("System clipboard was populated",clip);assertEquals(1,clip.getItemCount());
            text.set(clip.getItemAt(0).getText().toString());
        });
        return text.get();
    }
    private static void closeDialog(ActivityScenario<MainActivity> scenario){scenario.onActivity(a->{AlertDialog d=dialog(a);if(d!=null)d.dismiss();});}

    @Test public void topButtonCopiesBaseAndImportedAdditionsWithoutDuplicates()throws Exception{
        installSmall();Map<String,String> before=hashes();
        Map<String,?> tallies=new HashMap<>(c.getSharedPreferences(CheckpointTracker.FILE,0).getAll());
        engine.voices.saveAddOnDraft(Collections.singletonList("Unimported draft must not appear"));
        engine.voices.setCompleteScript("Unrelated loaded template");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            String actual=copyViaUi(scenario,true);
            assertEquals(FULL,VoiceScriptText.parse(actual));assertEquals(4,actual.split("\\[pause 3\\]",-1).length-1);
            assertFalse(actual.contains("Unimported draft"));assertFalse(actual.contains("Unrelated loaded"));
            assertEquals(FULL,engine.voices.completeTemplatePhrases());
            assertEquals(before,hashes());assertEquals(tallies,c.getSharedPreferences(CheckpointTracker.FILE,0).getAll());
            // Capture the actually rendered settings screen, not a design mock-up.
            Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(image!=null){try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"copy-script-top.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
            closeDialog(scenario);
        }
    }
    @Test public void dashboardShortcutCopiesAll476PhrasesIntoOneClipboardItem()throws Exception{
        List<String> phrases=new ArrayList<>();for(int i=0;i<360;i++)phrases.add("Original phrase "+i);
        for(int i=0;i<116;i++)phrases.add("Added street "+i);
        // Build an installed manifest fixture at real-library scale. Import itself
        // is covered separately, including the 608-phrase WAV regression.
        File dir=ExactPhrasePack.profileDir(c,profile),sample=wav(1,220);
        for(int i=0;i<phrases.size();i++)Files.copy(sample.toPath(),new File(dir,String.format(Locale.ROOT,"clip-%04d.wav",i)).toPath());
        JSONObject manifest=new JSONObject();manifest.put("version",2);manifest.put("phrases",new JSONArray(phrases));
        Files.write(new File(dir,"manifest.json").toPath(),manifest.toString().getBytes(StandardCharsets.UTF_8));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            String actual=copyViaUi(scenario,false);
            assertEquals(phrases,VoiceScriptText.parse(actual));assertEquals(476,engine.voices.completeTemplateCount());
            assertTrue(actual.contains("Original phrase 0"));assertTrue(actual.contains("Added street 115"));
        }
    }
    @Test public void copiedTemplateSurvivesNewProfileAndFullWavImport()throws Exception{
        installSmall();String actual=engine.voices.completeLibraryScript();assertEquals(FULL,VoiceScriptText.parse(actual));
        Map<String,String> before=hashes();CountDownLatch createdVoice=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();
        engine.voices.importTrainingAudio(null,"Another voice",(success,msg)->{ok.set(success);createdVoice.countDown();});
        assertTrue(createdVoice.await(5,TimeUnit.SECONDS));assertTrue(ok.get());
        created.add(engine.voices.activeProfile().id);
        VoiceManager restored=new VoiceManager(c,prefs);assertEquals(FULL,restored.completeTemplatePhrases());
        assertEquals(actual,restored.completeLibraryScript());
        CountDownLatch imported=new CountDownLatch(1);
        restored.importCompleteLibrary(Uri.fromFile(wav(5,320)),(success,msg)->{ok.set(success);imported.countDown();});
        assertTrue(imported.await(15,TimeUnit.SECONDS));assertTrue(ok.get());
        assertEquals(FULL,ExactPhrasePack.phrases(c,restored.activeProfile().id));assertEquals(before,hashes());
    }
    @Test public void brokenInstalledManifestDoesNotSilentlyCopyStaleTemplate()throws Exception{
        installSmall();engine.voices.setCompleteScript("Old script must not be copied");
        File dir=ExactPhrasePack.profileDir(c,profile);assertTrue(new File(dir,"clip-0000.wav").delete());
        CountDownLatch done=new CountDownLatch(1);AtomicReference<VoiceManager.ScriptExport> got=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
        engine.voices.exportEntireScript((export,msg)->{got.set(export);error.set(msg);done.countDown();});
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(got.get());assertFalse(error.get().isEmpty());
        assertEquals(Collections.singletonList("Old script must not be copied"),engine.voices.completeTemplatePhrases());
    }
    @Test public void copyWaitsForPendingAdditionAndKeepsTheSelectedSource()throws Exception{
        installSmall();VoiceManager manager=engine.voices;
        Field f=VoiceManager.class.getDeclaredField("files");f.setAccessible(true);ExecutorService worker=(ExecutorService)f.get(manager);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),imported=new CountDownLatch(1),copied=new CountDownLatch(1);
        worker.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        AtomicReference<VoiceManager.ScriptExport> result=new AtomicReference<>();AtomicBoolean importedOk=new AtomicBoolean();
        try{
            manager.saveAddOnDraft(Collections.singletonList("New checkpoint"));
            manager.importAddOn(Uri.fromFile(wav(1,450)),(ok,msg)->{importedOk.set(ok);imported.countDown();});
            manager.exportEntireScript((out,error)->{result.set(out);copied.countDown();});
            CountDownLatch createdVoice=new CountDownLatch(1);
            manager.importTrainingAudio(null,"Different selection",(ok,msg)->createdVoice.countDown());
            assertTrue(createdVoice.await(5,TimeUnit.SECONDS));created.add(manager.activeProfile().id);
        }finally{release.countDown();}
        assertTrue(imported.await(10,TimeUnit.SECONDS));assertTrue(importedOk.get());assertTrue(copied.await(10,TimeUnit.SECONDS));
        assertNotNull(result.get());assertEquals("Evelyn test",result.get().source);assertEquals(6,result.get().phrases);
        assertTrue(result.get().text.contains("New checkpoint"));
    }
}
