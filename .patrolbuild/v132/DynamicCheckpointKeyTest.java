package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DynamicCheckpointKeyTest {
    private Context context;

    @Before public void setup(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(CheckpointTracker.FILE,Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE).edit()
                .putString("zone","Australia/Brisbane").commit();
    }

    @After public void cleanup(){
        context.getSharedPreferences(CheckpointTracker.FILE,Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Observation row(String id,String guard,String property,String issue,long at){
        return new Observation(id,guard,property,issue,"",at);
    }

    @Test public void sameAreaAndTimeCountsSeparatelyForDifferentGuards(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+60_000L;
        t.observe(Arrays.asList(
                row("same-id","T.MURD","Rampage BC","General Patrol - Rampage BC",at),
                row("same-id","D.ROGERS1","Rampage BC","General Patrol - Rampage BC",at),
                row("same-id","D.DEO","Rampage BC","General Patrol - Rampage BC",at)
        ));
        assertEquals(1,t.total("T.MURD"));
        assertEquals(1,t.total("D.ROGERS1"));
        assertEquals(1,t.total("D.DEO"));
    }

    @Test public void activeShiftStillStartsAtSixPm(){
        CheckpointTracker t=new CheckpointTracker(context);
        java.time.ZonedDateTime z=java.time.Instant.ofEpochMilli(t.shiftStart())
                .atZone(java.time.ZoneId.of("Australia/Brisbane"));
        assertEquals(18,z.getHour());
        assertEquals(0,z.getMinute());
    }

    @Test public void issueIdDoesNotDefineDuplicates(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+120_000L;
        t.observe(Arrays.asList(
                row("100","D.ROGERS1","Balmara BC","General Patrol - Balmara Place",at),
                row("101","D.ROGERS1","Balmara BC","General Patrol - Balmara Place",at)
        ));
        assertEquals("Different issue IDs at same guard+area+time are still one hit",1,t.total("D.ROGERS1"));
    }

    @Test public void sameGuardAreaDifferentTimeIsAnotherHit(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+180_000L;
        t.observe(Arrays.asList(
                row("200","D.ROGERS1","Tradition BC","General Patrol - Tradition Place",at),
                row("201","D.ROGERS1","Tradition BC","General Patrol - Tradition Place",at+60_000L)
        ));
        assertEquals(2,t.total("D.ROGERS1"));
    }

    @Test public void rescanningSamePageDoesNotIncrementAgain(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+240_000L;
        List<Observation> page=Arrays.asList(
                row("300","T.MURD","Rampage BC","General Patrol - Rampage BC",at),
                row("301","D.ROGERS1","Balmara BC","General Patrol - Balmara Place",at+60_000L)
        );
        assertEquals(2,t.observe(page));
        assertEquals(0,t.observe(page));
        assertEquals(1,t.total("T.MURD"));
        assertEquals(1,t.total("D.ROGERS1"));
    }

    @Test public void unknownPatrolAreaIsAcceptedWithoutWhitelist(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+300_000L;
        t.observe(Collections.singletonList(
                row("400","D.DEO","Brand New BC","General Patrol - Brand New BC",at)));
        assertEquals(1,t.total("D.DEO"));
        assertEquals("Brand New BC",t.areas("D.DEO").get(0).label);
    }

    @Test public void recCentreLabelsRemainCorrect(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+330_000L;
        t.observe(Arrays.asList(
                row("gym","T.MURD","Recreational Facility Gym","General Patrol - Recreational Facility Gym",at),
                row("bbq","T.MURD","Recreational Facility 1","General Patrol - Recreational Facility 1",at+60_000L)
        ));
        java.util.List<CheckpointTracker.AreaCount> areas=t.areas("T.MURD");
        java.util.Set<String> labels=new java.util.HashSet<>();
        for(CheckpointTracker.AreaCount a:areas)labels.add(a.label);
        assertTrue(labels.contains("Rec Center 2 Gym"));
        assertTrue(labels.contains("Rec Center 2 Barbecue Area"));
    }

    @Test public void incidentsAtAPropertyAreNotCheckpointHits(){
        CheckpointTracker t=new CheckpointTracker(context);
        long at=t.shiftStart()+360_000L;
        t.observe(Collections.singletonList(
                row("500","D.DEO","Rampage BC","Door Found Open",at)));
        assertEquals(0,t.total("D.DEO"));
    }
}
