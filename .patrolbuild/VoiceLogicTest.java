package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class VoiceLogicTest {
 @Test public void breachIncludesGuardMatterAndLocation(){String s=VoiceReadout.format("T.MURD","Third warning parking breach","Impeccable");assertEquals("T MURD. Third warning parking breach. Impeccable.",s);}
 @Test public void scanKeepsSite(){assertEquals("D DEO. General Patrol - Serenade BC. Serenade.",VoiceReadout.format("D.DEO","General Patrol - Serenade BC","Serenade."));}
 @Test public void doesNotInventWarningLevel(){String s=VoiceReadout.format("D.ROGERS1","Parking Breach 3","Solo");assertTrue(s.contains("Parking Breach 3"));assertFalse(s.contains("third warning"));assertTrue(s.endsWith("Solo."));}
 @Test public void locationAlreadyPresentOnce(){assertEquals("D DEO. General Patrol - Serenade BC.",VoiceReadout.format("D.DEO","General Patrol - Serenade BC","Serenade BC"));}
 @Test public void substringCannotHideDifferentSite(){assertTrue(VoiceReadout.format("T.MURD","Patrol at Solomon","Solo").endsWith("Solo."));}
 @Test public void missingDataNotGuessed(){assertEquals("Guard not supplied. Activity details not supplied. Location not supplied.",VoiceReadout.format("","",""));}
 @Test public void longMatterIsNotTruncated(){String s="Detailed issue "+"additional details ".repeat(120);String out=VoiceReadout.format("T.MURD",s,"Western Bay");assertTrue(out.contains(s.trim()));assertTrue(out.endsWith("Western Bay."));}
 @Test public void activityNotReplacedByConnectionAlert(){String s=VoiceReadout.format("T.MURD","Parking breach","Impeccable");assertFalse(s.toLowerCase().contains("connection"));assertFalse(s.toLowerCase().contains("update available"));}
 @Test public void wavRoundTripAndBoundedReference()throws Exception{File f=File.createTempFile("voice",".wav");try{float[] a=new float[24000*15];for(int i=0;i<a.length;i++)a[i]=(float)(0.15*Math.sin(2*Math.PI*220*i/24000));VoiceAudio.write(f,a,24000);float[] b=VoiceAudio.reference(f);assertTrue(b.length>=24000*11);assertTrue(b.length<=24000*12);}finally{f.delete();}}
 @Test public void silentReferenceRejected()throws Exception{File f=File.createTempFile("voice",".wav");try{VoiceAudio.write(f,new float[24000*5],24000);try{VoiceAudio.reference(f);fail("Silent reference accepted");}catch(IOException expected){}}finally{f.delete();}}
 @Test public void corruptReferenceRejected()throws Exception{File f=File.createTempFile("voice",".wav");try{try(FileOutputStream out=new FileOutputStream(f)){out.write(new byte[128]);}try{VoiceAudio.reference(f);fail("Corrupt reference accepted");}catch(IOException expected){}}finally{f.delete();}}
}
