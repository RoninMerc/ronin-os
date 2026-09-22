package au.com.roningroup.patrollink;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceReadoutTest {
    @Test public void parkingBreachIncludesMatterLocationAndGuard() {
        String s = VoiceManager.fullReadout("Third warning parking breach", "Impeccable", "D.ROGERS1");
        assertTrue(s.contains("Third warning parking breach"));
        assertTrue(s.contains("Impeccable"));
        assertTrue(s.contains("Guard D ROGERS1"));
    }

    @Test public void scanIncludesLocationAndGuard() {
        String s = VoiceManager.fullReadout("Patrol scan point", "Christina RBC", "T.MURD");
        assertTrue(s.contains("Patrol scan point"));
        assertTrue(s.contains("Christina RBC"));
        assertTrue(s.contains("Guard T MURD"));
    }

    @Test public void doesNotRepeatLocationAlreadyInMatter() {
        String s = VoiceManager.fullReadout("Parking breach at Solo", "Solo", "D.DEO");
        assertEquals(1, countIgnoreCase(s, "solo"));
    }

    @Test public void fallsBackWithoutLosingGuard() {
        String s = VoiceManager.fullReadout("", "Serenade", "D.ROGERS1");
        assertTrue(s.contains("Serenade"));
        assertTrue(s.contains("Guard D ROGERS1"));
    }

    private static int countIgnoreCase(String text, String needle) {
        String a=text.toLowerCase(), b=needle.toLowerCase(); int count=0, from=0, p;
        while((p=a.indexOf(b,from))>=0){count++;from=p+b.length();}
        return count;
    }
}
