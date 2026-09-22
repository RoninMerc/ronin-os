package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class ExactSpeechTest {
    private SpeechRules rules(boolean phrase,boolean enabled){return new SpeechRules(Arrays.asList(new SpeechRules.Rule("a","General Patrol - Tradition B","General Patrol - Tradition Place",phrase,enabled)));}
    private Observation row(){return new Observation("test","D.ROGERS1","Tradition BC","General Patrol - Tradition B","",0);}
    @Test public void screenshotEnabledReplacementIsWholeAnnouncement(){assertEquals("General Patrol - Tradition Place",rules(false,true).readout(row(),Collections.singletonMap("d.rogers1","Dean")));}
    @Test public void screenshotPhraseToggleDoesNotAddGuardOrLocation(){assertEquals("General Patrol - Tradition Place",rules(true,true).readout(row(),Collections.emptyMap()));}
    @Test public void longerMatchStillSaysOnlyReplacement(){Observation o=new Observation("t","T.MURD","Extra property","General Patrol - Tradition B - more details","",0);assertEquals("General Patrol - Tradition Place",rules(true,true).readout(o,Collections.emptyMap()));}
    @Test public void noAutomaticPrefixOrSuffixOrPunctuation(){SpeechRules r=new SpeechRules(Arrays.asList(new SpeechRules.Rule("a",row().issue,"Just my words",false,true)));assertEquals("Just my words",r.readout(row(),Collections.emptyMap()));}
    @Test public void disabledRuleDoesNotOverrideOriginal(){assertNotEquals("General Patrol - Tradition Place",rules(false,false).readout(row(),Collections.emptyMap()));}
    @Test public void caseAndSpacingStillMatch(){assertEquals("General Patrol - Tradition Place",rules(false,true).apply(" GENERAL   PATROL - Tradition B "));}
    @Test public void phraseBoundaryStillProtectsWarningNumbers(){SpeechRules r=new SpeechRules(Arrays.asList(new SpeechRules.Rule("a","Breach 3","Third warning",true,true)));assertEquals("Breach 30",r.apply("Breach 30"));assertEquals("Third warning",r.apply("Parking Breach 3 at Solo"));}
    @Test public void longestSpecificPhraseWins(){SpeechRules r=new SpeechRules(Arrays.asList(new SpeechRules.Rule("a","Patrol","Other",true,true),new SpeechRules.Rule("b","General Patrol","Only this",true,true)));assertEquals("Only this",r.apply("General Patrol - Example"));}
    @Test public void originalObservationNotModified(){Observation o=row();rules(true,true).readout(o,Collections.emptyMap());assertEquals("D.ROGERS1",o.guard);assertEquals("Tradition BC",o.property);assertEquals("General Patrol - Tradition B",o.issue);}
    static File wav(int rate,int channels,int seconds)throws Exception{
        File file=File.createTempFile("exact-test",".wav");try(DataOutputStream out=new DataOutputStream(new FileOutputStream(file))){out.writeBytes("RIFF");ExactWav.le32(out,36+rate*channels*seconds*2);out.writeBytes("WAVEfmt ");ExactWav.le32(out,16);ExactWav.le16(out,1);ExactWav.le16(out,channels);ExactWav.le32(out,rate);ExactWav.le32(out,rate*channels*2);ExactWav.le16(out,channels*2);ExactWav.le16(out,16);out.writeBytes("data");ExactWav.le32(out,rate*channels*seconds*2);for(int i=0;i<rate*seconds;i++)for(int c=0;c<channels;c++)ExactWav.le16(out,(short)(1000*Math.sin(i*.2+c)));}return file;
    }
    @Test public void cutPreservesPcmBytesAndSampleRate()throws Exception{File f=wav(48000,2,3),dest=File.createTempFile("cut-test",".wav");try{ExactWav w=ExactWav.open(f);w.cut(dest,.5,1.25);ExactWav d=ExactWav.open(dest);assertEquals(48000,d.rate);assertEquals(2,d.channels);assertEquals(.75,d.seconds(),.0001);byte[] expected=new byte[(int)d.bytes],actual=new byte[(int)d.bytes];try(RandomAccessFile a=new RandomAccessFile(f,"r");RandomAccessFile b=new RandomAccessFile(dest,"r")){a.seek(w.data+24000L*4);a.readFully(expected);b.seek(d.data);b.readFully(actual);}assertArrayEquals(expected,actual);}finally{f.delete();dest.delete();}}
    @Test public void outOfRangeCutRejected()throws Exception{File f=wav(24000,1,1),d=File.createTempFile("invalid-cut",".wav");try{try{ExactWav.open(f).cut(d,0,5);fail();}catch(IOException expected){}}finally{f.delete();d.delete();}}
    @Test public void invalidFileRejected()throws Exception{File f=File.createTempFile("invalid-wave",".wav");try{try{ExactWav.open(f);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("WAV"));}}finally{f.delete();}}
    @Test public void fullCutDoesNotNormalizeVolume()throws Exception{File f=wav(22050,1,1),d=File.createTempFile("full-cut",".wav");try{ExactWav.open(f).cut(d,0,1);assertArrayEquals(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.file.Files.readAllBytes(d.toPath()));}finally{f.delete();d.delete();}}
}
