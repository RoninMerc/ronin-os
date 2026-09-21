package au.com.roningroup.patrollink;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.webkit.*;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RefreshLifecycleTest {
    private static void await(ActivityScenario<MainActivity> scenario, String label, long ms, Predicate<PatrolEngine> condition) throws Exception {
        long end = SystemClock.elapsedRealtime() + ms;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < end) {
            scenario.onActivity(a -> ok.set(condition.test(((PatrolApp)a.getApplication()).engine())));
            if (ok.get()) return;
            Thread.sleep(250);
        }
        AtomicReference<String> diag = new AtomicReference<>();
        scenario.onActivity(a -> diag.set(((PatrolApp)a.getApplication()).engine().diagnostics()));
        fail(label + "\n" + diag.get());
    }
    private static void show(MainActivity a, String method) {
        try { Method m=MainActivity.class.getDeclaredMethod(method);m.setAccessible(true);m.invoke(a); }
        catch(Exception ex){throw new AssertionError(ex);}
    }
    @Test public void automaticCyclesStayAttachedAndRecoverFromEmptyShell() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean emptyShell = new AtomicBoolean(false);
        Set<String> urls = Collections.synchronizedSet(new HashSet<>());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                e.stopRuntime("TEST_SETUP", "Offline fixture setup");
                e.prefs.edit().putString("guard0","D.DEO").putString("guard1","D.ROGERS1").putString("guard2","T.MURD").apply();
                WebView w=e.webView();
                WebViewClient original=w.getWebViewClient();
                w.setWebViewClient(new WebViewClient(){
                    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return original.shouldOverrideUrlLoading(v,r);}
                    @Override public void onPageStarted(WebView v,String u,Bitmap b){original.onPageStarted(v,u,b);}
                    @Override public void onPageFinished(WebView v,String u){original.onPageFinished(v,u);}
                    @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError err){original.onReceivedError(v,r,err);}
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                        String html="<html><body>Fixture resource</body></html>";
                        if(PatrolEngine.monitorPage(r.getUrl().toString())) {
                            int n=requests.incrementAndGet(); urls.add(r.getUrl().toString());
                            html="<html><head><meta name='viewport' content='width=device-width'></head><body><h1>Issue Monitor</h1>"
                                +"<div class='k-loading-mask' style='display:none'>Loading</div>"
                                +"<table><thead><tr><th>Issue ID</th><th>Property Name</th><th>Reported Issue</th><th>Created Date</th><th>Created By</th></tr></thead><tbody id='rows'></tbody></table>";
                            if(!emptyShell.get()) {
                                StringBuilder rows=new StringBuilder();
                                String[] guards={"D.DEO","D.ROGERS1","T.MURD"};
                                for(int i=0;i<3;i++) rows.append("<tr><td>").append(1144000000L+n*10+i)
                                    .append("</td><td>Fixture site ").append(i+1).append("</td><td>")
                                    .append(i==0?"General Patrol":i==1?"Parking Breach":"Rec centre lockup")
                                    .append(" ").append(n).append("</td><td>Mon 9/21 10:20 PM</td><td>").append(guards[i]).append("</td></tr>");
                                // Delayed, frame-dependent content replicates a monitor that fills AFTER page-finished.
                                html+="<script>setTimeout(function(){requestAnimationFrame(function(){document.getElementById('rows').innerHTML=\""+rows+"\";});},1600);</script>";
                            }
                            html+="</body></html>";
                        }
                        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                });
                CookieManager.getInstance().setCookie(PatrolEngine.MONITOR,"fixture_session=kept; Path=/; Secure");
                e.start();
            });
            await(scenario,"initial delayed rows",20000,e->e.healthy()&&e.rowsFound==3&&e.selectedCount==3);
            AtomicLong baseline=new AtomicLong(); AtomicInteger initialRequests=new AtomicInteger();
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();baseline.set(e.lastRead);initialRequests.set(requests.get());assertTrue(e.webView().isAttachedToWindow());assertTrue(e.webView().getWidth()>0);assertTrue(e.webView().getHeight()>0);});
            // No manual refresh: observe two complete 30-second automatic cycles on the dashboard.
            await(scenario,"two real automatic cycles",80000,e->requests.get()>=initialRequests.get()+2&&e.healthy()&&e.lastRead>baseline.get());
            assertTrue("unique uncached requests",urls.size()>=3);
            scenario.onActivity(a->{show(a,"showBrowser");show(a,"showDashboard");PatrolEngine e=((PatrolApp)a.getApplication()).engine();assertTrue(e.webView().getParent()!=null);assertTrue(CookieManager.getInstance().getCookie(PatrolEngine.MONITOR).contains("fixture_session=kept"));});
            await(scenario,"after browser/dashboard switch",20000,e->e.healthy());
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();baseline.set(e.lastRead);emptyShell.set(true);e.refreshMonitor();});
            await(scenario,"empty shell rejected",12000,e->e.rowsFound==0&&e.lastRead==baseline.get());
            await(scenario,"bounded timeout instead of CHECKING forever",30000,e->e.state.equals("REFRESH_TIMEOUT"));
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();assertEquals(baseline.get(),e.lastRead);assertFalse(e.healthy());emptyShell.set(false);});
            // Recovery is also automatic, not triggered by opening the website.
            await(scenario,"automatic recovery after timeout",20000,e->e.healthy()&&e.rowsFound==3&&e.lastRead>baseline.get());
            scenario.recreate();
            await(scenario,"rotation/recreation retains attached monitor",15000,e->e.webView().isAttachedToWindow()&&e.webView().getWidth()>0&&e.healthy());
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();assertEquals(3,e.selectedCount);assertTrue(e.recentForGuard("D.DEO").size()>=3);a.stopService(new Intent(a,MonitorService.class));e.stopRuntime("TEST_DONE","Fixture test complete");});
        }
    }
}
