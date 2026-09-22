package au.com.roningroup.patrollink;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceReadoutTest {
    @Test public void parkingBreachReadsUpdateGuardMatterLocationInOrder() {
        String s=VoiceManager.fullReadout("Third warning parking breach","Impeccable","T.MURD");
        int u=s.indexOf("Update available");
        int g=s.indexOf("T dot Murd");
        int m=s.indexOf("Third warning parking breach");
        int l=s.indexOf("Impeccable");
        assertTrue(u>=0 && g>u && m>g && l>m);
    }

    @Test public void scanIncludesExactLocation() {
        String s=VoiceManager.fullReadout("Patrol scan point","Christina RBC","D.ROGERS1");
        assertTrue(s.contains("D dot Rogers dot 1"));
        assertTrue(s.contains("Patrol scan point"));
        assertTrue(s.contains("Christina RBC"));
    }

    @Test public void doesNotRepeatLocationAlreadyInsideMatter() {
        String s=VoiceManager.fullReadout("Parking breach at Solo","Solo","D.DEO");
        assertEquals(1,countIgnoreCase(s,"solo"));
    }

    @Test public void guardDotsAreActuallySpokenAsDots() {
        assertEquals("T dot Murd",VoiceManager.spokenGuard("T.MURD"));
        assertEquals("D dot Rogers dot 1",VoiceManager.spokenGuard("D.ROGERS1"));
    }

    private static int countIgnoreCase(String text,String needle) {
        String a=text.toLowerCase(),b=needle.toLowerCase(); int count=0,from=0,p;
        while((p=a.indexOf(b,from))>=0){count++;from=p+b.length();}
        return count;
    }
}
