package au.com.roningroup.patrollink;

import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CompleteLibraryTemplateTest {
    private Context context;
    private SharedPreferences prefs;

    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private static void writeSyntheticPack(File f,int count)throws Exception{
        int rate=48000,phraseMs=520,pauseMs=3000,frames=0;
        for(int i=0;i<count;i++){frames+=rate*phraseMs/1000;if(i<count-1)frames+=rate*pauseMs/1000;}
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            d.writeBytes("RIFF");le32(d,36+frames*2);d.writeBytes("WAVEfmt ");le32(d,16);
            le16(d,1);le16(d,1);le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);
            d.writeBytes("data");le32(d,frames*2);
            for(int i=0;i<count;i++){
                double hz=210d+(i*45d);int n=rate*phraseMs/1000;
                for(int x=0;x<n;x++)le16(d,(short)(Math.sin(2*Math.PI*hz*x/rate)*9000));
                if(i<count-1)for(int x=0;x<rate*pauseMs/1000;x++)le16(d,0);
            }
        }
    }

    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        prefs.edit()
            .remove("voice_complete_library_template_v1")
            .remove("voice_complete_library_template_source_v1")
            .remove("voice_profiles_v5")
            .remove("voice_profile_active")
            .remove("voice_profiles_single_wav_v128")
            .commit();
        delete(new File(context.getFilesDir(),"exact_voice_packs/evelyn"));
    }

    @After public void cleanup() throws Exception {
        prefs.edit()
            .remove("voice_complete_library_template_v1")
            .remove("voice_complete_library_template_source_v1")
            .remove("voice_profiles_v5")
            .remove("voice_profile_active")
            .remove("voice_profiles_single_wav_v128")
            .commit();
        delete(new File(context.getFilesDir(),"exact_voice_packs/evelyn"));
    }

    private static void delete(File f){
        if(f==null||!f.exists())return;
        if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)delete(x);}
        f.delete();
    }

    @Test public void copiedCompleteScriptIncludesBaseAndAllInstalledAdditionsAndCanSeedNewVoice()throws Exception{
        VoiceManager v=new VoiceManager(context,prefs);
        String sourceProfile=v.activeProfile().id;

        List<String> base=Arrays.asList("Silvertracker update","General Patrol","Guard Patrol");
        File baseWav=new File(context.getCacheDir(),"complete-base.wav");
        writeSyntheticPack(baseWav,base.size());
        ExactPhrasePack.importPack(context,Uri.fromFile(baseWav),sourceProfile,base);

        List<String> additions=Arrays.asList("Bobsled Lane","Balmara Place");
        File addWav=new File(context.getCacheDir(),"complete-addon.wav");
        writeSyntheticPack(addWav,additions.size());
        ExactPhrasePack.importAdditions(context,Uri.fromFile(addWav),sourceProfile,additions);

        String script=v.completeLibraryScript();
        for(String p:base)assertTrue(script.contains(p));
        for(String p:additions)assertTrue(script.contains(p));
        assertEquals(5,v.completeTemplateCount());
        assertEquals("Evelyn",v.completeTemplateSource());

        CountDownLatch created=new CountDownLatch(1);
        final boolean[] createdOk={false};
        v.importTrainingAudio(null,"Derek",(ok,msg)->{createdOk[0]=ok;created.countDown();});
        assertTrue(created.await(5,TimeUnit.SECONDS));
        assertTrue(createdOk[0]);
        assertEquals("Derek",v.activeName());

        // Empty new profile still exposes the previously copied complete template,
        // rather than falling back to the original canonical-only script.
        String forNewVoice=v.completeLibraryScript();
        assertTrue(forNewVoice.contains("Bobsled Lane"));
        assertTrue(forNewVoice.contains("Balmara Place"));
        assertEquals(5,v.completeTemplateCount());
        assertEquals("Evelyn",v.completeTemplateSource());

        File derekWav=new File(context.getCacheDir(),"complete-derek.wav");
        writeSyntheticPack(derekWav,5);
        CountDownLatch imported=new CountDownLatch(1);
        final boolean[] importedOk={false};
        v.importCompleteLibrary(Uri.fromFile(derekWav),(ok,msg)->{importedOk[0]=ok;imported.countDown();});
        assertTrue(imported.await(10,TimeUnit.SECONDS));
        assertTrue(importedOk[0]);
        assertEquals(5,ExactPhrasePack.clips(context,v.activeProfile().id).size());
        assertNotNull(ExactPhrasePack.clips(context,v.activeProfile().id).get(ExactPhrasePack.normalize("Bobsled Lane")));
    }
}
