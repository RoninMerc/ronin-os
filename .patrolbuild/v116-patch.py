"""Apply a narrow TLS-recovery repair to the fully reconstructed v1.1.5 source."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
java = root / 'app/src/main/java/au/com/roningroup/patrollink'
p = java / 'PatrolEngine.java'
s = p.read_text()
def replace(old, new):
    global s
    if s.count(old) != 1:
        raise SystemExit(f'Expected exactly one patch anchor: {old[:100]!r}, found {s.count(old)}')
    s = s.replace(old, new, 1)
replace('    private String documentState = "not read";', '''    private String documentState = "not read";
    private String mainDocumentUrl = "";
    private boolean rebuildBeforeRetry;
    private int lastNetworkError, tlsPageErrors, tlsResourceErrors;
    private String lastTlsHost = "none", lastTlsCause = "none", lastTlsScope = "none";
    private String lastTlsCertificateDates = "not supplied";
    private long lastTlsAt;
''')
replace('''            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                navigationToken++;''', '''            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view != web || !running) return;
                mainDocumentUrl = url;
                navigationToken++;''')
replace('''            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) fail("OFFLINE", "Page load failed. Retrying without clearing the Silvertracker session.");
            }''', '''            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
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
            }''')
replace('''            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {''', '''            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view != web || !running) return;
                if (request.isForMainFrame()) {''')
replace('''                ssl.cancel(); fail("TLS_ERROR", "Secure connection failed. Certificate errors are never bypassed.");''', '''                // Always reject the insecure request. Recovery never means accepting its certificate.
                ssl.cancel();
                handleCertificateError(view, error);''')
replace('''            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail ignored) {
                refreshActive''', '''            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail ignored) {
                if (view != web) return true;
                refreshActive''')
replace('''    private void fail(String code, String message) {
        lastPageFailed = true; navigating = false; refreshActive = false; inspectionPending = false;
        navigationToken++; refreshAttempt++; failedRefreshes++;
        status(code, message);
        nextCheckElapsed = SystemClock.elapsedRealtime() + 5000L;
    }''', '''    private void handleCertificateError(WebView view, SslError error) {
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
        lastPageFailed = true; navigating = false; refreshActive = false; inspectionPending = false;
        navigationToken++; refreshAttempt++; failedRefreshes++; consecutiveFailures++;
        if (consecutiveFailures >= 2) rebuildBeforeRetry = true;
        nextCheckElapsed = SystemClock.elapsedRealtime() + ("TLS_ERROR".equals(code) ? INTERVAL : 5000L);
        status(code, message);
    }''')
replace('''        if ("TLS_ERROR".equals(state) || "BLOCKED".equals(state)) return;
        if (web != null && web.getUrl() != null && !"about:blank".equals(web.getUrl()) && !monitorPage(web.getUrl())) {''', '''        if ("BLOCKED".equals(state)) return;
        // A certificate/transport error page is not evidence that the authenticated session expired.
        boolean retryFailedDocument = TlsPolicy.retriesFailedDocument(state);
        if (!retryFailedDocument && web != null && web.getUrl() != null && !"about:blank".equals(web.getUrl()) && !monitorPage(web.getUrl())) {''')
replace('''        requestFreshMonitor();
    }
    private void requestFreshMonitor()''', '''        if (rebuildBeforeRetry) {
            rebuildBeforeRetry = false; consecutiveFailures = 0;
            resetRenderer(); // Recreate only the browser; the cookie store remains untouched.
        }
        requestFreshMonitor();
    }
    private void requestFreshMonitor()''')
replace('''        view.loadUrl(url, headers);''', '''        mainDocumentUrl = url;
        view.loadUrl(url, headers);''')
replace('''                completedRefreshes++; consecutiveFailures = 0;''', '''                completedRefreshes++; consecutiveFailures = 0; rebuildBeforeRetry = false;''')
replace('''        if (state.equals("SIGN_IN_REQUIRED")) return "SIGN-IN REQUIRED";''', '''        if (state.equals("SIGN_IN_REQUIRED")) return "SIGN-IN REQUIRED";
        if (state.equals("TLS_ERROR")) return "TLS ERROR · RETRYING";''')
replace('''                "\\nLast HTTP error: " + lastHttp''', '''                "\\nLast TLS host: " + lastTlsHost + "\\nLast TLS cause: " + lastTlsCause +
                "\\nLast TLS scope: " + lastTlsScope + "\\nTLS errors (page/resource): " + tlsPageErrors + "/" + tlsResourceErrors +
                "\\nLast TLS time (UTC): " + (lastTlsAt == 0 ? "none" : java.time.Instant.ofEpochMilli(lastTlsAt).toString()) +
                "\\nCertificate validity (UTC): " + lastTlsCertificateDates +
                "\\nDevice time (UTC): " + java.time.Instant.now().toString() +
                "\\nLast network error code: " + lastNetworkError +
                "\\nLast HTTP error: " + lastHttp''')
if 'ssl.proceed(' in s or '"TLS_ERROR".equals(state) || "BLOCKED"' in s:
    raise SystemExit('TLS safety/recovery regression')
p.write_text(s)
p = root / 'app/build.gradle'
s = p.read_text()
assert s.count('versionCode 115') == 1 and s.count("versionName '1.1.5'") == 1
p.write_text(s.replace('versionCode 115', 'versionCode 116').replace("versionName '1.1.5'", "versionName '1.1.6'"))
print('Applied v1.1.6 TLS recovery patch without changing certificate trust or clearing cookies.')
