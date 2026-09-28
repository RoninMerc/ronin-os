package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SpeechPreviewParityTest {
    private Context context;

    @Before public void setup(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(SpeechPreferences.FILE,Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void cleanup(){
        context.getSharedPreferences(SpeechPreferences.FILE,Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void editedAlertPreviewMatchesTheLiveExactSentence(){
        SpeechPreferences p=new SpeechPreferences(context);
        Observation row=new Observation("1","T.MURD","Ceil BC","General Patrol - Ceil Circui","",System.currentTimeMillis());
        p.save(SpeechPreferences.ALERT,row.issue,"General Patrol - Ceil Circuit");

        assertEquals("General Patrol - Ceil Circuit",p.format(row));
        assertEquals(p.format(row),p.preview(row,SpeechPreferences.ALERT,row.issue,"General Patrol - Ceil Circuit"));
    }
}
