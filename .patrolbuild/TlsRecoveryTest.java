package au.com.roningroup.patrollink;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.SystemClock;
import android.webkit.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import javax.net.ssl.*;
import static org.junit.Assert.*;

/** Real rejected TLS handshakes plus a controlled Issue Monitor feed. No account credentials. */
@RunWith(AndroidJUnit4.class)
public class TlsRecoveryTest {
    @Rule public GrantPermissionRule notifications=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    private static void await(ActivityScenario<MainActivity> scenario,String label,long ms,Predicate<PatrolEngine> condition) throws Exception {
        long end=SystemClock.elapsedRealtime()+ms;
        AtomicBoolean ok=new AtomicBoolean();
        while(SystemClock.elapsedRealtime()<end) {
            scenario.onActivity(a->ok.set(condition.test(((PatrolApp)a.getApplication()).engine())));
            if(ok.get()) return;
            Thread.sleep(250);
        }
        AtomicReference<String> diag=new AtomicReference<>();
        scenario.onActivity(a->diag.set(((PatrolApp)a.getApplication()).engine().diagnostics()));
        fail(label+"\n"+diag.get());
    }
    private static final class UntrustedServer implements AutoCloseable {
        final SSLServerSocket socket;
        final Thread thread;
        UntrustedServer() throws Exception {
            KeyStore ks=KeyStore.getInstance("PKCS12");
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("tls-fixture.p12")) {
                ks.load(in,"fixture-only".toCharArray());
            }
            KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks,"fixture-only".toCharArray());
            SSLContext ssl=SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(),null,null);
            socket=(SSLServerSocket)ssl.getServerSocketFactory().createServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(()->{
                while(!socket.isClosed()) {
                    try(Socket client=socket.accept()) {
                        client.setSoTimeout(5000);
                        // A validating WebView rejects our self-signed certificate before requesting content.
                        client.getInputStream().read(new byte[4096]);
                        byte[] body="fixture".getBytes(StandardCharsets.UTF_8);
                        client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().write(body);
                    } catch(IOException expected) { /* Rejected certificate or test shutdown. */ }
                }
            },"untrusted-tls-fixture");
            thread.setDaemon(true);thread.start();
        }
        String url(String path) {return "https://127.0.0.1:"+socket.getLocalPort()+path;}
        @Override public void close() throws Exception {socket.close();thread.join(6000);}
    }
    private static WebResourceRequest request(String url) {
        return new WebResourceRequest() {
            public Uri getUrl(){return Uri.parse(url);}
            public boolean isForMainFrame(){return true;}
            public boolean isRedirect(){return false;}
            public boolean hasGesture(){return false;}
            public String getMethod(){return "GET";}
            public Map<String,String> getRequestHeaders(){return Collections.emptyMap();}
        };
    }
    @Test public void rejectedResourceDoesNotFreezeFeedAndMainTlsFailureRecoversAutomatically() throws Exception {
        AtomicInteger requests=new AtomicInteger(), sslCallbacks=new AtomicInteger();
        AtomicReference<WebViewClient> realClient=new AtomicReference<>();
        try(UntrustedServer server=new UntrustedServer(); ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                e.stopRuntime("TEST_SETUP","Controlled TLS fixture setup");
                e.prefs.edit().putString("guard0","D.DEO").putString("guard1","D.ROGERS1").putString("guard2","T.MURD").apply();
                WebView w=e.webView();
                w.clearSslPreferences();
                WebViewClient original=w.getWebViewClient();realClient.set(original);
                w.setWebViewClient(new WebViewClient(){
                    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return original.shouldOverrideUrlLoading(v,r);}
                    @Override public void onPageStarted(WebView v,String u,Bitmap b){original.onPageStarted(v,u,b);}
                    @Override public void onPageCommitVisible(WebView v,String u){original.onPageCommitVisible(v,u);}
                    @Override public void onPageFinished(WebView v,String u){original.onPageFinished(v,u);}
                    @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError er){original.onReceivedError(v,r,er);}
                    @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse er){original.onReceivedHttpError(v,r,er);}
                    @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError er){sslCallbacks.incrementAndGet();original.onReceivedSslError(v,h,er);}
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                        if("127.0.0.1".equals(r.getUrl().getHost())) return null; // Real TLS; no trust override.
                        String html="<html><body>Fixture resource</body></html>";
                        if(PatrolEngine.monitorPage(r.getUrl().toString())) {
                            int n=requests.incrementAndGet();
                            StringBuilder rows=new StringBuilder();
                            String[] guards={"D.DEO","D.ROGERS1","T.MURD"};
                            String[] types={"Scan Point","Parking Breach","Rec centre unlock"};
                            for(int i=0;i<3;i++) rows.append("<tr><td>").append(2100000000L+n*10+i)
                                .append("</td><td>Updated area ").append(n).append('-').append(i)
                                .append("</td><td>").append(types[i]).append(' ').append(n)
                                .append("</td><td>Tue 9/22 10:20 PM</td><td>").append(guards[i]).append("</td></tr>");
                            html="<html><body><h1>Issue Monitor</h1><table><thead><tr><th>Issue ID</th><th>Property Name</th><th>Reported Issue</th><th>Created Date</th><th>Created By</th></tr></thead><tbody>"
                                +rows+"</tbody></table><img src='"+server.url("/blocked-image.png")+"'></body></html>";
                        }
                        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                });
                CookieManager.getInstance().setCookie(PatrolEngine.MONITOR,"fixture_tls_session=kept; Path=/; Secure");
                e.start();
            });
            await(scenario,"real resource certificate rejected but monitor rows accepted",20000,e->sslCallbacks.get()>0&&e.healthy()&&e.rowsFound==3&&e.selectedCount==3);
            AtomicLong baseline=new AtomicLong(); AtomicInteger before=new AtomicInteger();
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                assertTrue(e.diagnostics().contains("page resource (blocked)"));
                assertFalse(e.state.equals("TLS_ERROR"));baseline.set(e.lastRead);before.set(requests.get());
            });
            await(scenario,"automatic 30-second cycle after asset certificate rejection",45000,e->requests.get()>before.get()&&e.healthy()&&e.lastRead>baseline.get());
            // Main-page certificate failure must invalidate freshness, not be ignored as a resource.
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();baseline.set(e.lastRead);
                e.webView().clearSslPreferences();e.webView().loadUrl(server.url("/blocked-document.html"));
            });
            await(scenario,"main-document TLS failure surfaced",15000,e->e.state.equals("TLS_ERROR"));
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                assertEquals(baseline.get(),e.lastRead);assertFalse(e.healthy());
                assertTrue(e.diagnostics().contains("Last TLS scope: main document"));
                assertTrue(e.sourceStatus().contains("RETRYING"));before.set(requests.get());
            });
            // No tap, inspectNow, openMonitor or timer override: wait for the actual scheduler.
            await(scenario,"automatic recovery from failed HTTPS main document",45000,e->requests.get()>before.get()&&e.healthy()&&e.lastRead>baseline.get());
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                assertEquals(3,e.selectedCount);assertTrue(e.webView().isAttachedToWindow());
                assertTrue(CookieManager.getInstance().getCookie(PatrolEngine.MONITOR).contains("fixture_tls_session=kept"));
                baseline.set(e.lastRead);before.set(requests.get());
                // Exercise Android's separate non-recoverable-handshake callback as well.
                realClient.get().onReceivedError(e.webView(),request(e.webView().getUrl()),new WebResourceError(){
                    public int getErrorCode(){return WebViewClient.ERROR_FAILED_SSL_HANDSHAKE;}
                    public CharSequence getDescription(){return "synthetic handshake failure";}
                });
                assertEquals("TLS_ERROR",e.state);assertEquals(baseline.get(),e.lastRead);
                assertTrue(e.diagnostics().contains("Last network error code: -11"));
            });
            await(scenario,"automatic recovery after non-recoverable-handshake callback",45000,e->requests.get()>before.get()&&e.healthy()&&e.lastRead>baseline.get());
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();
                assertEquals(3,e.selectedCount);assertTrue(e.recentForGuard("D.DEO").size()>=3);
                assertTrue(e.recentForGuard("D.ROGERS1").size()>=3);assertTrue(e.recentForGuard("T.MURD").size()>=3);
                String[] guards={"D.DEO","D.ROGERS1","T.MURD"};
                String[] types={"Scan Point","Parking Breach","Rec centre unlock"};
                for(int i=0;i<3;i++) {
                    Observation o=e.latest.get(guards[i]);assertNotNull(o);
                    assertEquals("Updated area "+requests.get()+"-"+i,o.property);
                    assertEquals(types[i]+" "+requests.get(),o.issue);
                }
                a.stopService(new Intent(a,MonitorService.class));e.stopRuntime("TEST_DONE","TLS tests complete");
            });
        }
    }
}
