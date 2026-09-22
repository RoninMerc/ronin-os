package au.com.roningroup.patrollink;

import java.net.URI;
import java.util.Locale;

/** Classifies rejected requests; this policy never grants trust to a certificate. */
public final class TlsPolicy {
    private TlsPolicy() {}
    private static URI parse(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            URI uri = new URI(text);
            return uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme()) ? null : uri;
        } catch (Exception e) { return null; }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    private static boolean sameDocument(URI a, URI b) {
        return b != null && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b)
                && String.valueOf(a.getRawPath()).equalsIgnoreCase(String.valueOf(b.getRawPath()));
    }
    public static boolean isMainDocument(String failed, String requested, String visible) {
        URI f = parse(failed), r = parse(requested), v = parse(visible);
        // The SSL callback lacks isForMainFrame. Treat unknown/ambiguous targets conservatively.
        // Ignore query/fragment changes so cache-busting and redirects cannot hide a document error.
        return f == null || (r == null && v == null) || sameDocument(f, r) || sameDocument(f, v);
    }
    public static String safeHost(String url) {
        URI uri = parse(url);
        return uri == null ? "unavailable" : uri.getHost().toLowerCase(Locale.ROOT);
    }
    public static boolean retriesFailedDocument(String state) {
        return "TLS_ERROR".equals(state) || "OFFLINE".equals(state) || "HTTP_ERROR".equals(state)
                || "REFRESH_TIMEOUT".equals(state) || "WEBVIEW_ERROR".equals(state);
    }
}
