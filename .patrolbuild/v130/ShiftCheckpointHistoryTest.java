package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ShiftCheckpointHistoryTest {
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

    private Observation row(String id,String guard,String property,String issue,long when){
        return new Observation(id,guard,property,issue,"",when);
    }

    @Test public void activeShiftStartsAtMostRecentSixPmBrisbane(){
        CheckpointTracker t=new CheckpointTracker(context);
        ZonedDateTime z=Instant.ofEpochMilli(t.shiftStart()).atZone(ZoneId.of("Australia/Brisbane"));
        assertEquals(18,z.getHour());
        assertEquals(0,z.getMinute());
    }

    @Test public void pagesCanBeRevisitedWithoutDoublingIssueOrCheckpointTime(){
        CheckpointTracker t=new CheckpointTracker(context);
        long when=t.shiftStart()+60L*60L*1000L;
        Observation first=row("100","T.MURD","Ceil BC","General Patrol - Ceil Circui",when);
        Observation duplicateDifferentId=row("101","T.MURD","Ceil BC","General Patrol - Ceil Circui",when);

        t.observe(Arrays.asList(first,duplicateDifferentId));
        assertEquals(1,t.count("T.MURD","ceil"));
        t.observe(Arrays.asList(first,duplicateDifferentId));
        assertEquals(1,t.count("T.MURD","ceil"));
    }

    @Test public void rowsBeforeShiftStartAreIgnoredAndOlderPagesAfterSixPmAccumulate(){
        CheckpointTracker t=new CheckpointTracker(context);
        long start=t.shiftStart();
        t.observe(Arrays.asList(
                row("before","T.MURD","Quest BC","General Patrol - Quest",start-60_000L),
                row("after1","T.MURD","Quest BC","General Patrol - Quest",start+60_000L)
        ));
        assertEquals(1,t.count("T.MURD","quest"));

        t.observe(Collections.singletonList(
                row("after2","T.MURD","Quest BC","General Patrol - Quest",start+120_000L)));
        assertEquals(2,t.count("T.MURD","quest"));
    }
}
