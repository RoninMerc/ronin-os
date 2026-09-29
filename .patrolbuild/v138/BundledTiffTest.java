package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BundledTiffTest {
    private Context c;private SharedPreferences prefs;private String id,hash;private byte[] clip;
    private final Set<String> dirs=new HashSet<>();private final List<File> cache=new ArrayList<>();
    @Before public void setup()throws Exception{
        c=InstrumentationRegistry.getInstrumentation().getTargetContext();id="tiff-test-"+UUID.randomUUID();
        prefs=c.getSharedPreferences(id,0);dirs.add(id);hash=sha(id.getBytes(StandardCharsets.UTF_8));
        dirs.add("tiff-"+hash.substring(0,12));clip=wav();
        prefs.edit().putBoolean("voice_profiles_single_wav_v128",true)
            .putString("voice_profiles_v5","[{\"id\":\""+id+"\",\"name\":\"Evelyn\"}]")
            .putString("voice_profile_active",id).putBoolean("voice",false).commit();
    }
    @After public void cleanup(){
        for(String d:dirs)remove(new File(c.getFilesDir(),"exact_voice_packs/"+d));
        for(File f:cache)f.delete();prefs.edit().clear().commit();
    }
    private static void remove(File f){if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)remove(x);}f.delete();}
    private static String sha(byte[] b)throws Exception{StringBuilder s=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static void u16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void u32(DataOutputStream d,int v)throws IOException{u16(d,v);u16(d,v>>>16);}
    private static byte[] wav()throws IOException{
        int rate=16000,frames=8800;ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeBytes("RIFF");u32(d,36+frames*2);d.writeBytes("WAVEfmt ");u32(d,16);u16(d,1);u16(d,1);u32(d,rate);u32(d,rate*2);u16(d,2);u16(d,16);d.writeBytes("data");u32(d,frames*2);
        for(int i=0;i<frames;i++)u16(d,(short)(9000*Math.sin(2*Math.PI*230*i/rate)));d.close();return b.toByteArray();
    }
    private List<String> phrases(int n){List<String> list=new ArrayList<>();for(int i=0;i<n;i++)list.add("Phrase "+i);return list;}
    private void source(List<String> words)throws Exception{
        File dir=ExactPhrasePack.profileDir(c,id);
        for(int i=0;i<words.size();i++)Files.write(new File(dir,String.format(Locale.ROOT,"clip-%04d.wav",i)).toPath(),clip);
        JSONObject m=new JSONObject();m.put("version",2);m.put("phrases",new JSONArray(words));
        Files.write(new File(dir,"manifest.json").toPath(),m.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void saved(List<String> words){prefs.edit().putString("voice_complete_library_template_v1",new JSONArray(words).toString()).putString("voice_complete_library_template_source_v1","Evelyn").commit();}
    private File bundle(int n,boolean bad)throws Exception{
        JSONObject info=new JSONObject();info.put("version",1);info.put("name","Tiff");info.put("count",n);info.put("source_sha256",hash);
        JSONArray checks=new JSONArray();
        for(int i=0;i<n;i++){JSONObject k=new JSONObject();k.put("bytes",clip.length);k.put("sha256",bad&&i==n-1?"0".repeat(64):sha(clip));checks.put(k);}info.put("clips",checks);
        File out=new File(c.getCacheDir(),id+"-"+UUID.randomUUID()+".zip");cache.add(out);
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){
            z.putNextEntry(new ZipEntry("bundle.json"));z.write(info.toString().getBytes(StandardCharsets.UTF_8));z.closeEntry();
            for(int i=0;i<n;i++){z.putNextEntry(new ZipEntry(String.format(Locale.ROOT,"clip-%04d.wav",i)));z.write(clip);z.closeEntry();}
        }
        return out;
    }
    private BundledTiff.Result install(File b)throws Exception{return BundledTiff.install(c,prefs,new FileInputStream(b),null);}

    @Test public void all475ClipsRegisterAsTiffWithoutChangingEvelynOrCheckpointPrefs()throws Exception{
        List<String> words=phrases(475);source(words);saved(words);
        byte[] original=Files.readAllBytes(new File(ExactPhrasePack.profileDir(c,id),"manifest.json").toPath());
        Map<String,?> tally=new HashMap<>(c.getSharedPreferences(CheckpointTracker.FILE,0).getAll());
        VoiceManager v=new VoiceManager(c,prefs);CountDownLatch done=new CountDownLatch(1);AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> message=new AtomicReference<>();
        v.prepareTiffFixture(bundle(475,false),(success,text)->{ok.set(success);message.set(text);done.countDown();});
        assertTrue("Installation completed",done.await(40,TimeUnit.SECONDS));assertTrue(message.get(),ok.get());
        assertEquals("Evelyn",v.activeName());assertEquals(2,v.profiles().size());assertTrue(v.tiffReady());
        String tiff=BundledTiff.readyId(c,prefs);dirs.add(tiff);
        assertEquals(words,ExactPhrasePack.phrases(c,tiff));assertEquals(475,ExactPhrasePack.clips(c,tiff).size());
        assertArrayEquals(clip,Files.readAllBytes(ExactPhrasePack.clips(c,tiff).get("phrase 474").toPath()));
        assertArrayEquals(original,Files.readAllBytes(new File(ExactPhrasePack.profileDir(c,id),"manifest.json").toPath()));
        assertEquals(tally,c.getSharedPreferences(CheckpointTracker.FILE,0).getAll());
        v.selectTiff();assertEquals("Tiff",v.activeName());assertEquals("",v.recordingIssue("Phrase 474"));
        assertEquals(words,VoiceScriptText.parse(v.completeLibraryScript()));
        VoiceManager restarted=new VoiceManager(c,prefs);assertTrue(restarted.tiffReady());assertEquals("Tiff",restarted.activeName());
    }
    @Test public void absentMatchingScriptIsRejectedWithoutGuessingFromCanonicalBase()throws Exception{
        source(phrases(3));saved(phrases(2));String profiles=prefs.getString("voice_profiles_v5","");
        try{install(bundle(4,false));fail("Wrong script must not be paired by position");}catch(IOException expected){assertTrue(expected.getMessage().contains("saved script"));}
        assertEquals(profiles,prefs.getString("voice_profiles_v5",""));assertFalse(prefs.getBoolean(BundledTiff.DONE_PREF,false));
        assertEquals(phrases(3),ExactPhrasePack.phrases(c,id));
    }
    @Test public void damagedClipRejectsTheWholeBundleAndKeepsExistingAudio()throws Exception{
        source(phrases(3));saved(phrases(3));
        try{install(bundle(3,true));fail("Checksum mismatch accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("integrity"));}
        assertEquals(phrases(3),ExactPhrasePack.phrases(c,id));assertTrue(BundledTiff.readyId(c,prefs).isEmpty());
        File root=new File(c.getFilesDir(),"exact_voice_packs");
        for(File child:Objects.requireNonNull(root.listFiles()))assertFalse(child.getName().startsWith("tiff-stage-"));
    }
    @Test public void savedOrderedCopyWinsOverTheSameSizedCurrentLibrary()throws Exception{
        source(Arrays.asList("Old one","Old two","Old three"));
        List<String> copied=Arrays.asList("Updated first","Updated second","Updated third");saved(copied);
        BundledTiff.Result r=install(bundle(3,false));dirs.add(r.id);
        assertEquals(copied,ExactPhrasePack.phrases(c,r.id));
    }
    @Test public void failedEmptyTiffProfileIsReusedRatherThanDuplicated()throws Exception{
        source(phrases(3));saved(phrases(3));String empty="empty-tiff-"+UUID.randomUUID();dirs.add(empty);
        JSONArray profiles=new JSONArray(prefs.getString("voice_profiles_v5","[]"));JSONObject p=new JSONObject();p.put("id",empty);p.put("name","Tiff");profiles.put(p);
        prefs.edit().putString("voice_profiles_v5",profiles.toString()).commit();
        ExactPhrasePack.profileDir(c,empty);
        BundledTiff.Result r=install(bundle(3,false));assertEquals(empty,r.id);BundledTiff.register(prefs,r);
        assertEquals(2,new JSONArray(prefs.getString("voice_profiles_v5","[]")).length());
    }
    @Test public void retryAndRestartPreserveLaterAdditionsToTiff()throws Exception{
        source(phrases(3));saved(phrases(3));File b=bundle(3,false);
        BundledTiff.Result r=install(b);dirs.add(r.id);BundledTiff.register(prefs,r);
        File extra=new File(c.getCacheDir(),id+"-extra.wav");cache.add(extra);Files.write(extra.toPath(),clip);
        ExactPhrasePack.importAdditions(c,android.net.Uri.fromFile(extra),r.id,Collections.singletonList("Extra checkpoint"));
        BundledTiff.Result again=install(b);BundledTiff.register(prefs,again);
        assertEquals(4,again.count);assertEquals(4,ExactPhrasePack.phrases(c,r.id).size());
        assertTrue(ExactPhrasePack.clips(c,r.id).containsKey("extra checkpoint"));
        assertEquals(2,new JSONArray(prefs.getString("voice_profiles_v5","[]")).length());
    }
    @Test public void registrationCanRecoverAfterInstallCompletedBeforeProcessStopped()throws Exception{
        source(phrases(3));saved(phrases(3));File b=bundle(3,false);
        BundledTiff.Result r=install(b);dirs.add(r.id);
        assertFalse(prefs.getBoolean(BundledTiff.DONE_PREF,false));
        BundledTiff.Result recovered=install(b);assertEquals(r.id,recovered.id);BundledTiff.register(prefs,recovered);
        assertEquals(r.id,BundledTiff.readyId(c,prefs));
    }
}
