from pathlib import Path
import sys
root=Path(sys.argv[1])
java=root/'app/src/main/java/au/com/roningroup/patrollink'
p=java/'PatrolEngine.java';s=p.read_text()
anchor='            @Override public void onPageFinished(WebView view, String url) {'
assert anchor in s
s=s.replace(anchor,'''            @Override public void onPageCommitVisible(WebView view, String url) {
                if (view != web || !running || !refreshActive || lastPageFailed || !monitorPage(url)) return;
                if (!url.equals(view.getUrl())) return;
                navigating = false;
                signedMonitorUrl = withoutRefreshNonce(url);
                final long token = navigationToken;
                handler.postDelayed(() -> inspect(token, false), 250L);
            }
'''+anchor,1)
s=s.replace('                navigating = false;\n                CookieManager.getInstance().flush();', '                if (url != null && !url.equals(view.getUrl())) return;\n                navigating = false;\n                CookieManager.getInstance().flush();',1)
p.write_text(s)
p=root/'app/src/androidTest/java/au/com/roningroup/patrollink/RefreshLifecycleTest.java';s=p.read_text()
s=s.replace('import org.junit.Test;', 'import org.junit.Test;\nimport org.junit.Rule;\nimport androidx.test.rule.GrantPermissionRule;')
s=s.replace('public class RefreshLifecycleTest {', 'public class RefreshLifecycleTest {\n    @Rule public GrantPermissionRule notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);')
s=s.replace('        AtomicInteger requests = new AtomicInteger();','        AtomicInteger requests = new AtomicInteger();\n        AtomicLong firstRequestAt = new AtomicLong();')
s=s.replace('                    @Override public void onPageFinished', '                    @Override public void onPageCommitVisible(WebView v,String u){original.onPageCommitVisible(v,u);}\n                    @Override public void onPageFinished')
s=s.replace('                        String html="<html><body>Fixture resource</body></html>";', '''                        if(r.getUrl().getPath().endsWith("slow-fixture.png")) {
                            try { Thread.sleep(20000); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
                            return new WebResourceResponse("image/png","UTF-8",new ByteArrayInputStream(new byte[0]));
                        }
                        String html="<html><body>Fixture resource</body></html>";''')
s=s.replace('                            int n=requests.incrementAndGet(); urls.add(r.getUrl().toString());', '                            int n=requests.incrementAndGet(); urls.add(r.getUrl().toString());\n                            if(n==1) firstRequestAt.set(SystemClock.elapsedRealtime());')
s=s.replace('                            html+="</body></html>";', '                            if(n==1) html+="<img src=\"+\"\\\"https://trlsecurity.silvertracker.net/slow-fixture.png\\\"\"+\">";\n                            html+="</body></html>";')
s=s.replace('            AtomicLong baseline=new AtomicLong();', '            scenario.onActivity(a -> assertTrue("Do not wait for unrelated image loads", ((PatrolApp)a.getApplication()).engine().lastReadElapsed-firstRequestAt.get()<15000));\n            AtomicLong baseline=new AtomicLong();')
p.write_text(s)
print('Added early document readiness and notification-permission test setup')
