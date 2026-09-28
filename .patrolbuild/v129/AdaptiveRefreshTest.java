package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdaptiveRefreshTest {
    @Test public void cadenceUsesTenSecondNormalAndSafeBackoff(){
        assertEquals(10_000L,PatrolEngine.INTERVAL);
        assertEquals(15_000L,PatrolEngine.BACKOFF_INTERVAL);
        assertEquals(30_000L,PatrolEngine.FAILURE_INTERVAL);
    }

    @Test public void diagnosticsReportsAdaptiveCadence(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PatrolEngine e=new PatrolEngine(c);
        assertTrue(e.diagnostics().contains("adaptive 10s normal / 15s backoff / 30s repeated failure"));
    }
}
