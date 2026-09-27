package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EvelynMultipartTest {
    private Context context;

    @Before public void before() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        MultipartPhrasePack.clear(context);
        context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE).edit().remove("evelyn_multipart_script_v1").putString("voice_profile_active","evelyn").commit();
    }

    @After public void after() throws Exception { MultipartPhrasePack.clear(context); }

    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}

    private File wav(String name,int phrases)throws Exception{
        int rate=48000,phraseFrames=rate*3/4,pauseFrames=rate*3;
        int total=phrases*phraseFrames+Math.max(0,phrases-1)*pauseFrames;
        File f=new File(context.getCacheDir(),name);
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            d.writeBytes("RIFF");le32(d,36+total*2);d.writeBytes("WAVEfmt ");le32(d,16);le16(d,1);le16(d,1);
            le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);d.writeBytes("data");le32(d,total*2);
            for(int p=0;p<phrases;p++){
                double hz=220+p*25;
                for(int i=0;i<phraseFrames;i++)le16(d,(short)(Math.sin(2*Math.PI*hz*i/rate)*9000));
                if(p<phrases-1)for(int i=0;i<pauseFrames;i++)le16(d,0);
            }
        }
        return f;
    }

    @Test public void multipleWavsMergeIntoOneEvelynLibrary()throws Exception{
        List<String> first=Arrays.asList("Silvertracker update","Tristan","Suspicious Activity");
        List<String> second=Arrays.asList("Noise Complaint","Impeccable BC","Welfare Check");
        MultipartPhrasePack.Result a=MultipartPhrasePack.importPart(context,Uri.fromFile(wav("evelyn-p1.wav",3)),1,2,"rev-A",first);
        assertEquals(1,a.part);assertEquals(3,a.phrases);assertEquals(new LinkedHashSet<>(Arrays.asList(1)),MultipartPhrasePack.importedParts(context));
        MultipartPhrasePack.Result b=MultipartPhrasePack.importPart(context,Uri.fromFile(wav("evelyn-p2.wav",3)),2,2,"rev-A",second);
        assertEquals(6,b.totalClips);assertEquals(new LinkedHashSet<>(Arrays.asList(1,2)),MultipartPhrasePack.importedParts(context));
        Map<String,File> clips=MultipartPhrasePack.clips(context);
        for(String phrase:first)assertTrue(phrase,clips.containsKey(ExactPhrasePack.normalize(phrase)));
        for(String phrase:second)assertTrue(phrase,clips.containsKey(ExactPhrasePack.normalize(phrase)));
    }

    @Test public void replacingOnePartDoesNotDeleteOtherParts()throws Exception{
        MultipartPhrasePack.importPart(context,Uri.fromFile(wav("old1.wav",2)),1,2,"rev-B",Arrays.asList("One","Two"));
        MultipartPhrasePack.importPart(context,Uri.fromFile(wav("old2.wav",2)),2,2,"rev-B",Arrays.asList("Three","Four"));
        MultipartPhrasePack.importPart(context,Uri.fromFile(wav("new1.wav",2)),1,2,"rev-B",Arrays.asList("One","Replacement"));
        Map<String,File> clips=MultipartPhrasePack.clips(context);
        assertTrue(clips.containsKey("three"));assertTrue(clips.containsKey("four"));assertTrue(clips.containsKey("replacement"));
    }

    @Test public void voiceManagerExposesOnlyEvelynAndBuildsShortParts(){
        VoiceManager v=new VoiceManager(context,context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE));
        assertEquals(1,v.profiles().size());assertEquals("Evelyn",v.activeName());assertEquals("evelyn",v.activeProfile().id);
        List<VoiceManager.Part> parts=v.prepareParts(Collections.emptyList(),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        assertTrue(parts.size()>1);
        for(VoiceManager.Part p:parts){assertTrue(p.phraseCount<=22);assertFalse(p.script.endsWith("[pause 3]\n"));}
        assertTrue(parts.get(0).script.contains("Good evening, Tristan. Let's have a good shift"));
    }

    @Test public void mismatchedPartRefusesImportWithoutDamagingExistingLibrary()throws Exception{
        MultipartPhrasePack.importPart(context,Uri.fromFile(wav("good.wav",2)),1,2,"rev-C",Arrays.asList("Alpha","Bravo"));
        int before=MultipartPhrasePack.clipCount(context);
        try{
            MultipartPhrasePack.importPart(context,Uri.fromFile(wav("bad.wav",2)),2,2,"rev-C",Arrays.asList("Charlie","Delta","Echo"));
            fail("Mismatched WAV accepted");
        }catch(IOException expected){assertTrue(expected.getMessage().contains("expected 3"));}
        assertEquals(before,MultipartPhrasePack.clipCount(context));
        assertTrue(MultipartPhrasePack.clips(context).containsKey("alpha"));
    }
}
