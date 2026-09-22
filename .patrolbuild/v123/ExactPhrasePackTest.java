package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExactPhrasePackTest {
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private static void writeSyntheticPack(File f,List<Double> tones)throws Exception{
        int rate=48000;int phraseMs=550;int pauseMs=3000;
        int frames=0;for(int i=0;i<tones.size();i++){frames+=rate*phraseMs/1000;if(i<tones.size()-1)frames+=rate*pauseMs/1000;}
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            d.writeBytes("RIFF");le32(d,36+frames*2);d.writeBytes("WAVEfmt ");le32(d,16);le16(d,1);le16(d,1);le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);d.writeBytes("data");le32(d,frames*2);
            for(int i=0;i<tones.size();i++){
                double hz=tones.get(i);int n=rate*phraseMs/1000;
                for(int x=0;x<n;x++)le16(d,(short)(Math.sin(2*Math.PI*hz*x/rate)*9000));
                if(i<tones.size()-1)for(int x=0;x<rate*pauseMs/1000;x++)le16(d,0);
            }
        }
    }
    @Test public void pauseThreeScriptSplitsExactlyAndPreservesRecordedClips()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile="exact-test-"+System.nanoTime();
        List<String> phrases=Arrays.asList("Silvertracker update","Tristan","Third warning parking breach","Impeccable");
        File source=new File(c.getCacheDir(),profile+".wav");
        writeSyntheticPack(source,Arrays.asList(210d,260d,310d,360d));
        ExactPhrasePack.ImportResult result=ExactPhrasePack.importPack(c,Uri.fromFile(source),profile,phrases);
        assertEquals(4,result.phrases);assertTrue(ExactPhrasePack.installed(c,profile));
        LinkedHashMap<String,File> clips=ExactPhrasePack.clips(c,profile);
        assertEquals(4,clips.size());
        for(String p:phrases){File clip=clips.get(ExactPhrasePack.normalize(p));assertNotNull(p,clip);assertTrue(clip.length()>10000);}
    }
    @Test public void missingPauseMarkersAreRejectedInsteadOfGuessing()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile="exact-bad-"+System.nanoTime();
        File source=new File(c.getCacheDir(),profile+".wav");
        writeSyntheticPack(source,Arrays.asList(220d,280d)); // two phrases only
        try{
            ExactPhrasePack.importPack(c,Uri.fromFile(source),profile,Arrays.asList("one","two","three"));
            fail("Importer guessed phrase alignment");
        }catch(IOException expected){assertTrue(expected.getMessage().contains("found 2 recorded phrases"));}
    }
}
