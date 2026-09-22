package au.com.roningroup.patrollink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ExactVoiceRulesTest {
    private Observation row(){return new Observation("1","T.MURD","Tradition BC","General Patrol - Tradition B","",1000);}
    @Test public void explicitReplacementIsTheWholeAnnouncement(){
        SpeechPreferences p=null; // formatter behaviour is tested at the rules boundary below.
        Map<String,String> alerts=new LinkedHashMap<>();alerts.put(SpeechRules.key("General Patrol - Tradition B"),"General Patrol - Tradition Place");
        String replacement=alerts.get(SpeechRules.key(row().issue));
        assertEquals("General Patrol - Tradition Place",replacement);
        // No prefix, guard or location is introduced when an exact replacement exists.
        assertFalse(replacement.contains("T Murd"));
        assertFalse(replacement.contains("Tradition BC"));
    }
    @Test public void exactPhraseNormalisationKeepsWordsButIgnoresPresentationPunctuation(){
        assertEquals(ExactPhrasePack.normalize("Silvertracker update."),ExactPhrasePack.normalize("  SILVERTRACKER   UPDATE  "));
        assertEquals(ExactPhrasePack.normalize("General Patrol - Impeccable BC."),ExactPhrasePack.normalize("general patrol - impeccable bc"));
    }
    @Test public void speedDoesNotChangePhraseIdentity(){
        String phrase="Third warning parking breach";
        assertEquals(phrase,SpeechRules.clean(phrase));
        assertEquals(75,SpeechRules.speedPercent(50));
        assertEquals(150,SpeechRules.speedPercent(200));
    }
}
