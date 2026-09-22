package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class AnnouncementTextTest {
    private Observation row(String id,String guard,String location,String issue,long t){return new Observation(id,guard,location,issue,"",t);}
    @Test public void allFieldsAreActuallyRead(){assertEquals("T Murd. Third warning parking breach. Impeccable.",AnnouncementText.format(row("10001","T.MURD","Impeccable","Third warning parking breach",100)));}
    @Test public void patrolIncludesGuardAndLocation(){String s=AnnouncementText.format(row("10002","D.ROGERS1","Christina RBC","General Patrol - Christina",100));assertTrue(s.contains("D Rogers 1"));assertTrue(s.contains("General Patrol - Christina"));assertTrue(s.contains("Christina RBC"));}
    @Test public void warningNumberIsNotReducedToGenericAlert(){String s=AnnouncementText.format(row("10003","D.DEO","Solo","Parking breach 3",100));assertTrue(s.contains("Parking breach 3"));assertTrue(s.contains("Solo"));assertFalse(s.contains("update available"));assertFalse(s.contains("Connection interrupted"));}
    @Test public void exactRepeatedLocationIsNotReadTwice(){assertEquals("D Deo. Parking breach at Solo.",AnnouncementText.format(row("10004","D.DEO","Solo","Parking breach at Solo",100)));}
    @Test public void substringDoesNotHideLocation(){assertEquals("D Deo. Report at Solomon. Solo.",AnnouncementText.format(row("10005","D.DEO","Solo","Report at Solomon",100)));}
    @Test public void missingLocationIsExplicit(){assertTrue(AnnouncementText.format(row("10006","T.MURD","","Parking breach",100)).contains("Location not supplied"));}
    @Test public void noMatterTextIsTruncated(){String issue="Visitor parking, third warning, grey Toyota ABC123, eastern entrance beside number 31; calling card left.";assertTrue(AnnouncementText.format(row("10007","T.MURD","Serenade",issue,100)).contains(issue));}
    @Test public void initialRowsAreQuiet(){AnnouncementLedger l=new AnnouncementLedger();assertTrue(l.collect(Arrays.asList(row("10001","T.MURD","Solo","Patrol",100000)),false,100010).isEmpty());}
    @Test public void unchangedRowsDoNotRepeat(){AnnouncementLedger l=new AnnouncementLedger();List<Observation> rows=Arrays.asList(row("10001","T.MURD","Solo","Patrol",100000));l.collect(rows,false,100010);assertTrue(l.collect(rows,true,120000).isEmpty());}
    @Test public void correctedLocationIsReadOnce(){AnnouncementLedger l=new AnnouncementLedger();l.collect(Arrays.asList(row("10001","T.MURD","Solo","Parking breach 3",100000)),false,100010);List<Observation> changed=Arrays.asList(row("10001","T.MURD","Impeccable","Parking breach 3",100000));assertEquals(1,l.collect(changed,true,120000).size());assertTrue(l.collect(changed,true,130000).isEmpty());}
    @Test public void allThreeSelectedGuardsGetTheirOwnUpdates(){AnnouncementLedger l=new AnnouncementLedger();l.collect(Collections.emptyList(),false,100000);List<Observation> rows=Arrays.asList(row("10001","T.MURD","Solo","Scan point",100100),row("10002","D.DEO","Impeccable","Parking breach 3",100200),row("10003","D.ROGERS1","Serenade","Recreation centre locked",100300));List<Observation> out=l.collect(rows,true,100400);assertEquals(3,out.size());assertEquals("10001",out.get(0).id);}
    @Test public void oldRowsDoNotBecomeCurrentSpokenLocations(){AnnouncementLedger l=new AnnouncementLedger();l.collect(Collections.emptyList(),false,1000000);assertTrue(l.collect(Arrays.asList(row("10001","T.MURD","Solo","Patrol",100000)),true,1000010).isEmpty());}
}
