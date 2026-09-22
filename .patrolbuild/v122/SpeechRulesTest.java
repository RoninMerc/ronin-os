package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class SpeechRulesTest {
    private SpeechRules.Rule r(String id,String a,String b,boolean phrase){return new SpeechRules.Rule(id,a,b,phrase,true);}
    private SpeechRules rules(SpeechRules.Rule... rs){return new SpeechRules(Arrays.asList(rs));}
    private Observation source(){return new Observation("999901","T.MURD","Impeccable","Parking Breach 3","now",1);}
    @Test public void exactRuleChangesSpeechOnly(){Observation o=source();String s=rules(r("a","Parking Breach 3","Third warning parking breach",false)).readout(o,Map.of("T.MURD","Tristan"));assertEquals("Guard Tristan recorded Third warning parking breach at Impeccable.",s);assertEquals("Parking Breach 3",o.issue);assertEquals("T.MURD",o.guard);assertEquals("Impeccable",o.property);}
    @Test public void extraSpacesAndCaseMatch(){assertEquals("third warning",rules(r("a","Parking Breach 3","third warning",false)).apply("  PARKING\u00a0  BREACH\n3  "));}
    @Test public void noRuleLeavesOriginalMeaning(){assertEquals(VoiceReadout.of(source()),rules().readout(source(),Map.of()));}
    @Test public void phraseLeavesRestOfAlert(){assertEquals("Third warning - Solo visitor car park",rules(r("a","Parking Breach 3","Third warning",true)).apply("Parking Breach 3 - Solo visitor car park"));}
    @Test public void phraseDoesNotMatchNumberPrefix(){SpeechRules p=rules(r("a","Parking Breach 3","Third warning",true));assertEquals("Parking Breach 30",p.apply("Parking Breach 30"));assertEquals("Parking Breach 31",p.apply("Parking Breach 31"));}
    @Test public void phraseDoesNotMatchInsideWord(){assertEquals("Carparking",rules(r("a","parking","bay",true)).apply("Carparking"));}
    @Test public void exactWinsOverPhrase(){assertEquals("exact",rules(r("a","Parking Breach 3","exact",false),r("b","Parking Breach","phrase",true)).apply("Parking Breach 3"));}
    @Test public void longestOverlapWins(){assertEquals("full 3",rules(r("a","Parking","short",true),r("b","Parking Breach","full",true)).apply("Parking Breach 3"));}
    @Test public void replacementsDoNotCascade(){assertEquals("B",rules(r("a","A","B",true),r("b","B","C",true)).apply("A"));}
    @Test public void multipleDisjointPhrasesApply(){assertEquals("Patrol at recreation centre",rules(r("a","Gen. patrol","Patrol",true),r("b","rec centre","recreation centre",true)).apply("Gen. patrol at rec centre"));}
    @Test public void replacementIsLiteral(){assertEquals("$5 (bay) \\ entry",rules(r("a","[B3]","$5 (bay) \\ entry",true)).apply("[B3]"));}
    @Test public void disabledRuleDoesNotApply(){assertEquals("Parking Breach 3",rules(new SpeechRules.Rule("a","Parking Breach 3","changed",false,false)).apply("Parking Breach 3"));}
    @Test public void nicknameIsNotParsedAsUsername(){assertEquals("Guard T.J. recorded Parking Breach 3 at Impeccable.",rules().readout(source(),Map.of("T.MURD","T.J.")));}
    @Test public void knownNicknameCaseMatchesGuard(){Observation o=new Observation("99","d.deo","Solo","Patrol","",0);assertEquals("Guard Dylan recorded Patrol at Solo.",rules().readout(o,Map.of("D.DEO","Dylan")));}
    @Test public void blankNicknameFallsBack(){assertEquals(VoiceReadout.of(source()),rules().readout(source(),Map.of("T.MURD"," ")));}
    @Test public void propertyPreservedWhenExactOverrideRemovesIt(){Observation o=new Observation("99","T.MURD","Solo","General Patrol - Solo","",0);assertEquals("Guard T dot Murd recorded Patrol scan at Solo.",rules(r("a",o.issue,"Patrol scan",false)).readout(o,Map.of()));}
    @Test public void propertyNotDuplicatedIfAlreadyInSpokenText(){Observation o=new Observation("99","T.MURD","Solo","Gen Patrol","",0);assertEquals("Guard T dot Murd recorded Patrol at Solo.",rules(r("a",o.issue,"Patrol at Solo",false)).readout(o,Map.of()));}
    @Test public void missingLocationStillExplicit(){Observation o=new Observation("99","T.MURD","","Patrol","",0);assertTrue(rules().readout(o,Map.of()).endsWith("Location not supplied."));}
}
