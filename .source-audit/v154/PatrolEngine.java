package au.com.roningroup.patrollink;

import android.annotation.SuppressLint;
import android.content.*;
import android.graphics.Bitmap;
import android.net.*;
import android.net.http.SslError;
import android.os.*;
import android.media.AudioAttributes;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;

/** Persistent authorised Silvertracker reader. The web page is never projected into Android Auto. */
public final class PatrolEngine {
    public static final String MONITOR = "https://trlsecurity.silvertracker.net/AdminCustomer/Issues/Monitor.aspx";
    public static final String HOST = "trlsecurity.silvertracker.net";
    public static final String PROD_HOST = "prod.silvertracker.net";
    public static final long INTERVAL = 10_000L;
    public static final long BACKOFF_INTERVAL = 15_000L;
    public static final long FAILURE_INTERVAL = 30_000L;
    private static final int RECENT_LIMIT = 60;
    public interface Listener { void changed(); }
    public final Context context;
    public final SharedPreferences prefs;
    public final VoiceManager voices;
    public final FeatherlessVoice featherlessVoice;
    public final SilvertrackerLoginStore loginStore;
    private final SilvertrackerAutoLogin autoLogin;
    public final CheckpointTracker checkpoints;
    public final HistoryPageAudit historyAudit=new HistoryPageAudit();
    private final HistoryScanGate historyGate=new HistoryScanGate();
    private long nextHistoryRead;
    public String historyReadStatus="Waiting for the visible page";
    public final Handler handler = new Handler(Looper.getMainLooper());
    public final Map<String, Observation> latest = new LinkedHashMap<>();
    public final Set<String> present = new HashSet<>();
    private final LinkedHashMap<String, Observation> recentById = new LinkedHashMap<>();
    private final AnnouncementLedger speechLedger = new AnnouncementLedger();
    private String lastRefreshFailure = "none";
    private boolean welcomeSpokenThisSession;
    public final List<Listener> listeners = new ArrayList<>();
    public boolean running, browserVisible, carConnected, appVisible;
    public String state = "STARTING", detail = "Open Silvertracker once if sign-in is requested.";
    public long lastRead, lastReadElapsed, nextCheckElapsed;
    public int rowsFound, selectedCount, lastHttp;
    private WebView web;
    private String extractor;
    private long navigationToken, startedElapsed;
    private boolean navigating, lastPageFailed, acceptedThisNavigation;
    private long refreshAttempt;
    private boolean refreshActive, inspectionPending;
    private long refreshDeadline;
    private int completedRefreshes, failedRefreshes, consecutiveFailures;
    private String signedMonitorUrl = MONITOR;
    private String documentState = "not read";
    private String mainDocumentUrl = "";
    private boolean rebuildBeforeRetry;
    private int lastNetworkError, tlsPageErrors, tlsResourceErrors;
    private String lastTlsHost = "none", lastTlsCause = "none", lastTlsScope = "none";
    private String lastTlsCertificateDates = "not supplied";
    private long lastTlsAt;
    private boolean masterPollBusy;

    public WebView peekWebView() { return web; }

    private final Runnable cycle = new Runnable() { public void run() { if(MonitorFilterUi.interacting(web)){handler.postDelayed(this,1000);return;} 
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if(!FleetRelay.configured(context)){
            if(!"SETUP_REQUIRED".equals(state))status("SETUP_REQUIRED","Choose Master or Patrol role to begin.");
            handler.postDelayed(this,1000L);return;
        }
        if(MasterFeedClient.subscriber(context)){
            if(!masterPollBusy && now>=nextCheckElapsed){
                FleetRelay.syncGuards(context,(changed,guardError)->{
                    if(changed){
                        latest.clear();present.clear();lastRead=lastReadElapsed=0;
                        status("GUARDS_SYNCED","Active guards updated automatically from the Master phone.");
                    }
                });
                masterPollBusy=true;nextCheckElapsed=now+INTERVAL;
                MasterFeedClient.poll(context,(snapshot,error)->{
                    masterPollBusy=false;
                    if(!running||!MasterFeedClient.subscriber(context))return;
                    if(error!=null){status("MASTER_RELAY_ERROR",error+" · previous activity is not live");nextCheckElapsed=SystemClock.elapsedRealtime()+BACKOFF_INTERVAL;return;}
                    if(snapshot==null||!snapshot.optBoolean("masterOnline",false)){
                        status("MASTER_STALE","Master phone not publishing a fresh SilverTrack snapshot. Previous activity retained but not live.");return;
                    }
                    acceptMasterSnapshot(snapshot);
                });
            }
            handler.postDelayed(this,1000L);return;
        }
        if (refreshActive && now >= refreshDeadline) {
            refreshActive = false; navigating = false; inspectionPending = false;
            navigationToken++; refreshAttempt++; failedRefreshes++; consecutiveFailures++;
            if (web != null) web.stopLoading();
            lastRefreshFailure = "Row-readiness timeout (25 seconds)";
            status("REFRESH_TIMEOUT", "No usable monitor rows arrived within 25 seconds. Keeping last known activity and backing off safely.");
            long retryDelay = consecutiveFailures >= 2 ? FAILURE_INTERVAL : BACKOFF_INTERVAL;
            if (consecutiveFailures >= 2 && !browserVisible) {
                consecutiveFailures = 0;
                resetRenderer();
            }
            nextCheckElapsed = now + retryDelay;
        }
        if (browserVisible) {
            // Do not fight the operator while they use Silvertracker's own pager.
            if (!refreshActive) {
                nextCheckElapsed = now + INTERVAL;
                if(web!=null && monitorPage(web.getUrl()) && !navigating && now>=nextHistoryRead){
                    nextHistoryRead=now+1800L;
                    scanVisiblePage();
                }
            }
        } else if (!refreshActive && now >= nextCheckElapsed) refreshMonitor();
        handler.postDelayed(this, 1000L);
    }};
    private void resetRenderer() {
        historyGate.reset();
        if (web != null) {
            CookieManager.getInstance().flush();
            if (web.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup)web.getParent()).removeView(web);
            web.destroy(); web = null;
        }
        webView();
        notifyChanged();
    }

    public PatrolEngine(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("patrol_settings", Context.MODE_PRIVATE);
        this.loginStore = new SilvertrackerLoginStore(this.context,this.prefs);
        this.autoLogin = new SilvertrackerAutoLogin(this.loginStore);
        this.voices = new VoiceManager(this.context, this.prefs);
        this.featherlessVoice = new FeatherlessVoice(this.context, this.prefs);
        this.checkpoints = new CheckpointTracker(this.context);
        this.lastRead = prefs.getLong("last_live_read_at",0L);
        if(this.lastRead < this.checkpoints.shiftStart()) this.lastRead = 0L;
        try (InputStream in = context.getAssets().open("extract.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            extractor = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { extractor = "JSON.stringify({schema:3,recognized:false})"; }
    }
    public boolean rememberSilvertrackerLogin(){ return loginStore.remember(); }
    public boolean hasRememberedSilvertrackerLogin(){ return loginStore.has(); }
    public void setRememberSilvertrackerLogin(boolean enabled){
        loginStore.setRemember(enabled);
        if(enabled && web!=null && trusted(web.getUrl()) && !monitorPage(web.getUrl())) autoLogin.onLoginPage(web,web.getUrl());
        notifyChanged();
    }
    public List<String> guards() {
        return Arrays.asList(prefs.getString("guard0","D.DEO"), prefs.getString("guard1","D.ROGERS1"), prefs.getString("guard2","T.MURD"));
    }
    public ZoneId zone() {
        try { return ZoneId.of(prefs.getString("zone", "Australia/Brisbane")); }
        catch (Exception e) { return ZoneId.of("Australia/Brisbane"); }
    }
    public int oldMinutes() { return prefs.getInt("oldMinutes", 15); }
    public boolean patrolOnly() { return false; }
    public List<Observation> recent() {
        ArrayList<Observation> list = new ArrayList<>(recentById.values());
        list.sort((a,b) -> FeedReducer.newer(a,b) ? -1 : FeedReducer.newer(b,a) ? 1 : 0);
        return list;
    }
    public List<Observation> recentForGuard(String guard) {
        String wanted = FeedReducer.key(guard);
        ArrayList<Observation> result = new ArrayList<>();
        for (Observation o : recent()) if (FeedReducer.key(o.guard).equals(wanted)) result.add(o);
        return result;
    }
    public void add(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void remove(Listener listener) { listeners.remove(listener); }
    public void notifyChanged() { for (Listener l : new ArrayList<>(listeners)) l.changed(); }
    public void status(String state, String detail) { this.state = state; this.detail = detail; notifyChanged(); }
    public static boolean trusted(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        boolean hostOk = host != null && (HOST.equalsIgnoreCase(host) || PROD_HOST.equalsIgnoreCase(host));
        return "https".equalsIgnoreCase(uri.getScheme()) && hostOk && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    public static boolean monitorPage(String url) {
        if (!trusted(url)) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.equalsIgnoreCase("/AdminCustomer/Issues/Monitor.aspx");
    }
    @SuppressLint("SetJavaScriptEnabled")
    public WebView webView() {
        if (web != null) return web;
        web = new WebView(context);
        MonitorFilterUi.prepare(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSafeBrowsingEnabled(true); s.setSaveFormData(false);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        web.setInitialScale(135);
        web.setHorizontalScrollBarEnabled(true);
        web.setVerticalScrollBarEnabled(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setOffscreenPreRaster(true);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Mobile ", " "));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(autoLogin.bridge(),"RoninSilverLogin");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                if (request.isForMainFrame() && !trusted(target)) {
                    Uri u = request.getUrl();
                    String scheme = u.getScheme();
                    if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                        status("BLOCKED", "Silvertracker redirected outside its approved hosts: " + (u.getHost() == null ? "unknown" : u.getHost()));
                        return true;
                    }
                }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view != web || !running) return;
                mainDocumentUrl = url;
                navigationToken++; navigating = true; startedElapsed = SystemClock.elapsedRealtime();
                refreshActive = running; refreshDeadline = startedElapsed + 25_000L;
                inspectionPending = false; lastPageFailed = false; acceptedThisNavigation = false; lastHttp = 0;
                documentState = "document loading";
                historyGate.reset();
                if(browserVisible)historyReadStatus="Waiting for Silvertracker page to load";
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                if (view != web || !running || !refreshActive || lastPageFailed || !monitorPage(url)) return;
                if (!url.equals(view.getUrl())) return;
                navigating = false;
                signedMonitorUrl = withoutRefreshNonce(url);
                final long token = navigationToken;
                handler.postDelayed(() -> inspect(token, false), 250L);
            }
            @Override public void onPageFinished(WebView view, String url) { MonitorFilterUi.pageReady(view); 
                if (view != web || !running || !refreshActive || lastPageFailed) return;
                if (url != null && !url.equals(view.getUrl())) return;
                navigating = false;
                CookieManager.getInstance().flush();
                if (!monitorPage(url)) {
                    refreshActive = false;
                    if(autoLogin.onLoginPage(view,url))
                        status("AUTO_LOGIN", "Silvertracker session expired. Re-authenticating securely on this device…");
                    else
                        status("SIGN_IN_REQUIRED", loginStore.remember() && !loginStore.has()
                            ? "Remember login is enabled. Sign in once in Silvertracker; Patrol Link will securely remember this device's login."
                            : "Silvertracker is not on Issue Monitor. Open Silvertracker to complete sign-in once.");
                    nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
                    return;
                }
                autoLogin.monitorReached();
                signedMonitorUrl = withoutRefreshNonce(url);
                final long token = navigationToken;
                handler.postDelayed(() -> inspect(token, false), 300L);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view != web || !running) return;
                if (error.getErrorCode() == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                    // Android delivers non-recoverable TLS failures here, not to onReceivedSslError.
                    if (request.isForMainFrame()) lastNetworkError = error.getErrorCode();
                    if (request.isForMainFrame() && lastPageFailed && "TLS_ERROR".equals(state)) return;
                    recordTls(request.getUrl().toString(), "TLS handshake failed", request.isForMainFrame(), null);
                    if (request.isForMainFrame())
                        fail("TLS_ERROR", "Secure connection failed. Retrying automatically every 30 seconds; keeping last known activity.");
                    else notifyChanged();
                    return;
                }
                if (request.isForMainFrame()) {
                    // Do not overwrite a more specific certificate failure with a generic cancellation.
                    if (lastPageFailed && "TLS_ERROR".equals(state)) return;
                    lastNetworkError = error.getErrorCode();
                    fail("OFFLINE", "Page load failed. Retrying without clearing the Silvertracker session.");
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view != web || !running) return;
                if (request.isForMainFrame()) {
                    lastHttp = response.getStatusCode();
                    if (lastHttp == 401 || lastHttp == 403) {
                        refreshActive = false; navigating = false; lastPageFailed = true;
                        status("SIGN_IN_REQUIRED", "Silvertracker requires sign-in. Open Silvertracker once.");
                    } else fail("HTTP_ERROR", "Server returned HTTP " + lastHttp + ". Retrying without clearing the session.");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
                // Always reject the insecure request. Recovery never means accepting its certificate.
                ssl.cancel();
                handleCertificateError(view, error);
            }
            @Override public void onFormResubmission(WebView view, Message dontResend, Message resend) { dontResend.sendToTarget(); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail ignored) {
                if (view != web) return true;
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
        });
        web.setWebChromeClient(new WebChromeClient());
        return web;
    }
    private void handleCertificateError(WebView view, SslError error) {
        if (view != web || !running) return;
        String failedUrl = error == null ? null : error.getUrl();
        boolean main = TlsPolicy.isMainDocument(failedUrl, mainDocumentUrl, view.getUrl());
        recordTls(failedUrl, certificateCause(error), main, error == null ? null : error.getCertificate());
        if (main) {
            fail("TLS_ERROR", "Secure connection failed (" + lastTlsCause + "). Retrying automatically every 30 seconds; keeping last known activity.");
        } else {
            // A rejected image/font/frame must not stop an otherwise readable, verified monitor.
            // If a required script fails, row readiness still prevents accepting an empty page shell.
            notifyChanged();
        }
    }
    private static String certificateCause(SslError error) {
        if (error == null) return "certificate error; reason unavailable";
        List<String> reasons = new ArrayList<>();
        if (error.hasError(SslError.SSL_NOTYETVALID)) reasons.add("certificate not yet valid");
        if (error.hasError(SslError.SSL_EXPIRED)) reasons.add("certificate expired");
        if (error.hasError(SslError.SSL_IDMISMATCH)) reasons.add("hostname mismatch");
        if (error.hasError(SslError.SSL_UNTRUSTED)) reasons.add("untrusted certificate chain");
        if (error.hasError(SslError.SSL_DATE_INVALID)) reasons.add("invalid certificate dates");
        if (error.hasError(SslError.SSL_INVALID)) reasons.add("invalid certificate");
        return reasons.isEmpty() ? "certificate error; reason unavailable" : String.join(", ", reasons);
    }
    private void recordTls(String url, String cause, boolean main, android.net.http.SslCertificate cert) {
        lastTlsAt = System.currentTimeMillis();
        lastTlsHost = TlsPolicy.safeHost(url); // Never record query strings, paths, usernames or cookies.
        lastTlsCause = cause;
        lastTlsScope = main ? "main document" : "page resource (blocked)";
        lastTlsCertificateDates = "not supplied";
        if (cert != null && cert.getValidNotBeforeDate() != null && cert.getValidNotAfterDate() != null)
            lastTlsCertificateDates = cert.getValidNotBeforeDate().toInstant().toString()
                    + " to " + cert.getValidNotAfterDate().toInstant().toString();
        if (main) tlsPageErrors++; else tlsResourceErrors++;
    }
    private void fail(String code, String message) {
        lastRefreshFailure = code + ": " + message;
        lastPageFailed = true; navigating = false; refreshActive = false; inspectionPending = false;
        navigationToken++; refreshAttempt++; failedRefreshes++; consecutiveFailures++;
        long retryDelay = "TLS_ERROR".equals(code) ? FAILURE_INTERVAL
                : (consecutiveFailures >= 2 ? FAILURE_INTERVAL : BACKOFF_INTERVAL);
        if (consecutiveFailures >= 2) rebuildBeforeRetry = true;
        nextCheckElapsed = SystemClock.elapsedRealtime() + retryDelay;
        status(code, message);
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
        if(MasterFeedClient.subscriber(context)){
            status("MASTER_FEED","This patrol phone receives activity from the Master; it does not sign in to SilverTrack.");
            return;
        }
        if (!running || refreshActive) return;
        requestFreshMonitor();
    }
    public void loadMonitor() { refreshMonitor(); }
    public void refreshMonitor() {
        if (!running) return;
        if(MasterFeedClient.subscriber(context))return;
        long now = SystemClock.elapsedRealtime();
        if (refreshActive) return;
        nextCheckElapsed = now + INTERVAL;
        if (!online()) { status("OFFLINE", "No network connection. Keeping the session and retrying."); nextCheckElapsed = now + BACKOFF_INTERVAL; return; }
        if ("BLOCKED".equals(state)) return;
        // A certificate/transport error page is not evidence that the authenticated session expired.
        boolean retryFailedDocument = TlsPolicy.retriesFailedDocument(state);
        if (!retryFailedDocument && web != null && web.getUrl() != null && !"about:blank".equals(web.getUrl()) && !monitorPage(web.getUrl())) {
            status("SIGN_IN_REQUIRED", "Open Silvertracker to complete sign-in once. Cookies have not been cleared.");
            return;
        }
        if (rebuildBeforeRetry) {
            rebuildBeforeRetry = false; consecutiveFailures = 0;
            resetRenderer(); // Recreate only the browser; the cookie store remains untouched.
        }
        requestFreshMonitor();
    }
    private void requestFreshMonitor() {
        if (!online()) {
            status("OFFLINE", "No network connection. Keeping the session and retrying.");
            nextCheckElapsed = SystemClock.elapsedRealtime() + BACKOFF_INTERVAL;
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
        mainDocumentUrl = url;
        view.loadUrl(url, headers);
    }
    public void pageHistory(boolean right) {
        if(!running || web==null || !browserVisible)return;
        if(!monitorPage(web.getUrl())){
            status("SIGN_IN_REQUIRED","Open Silvertracker and sign in once.");
            return;
        }
        if(navigating){
            status("CHECKING","Waiting for the current Silvertracker page to finish loading.");
            return;
        }

        final String wanted = right
                ? "['>','›','»','next','next page']"
                : "['<','‹','«','previous','prev','previous page']";
        String script =
                "(function(){"+
                "var wanted="+wanted+";"+
                "var els=[].slice.call(document.querySelectorAll('a,button,input[type=button],input[type=submit],span'));"+
                "for(var i=els.length-1;i>=0;i--){"+
                " var e=els[i];"+
                " var cs=window.getComputedStyle(e);"+
                " if(!cs||cs.display==='none'||cs.visibility==='hidden'||e.offsetParent===null)continue;"+
                " if(e.disabled||e.getAttribute('aria-disabled')==='true'||((' '+e.className+' ').toLowerCase().indexOf(' disabled ')>=0))continue;"+
                " var t=((e.innerText||e.value||e.getAttribute('aria-label')||e.title||'')+'').replace(/\\s+/g,' ').trim().toLowerCase();"+
                " if(wanted.indexOf(t)>=0){e.click();return 'CLICKED:'+t;}"+
                "}"+
                "return 'NOT_FOUND';"+
                "})()";

        status("HISTORY_NAV","Moving to another Silvertracker page…");
        web.evaluateJavascript(script,result->{
            if(result!=null && result.contains("NOT_FOUND")){
                status("HISTORY_NAV","Silvertracker's page arrow was not found on this page. You can still use its own < and > controls below.");
                return;
            }
            handler.postDelayed(()->{
                if(browserVisible && running && !navigating && !refreshActive)scanVisiblePage();
            },1800L);
        });
    }

    public void scanVisiblePage() {
        if(!running)return;
        if(web==null){openMonitor();return;}
        if(!monitorPage(web.getUrl())){
            status("SIGN_IN_REQUIRED","Open Silvertracker and sign in once.");
            return;
        }
        if(navigating){status("CHECKING","Waiting for this Silvertracker page to finish loading.");return;}
        if(refreshActive){
            historyReadStatus="Reading / waiting for page changes; repeated taps are combined";
            notifyChanged();return;
        }
        long now=SystemClock.elapsedRealtime();
        refreshAttempt++; navigationToken++;
        refreshActive=true; inspectionPending=false; acceptedThisNavigation=false; lastPageFailed=false; navigating=false;
        startedElapsed=now; refreshDeadline=now+10_000L;
        documentState="manual visible-page scan";
        historyReadStatus="Reading visible page";
        notifyChanged();
        final long token=navigationToken;
        handler.post(()->inspect(token,false));
    }

    public void resumeLiveFeed() {
        browserVisible=false;
        historyGate.reset();
        if(!running)return;
        refreshActive=false; inspectionPending=false; navigating=false; acceptedThisNavigation=false; lastPageFailed=false;
        navigationToken++; signedMonitorUrl=MONITOR;
        nextCheckElapsed=SystemClock.elapsedRealtime();
        handler.post(this::refreshMonitor);
    }

    public void inspectNow() {
        if(browserVisible){scanVisiblePage();return;}
        if (refreshActive && web != null && !navigating) inspect(navigationToken, false);
        else if (!refreshActive) refreshMonitor();
    }
    private void inspectAgain(long token) {
        if (running && refreshActive && token == navigationToken && !lastPageFailed)
            handler.postDelayed(() -> inspect(token, false), 700L);
    }
    private void inspect(long token, boolean lastTry) {
        if (!running || !refreshActive || inspectionPending || navigating || web == null || token != navigationToken || lastPageFailed || acceptedThisNavigation || !monitorPage(web.getUrl())) return;
        String script = extractor.replace("__GUARD_IDS__", new JSONArray(guards()).toString());
        inspectionPending = true;
        web.evaluateJavascript(script, encoded -> {
            if (!running || !refreshActive || web == null || token != navigationToken || lastPageFailed) return;
            inspectionPending = false;
            try {
                Object decoded = new JSONTokener(encoded).nextValue();
                if (!(decoded instanceof String)) throw new JSONException("No reader response");
                JSONObject data = new JSONObject((String)decoded);
                if (data.optBoolean("login")) {
                    refreshActive = false;
                    status("SIGN_IN_REQUIRED", "Silvertracker invalidated the web session. Open Silvertracker and sign in once.");
                    return;
                }
                rowsFound = data.optInt("totalRows", 0);
                JSONArray entries = data.optJSONArray(browserVisible?"allRows":"selectedRows");
                if(entries==null)entries=data.optJSONArray("selectedRows");
                documentState = data.optString("ready", "unknown") + "; " + data.optString("mode", "unknown")
                        + "; busy=" + data.optBoolean("loading");
                if (!SnapshotPolicy.usable(rowsFound, data.optBoolean("recognized"),
                        data.optBoolean("loading"), data.optBoolean("emptyConfirmed"))) {
                    if(browserVisible){historyGate.reset();historyReadStatus="Page is still loading; waiting for rows";notifyChanged();}
                    inspectAgain(token);
                    return;
                }
                String historyFingerprint=entries==null?"[]":entries.toString();
                if(browserVisible && !historyGate.ready(historyFingerprint,false,SystemClock.elapsedRealtime())){
                    historyReadStatus="Checking that the new page has finished changing";notifyChanged();
                    inspectAgain(token);return;
                }
                GuardDirectory.remember(prefs, data.optJSONArray("allRows"));
                SilverTrackVehicleCollector.collect(context,web,data.optJSONArray("allRows"),zone());
                if(!browserVisible && MasterFeedClient.master(context)){
                    MasterFeedClient.publish(context,data.optJSONArray("allRows"));
                    FleetRelay.publishGuards(context,guards());
                }
                List<Observation> observations = new ArrayList<>();
                long now = System.currentTimeMillis();
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.getJSONObject(i);
                    observations.add(new Observation(e.optString("id"), e.optString("guard"), e.optString("property"),
                            e.optString("issue"), e.optString("date"), TimeParser.parse(e.optString("date"), zone(), now)));
                }
                List<Observation> selected = FeedReducer.selected(observations, guards());
                voices.speechSettings.remember(selected);
                if(!browserVisible)checkpoints.observe(selected);

                if(browserVisible) {
                    historyAudit.accept(historyFingerprint,observations,guards(),checkpoints,
                            rowsFound,data.optInt("unreadRows",0),now);
                    historyReadStatus=historyAudit.needsCheck()?"Page read — some rows need checking":"Scan complete — all readable rows checked";
                    nextHistoryRead=SystemClock.elapsedRealtime()+1800L;
                    selectedCount=selected.size();
                    acceptedThisNavigation=true; refreshActive=false; navigating=false; inspectionPending=false;
                    completedRefreshes++; consecutiveFailures=0; rebuildBeforeRetry=false;
                    CookieManager.getInstance().flush();
                    documentState="history page counted into shift tally";
                    nextCheckElapsed=SystemClock.elapsedRealtime()+INTERVAL;
                    status("HISTORY_READ", historyReadStatus);
                    return;
                }

                Map<String, Observation> batch = FeedReducer.latest(selected, guards(), false);
                boolean hadBaseline = lastRead > 0;
                List<Observation> announce = speechLedger.collect(selected, hadBaseline, lastRead, now);
                present.clear(); present.addAll(batch.keySet());
                for (Observation o : selected) recentById.put(o.id, o);
                trimRecent();
                for (Map.Entry<String, Observation> e : batch.entrySet()) {
                    Observation previous = latest.get(e.getKey());
                    if (previous == null || previous.id.equals(e.getValue().id) || FeedReducer.newer(e.getValue(), previous)) latest.put(e.getKey(), e.getValue());
                }
                selectedCount = batch.size(); lastRead = now; lastReadElapsed = SystemClock.elapsedRealtime();
                prefs.edit().putLong("last_live_read_at",now).apply();
                acceptedThisNavigation = true; refreshActive = false;
                completedRefreshes++; consecutiveFailures = 0; rebuildBeforeRetry = false;
                nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
                CookieManager.getInstance().flush();
                status("READ_OK", selected.size() + " recent activities read for the selected guards.");
                if (running && prefs.getBoolean("voice", true) && !welcomeSpokenThisSession) {
                    if (featherlessVoice.selected()) { featherlessVoice.speak("Good evening, Tristan. Let's have a good shift."); welcomeSpokenThisSession=true; }
                    else if (voices.welcome()) welcomeSpokenThisSession=true;
                }
                if (running && prefs.getBoolean("voice", true)) for (Observation o : announce) {
                    if (featherlessVoice.selected()) featherlessVoice.speak(voices.speechSettings.format(o)); else voices.speak(o);
                }
            } catch (Exception e) {
                documentState = "reader response unavailable";
                inspectAgain(token);
            }
        });
    }
    private void acceptMasterSnapshot(JSONObject data){
        try{
            JSONArray rows=data.optJSONArray("rows");if(rows==null)throw new JSONException("No relay rows");
            GuardDirectory.remember(prefs,rows);
            List<Observation> all=new ArrayList<>();long now=System.currentTimeMillis();
            for(int i=0;i<rows.length();i++){
                JSONObject e=rows.optJSONObject(i);if(e==null)continue;
                all.add(new Observation(e.optString("id"),e.optString("guard"),e.optString("property"),
                      e.optString("issue"),e.optString("date"),TimeParser.parse(e.optString("date"),zone(),now)));
            }
            List<Observation> selected=FeedReducer.selected(all,guards());
            Map<String,Observation> batch=FeedReducer.latest(selected,guards(),false);
            List<Observation> announce=speechLedger.collect(selected,lastRead>0,lastRead,now);
            checkpoints.observe(selected);voices.speechSettings.remember(selected);
            present.clear();present.addAll(batch.keySet());
            for(Observation o:selected)recentById.put(o.id,o);
            trimRecent();
            for(Map.Entry<String,Observation> e:batch.entrySet()){
                Observation previous=latest.get(e.getKey());
                if(previous==null||previous.id.equals(e.getValue().id)||FeedReducer.newer(e.getValue(),previous))latest.put(e.getKey(),e.getValue());
            }
            selectedCount=batch.size();rowsFound=rows.length();lastRead=now;lastReadElapsed=SystemClock.elapsedRealtime();
            prefs.edit().putLong("last_live_read_at",now).apply();
            status("READ_OK","Read-only Master feed · "+rows.length()+" activity rows · no SilverTrack login on this patrol phone.");
            if(running&&prefs.getBoolean("voice",true))for(Observation o:announce){
                if(featherlessVoice.selected())featherlessVoice.speak(voices.speechSettings.format(o));else voices.speak(o);
            }
        }catch(Exception e){status("MASTER_RELAY_ERROR","Unable to interpret Master activity snapshot. Last known state retained.");}
    }
    public void changeFeedMode(){
        refreshActive=false;inspectionPending=false;navigating=false;navigationToken++;
        nextCheckElapsed=SystemClock.elapsedRealtime();lastReadElapsed=0;
        if(MasterFeedClient.subscriber(context)){
            if(web!=null)web.stopLoading();
            if(!FleetRelay.master(context)){
                loginStore.clear();
                CookieManager.getInstance().removeAllCookies(done->CookieManager.getInstance().flush());
            }
            status("MASTER_FEED","Paired patrol mode. SilverTrack login is not required on this phone.");
        }else if(MasterFeedClient.master(context)){
            FleetRelay.publishGuards(context,guards());
            status("MASTER_MODE","Master reads SilverTrack and relays authorised activity plus the active guard list.");
            if(running)openMonitor();
        }else status("SETUP_REQUIRED","Select a device role.");
    }
    private void trimRecent() {
        if (recentById.size() <= RECENT_LIMIT) return;
        List<Observation> list = recent();
        recentById.clear();
        for (int i=0; i<Math.min(RECENT_LIMIT, list.size()); i++) recentById.put(list.get(i).id, list.get(i));
    }
    public void start() {
        if (running) return;
        running = true; handler.removeCallbacks(cycle);
        nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
        if(!FleetRelay.configured(context))status("SETUP_REQUIRED","Select the device role to begin.");
        else if(MasterFeedClient.subscriber(context))status("MASTER_FEED","Paired patrol uses Master activity, not direct SilverTrack sign-in.");
        else if(web!=null&&monitorPage(web.getUrl()))refreshMonitor();else openMonitor();
        handler.post(cycle);
    }
    public void stopRuntime(String code, String reason) {
        running = false; handler.removeCallbacks(cycle); navigationToken++; refreshAttempt++;
        refreshActive = false; inspectionPending = false;
        if (web != null) web.stopLoading(); navigating = false;
        voices.stop();
        featherlessVoice.stop();
        status(code, reason);
    }
    public void exitAndClear(Runnable after) {
        stopRuntime("EXITED", "Signed out by Exit.");
        latest.clear(); present.clear(); recentById.clear(); speechLedger.clear(); welcomeSpokenThisSession = false; lastRead = lastReadElapsed = 0;
        prefs.edit().remove("last_live_read_at").apply();
        loginStore.clear();
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            cm.flush(); WebStorage.getInstance().deleteAllData();
            if (web != null) { web.clearCache(true); web.clearHistory(); web.loadUrl("about:blank"); }
            if (after != null) after.run();
        });
    }
    public String sourceStatus() {
        if (!running) return state.equals("EXITED") ? "SIGNED OUT" : "MONITOR RESTARTING";
        if (state.equals("SIGN_IN_REQUIRED")) return "SIGN-IN REQUIRED";
        if (state.equals("TLS_ERROR")) return "TLS ERROR · RETRYING";
        if (!online()) return "OFFLINE · LAST KNOWN";
        if (!state.equals("READ_OK") && !state.equals("CHECKING")) return state.replace('_',' ');
        if (lastReadElapsed == 0) return "CONNECTING";
        if (SystemClock.elapsedRealtime() - lastReadElapsed > 75_000L) return "STALE FEED · RETRYING";
        return state.equals("CHECKING") ? "REFRESHING · LAST KNOWN" : "CONNECTED · FEED CHECKED";
    }
    public boolean healthy() { return running && online() && state.equals("READ_OK") && lastReadElapsed > 0 && SystemClock.elapsedRealtime() - lastReadElapsed <= 75_000L; }
    public boolean online() {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    public String diagnostics() {
        android.content.pm.PackageInfo pkg = WebView.getCurrentWebViewPackage();
        return "TRL Patrol Link " + BuildConfig.VERSION_NAME + "\nAndroid API: " + Build.VERSION.SDK_INT +
                "\nReader schema: 4\nWebView: " + (pkg == null ? "unavailable" : pkg.versionName) +
                "\nState: " + state + "\nMonitoring: " + running + "\nNetwork available: " + online() +
                "\nSource: " + HOST + "\nAt monitor page: " + (web != null && monitorPage(web.getUrl())) +
                "\nRefresh method: attached browser + uncached GET + row-readiness deadline" +
                "\nRefresh cadence: adaptive 10s normal / 15s backoff / 30s repeated failure; paused during manual Silvertracker paging" +
                "\nBrowser attached: " + (web != null && web.isAttachedToWindow()) +
                "\nBrowser size: " + (web == null ? "none" : web.getWidth() + "x" + web.getHeight()) +
                "\nRefresh attempts/completed/failed: " + refreshAttempt + "/" + completedRefreshes + "/" + failedRefreshes +
                "\nRequest active: " + refreshActive + "\nDocument state: " + documentState +
                "\nTable rows found: " + rowsFound + "\nMatched guards: " + selectedCount + "\nRecent activity cache: " + recentById.size() +
                "\nLast TLS host: " + lastTlsHost + "\nLast TLS cause: " + lastTlsCause +
                "\nLast TLS scope: " + lastTlsScope + "\nTLS errors (page/resource): " + tlsPageErrors + "/" + tlsResourceErrors +
                "\nLast TLS time (UTC): " + (lastTlsAt == 0 ? "none" : java.time.Instant.ofEpochMilli(lastTlsAt).toString()) +
                "\nCertificate validity (UTC): " + lastTlsCertificateDates +
                "\nDevice time (UTC): " + java.time.Instant.now().toString() +
                "\nLast network error code: " + lastNetworkError +
                "\nLast HTTP error: " + lastHttp + "\nLast read: " + (lastRead == 0 ? "none" : TimeParser.age(lastRead, System.currentTimeMillis())) +
                "\nRemember Silvertracker login: " + loginStore.remember() + " · stored credentials: " + loginStore.has() +
                "\nFleet role: " + FleetRelay.label(context) + " · on duty: " + FleetRelay.onDuty(context) +
                "\nLast refresh failure: " + lastRefreshFailure + "\n" + voices.diagnostics() + "\n" + featherlessVoice.diagnostics() +
                "\nNo passwords, cookies or page HTML are included.";
    }

}
