package au.com.roningroup.patrollink;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdditivePhrasePackTest {
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}

    private static void writeSyntheticPack(File f,List<Double> tones)throws Exception{
        int rate=48000,phraseMs=550,pauseMs=3000;
        int frames=0;
        for(int i=0;i<tones.size();i++){
            frames+=rate*phraseMs/1000;
            if(i<tones.size()-1)frames+=rate*pauseMs/1000;
        }
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            d.writeBytes("RIFF");le32(d,36+frames*2);d.writeBytes("WAVEfmt ");le32(d,16);
            le16(d,1);le16(d,1);le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);
            d.writeBytes("data");le32(d,frames*2);
            for(int i=0;i<tones.size();i++){
                double hz=tones.get(i);int n=rate*phraseMs/1000;
                for(int x=0;x<n;x++)le16(d,(short)(Math.sin(2*Math.PI*hz*x/rate)*9000));
                if(i<tones.size()-1)for(int x=0;x<rate*pauseMs/1000;x++)le16(d,0);
            }
        }
    }

    @Test public void smallAddOnsAccumulateWithoutReplacingBaseLibrary()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile="addon-test-"+System.nanoTime();

        File base=new File(c.getCacheDir(),profile+"-base.wav");
        writeSyntheticPack(base,Arrays.asList(200d,250d,300d));
        ExactPhrasePack.importPack(c,Uri.fromFile(base),profile,
                Arrays.asList("Silvertracker update","General Patrol","Guard Patrol"));

        LinkedHashMap<String,File> before=ExactPhrasePack.clips(c,profile);
        byte[] generalBefore=Files.readAllBytes(before.get(ExactPhrasePack.normalize("General Patrol")).toPath());

        File add1=new File(c.getCacheDir(),profile+"-add1.wav");
        writeSyntheticPack(add1,Arrays.asList(350d,400d));
        ExactPhrasePack.ImportResult r1=ExactPhrasePack.importAdditions(c,Uri.fromFile(add1),profile,
                Arrays.asList("Bobsled Lane","Tradition Place"));

        assertEquals(2,r1.added);
        assertEquals(0,r1.replaced);
        assertEquals(5,r1.total);

        LinkedHashMap<String,File> after1=ExactPhrasePack.clips(c,profile);
        assertNotNull(after1.get(ExactPhrasePack.normalize("Bobsled Lane")));
        assertNotNull(after1.get(ExactPhrasePack.normalize("Tradition Place")));
        assertArrayEquals(generalBefore,Files.readAllBytes(after1.get(ExactPhrasePack.normalize("General Patrol")).toPath()));

        File add2=new File(c.getCacheDir(),profile+"-add2.wav");
        writeSyntheticPack(add2,Collections.singletonList(475d));
        ExactPhrasePack.ImportResult r2=ExactPhrasePack.importAdditions(c,Uri.fromFile(add2),profile,
                Collections.singletonList("Bobsled Lane"));

        assertEquals(0,r2.added);
        assertEquals(1,r2.replaced);
        assertEquals(5,r2.total);
        assertEquals(5,ExactPhrasePack.clips(c,profile).size());
    }

    @Test public void singlePhraseAddOnIsAllowed()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile="addon-single-"+System.nanoTime();
        File one=new File(c.getCacheDir(),profile+".wav");
        writeSyntheticPack(one,Collections.singletonList(320d));

        ExactPhrasePack.ImportResult r=ExactPhrasePack.importAdditions(c,Uri.fromFile(one),profile,
                Collections.singletonList("Balmara Place"));
        assertEquals(1,r.added);
        assertEquals(1,r.total);
        assertTrue(ExactPhrasePack.installed(c,profile));
    }
}
