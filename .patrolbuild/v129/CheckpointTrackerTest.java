package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CheckpointTrackerTest {
    private Context context;

    @Before public void setup(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(CheckpointTracker.FILE,Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void cleanup(){
        context.getSharedPreferences(CheckpointTracker.FILE,Context.MODE_PRIVATE).edit().clear().commit();
    }

    private Observation row(String id,String guard,String property,String issue,long when){
        return new Observation(id,guard,property,issue,"",when);
    }

    @Test public void countsOnlyUniquePatrolScansAndGroupsThem(){
        CheckpointTracker t=new CheckpointTracker(context);
        long now=System.currentTimeMillis();
        List<Observation> rows=Arrays.asList(
            row("1","T.MURD","Ceil BC","General Patrol - Ceil Circui",now-5000),
            row("2","T.MURD","Slipstream BC","General Patrol - Slipstream",now-4000),
            row("3","T.MURD","Quest BC","General Patrol - Quest BC",now-3000),
            row("4","T.MURD","Serenade BC","General Patrol - Serenade",now-2000),
            row("5","T.MURD","Ceil BC","Noise Complaint",now-1000)
        );
        t.observe(rows);
        assertEquals(1,t.count("T.MURD","ceil"));
        assertEquals(1,t.count("T.MURD","slipstream"));
        assertEquals(1,t.count("T.MURD","quest"));
        assertEquals(1,t.count("T.MURD","serenade"));
        assertEquals(2,t.groupTotal("T.MURD","PBC 2"));
        assertEquals(2,t.groupTotal("T.MURD","PBC 3"));
        assertEquals(4,t.total("T.MURD"));

        // Same monitor rows on the next refresh must not increment again.
        t.observe(rows);
        assertEquals(4,t.total("T.MURD"));
    }

    @Test public void matchesKnownSilvertrackerAndSpokenAliases(){
        long now=System.currentTimeMillis();
        CheckpointTracker t=new CheckpointTracker(context);
        t.observe(Arrays.asList(
            row("10","D.DEO","Boambillee BC","General Patrol - Boambillee",now-6000),
            row("11","D.DEO","Christina BC","General Patrol - Christina B",now-5000),
            row("12","D.DEO","Recreational Facility 1","General Patrol - Recreation",now-4000),
            row("13","D.DEO","Recreational Facility 2","General Patrol - Recreation",now-3000),
            row("14","D.DEO","Eolo BC","General Patrol - Eolo",now-2000),
            row("15","D.DEO","Bobsled BC","General patrol - Bobsled Lan",now-1000)
        ));
        assertEquals(1,t.count("D.DEO","boambillee"));
        assertEquals(1,t.count("D.DEO","christina"));
        assertEquals(1,t.count("D.DEO","rec1"));
        assertEquals(1,t.count("D.DEO","rec2"));
        assertEquals(1,t.count("D.DEO","eolo"));
        assertEquals(1,t.count("D.DEO","bobsled"));
    }

    @Test public void resetStartsFreshShift(){
        CheckpointTracker t=new CheckpointTracker(context);
        long now=System.currentTimeMillis();
        t.observe(Collections.singletonList(row("20","T.MURD","Impeccable BC","General Patrol - Impeccable",now)));
        assertEquals(1,t.total("T.MURD"));
        t.reset();
        assertEquals(0,t.total("T.MURD"));
        assertTrue(t.shiftStart()>0);
    }
}
