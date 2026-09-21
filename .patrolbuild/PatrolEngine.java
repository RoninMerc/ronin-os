package au.com.roningroup.patrollink;

import android.annotation.SuppressLint;
import android.content.*;
import android.graphics.Bitmap;
import android.net.*;
import android.net.http.SslError;
import android.os.*;
import android.speech.tts.*;
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
    public static final long INTERVAL = 30_000L;
    private static final int RECENT_LIMIT = 60;
    public interface Listener { void changed(); }
    public final Context context;
    public final SharedPreferences prefs;
    public final Handler handler = new Handler(Looper.getMainLooper());
    public final Map<String, Observation> latest = new LinkedHashMap<>();
    public final Set<String> present = new HashSet<>();
    private final LinkedHashMap<String, Observation> recentById = new LinkedHashMap<>();
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

    private TextToSpeech tts;
    private boolean voiceReady;
    private final Runnable cycle = new Runnable() { public void run() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= nextCheckElapsed) {
            if (!(navigating && now - startedElapsed < 25_000)) refreshMonitor();
            nextCheckElapsed = now + INTERVAL;
        }
        handler.postDelayed(this, 1000);
    }};

    public PatrolEngine(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("patrol_settings", Context.MODE_PRIVATE);
        try (InputStream in = context.getAssets().open("extract.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            extractor = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { extractor = "JSON.stringify({schema:3,recognized:false})"; }
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
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSafeBrowsingEnabled(true); s.setSaveFormData(false);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Mobile ", " "));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
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
                navigationToken++; navigating = true; startedElapsed = SystemClock.elapsedRealtime();
                lastPageFailed = false; acceptedThisNavigation = false; lastHttp = 0;
            }
            @Override public void onPageFinished(WebView view, String url) {
                navigating = false; CookieManager.getInstance().flush();
                if (lastPageFailed) return;
                if (!monitorPage(url)) {
                    status("SIGN_IN_REQUIRED", "Open Silvertracker and sign in once. Automatic refresh is paused until Issue Monitor returns.");
                    nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
                    return;
                }
                final long token = navigationToken;
                for (int wait : new int[]{700, 1600, 3000, 5500, 9000}) handler.postDelayed(() -> inspect(token, wait == 9000), wait);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) fail("OFFLINE", "Page load failed. Retrying without clearing the Silvertracker session.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    lastHttp = response.getStatusCode();
                    if (lastHttp == 401 || lastHttp == 403) {
                        status("SIGN_IN_REQUIRED", "Silvertracker invalidated the web session. Open Silvertracker and sign in once.");
                    } else fail("HTTP_ERROR", "Server returned HTTP " + lastHttp + ". Retrying without clearing the session.");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
                ssl.cancel(); fail("TLS_ERROR", "Secure connection failed. Certificate errors are never bypassed.");
            }
            @Override public void onFormResubmission(WebView view, Message dontResend, Message resend) { resend.sendToTarget(); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                fail("WEBVIEW_ERROR", "Web renderer restarted. Patrol Link will reconnect without deliberately signing out.");
                if (view.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup)view.getParent()).removeView(view);
                view.destroy(); web = null;
                if (running) handler.postDelayed(PatrolEngine.this::openMonitor, 1200);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        return web;
    }
    private void fail(String code, String message) {
        lastPageFailed = true; navigating = false; status(code, message);
        if (running && !"TLS_ERROR".equals(code) && !"BLOCKED".equals(code)) {
            handler.postDelayed(() -> {
                if (!running) return;
                if (web != null && monitorPage(web.getUrl())) refreshMonitor(); else if (!"SIGN_IN_REQUIRED".equals(state)) openMonitor();
            }, 5000);
        }
    }
    public void openMonitor() {
        if (!running) return;
        if (!online()) { status("OFFLINE", "No network connection. Retrying automatically."); nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL; return; }
        if (web != null && navigating) return;
        status("CONNECTING", "Opening Silvertracker Issue Monitor…");
        webView().loadUrl(MONITOR);
        nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
    }
    public void loadMonitor() { refreshMonitor(); }
    public void refreshMonitor() {
        if (!running) return;
        if (!online()) { status("OFFLINE", "No network connection. Retrying automatically."); nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL; return; }
        if (web == null) { openMonitor(); return; }
        if (navigating && SystemClock.elapsedRealtime() - startedElapsed < 25_000L) return;
        if (!monitorPage(web.getUrl())) {
            status("SIGN_IN_REQUIRED", "Open Silvertracker and sign in once. Automatic refresh is paused until Issue Monitor returns.");
            nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
            return;
        }
        final long attempt = ++refreshAttempt;
        status("CHECKING", "Requesting a fresh Issue Monitor update…");
        String js = "(function(){" +
                "window.__roninRefresh=window.__roninRefresh||{done:0,hooked:false};" +
                "try{if(!window.__roninRefresh.hooked&&window.Sys&&Sys.WebForms&&Sys.WebForms.PageRequestManager){" +
                "var prm=Sys.WebForms.PageRequestManager.getInstance();prm.add_endRequest(function(){window.__roninRefresh.done++;});window.__roninRefresh.hooked=true;}}catch(e){}" +
                "function vis(e){if(!e)return false;var s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden'&&e.offsetParent!==null;}" +
                "var before=window.__roninRefresh.done||0;" +
                "var a=document.querySelectorAll('button,input[type=button],input[type=submit],a');" +
                "for(var i=0;i<a.length;i++){var e=a[i];if(!vis(e))continue;var t=((e.innerText||e.textContent||e.value||'')+'').replace(/\\s+/g,' ').trim();var id=((e.id||'')+' '+(e.name||'')).toLowerCase();" +
                "if(/^update$/i.test(t)||id.indexOf('update')>=0){" +
                "try{if(e.form&&typeof e.form.requestSubmit==='function'&&(e.type==='submit'||e.tagName==='INPUT'))e.form.requestSubmit(e);else e.click();}" +
                "catch(x){try{e.click();}catch(y){}}" +
                "return JSON.stringify({clicked:true,before:before});}}" +
                "return JSON.stringify({clicked:false,before:before});})();";
        web.evaluateJavascript(js, result -> {
            if (!running || web == null || attempt != refreshAttempt) return;
            long before = parseRefreshCounter(result);
            if (result == null || !result.contains("\\"clicked\\":true")) {
                forceVerifiedReload(attempt);
                return;
            }
            waitForRefreshCompletion(attempt, before, 0);
        });
        nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
    }
    private long parseRefreshCounter(String encoded) {
        try {
            Object outer = new JSONTokener(encoded).nextValue();
            if (!(outer instanceof String)) return 0;
            JSONObject o = new JSONObject((String)outer);
            return o.optLong("before",0);
        } catch(Exception e) { return 0; }
    }
    private void waitForRefreshCompletion(long attempt, long before, int checks) {
        if (!running || web == null || attempt != refreshAttempt) return;
        if (navigating) {
            handler.postDelayed(() -> waitForRefreshCompletion(attempt,before,checks+1), 700);
            return;
        }
        String js = "(function(){var r=window.__roninRefresh||{done:0};return String(r.done||0);})();";
        web.evaluateJavascript(js, value -> {
            if (!running || web == null || attempt != refreshAttempt) return;
            long done = 0;
            try { done = Long.parseLong(String.valueOf(value).replace("\\"","").trim()); } catch(Exception ignored) {}
            if (done > before) {
                handler.postDelayed(() -> {
                    if (!running || web == null || attempt != refreshAttempt) return;
                    acceptedThisNavigation = false;
                    inspect(navigationToken, true);
                }, 500);
                return;
            }
            if (checks >= 10) {
                forceVerifiedReload(attempt);
            } else {
                handler.postDelayed(() -> waitForRefreshCompletion(attempt,before,checks+1), 800);
            }
        });
    }
    private void forceVerifiedReload(long attempt) {
        if (!running || web == null || attempt != refreshAttempt) return;
        status("CHECKING", "Silvertracker did not confirm the in-page update. Reloading the signed-in monitor…");
        acceptedThisNavigation = false;
        web.reload();
    }
    private void scheduleCurrentInspection(long delay) {
        handler.postDelayed(() -> {
            if (!running || web == null || navigating || !monitorPage(web.getUrl())) return;
            acceptedThisNavigation = false;
            inspect(navigationToken, true);
        }, delay);
    }
    public void inspectNow() {
        if (web == null) { openMonitor(); return; }
        if (!monitorPage(web.getUrl())) { status("SIGN_IN_REQUIRED", "Open Silvertracker and sign in once."); return; }
        if (navigating) return;
        acceptedThisNavigation = false; inspect(navigationToken, true);
    }
    private void inspect(long token, boolean lastTry) {
        if (web == null || token != navigationToken || lastPageFailed || acceptedThisNavigation || !monitorPage(web.getUrl())) return;
        String script = extractor.replace("__GUARD_IDS__", new JSONArray(guards()).toString());
        web.evaluateJavascript(script, encoded -> {
            if (web == null || token != navigationToken || lastPageFailed) return;
            try {
                Object decoded = new JSONTokener(encoded).nextValue();
                if (!(decoded instanceof String)) throw new JSONException("No reader response");
                JSONObject data = new JSONObject((String)decoded);
                if (data.optBoolean("login")) {
                    status("SIGN_IN_REQUIRED", "Silvertracker invalidated the web session. Open Silvertracker and sign in once.");
                    return;
                }
                rowsFound = data.optInt("totalRows", 0);
                JSONArray entries = data.optJSONArray("selectedRows");
                if (data.optBoolean("loading") || !data.optBoolean("recognized") || (rowsFound == 0 && !lastTry)) {
                    if (lastTry) status("READER_CHECK", "Issue Monitor is still updating. The next 30-second refresh will try again.");
                    return;
                }
                List<Observation> observations = new ArrayList<>();
                long now = System.currentTimeMillis();
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.getJSONObject(i);
                    observations.add(new Observation(e.optString("id"), e.optString("guard"), e.optString("property"),
                            e.optString("issue"), e.optString("date"), TimeParser.parse(e.optString("date"), zone(), now)));
                }
                List<Observation> selected = FeedReducer.selected(observations, guards());
                Map<String, Observation> batch = FeedReducer.latest(selected, guards(), false);
                boolean hadBaseline = lastRead > 0;
                List<Observation> announce = new ArrayList<>();
                present.clear(); present.addAll(batch.keySet());
                for (Observation o : selected) {
                    boolean unseen = !recentById.containsKey(o.id);
                    if (unseen) {
                        recentById.put(o.id, o);
                        if (hadBaseline && o.recordedAt > 0 && now - o.recordedAt <= 300_000L) announce.add(o);
                    }
                }
                trimRecent();
                for (Map.Entry<String, Observation> e : batch.entrySet()) {
                    Observation previous = latest.get(e.getKey());
                    if (previous == null || FeedReducer.newer(e.getValue(), previous)) latest.put(e.getKey(), e.getValue());
                }
                selectedCount = batch.size(); lastRead = now; lastReadElapsed = SystemClock.elapsedRealtime();
                acceptedThisNavigation = true;
                status("READ_OK", selected.size() + " recent activities read for the selected guards.");
                if (running && prefs.getBoolean("voice", false)) for (Observation o : announce) speak(o);
            } catch (Exception e) {
                if (lastTry) status("READER_CHECK", "The Issue Monitor format was not readable on this refresh. The session has not been cleared.");
            }
        });
    }
    private void trimRecent() {
        if (recentById.size() <= RECENT_LIMIT) return;
        List<Observation> list = recent();
        recentById.clear();
        for (int i=0; i<Math.min(RECENT_LIMIT, list.size()); i++) recentById.put(list.get(i).id, list.get(i));
    }
    public void start() {
        if (running) return;
        running = true; handler.removeCallbacks(cycle); initVoice();
        nextCheckElapsed = SystemClock.elapsedRealtime() + INTERVAL;
        if (web != null && monitorPage(web.getUrl())) refreshMonitor(); else openMonitor();
        handler.post(cycle);
    }
    public void stopRuntime(String code, String reason) {
        running = false; handler.removeCallbacks(cycle); navigationToken++;
        if (web != null) web.stopLoading(); navigating = false;
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; voiceReady = false; }
        status(code, reason);
    }
    public void exitAndClear(Runnable after) {
        stopRuntime("EXITED", "Signed out by Exit.");
        latest.clear(); present.clear(); recentById.clear(); lastRead = lastReadElapsed = 0;
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
        if (!online()) return "OFFLINE · LAST KNOWN";
        if (!state.equals("READ_OK") && !state.equals("CHECKING")) return state.replace('_',' ');
        if (lastReadElapsed == 0) return "CONNECTING";
        if (SystemClock.elapsedRealtime() - lastReadElapsed > 75_000L) return "STALE FEED · RETRYING";
        return state.equals("CHECKING") ? "REFRESHING · LAST KNOWN" : "SIGNED IN · LIVE FEED";
    }
    public boolean healthy() { return running && online() && state.equals("READ_OK") && lastReadElapsed > 0 && SystemClock.elapsedRealtime() - lastReadElapsed <= 75_000L; }
    public boolean online() {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    public String diagnostics() {
        android.content.pm.PackageInfo pkg = WebView.getCurrentWebViewPackage();
        return "Ronin Patrol Link " + BuildConfig.VERSION_NAME + "\nAndroid API: " + Build.VERSION.SDK_INT +
                "\nReader schema: 3\nWebView: " + (pkg == null ? "unavailable" : pkg.versionName) +
                "\nState: " + state + "\nMonitoring: " + running + "\nNetwork available: " + online() +
                "\nSource: " + HOST + "\nAt monitor page: " + (web != null && monitorPage(web.getUrl())) +
                "\nRefresh method: confirmed in-page Update + signed-session reload fallback" +
                "\nTable rows found: " + rowsFound + "\nMatched guards: " + selectedCount + "\nRecent activity cache: " + recentById.size() +
                "\nLast HTTP error: " + lastHttp + "\nLast read: " + (lastRead == 0 ? "none" : TimeParser.age(lastRead, System.currentTimeMillis())) +
                "\nCar host connected: " + carConnected + "\nOffline voice ready: " + voiceReady +
                "\nNo passwords, cookies or page HTML are included.";
    }
    private void initVoice() {
        if (!prefs.getBoolean("voice", false) || tts != null) return;
        tts = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) return;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) for (Voice voice : voices) {
                if (!voice.isNetworkConnectionRequired() && voice.getLocale().getLanguage().equals("en") &&
                    (voice.getFeatures() == null || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                    tts.setVoice(voice); voiceReady = true; break;
                }
            }
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()); notifyChanged();
        });
    }
    private void speak(Observation o) {
        if (tts == null || !voiceReady) return;
        tts.speak("New Silvertracker activity. " + o.guard + ". " + o.activity(), TextToSpeech.QUEUE_ADD, null, "patrol-" + o.id);
    }
}
