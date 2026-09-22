package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class VoiceReferenceTest {
    @Test public void roundTripAndPrepareWav()throws Exception{
        File source=File.createTempFile("voice-source",".wav"),dest=File.createTempFile("voice-ref",".wav");
        try{float[] data=new float[VoiceReference.RATE*8];for(int i=0;i<data.length;i++)data[i]=(float)(.1*Math.sin(i*2*Math.PI*220/VoiceReference.RATE));VoiceReference.write(source,data);float[] read=VoiceReference.read(source);assertEquals(data.length,read.length);assertEquals(data[100],read[100],.0001f);VoiceReference.prepare(source,dest);assertTrue(dest.length()>100000);assertEquals(64,VoiceReference.hash(dest).length());}finally{source.delete();dest.delete();}
    }
    @Test public void silentFileCannotMasqueradeAsReadyVoice()throws Exception{
        File source=File.createTempFile("voice-silent",".wav"),dest=File.createTempFile("voice-ref",".wav");
        try{VoiceReference.write(source,new float[VoiceReference.RATE*5]);try{VoiceReference.prepare(source,dest);fail("Silence was accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("speech"));}}finally{source.delete();dest.delete();}
    }
    @Test public void truncatedFileRejected()throws Exception{
        File source=File.createTempFile("voice-invalid",".wav");try{try(FileOutputStream out=new FileOutputStream(source)){out.write(new byte[]{1,2,3});}try{VoiceReference.read(source);fail("Corrupt WAV accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("WAV"));}}finally{source.delete();}
    }
    @Test public void longSourceIsBoundedToReferenceNotWholeTrainingPlayback()throws Exception{
        File source=File.createTempFile("voice-long",".wav"),dest=File.createTempFile("voice-short",".wav");try{float[] data=new float[VoiceReference.RATE*30];for(int i=0;i<data.length;i++)data[i]=(float)(.07*Math.sin(i*.03));VoiceReference.write(source,data);assertEquals(VoiceReference.RATE*25,VoiceReference.read(source).length);VoiceReference.prepare(source,dest);assertTrue(VoiceReference.read(dest).length<=VoiceReference.RATE*12);}finally{source.delete();dest.delete();}
    }
}
