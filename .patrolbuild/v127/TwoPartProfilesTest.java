package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TwoPartProfilesTest {
    private Context context;
    private android.content.SharedPreferences prefs;

    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        prefs.edit()
            .remove("voice_profiles_reset_v127")
            .remove("voice_profiles_v4")
            .remove("voice_profiles_v3")
            .remove("voice_profile_active")
            .commit();
        delete(new File(context.getFilesDir(),"exact_voice_packs_v3"));
        // Seed a legacy Felicity profile to prove migration does not expose it.
        org.json.JSONArray old=new org.json.JSONArray();
        org.json.JSONObject f=new org.json.JSONObject();f.put("id","felicity");f.put("name","Felicity");old.put(f);
        prefs.edit().putString("voice_profiles_v3",old.toString()).putString("voice_profile_active","felicity").commit();
    }

    @After public void cleanup() throws Exception { delete(new File(context.getFilesDir(),"exact_voice_packs_v3")); }

    private static void delete(File f){
        if(f==null||!f.exists())return;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)delete(k);}f.delete();
    }

    @Test public void firstLaunchShowsOnlyEvelynAndNoFelicity(){
        VoiceManager v=new VoiceManager(context,prefs);
        assertEquals(1,v.profiles().size());
        assertEquals("Evelyn",v.profiles().get(0).name);
        assertEquals("evelyn",v.activeProfile().id);
        for(VoiceManager.Profile p:v.profiles())assertNotEquals("Felicity",p.name);
    }

    @Test public void addMoreProfilesAndDeleteEvelynWhenAnotherExists(){
        VoiceManager v=new VoiceManager(context,prefs);
        AtomicBoolean ok=new AtomicBoolean();
        v.importTrainingAudio(null,"Derek",(success,msg)->ok.set(success));
        assertTrue(ok.get());
        assertEquals(2,v.profiles().size());
        assertEquals("Derek",v.activeName());
        assertTrue(v.delete("evelyn"));
        assertEquals(1,v.profiles().size());
        assertEquals("Derek",v.activeName());
        assertFalse("sole remaining profile must not disappear",v.delete(v.activeProfile().id));
    }

    @Test public void masterScriptIsExactlyTwoHalves(){
        VoiceManager v=new VoiceManager(context,prefs);
        List<VoiceManager.Part> parts=v.prepareParts(Collections.emptyList(),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        assertEquals(2,parts.size());
        assertEquals(1,parts.get(0).number);assertEquals(2,parts.get(1).number);
        assertEquals(2,parts.get(0).total);assertEquals(2,parts.get(1).total);
        assertTrue(Math.abs(parts.get(0).phraseCount-parts.get(1).phraseCount)<=1);
        assertFalse(parts.get(0).script.endsWith("[pause 3]\n"));
        assertFalse(parts.get(1).script.endsWith("[pause 3]\n"));
        assertTrue(parts.get(0).script.contains("Good evening, Tristan. Let's have a good shift"));
    }

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
                double hz=220+p*20;
                for(int i=0;i<phraseFrames;i++)le16(d,(short)(Math.sin(2*Math.PI*hz*i/rate)*9000));
                if(p<phrases-1)for(int i=0;i<pauseFrames;i++)le16(d,0);
            }
        }
        return f;
    }

    @Test public void twoPartsMergePerProfileAndProfilesStaySeparate() throws Exception {
        VoiceManager v=new VoiceManager(context,prefs);
        List<VoiceManager.Part> parts=v.prepareParts(Collections.emptyList(),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        String rev=prefs.getString("two_part_manifest:evelyn","");
        assertFalse(rev.isEmpty());

        // Use smaller direct pack import fixtures for deterministic merge behavior.
        List<String> a=Arrays.asList("Silvertracker update","Tristan","Noise Complaint");
        List<String> b=Arrays.asList("Security Patrol","Impeccable BC","Welfare Check");
        ProfilePhrasePack.importPart(context,Uri.fromFile(wav("p1.wav",a.size())),"evelyn",1,"rev-test",a);
        ProfilePhrasePack.importPart(context,Uri.fromFile(wav("p2.wav",b.size())),"evelyn",2,"rev-test",b);
        assertEquals(new LinkedHashSet<>(Arrays.asList(1,2)),ProfilePhrasePack.importedParts(context,"evelyn"));
        assertEquals(6,ProfilePhrasePack.clipCount(context,"evelyn"));

        AtomicBoolean ok=new AtomicBoolean();
        v.importTrainingAudio(null,"Derek",(success,msg)->ok.set(success));
        assertTrue(ok.get());
        String derek=v.activeProfile().id;
        assertEquals(0,ProfilePhrasePack.clipCount(context,derek));
        List<String> c=Arrays.asList("Silvertracker update","Derek");
        ProfilePhrasePack.importPart(context,Uri.fromFile(wav("d1.wav",c.size())),derek,1,"derek-rev",c);
        assertEquals(2,ProfilePhrasePack.clipCount(context,derek));
        assertEquals(6,ProfilePhrasePack.clipCount(context,"evelyn"));
    }

    @Test public void replacingPartOneDoesNotDeletePartTwo() throws Exception {
        List<String> p1=Arrays.asList("One","Two"),p2=Arrays.asList("Three","Four");
        ProfilePhrasePack.importPart(context,Uri.fromFile(wav("a.wav",2)),"evelyn",1,"r1",p1);
        ProfilePhrasePack.importPart(context,Uri.fromFile(wav("b.wav",2)),"evelyn",2,"r1",p2);
        ProfilePhrasePack.importPart(context,Uri.fromFile(wav("c.wav",2)),"evelyn",1,"r1",Arrays.asList("One","Replacement"));
        Map<String,File> clips=ProfilePhrasePack.clips(context,"evelyn");
        assertTrue(clips.containsKey("three"));
        assertTrue(clips.containsKey("four"));
        assertTrue(clips.containsKey("replacement"));
    }
}
