from pathlib import Path
import sys

root = Path(sys.argv[1])
java = root / 'app/src/main/java/au/com/roningroup/patrollink'

def replace_once(s, old, new):
    if old not in s:
        raise RuntimeError('Patch anchor missing: ' + old[:90])
    return s.replace(old, new, 1)

def replace_block(s, start, end, new):
    a=s.index(start); b=s.index(end,a)
    return s[:a]+new+s[b:]

p=java/'PatrolEngine.java'
s=p.read_text()
s=replace_once(s, '    private long refreshAttempt;', '''    private long refreshAttempt;
    private boolean refreshActive, inspectionPending;
    private long refreshDeadline;
    private int completedRefreshes, failedRefreshes, consecutiveFailures;
    private String signedMonitorUrl = MONITOR;
    private String documentState = "not read";
    public WebView peekWebView() { return web; }
''')
s=replace_block(s, '    private final Runnable cycle =', '    public PatrolEngine(Context context)', '''    private final Runnable cycle = new Runnable() { public void run() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (refreshActive && now >= refreshDeadline) {
            refreshActive = false; navigating = false; inspectionPending = false;
            navigationToken++; refreshAttempt++; failedRefreshes++; consecutiveFailures++;
            if (web != null) web.stopLoading();
            status("REFRESH_TIMEOUT", "No usable monitor rows arrived within 25 seconds. Keeping last known activity and retrying.");
            if (consecutiveFailures >= 2) {
                consecutiveFailures = 0;
                resetRenderer();
            }
            nextCheckElapsed = now + 5000L;
        }
        if (!refreshActive && now >= nextCheckElapsed) refreshMonitor();
        handler.postDelayed(this, 1000L);
    }};
    private void resetRenderer() {
        if (web != null) {
            CookieManager.getInstance().flush();
            if (web.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup)web.getParent()).removeView(web);
            web.destroy(); web = null;
        }
        webView();
        notifyChanged();
    }

''')
s=replace_once(s, '        s.setCacheMode(WebSettings.LOAD_DEFAULT);', '''        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setOffscreenPreRaster(true);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);''')
s=replace_block(s, '            @Override public void onPageStarted(', '            @Override public void onReceivedError(', '''            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                navigationToken++; navigating = true; startedElapsed = SystemClock.elapsedRealtime();
                refreshActive = running; refreshDeadline = startedElapsed + 25_000L;
                inspectionPending = false; lastPageFailed = false; acceptedThisNavigation = false; lastHttp = 0;
                documentState = "document loading";
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != web || !running || !refreshActive || lastPageFailed) return;
                navigating = false;
                CookieManager.getInstance().flush();
                if (!monitorPage(url)) {
                    refreshActive = false;
                    status("SIGN_IN_REQUIRED", "Silvertracker is not on Issue Monitor. Open Silvertracker to complete sign-in once.");
                    nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
                    return;
                }
                signedMonitorUrl = withoutRefreshNonce(url);
                final long token = navigationToken;
                handler.postDelayed(() -> inspect(token, false), 300L);
            }
''')
s=s.replace('                        status("SIGN_IN_REQUIRED", "Silvertracker invalidated the web session. Open Silvertracker and sign in once.");', '                        refreshActive = false; navigating = false; lastPageFailed = true;\n                        status("SIGN_IN_REQUIRED", "Silvertracker requires sign-in. Open Silvertracker once.");')
s=s.replace('resend.sendToTarget();', 'dontResend.sendToTarget();')
s=replace_block(s, '            @Override public boolean onRenderProcessGone(', '        });\n        web.setWebChromeClient', '''            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail ignored) {
                refreshActive = false; navigating = false; inspectionPending = false;
                navigationToken++; refreshAttempt++;
                if (view.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup)view.getParent()).removeView(view);
                view.destroy(); web = null;
                webView();
                status("WEBVIEW_ERROR", "Browser renderer restarted. Reconnecting with the existing cookies.");
                nextCheckElapsed = SystemClock.elapsedRealtime() + 1500L;
                return true;
            }
''')
s=replace_block(s, '    private void fail(', '    private void inspect(long token, boolean lastTry)', '''    private void fail(String code, String message) {
        lastPageFailed = true; navigating = false; refreshActive = false; inspectionPending = false;
        navigationToken++; refreshAttempt++; failedRefreshes++;
        status(code, message);
        nextCheckElapsed = SystemClock.elapsedRealtime() + 5000L;
    }
    private static String withoutRefreshNonce(String url) {
        Uri u = Uri.parse(url);
        Uri.Builder b = u.buildUpon().clearQuery().fragment(null);
        for (String key : u.getQueryParameterNames()) {
            if (!key.equals("_ronin_refresh"))
                for (String value : u.getQueryParameters(key)) b.appendQueryParameter(key, value);
        }
        return b.build().toString();
    }
    public void openMonitor() {
        if (!running || refreshActive) return;
        requestFreshMonitor();
    }
    public void loadMonitor() { refreshMonitor(); }
    public void refreshMonitor() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (refreshActive) return;
        nextCheckElapsed = now + INTERVAL;
        if (!online()) { status("OFFLINE", "No network connection. Keeping the session and retrying."); return; }
        if ("TLS_ERROR".equals(state) || "BLOCKED".equals(state)) return;
        if (web != null && web.getUrl() != null && !"about:blank".equals(web.getUrl()) && !monitorPage(web.getUrl())) {
            status("SIGN_IN_REQUIRED", "Open Silvertracker to complete sign-in once. Cookies have not been cleared.");
            return;
        }
        requestFreshMonitor();
    }
    private void requestFreshMonitor() {
        if (!online()) {
            status("OFFLINE", "No network connection. Keeping the session and retrying.");
            nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
            return;
        }
        WebView view = webView();
        if (monitorPage(view.getUrl())) signedMonitorUrl = withoutRefreshNonce(view.getUrl());
        if (!monitorPage(signedMonitorUrl)) signedMonitorUrl = MONITOR;
        long now = SystemClock.elapsedRealtime();
        refreshAttempt++; navigationToken++;
        refreshActive = true; navigating = true; inspectionPending = false;
        acceptedThisNavigation = false; lastPageFailed = false;
        startedElapsed = now; refreshDeadline = now + 25_000L;
        nextCheckElapsed = now + INTERVAL;
        documentState = "request sent";
        status("CHECKING", "Fetching Issue Monitor; waiting for its activity rows, not just the page shell.");
        view.onResume(); view.resumeTimers();
        view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        String url = Uri.parse(signedMonitorUrl).buildUpon()
                .appendQueryParameter("_ronin_refresh", Long.toString(System.currentTimeMillis()) + "-" + refreshAttempt).build().toString();
        Map<String,String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache, no-store, max-age=0");
        headers.put("Pragma", "no-cache");
        view.loadUrl(url, headers);
    }
    public void inspectNow() {
        if (refreshActive && web != null && !navigating) inspect(navigationToken, false);
        else if (!refreshActive) refreshMonitor();
    }
    private void inspectAgain(long token) {
        if (running && refreshActive && token == navigationToken && !lastPageFailed)
            handler.postDelayed(() -> inspect(token, false), 700L);
    }
''')
s=replace_once(s, '        if (web == null || token != navigationToken || lastPageFailed || acceptedThisNavigation || !monitorPage(web.getUrl())) return;', '        if (!running || !refreshActive || inspectionPending || navigating || web == null || token != navigationToken || lastPageFailed || acceptedThisNavigation || !monitorPage(web.getUrl())) return;')
s=replace_once(s, '        web.evaluateJavascript(script, encoded -> {', '        inspectionPending = true;\n        web.evaluateJavascript(script, encoded -> {')
s=replace_once(s, '            if (web == null || token != navigationToken || lastPageFailed) return;', '            if (!running || !refreshActive || web == null || token != navigationToken || lastPageFailed) return;\n            inspectionPending = false;')
s=replace_once(s, '                if (data.optBoolean("login")) {', '                if (data.optBoolean("login")) {\n                    refreshActive = false;')
s=replace_block(s, '                rowsFound = data.optInt(', '                List<Observation> observations', '''                rowsFound = data.optInt("totalRows", 0);
                JSONArray entries = data.optJSONArray("selectedRows");
                documentState = data.optString("ready", "unknown") + "; " + data.optString("mode", "unknown")
                        + "; busy=" + data.optBoolean("loading");
                if (!SnapshotPolicy.usable(rowsFound, data.optBoolean("recognized"),
                        data.optBoolean("loading"), data.optBoolean("emptyConfirmed"))) {
                    inspectAgain(token);
                    return;
                }
''')
s=replace_once(s, '                acceptedThisNavigation = true;', '''                acceptedThisNavigation = true; refreshActive = false;
                completedRefreshes++; consecutiveFailures = 0;
                CookieManager.getInstance().flush();''')
s=s.replace('                if (lastTry) status("READER_CHECK", "The Issue Monitor format was not readable on this refresh. The session has not been cleared.");', '                documentState = "reader response unavailable";\n                inspectAgain(token);')
s=replace_once(s, '        running = false; handler.removeCallbacks(cycle); navigationToken++;', '        running = false; handler.removeCallbacks(cycle); navigationToken++; refreshAttempt++;\n        refreshActive = false; inspectionPending = false;')
s=s.replace('SIGNED IN · LIVE FEED', 'CONNECTED · FEED CHECKED')
s=s.replace('Reader schema: 3', 'Reader schema: 4')
s=s.replace('Refresh method: signed-session full reload every 30 seconds', 'Refresh method: attached browser + uncached GET + row-readiness deadline')
s=replace_once(s, '                "\\nTable rows found: " + rowsFound', '''                "\\nBrowser attached: " + (web != null && web.isAttachedToWindow()) +
                "\\nBrowser size: " + (web == null ? "none" : web.getWidth() + "x" + web.getHeight()) +
                "\\nRefresh attempts/completed/failed: " + refreshAttempt + "/" + completedRefreshes + "/" + failedRefreshes +
                "\\nRequest active: " + refreshActive + "\\nDocument state: " + documentState +
                "\\nTable rows found: " + rowsFound''')
p.write_text(s)

(java/'SnapshotPolicy.java').write_text('''package au.com.roningroup.patrollink;
/** A heading-only loading shell is never a successful feed refresh. */
public final class SnapshotPolicy {
    private SnapshotPolicy() {}
    public static boolean usable(int rows, boolean recognized, boolean loading, boolean explicitEmpty) {
        return recognized && !loading && (rows > 0 || explicitEmpty);
    }
}
''')

p=java/'MainActivity.java'; s=p.read_text()
s=replace_block(s, '    private void base() {', '    private void showDashboard()', '''    private void base() {
        detachWeb();
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        host.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        attachedWeb = engine.webView();
        if (attachedWeb.getParent() instanceof ViewGroup) ((ViewGroup)attachedWeb.getParent()).removeView(attachedWeb);
        attachedWeb.setVisibility(View.VISIBLE);
        attachedWeb.setImportantForAccessibility(browserMode ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        host.addView(attachedWeb,new FrameLayout.LayoutParams(-1,-1));
        root=vertical(); root.setBackgroundColor(BG); root.setClickable(true);
        host.addView(root,new FrameLayout.LayoutParams(-1,-1));
        setContentView(host); host.requestApplyInsets();
        attachedWeb.onResume(); attachedWeb.resumeTimers();
        // The WebView remains sized and attached underneath the opaque dashboard.
        // Never hide it with GONE or leave it detached while polling the webpage.
    }
''')
s=s.replace('    @Override public void changed() { refresh(); }', '''    @Override public void changed() {
        if (isFinishing() || isDestroyed()) return;
        if (engine.peekWebView() != null && attachedWeb != engine.peekWebView()) {
            if (browserMode) showBrowser(); else showDashboard();
            return;
        }
        refresh();
    }''')
s=s.replace("Patrol Link keeps that WebView session and then uses Silvertracker's own Update control every 30 seconds instead of repeatedly reopening the login URL.", "Patrol Link retains that session, keeps the browser attached behind the dashboard, and requests fresh monitor rows every 30 seconds.")
s=s.replace("After a successful Silvertracker sign-in, Patrol Link keeps the same WebView cookie/session and refreshes Issue Monitor every 30 seconds using Silvertracker's own Update control. It no longer reopens the login URL every 30 seconds.", "The monitor browser stays attached behind the dashboard. Every 30 seconds Patrol Link requests an uncached monitor page and waits for readable rows. A blank loading shell is never marked as a successful refresh. Timed-out attempts are cancelled and retried without deleting cookies.")
s=s.replace('super.onResume();engine.appVisible=true;', 'super.onResume();if(attachedWeb!=null){attachedWeb.onResume();attachedWeb.resumeTimers();}engine.appVisible=true;')
p.write_text(s)

p=root/'app/src/main/assets/extract.js'; s=p.read_text()
s=s.replace('schema: 3', 'schema: 4')
s=s.replace('loading: false, totalRows:', "loading: false, ready: document.readyState, emptyConfirmed: false, totalRows:")
s=replace_once(s, "    const notHidden = e => { if (!e) return false; const s = getComputedStyle(e); return s.display !== 'none' && s.visibility !== 'hidden'; };", "    const notHidden = e => { if (!e) return false; for(let p=e;p&&p.nodeType===1;p=p.parentElement){const s=getComputedStyle(p);if(s.display==='none'||s.visibility==='hidden')return false;}return true; };")
s=replace_once(s, "    out.loading = !!document.querySelector('.k-loading-mask,.rgLoading,.dataTables_processing,[aria-busy=\"true\"]');", "    out.loading = Array.from(document.querySelectorAll('.k-loading-mask,.rgLoading,.dataTables_processing,[aria-busy=\"true\"]')).some(notHidden);\n    try { if(window.Sys && Sys.WebForms && Sys.WebForms.PageRequestManager) out.loading = out.loading || Sys.WebForms.PageRequestManager.getInstance().get_isInAsyncPostBack(); } catch(e) {}\n    out.emptyConfirmed = hasMonitor && /\\b(no records to display|no records found|no issues found|no data available)\\b/i.test(text);")
p.write_text(s)

p=root/'app/build.gradle'; s=p.read_text()
s=s.replace('versionCode 110','versionCode 115').replace("versionName '1.1.0'", "versionName '1.1.5'")
s=s.replace('targetSdk 35','targetSdk 36')
s=s.replace('        minSdk 28', "        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'\n        minSdk 28")
s=s.replace("    testImplementation 'junit:junit:4.13.2'", "    testImplementation 'junit:junit:4.13.2'\n    androidTestImplementation 'androidx.test:runner:1.6.2'\n    androidTestImplementation 'androidx.test:rules:1.6.1'\n    androidTestImplementation 'androidx.test.ext:junit:1.2.1'")
p.write_text(s)

test=root/'app/src/test/java/au/com/roningroup/patrollink/SnapshotPolicyTest.java'
test.write_text('''package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
public class SnapshotPolicyTest {
 @Test public void headingOnlyIsNotSuccess(){assertFalse(SnapshotPolicy.usable(0,true,false,false));}
 @Test public void visibleLoaderIsNotSuccess(){assertFalse(SnapshotPolicy.usable(15,true,true,false));}
 @Test public void realRowsAreSuccess(){assertTrue(SnapshotPolicy.usable(15,true,false,false));}
 @Test public void knownEmptyIsSuccess(){assertTrue(SnapshotPolicy.usable(0,true,false,true));}
 @Test public void unrecognizedPageIsNotSuccess(){assertFalse(SnapshotPolicy.usable(0,false,false,true));}
}
''')

p=java/'MonitorService.java'; s=p.read_text()
s=s.replace('engine.status("READ_OK", "Android background-service budget ended; active app/car session continues.");', 'engine.notifyChanged(); // A service lifecycle event is not a successful feed read.')
p.write_text(s)
print('Patched v1.1.5 browser lifecycle, bounded refresh, and snapshot acceptance')
