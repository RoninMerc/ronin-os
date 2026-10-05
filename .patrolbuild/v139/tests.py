from pathlib import Path
import sys
root=Path(sys.argv[1]);unit=root/'app/src/test/java/au/com/roningroup/patrollink';android=root/'app/src/androidTest/java/au/com/roningroup/patrollink'
(unit/'GpsFixPolicyTest.java').write_text('''package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
public final class GpsFixPolicyTest {
    @Test public void realRecentFixAccepted(){assertTrue(GpsFixPolicy.valid(-27.0,153.0,5f,200000,215000));}
    @Test public void zeroCoordinatesAreValid(){assertTrue(GpsFixPolicy.valid(0,0,10f,200000,200000));}
    @Test public void staleAndFutureRejected(){assertFalse(GpsFixPolicy.valid(0,0,5f,100000,221000));assertFalse(GpsFixPolicy.valid(0,0,5f,225000,220000));}
    @Test public void invalidCoordinatesRejected(){assertFalse(GpsFixPolicy.valid(Double.NaN,1,5f,200000,200000));assertFalse(GpsFixPolicy.valid(91,1,5f,200000,200000));assertFalse(GpsFixPolicy.valid(1,-181,5f,200000,200000));}
    @Test public void unknownAccuracyRejected(){assertFalse(GpsFixPolicy.valid(1,1,Float.NaN,200000,200000));assertFalse(GpsFixPolicy.valid(1,1,-1,200000,200000));}
    @Test public void disabledGuardsAreNotSelected(){
        java.util.List<Observation> rows=java.util.Arrays.asList(new Observation("12345","N/A","Test","General Patrol","",123),new Observation("12346","D.DEO","Test","General Patrol","",124));
        assertEquals(1,FeedReducer.selected(rows,java.util.Arrays.asList("N/A","N/A","D.DEO")).size());
    }
}
''')
(android/'Patrol139Test.java').write_text(r'''package au.com.roningroup.patrollink;
import android.app.Activity;
import android.content.*;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public final class Patrol139Test {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private static void le16(DataOutputStream d,int v)throws IOException{d.writeByte(v);d.writeByte(v>>>8);}
    private static void le32(DataOutputStream d,int v)throws IOException{le16(d,v);le16(d,v>>>16);}
    private static void wave(File f,int count)throws Exception{
        int rate=16000;byte[] tone=new byte[rate*55/100*2],pause=new byte[rate*2*2];
        for(int i=0;i<tone.length/2;i++){short sample=(short)(Math.sin(2*Math.PI*350*i/rate)*9000);tone[i*2]=(byte)sample;tone[i*2+1]=(byte)(sample>>>8);}
        int bytes=count*tone.length+(count-1)*pause.length;
        try(DataOutputStream d=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){
            d.writeBytes("RIFF");le32(d,36+bytes);d.writeBytes("WAVEfmt ");le32(d,16);le16(d,1);le16(d,1);le32(d,rate);le32(d,rate*2);le16(d,2);le16(d,16);d.writeBytes("data");le32(d,bytes);
            for(int i=0;i<count;i++){d.write(tone);if(i<count-1)d.write(pause);}
        }
    }
    @Test public void editorAcceptsFiveHundredAndRejectsFiveHundredOne(){
        Context c=context();VoiceManager manager=new VoiceManager(c,c.getSharedPreferences("v139-parser-test",0));
        StringBuilder text=new StringBuilder();for(int i=0;i<500;i++)text.append("Additional phrase ").append(i).append('\n');
        assertEquals(500,manager.parseAddOnPhrases(text.toString()).size());
        try{manager.parseAddOnPhrases(text+"one extra phrase");fail("501 must be rejected with a clear limit");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("500"));}
    }
    @Test public void actualFiveHundredPhraseWavMergesWithoutReplacingExistingAudio()throws Exception{
        Context c=context();String id="v139-500-"+System.nanoTime();File original=new File(c.getCacheDir(),id+"-base.wav"),extra=new File(c.getCacheDir(),id+"-500.wav");
        try{
            wave(original,1);ExactPhrasePack.importPack(c,Uri.fromFile(original),id,Collections.singletonList("Retain this recording"));
            File retained=ExactPhrasePack.clips(c,id).get("retain this recording");byte[] before=Files.readAllBytes(retained.toPath());
            List<String> phrases=new ArrayList<>();for(int i=0;i<500;i++)phrases.add("Additional phrase "+i);
            wave(extra,500);ExactPhrasePack.ImportResult result=ExactPhrasePack.importAdditions(c,Uri.fromFile(extra),id,phrases);
            assertEquals(500,result.added);assertEquals(0,result.replaced);assertEquals(501,result.total);
            LinkedHashMap<String,File> clips=ExactPhrasePack.clips(c,id);assertEquals(501,clips.size());
            assertArrayEquals(before,Files.readAllBytes(clips.get("retain this recording").toPath()));
            assertTrue(clips.containsKey("additional phrase 499"));
        }finally{original.delete();extra.delete();}
    }
    @Test public void guardsLearnUnselectedCreatorsAndAllowMultipleUnusedSlots()throws Exception{
        SharedPreferences prefs=context().getSharedPreferences("v139-guard-test",0);prefs.edit().clear().commit();
        GuardDirectory.remember(prefs,new JSONArray("[{\"guard\":\"D.DEO\"},{\"guard\":\"New.Guard\"},{\"guard\":\"new.guard\"}]"));
        List<String> choices=GuardDirectory.options(prefs,Arrays.asList("T.MURD","N/A","N/A"));
        assertEquals(Arrays.asList("N/A","D.DEO","NEW.GUARD","T.MURD"),choices);
        GuardDirectory.validate(Arrays.asList("D.DEO","N/A","N/A"));GuardDirectory.validate(Arrays.asList("N/A","N/A","N/A"));
        try{GuardDirectory.validate(Arrays.asList("D.DEO","d.deo","N/A"));fail("Duplicate active guard");}catch(IllegalArgumentException expected){}
    }
    @Test public void gpsFixesRetainOriginalTimeRejectStaleAndDoNotTouchPatrolSettings(){
        Context c=context();SharedPreferences gp=LocalGpsService.prefs(c),patrol=c.getSharedPreferences("patrol_settings",0);
        String before=patrol.getAll().toString();gp.edit().remove("fix_time").remove("fix_elapsed").remove("boot").commit();
        long now=SystemClock.elapsedRealtime();Location fresh=new Location("gps");fresh.setLatitude(12.345);fresh.setLongitude(45.678);fresh.setAccuracy(6f);fresh.setTime(System.currentTimeMillis());fresh.setElapsedRealtimeNanos((now-1000)*1000000L);
        assertTrue(LocalGpsService.saveFix(c,fresh));long recorded=gp.getLong("fix_time",0);
        Location stale=new Location(fresh);stale.setElapsedRealtimeNanos((now-121000)*1000000L);assertFalse(LocalGpsService.saveFix(c,stale));
        Location invalid=new Location(fresh);invalid.setLatitude(Double.NaN);assertFalse(LocalGpsService.saveFix(c,invalid));
        assertEquals(recorded,gp.getLong("fix_time",0));assertEquals(before,patrol.getAll().toString());
        gp.edit().putBoolean("enabled",false).commit();assertTrue(new LocalGpsUi(new Activity()).getClass()!=null);
    }
    @Test public void foregroundGpsStartsStopsAndOffersVisibleUiWithoutAServer()throws Exception{
        Context c=context();assertTrue("Workflow grants location for this test",LocalGpsService.hasPermission(c));
        android.app.Instrumentation ins=InstrumentationRegistry.getInstrumentation();
        Activity a=ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            ins.runOnMainSync(()->assertTrue(LocalGpsService.start(a)));
            for(int i=0;i<40&&!LocalGpsService.active;i++)Thread.sleep(100);
            assertTrue(LocalGpsService.active);
            ins.runOnMainSync(()->new LocalGpsUi(a).show());ins.waitForIdleSync();Thread.sleep(500);
            Bitmap shot=ins.getUiAutomation().takeScreenshot();if(shot!=null)try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"patrol139-gps.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}
            ins.runOnMainSync(()->a.moveTaskToBack(true));Thread.sleep(1200);assertTrue("Service survives activity backgrounding",LocalGpsService.active);
            ins.runOnMainSync(()->LocalGpsService.stop(a));for(int i=0;i<40&&LocalGpsService.active;i++)Thread.sleep(100);
            assertFalse(LocalGpsService.active);assertFalse(LocalGpsService.prefs(c).getBoolean("enabled",true));
        }finally{ins.runOnMainSync(()->{LocalGpsService.stop(a);a.finish();});}
    }
}
''')
print('Added 6 JVM tests and 5 Android update checks, including a real 500-phrase WAV merge.')
