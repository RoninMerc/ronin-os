package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SpeechRulesTest {
    private Map<String,String> empty(){return Collections.emptyMap();}
    private Map<String,String> rule(String from,String to){Map<String,String> m=new LinkedHashMap<>();m.put(SpeechRules.key(from),to);return m;}
    private Map<String,String> names(){Map<String,String> n=new LinkedHashMap<>();n.put("T.MURD","Tristan");n.put("D.DEO","Dylan");n.put("D.ROGERS1","Dean");return n;}
    private Observation row(){return new Observation("55555","T.MURD","Impeccable BC","General Patrol - Impeccable BC","",1000);}
    @Test public void fullUserEditedAnnouncement(){String s=SpeechRules.format(row(),"Silvertracker update",names(),rule(row().issue,"Guard patrol Impeccable body corporate"),rule("Impeccable BC","Impeccable body corporate"),empty());assertEquals("Silvertracker update. Tristan. Guard patrol Impeccable body corporate.",s);}
    @Test public void requestedTristanNickname(){assertEquals("Tristan",SpeechRules.guard("T.MURD",names()));}
    @Test public void requestedDylanNickname(){assertEquals("Dylan",SpeechRules.guard("D.DEO",names()));}
    @Test public void requestedDeanNickname(){assertEquals("Dean",SpeechRules.guard("D.ROGERS1",names()));}
    @Test public void blankNicknameUsesUsername(){Map<String,String> n=names();n.put("T.MURD","");assertEquals(AnnouncementText.spokenGuard("T.MURD"),SpeechRules.guard("T.MURD",n));}
    @Test public void whitespaceNicknameUsesUsername(){Map<String,String> n=names();n.put("D.DEO","   ");assertEquals("D Deo",SpeechRules.guard("D.DEO",n));}
    @Test public void noNicknameDoesNotBorrowAnotherGuardsName(){assertEquals("A Smith",SpeechRules.guard("A.SMITH",names()));}
    @Test public void nicknameMatchingIgnoresCaseAndSpaces(){assertEquals("Tristan",SpeechRules.guard(" t.murd ",names()));}
    @Test public void differentGuardIDDoesNotBorrowDean(){assertEquals("D Rogers 2",SpeechRules.guard("D.ROGERS2",names()));}
    @Test public void caseWhitespaceAlertMatch(){assertEquals("Patrol at Impeccable",SpeechRules.exact(" GENERAL   PATROL ",rule("general patrol","Patrol at Impeccable")));}
    @Test public void fullAlertRuleDoesNotOvermatch(){assertEquals("Parking breach 30",SpeechRules.exact("Parking breach 30",rule("Parking breach 3","Third warning parking breach")));}
    @Test public void blankAlertMeansOriginal(){assertEquals("Parking Breach 3",SpeechRules.exact("Parking Breach 3",rule("parking breach 3","")));}
    @Test public void wholeAbbreviationExpands(){assertEquals("Impeccable body corporate",SpeechRules.phrases("Impeccable BC",rule("BC","body corporate")));}
    @Test public void plateCharactersAreNotSubstituted(){assertEquals("Plate ABC123 beside body corporate gate",SpeechRules.phrases("Plate ABC123 beside BC gate",rule("BC","body corporate")));}
    @Test public void longestPhraseWins(){Map<String,String> r=rule("BC","body corporate");r.put("impeccable bc","Impeccable");assertEquals("Impeccable and body corporate",SpeechRules.phrases("Impeccable BC and BC",r));}
    @Test public void replacementsDoNotCascade(){Map<String,String> r=rule("A","B");r.put("b","C");assertEquals("B C",SpeechRules.phrases("A B",r));}
    @Test public void specialCharactersAreLiteral(){assertEquals("Fee $5.00",SpeechRules.phrases("Fee [five]",rule("[five]","$5.00")));}
    @Test public void locationsStillSpokenWhenAlertIsShortened(){String s=SpeechRules.format(row(),"",names(),rule(row().issue,"Patrol completed"),empty(),empty());assertEquals("Tristan. Patrol completed. Impeccable BC.",s);}
    @Test public void ruleDoesNotMutateOriginalObservation(){Observation o=row();SpeechRules.format(o,"",names(),rule(o.issue,"New spoken words"),rule(o.property,"Impeccable"),empty());assertEquals("General Patrol - Impeccable BC",o.issue);assertEquals("Impeccable BC",o.property);assertEquals("T.MURD",o.guard);}
    @Test public void blankPrefixRemovesIntro(){assertFalse(SpeechRules.format(row(),"",names(),empty(),empty(),empty()).startsWith("Silvertracker"));}
    @Test public void emptySourceFieldsAreExplicit(){String s=SpeechRules.format(new Observation("1","","","","",0),"",empty(),empty(),empty(),empty());assertEquals("Guard not supplied. Activity description not supplied. Location not supplied.",s);}
    @Test public void speedIsBounded(){assertEquals(75,SpeechRules.speedPercent(0));assertEquals(150,SpeechRules.speedPercent(900));assertEquals(100,SpeechRules.speedPercent(100));}
    @Test public void fullMatterRetainsNumbersAndWarningUntilUserOverrides(){String issue="Parking breach 3, reg ABC123, visitor car park near number 17";String s=SpeechRules.format(new Observation("1","T.MURD","Solo",issue,"",0),"",names(),empty(),empty(),empty());assertTrue(s.contains(issue));assertTrue(s.endsWith("Solo."));}
    @Test public void phraseRulesExpandWithinAlertAndPlace(){String s=SpeechRules.format(row(),"",names(),empty(),empty(),rule("BC","body corporate"));assertEquals("Tristan. General Patrol - Impeccable body corporate.",s);}
}
