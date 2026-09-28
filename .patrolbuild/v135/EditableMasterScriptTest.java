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
public class EditableMasterScriptTest {
    private Context context;
    private SharedPreferences prefs;
    private String profile;
    private final List<String> created=new ArrayList<>();
    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        profile="v135-"+UUID.randomUUID();created.add(profile);
        prefs=context.getSharedPreferences(profile,Context.MODE_PRIVATE);
        prefs.edit().putBoolean("voice_profiles_single_wav_v128",true)
            .putString("voice_profiles_v5","[{\"id\":\""+profile+"\",\"name\":\"Source\"}]")
            .putString("voice_profile_active",profile).commit();
    }
    @After public void cleanup() {
        for(String id:created)remove(new File(context.getFilesDir(),"exact_voice_packs/"+id));
        prefs.edit().clear().commit();
        File[] cache=context.getCacheDir().listFiles((d,n)->n.startsWith(profile));
        if(cache!=null)for(File f:cache)f.delete();
    }
    private static void remove(File f) {
        if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File child:list)remove(child);}
        f.delete();
    }
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private File wav(int phrases)throws Exception {
        File file=new File(context.getCacheDir(),profile+"-"+UUID.randomUUID()+".wav");
        int rate=16000,voiceFrames=rate*550/1000,pauseFrames=rate*3;
        int frames=phrases*voiceFrames+(phrases-1)*pauseFrames;
        byte[] voice=new byte[voiceFrames*2],silence=new byte[pauseFrames*2];
        for(int i=0;i<voiceFrames;i++){
            short v=(short)(Math.sin(2*Math.PI*230*i/rate)*10000);
            voice[2*i]=(byte)v;voice[2*i+1]=(byte)(v>>8);
        }
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){
            d.writeBytes("RIFF");le32(d,36+frames*2);d.writeBytes("WAVEfmt ");le32(d,16);
            le16(d,1);le16(d,1);le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);
            d.writeBytes("data");le32(d,frames*2);
            for(int i=0;i<phrases;i++){d.write(voice);if(i<phrases-1)d.write(silence);}
        }
        return file;
    }
    private List<String> list(int count){
        ArrayList<String> out=new ArrayList<>();for(int i=0;i<count;i++)out.add("Test phrase "+i);return out;
    }
    @Test public void exportedScriptHasRealNewlinesAndRoundTrips()throws Exception {
        List<String> words=Arrays.asList("Bobsled Lane","Rec Center 1 Gym","Daddy","Sweetheart");
        String script=VoiceScriptText.format(words);
        assertTrue(script.contains("[pause 3]\n\n"));
        assertFalse(script.contains("\\n"));
        assertEquals(words,VoiceScriptText.parse(script));
        assertEquals(words,VoiceScriptText.parse("\uFEFF"+script.replace("\n","\r\n")));
        assertEquals(words,VoiceScriptText.parse(String.join("\n",words)));
    }
    @Test public void duplicateOrWrongSeparatorIsRejectedRatherThanMisaligningAudio()throws Exception {
        for(String bad:Arrays.asList("Bobsled Lane\nBobsled Lane","Bobsled Lane\n[pause 2]\nBalmara Place")){
            try{VoiceScriptText.parse(bad);fail("Invalid manifest was accepted");}catch(IOException expected){}
        }
    }
    @Test public void full608PhraseRecordingImportsWithoutTheOld250PhraseLimit()throws Exception {
        List<String> phrases=list(608);
        ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(context,Uri.fromFile(wav(608)),profile,phrases);
        assertEquals(608,result.total);assertTrue(ExactPhrasePack.installed(context,profile));
        LinkedHashMap<String,File> clips=ExactPhrasePack.clips(context,profile);
        assertEquals(608,clips.size());assertTrue(clips.containsKey("test phrase 607"));
        assertEquals(phrases,ExactPhrasePack.phrases(context,profile));
    }
    @Test public void importedTextSurvivesCreatingNewVoiceAndRecreatingManager()throws Exception {
        ExactPhrasePack.importPack(context,Uri.fromFile(wav(3)),profile,Arrays.asList("Base one","Base two","Base three"));
        VoiceManager manager=new VoiceManager(context,prefs);
        List<String> phrases=list(608);
        assertEquals(608,manager.setCompleteScript(VoiceScriptText.format(phrases)));
        // Loading text must not replace current recorded audio.
        assertEquals(3,ExactPhrasePack.clips(context,profile).size());
        CountDownLatch createdVoice=new CountDownLatch(1);boolean[] ok={false};
        manager.importTrainingAudio(null,"New voice",(success,msg)->{ok[0]=success;createdVoice.countDown();});
        assertTrue(createdVoice.await(5,TimeUnit.SECONDS));assertTrue(ok[0]);
        created.add(manager.activeProfile().id);
        assertEquals(608,manager.completeTemplateCount());
        VoiceManager restored=new VoiceManager(context,prefs);
        assertEquals(phrases,restored.completeTemplatePhrases());
        assertEquals(phrases,VoiceScriptText.parse(restored.loadedScriptText()));
        assertEquals("Loaded text script",restored.completeTemplateSource());
        assertFalse(restored.ready());
    }
    @Test public void correctedNameWorksWithoutRemovingLegacyLibrarySupport()throws Exception {
        LinkedHashMap<String,File> clips=new LinkedHashMap<>();
        File modern=new File("modern.wav"),legacy=new File("legacy.wav");
        clips.put("general patrol - bobsled lane",modern);
        assertEquals(modern,ExactPhraseResolver.resolve(clips,"General patrol - Bobsled Lan").get(0));
        clips.put("general patrol - bobsled lan",legacy);
        assertEquals(legacy,ExactPhraseResolver.resolve(clips,"General patrol - Bobsled Lan").get(0));
    }
    @Test public void invalidWavDoesNotReplaceExistingLibrary()throws Exception {
        List<String> base=Arrays.asList("Base one","Base two","Base three");
        ExactPhrasePack.importPack(context,Uri.fromFile(wav(3)),profile,base);
        try{
            ExactPhrasePack.importPack(context,Uri.fromFile(wav(2)),profile,list(608));
            fail("Misaligned recording accepted");
        }catch(IOException expected){}
        assertEquals(base,ExactPhrasePack.phrases(context,profile));
    }
    @Test public void fullSinglePhraseLibraryIsAllowed()throws Exception {
        ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(context,Uri.fromFile(wav(1)),profile,Collections.singletonList("Daddy"));
        assertEquals(1,result.total);assertTrue(ExactPhrasePack.installed(context,profile));
    }
    @Test public void addOnBatchLimitStillApplies()throws Exception {
        try{
            ExactPhrasePack.importAdditions(context,Uri.fromFile(wav(1)),profile,list(251));
            fail("Add-on cap lost");
        }catch(IOException expected){assertTrue(expected.getMessage().contains("250"));}
    }
}
