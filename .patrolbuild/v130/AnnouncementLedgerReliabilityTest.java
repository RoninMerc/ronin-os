package au.com.roningroup.patrollink;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AnnouncementLedgerReliabilityTest {
    private Observation row(String id,String issue,long when){
        return new Observation(id,"D.DEO","Solo BC",issue,"",when);
    }

    @Test public void delayedNewAlertIsNotDiscardedJustBecauseItIsOlderThanFiveMinutes(){
        AnnouncementLedger ledger=new AnnouncementLedger();
        long now=1_000_000_000L;
        long previousRead=now-20L*60L*1000L;

        assertTrue(ledger.collect(Collections.singletonList(
                row("old","General Patrol - Solo",previousRead-1000)),false,0,previousRead).isEmpty());

        Observation door=row("door","Door Found Open",now-12L*60L*1000L);
        List<Observation> spoken=ledger.collect(Collections.singletonList(door),true,previousRead,now);
        assertEquals(1,spoken.size());
        assertEquals("Door Found Open",spoken.get(0).issue);
    }

    @Test public void anUnseenHistoricalRowFromBeforeThePreviousLiveReadIsNotAnnounced(){
        AnnouncementLedger ledger=new AnnouncementLedger();
        long now=2_000_000_000L;
        long previousRead=now-10L*60L*1000L;
        ledger.collect(Collections.singletonList(row("baseline","General Patrol - Solo",previousRead)),false,0,previousRead);

        Observation historical=row("history","Door Found Open",previousRead-10L*60L*1000L);
        assertTrue(ledger.collect(Collections.singletonList(historical),true,previousRead,now).isEmpty());
    }

    @Test public void sameRowIsNotRepeatedOnEveryRefresh(){
        AnnouncementLedger ledger=new AnnouncementLedger();
        long now=3_000_000_000L;
        long previousRead=now-60_000L;
        ledger.collect(Collections.singletonList(row("baseline","General Patrol - Solo",previousRead)),false,0,previousRead);

        Observation door=row("door","Door Found Open",now-30_000L);
        assertEquals(1,ledger.collect(Collections.singletonList(door),true,previousRead,now).size());
        assertTrue(ledger.collect(Collections.singletonList(door),true,now,now+10_000L).isEmpty());
    }
}
