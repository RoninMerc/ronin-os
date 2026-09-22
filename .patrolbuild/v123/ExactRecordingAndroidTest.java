package au.com.roningroup.patrollink;

import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExactRecordingAndroidTest {
    @Rule public GrantPermissionRule permission=GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS");
    private static void waitFor(BooleanSupplier check,long timeout)throws Exception{long until=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<until){if(check.getAsBoolean())return;Thread.sleep(100);}assertTrue("Timed out",check.getAsBoolean());}
    private static File fixture(Context c)throws Exception{
        File f=new File(c.getFilesDir(),"exact-recording-fixture.wav");int rate=24000,n=rate*2;try(DataOutputStream d=new DataOutputStream(new FileOutputStream(f))){d.writeBytes("RIFF");ExactWav.le32(d,36+n*2);d.writeBytes("WAVEfmt ");ExactWav.le32(d,16);ExactWav.le16(d,1);ExactWav.le16(d,1);ExactWav.le32(d,rate);ExactWav.le32(d,rate*2);ExactWav.le16(d,2);ExactWav.le16(d,16);d.writeBytes("data");ExactWav.le32(d,n*2);for(int i=0;i<n;i++)ExactWav.le16(d,(int)(2000*Math.sin(i*.1)));}return f;
    }
    @Test public void exactOverrideSurvivesReloadAndPreviewAddsNothing(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();SharedPreferences store=c.getSharedPreferences("v123-test-wording",Context.MODE_PRIVATE);store.edit().clear().commit();
        SpeechPreferences p=new SpeechPreferences(store);Observation o=new Observation("fixture","D.ROGERS1","Tradition BC","General Patrol - Tradition B","",0);
        p.nickname("D.ROGERS1","Dean");p.saveRule(null,o.issue,"General Patrol - Tradition Place",true,true);
        assertEquals("General Patrol - Tradition Place",p.readout(o));assertEquals("General Patrol - Tradition Place",new SpeechPreferences(store).readout(o));
        assertEquals("Only this",p.preview(p.rules().get(0).id,o.issue,"Only this",true,true,o));assertEquals("General Patrol - Tradition Place",p.readout(o));
        p.removeRule(p.rules().get(0).id);assertTrue(p.readout(o).contains("Dean"));p.nickname("D.ROGERS1","");assertFalse(p.readout(o).contains("Dean"));store.edit().clear().commit();
    }
    @Test public void directPlaybackCompletesAndOtherVoiceNeverUsesFelicityClip()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();String unique="Exact test "+UUID.randomUUID();File source=fixture(c);AtomicReference<VoiceManager> ref=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{a.stopService(new Intent(a,MonitorService.class));PatrolEngine e=((PatrolApp)a.getApplication()).engine();e.stopRuntime("TEST","Recorded playback fixture");VoiceManager vm=new VoiceManager(a,a.getSharedPreferences("v123-test-voice",Context.MODE_PRIVATE));vm.setActive("felicity");ref.set(vm);});
            VoiceManager vm=ref.get();File cut=vm.recordings.save("felicity",unique,source,.25,1.25);assertEquals(1.0,ExactWav.open(cut).seconds(),.001);
            scenario.onActivity(a->vm.preview(unique));waitFor(()->vm.completedCount()==1,15000);assertEquals(unique,vm.lastText());
            AtomicReference<VoiceManager.Profile> imported=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
            scenario.onActivity(a->vm.importAudio(Uri.fromFile(source),"Other test voice",(p,e)->{imported.set(p);error.set(e);}));waitFor(()->imported.get()!=null||error.get()!=null,15000);assertNull(error.get());assertArrayEquals(Files.readAllBytes(source.toPath()),Files.readAllBytes(new File(imported.get().path).toPath()));
            scenario.onActivity(a->vm.preview(unique));Thread.sleep(1000);assertEquals("Must not use Felicity's assigned recording for a different voice",1,vm.completedCount());assertTrue(vm.status().contains("Recording needed"));
            vm.recordings.save(imported.get().id,unique,source,0,1);scenario.onActivity(a->{vm.speed(1.25f);vm.preview(unique);});waitFor(()->vm.completedCount()==2,15000);
            assertEquals(1.25f,vm.speed(),.001);scenario.onActivity(a->{vm.stop();vm.speed(1f);vm.setActive("felicity");});
        }
    }
    @Test public void recordedSettingsKeepBrowserAttachedAndExposeScriptControls()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();android.webkit.WebView w=e.webView();assertNotNull(w.getParent());a.recordedAnnouncements();assertNotNull("Opening recording settings must not detach monitor",w.getParent());});
            Thread.sleep(500);scenario.recreate();scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();assertNotNull(e.webView().getParent());});
        }
    }
}
