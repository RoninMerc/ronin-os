package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecCentreLabelsTest {
    @Test public void recCentreTwoUsesGymAndBarbecueAreaLabels(){
        Map<String,String> labels=new HashMap<>();
        for(CheckpointTracker.Point p:CheckpointTracker.POINTS)labels.put(p.id,p.label);
        assertEquals("Rec Center 2 Gym",labels.get("rec2"));
        assertEquals("Rec Center 2 Barbecue Area",labels.get("rec1"));
        assertFalse(labels.containsValue("Recreation Centre 1"));
        assertFalse(labels.containsValue("Recreation Centre 2"));
    }

    @Test public void gymAndBarbecueSourceAliasesMapToSeparatePoints(){
        long now=System.currentTimeMillis();
        Observation gym=new Observation("g","T.MURD","Recreational Facility Gym",
                "General Patrol - Recreational Facility Gym","",now);
        Observation bbq=new Observation("b","T.MURD","Recreational Facility 1",
                "General Patrol - Recreational Facility 1","",now);
        assertEquals("rec2",CheckpointTracker.match(gym).id);
        assertEquals("rec1",CheckpointTracker.match(bbq).id);
    }
}
