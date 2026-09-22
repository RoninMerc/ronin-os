package au.com.roningroup.patrollink;

import android.Manifest;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class VoicePlaybackTest {
    private static void main(Runnable r){InstrumentationRegistry.getInstrumentation().runOnMainSync(r);}
    private static String diagnostic(VoiceManager vm){AtomicReference<String> s=new AtomicReference<>();main(()->s.set(vm.diagnostics()));return s.get();}
    private static void awaitReady(VoiceManager vm)throws Exception{
        long end=SystemClock.elapsedRealtime()+90000;
        while(SystemClock.elapsedRealtime()<end){AtomicBoolean ready=new AtomicBoolean();main(()->ready.set(vm.ready()));if(ready.get())return;Thread.sleep(250);}
        fail("Local model not ready: "+diagnostic(vm));
    }
    private static void awaitReadout(VoiceManager vm,String text)throws Exception{
        long end=SystemClock.elapsedRealtime()+120000;
        while(SystemClock.elapsedRealtime()<end){AtomicReference<String> spoken=new AtomicReference<>();main(()->spoken.set(vm.lastText()));if(text.equals(spoken.get()))return;Thread.sleep(200);}
        fail("Full generated speech did not complete actual Android playback: "+diagnostic(vm));
    }
    @Test public void fullAnnouncementsCompletePlaybackAndImportedVoiceCanBeSelected()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        AtomicReference<VoiceManager> manager=new AtomicReference<>();AtomicReference<PatrolEngine> engine=new AtomicReference<>();
        String importedId=null;File source=new File(context.getFilesDir(),"import-test-reference.wav");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();engine.set(e);manager.set(e.voices);});
            Thread.sleep(1500);
            main(()->{context.stopService(new Intent(context,MonitorService.class));engine.get().stopRuntime("TEST","Local speech playback fixture only");manager.get().setActive(VoiceManager.DEFAULT_ID);});
            VoiceManager vm=manager.get();awaitReady(vm);
            Observation first=new Observation("playback-1","T.MURD","Impeccable","Third warning parking breach","",System.currentTimeMillis());
            main(()->vm.speak(first));awaitReadout(vm,VoiceReadout.of(first));
            System.out.println("PLAYBACK_COMPLETED Felicity guard, full matter and property: "+vm.lastText());
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("alternate-reference.wav");OutputStream out=new FileOutputStream(source)){
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            CountDownLatch imported=new CountDownLatch(1);AtomicReference<VoiceManager.Profile> profile=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
            main(()->vm.importAudio(Uri.fromFile(source),"Imported test voice",(p,message)->{profile.set(p);error.set(message);imported.countDown();}));
            assertTrue(imported.await(30,TimeUnit.SECONDS));assertNull(error.get());assertNotNull(profile.get());importedId=profile.get().id;
            AtomicReference<String> selected=new AtomicReference<>();main(()->selected.set(vm.activeProfile().id));assertEquals(importedId,selected.get());
            Observation second=new Observation("playback-2","D.ROGERS1","Solo","Parking Breach 3","",System.currentTimeMillis());
            main(()->vm.speak(second));awaitReadout(vm,VoiceReadout.of(second));
            System.out.println("PLAYBACK_COMPLETED Imported voice guard, full matter and property: "+vm.lastText());
            assertTrue(diagnostic(vm),diagnostic(vm).contains("Completed/failed speech: 2/0"));
        }finally{
            String remove=importedId;if(manager.get()!=null)main(()->{if(remove!=null)manager.get().delete(remove);manager.get().setActive(VoiceManager.DEFAULT_ID);manager.get().stop();});
            source.delete();
        }
    }
}
