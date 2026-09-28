package au.com.roningroup.patrollink;

import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.webkit.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class HistoryReaderRegressionTest {
    @Rule public GrantPermissionRule notifications=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    private Context c;
    @Before public void setup(){
        c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        c.getSharedPreferences(CheckpointTracker.FILE,0).edit().clear().commit();
        c.getSharedPreferences("patrol_settings",0).edit().putBoolean("voice",false)
            .putString("guard0","D.ROGERS1").putString("guard1","D.DEO").putString("guard2","T.MURD")
            .putString("zone","Australia/Brisbane").commit();
    }
    @After public void clear(){c.getSharedPreferences(CheckpointTracker.FILE,0).edit().clear().commit();}
    private Observation row(String id,String guard,long at){return new Observation(id,guard,"Rampage BC","General Patrol - Rampage BC","Recorded time",at);}

    @Test public void allThreeGuardsGrowBeyondFourteenAndDuplicatesStaySeparate(){
        CheckpointTracker t=new CheckpointTracker(c);long at=t.shiftStart()+1000;
        List<Observation> rows=new ArrayList<>();
        for(int i=0;i<20;i++)for(String guard:Arrays.asList("D.ROGERS1","D.DEO","T.MURD"))rows.add(row("114600"+i,guard,at+i*1000));
        assertEquals(60,t.observe(rows));
        for(String guard:Arrays.asList("D.ROGERS1","D.DEO","T.MURD"))assertEquals(20,t.total(guard));
        assertEquals(0,t.observe(rows));
        CheckpointTracker reloaded=new CheckpointTracker(c);
        assertEquals(0,reloaded.observe(rows));assertEquals(20,reloaded.total("D.ROGERS1"));
    }
    @Test public void auditKeepsAddedResultWhenScanIsPressedAgain(){
        CheckpointTracker t=new CheckpointTracker(c);long at=t.shiftStart()+1000;
        HistoryPageAudit audit=new HistoryPageAudit();List<String> guards=Arrays.asList("D.ROGERS1","D.DEO","T.MURD");
        List<Observation> rows=Arrays.asList(row("1146000001","D.ROGERS1",at),
            new Observation("1146000002","D.ROGERS1","Harbour Front BC","Harbour Front BC","timestamp",at+1000),
            new Observation("1146000003","D.DEO","Rampage BC","Parking Breach - 3rd Warning","timestamp",at));
        audit.accept("page2",rows,guards,t,3,0,System.currentTimeMillis());
        assertEquals(2,audit.added("D.ROGERS1"));assertEquals(2,t.total("D.ROGERS1"));
        for(int i=0;i<20;i++)audit.accept("page2",rows,guards,t,3,0,System.currentTimeMillis());
        assertEquals(2,audit.added("D.ROGERS1"));assertEquals(2,t.total("D.ROGERS1"));
        assertTrue(audit.details().contains("Not a checkpoint/patrol scan"));
        assertEquals(0,t.total("D.DEO"));
        audit.accept("different-page",rows,guards,t,3,1,System.currentTimeMillis());
        assertEquals(0,audit.added("D.ROGERS1"));assertTrue(audit.needsCheck());
        assertTrue(audit.details().contains("ALREADY COUNTED"));
    }
    @Test public void stabilizationWaitsForFinalAjaxRows(){
        HistoryScanGate g=new HistoryScanGate();
        assertFalse(g.ready("old",false,0));assertFalse(g.ready("old",false,200));
        assertFalse(g.ready("old",true,800));assertFalse(g.ready("new",false,900));
        assertFalse(g.ready("new",false,1100));assertTrue(g.ready("new",false,1500));
        assertFalse(g.ready("third",false,1600));
    }
    private static void show(MainActivity a,String name){
        try{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void await(ActivityScenario<MainActivity> scenario,String message,long millis,Predicate<PatrolEngine> test)throws Exception{
        long end=SystemClock.elapsedRealtime()+millis;AtomicBoolean done=new AtomicBoolean();
        while(SystemClock.elapsedRealtime()<end){scenario.onActivity(a->done.set(test.test(((PatrolApp)a.getApplication()).engine())));if(done.get())return;Thread.sleep(150);}
        AtomicReference<String> state=new AtomicReference<>();scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();state.set(e.historyReadStatus+"\n"+e.historyAudit.details()+"\n"+e.diagnostics());});
        fail(message+"\n"+state.get());
    }
    private static String tableRow(int i,String date){
        String guard=i%3==0?"D.ROGERS1":i%3==1?"D.DEO":"T.MURD";
        String shown=i%3==0?"D.<br>ROGERS1":guard;
        return "<tr><td><a>"+(1146000000+i)+"</a><span>camera</span><div>"+guard+"</div></td><td>Rampage BC</td><td>General Patrol - Rampage BC</td><td>"+date+"</td><td>"+shown+"</td><td>1</td><td>T.MURD</td></tr>";
    }
    @Test public void realWebViewReadsInPlacePagingAndCombinesSpamTaps()throws Exception{
        AtomicInteger networkPages=new AtomicInteger();
        String stamp=DateTimeFormatter.ofPattern("EEE M/d h:mm:ss a",Locale.US).format(ZonedDateTime.now(ZoneId.of("Australia/Brisbane")).minusSeconds(5));
        String first=tableRow(0,stamp)+tableRow(1,stamp)+tableRow(2,stamp);
        String stamp2=DateTimeFormatter.ofPattern("EEE M/d h:mm:ss a",Locale.US).format(ZonedDateTime.now(ZoneId.of("Australia/Brisbane")).minusSeconds(2));
        String second=tableRow(3,stamp2)+tableRow(4,stamp2)+tableRow(5,stamp2);
        String html="<html><body><h1>ISSUE MONITOR</h1><table><thead><tr><th>Issue ID</th><th>Property Name</th><th>Reported Issue</th><th>Created Date</th><th>Created By</th><th>All</th><th>Assigned To</th></tr></thead><tbody id='rows'>"+first+"</tbody></table><script>function nextFixture(){var e=document.getElementById('rows');e.setAttribute('aria-busy','true');setTimeout(function(){e.innerHTML=\""+second+"\";e.setAttribute('aria-busy','false');},1200);}</script></body></html>";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                PatrolEngine e=((PatrolApp)a.getApplication()).engine();e.stopRuntime("TEST","Offline fixture");e.checkpoints.reset();
                WebView w=e.webView();WebViewClient original=w.getWebViewClient();
                w.setWebViewClient(new WebViewClient(){
                    @Override public void onPageStarted(WebView v,String u,Bitmap b){original.onPageStarted(v,u,b);}
                    @Override public void onPageFinished(WebView v,String u){original.onPageFinished(v,u);}
                    @Override public void onPageCommitVisible(WebView v,String u){original.onPageCommitVisible(v,u);}
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                        boolean monitor=PatrolEngine.monitorPage(r.getUrl().toString());if(monitor)networkPages.incrementAndGet();
                        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream((monitor?html:"").getBytes(StandardCharsets.UTF_8)));
                    }
                });
                e.start();show(a,"showBrowser");
            });
            await(scenario,"Initial page counts all creators",18000,e->e.historyAudit.readAt()>0&&e.checkpoints.total("D.ROGERS1")==1&&e.checkpoints.total("D.DEO")==1);
            int pages=networkPages.get();
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                e.webView().evaluateJavascript("nextFixture()",null);
                for(int i=0;i<40;i++)e.scanVisiblePage();
            });
            await(scenario,"AJAX history change counts every guard without another navigation",18000,e->e.checkpoints.total("D.ROGERS1")==2&&e.checkpoints.total("D.DEO")==2&&e.checkpoints.total("T.MURD")==2);
            Thread.sleep(2500);
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                assertEquals(2,e.checkpoints.total("D.ROGERS1"));assertEquals(1,e.historyAudit.added("D.ROGERS1"));
                assertEquals(pages,networkPages.get());
                a.stopService(new Intent(a,MonitorService.class));e.stopRuntime("TEST_DONE","Complete");
            });
        }
    }
}
