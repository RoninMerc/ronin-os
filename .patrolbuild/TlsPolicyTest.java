package au.com.roningroup.patrollink;
import org.junit.Test;
import static org.junit.Assert.*;
public class TlsPolicyTest {
    private static final String PAGE="https://trlsecurity.silvertracker.net/AdminCustomer/Issues/Monitor.aspx";
    @Test public void exactDocumentIsFatal() { assertTrue(TlsPolicy.isMainDocument(PAGE,PAGE,PAGE)); }
    @Test public void cacheNonceAndFragmentCannotHideDocumentFailure() {
        assertTrue(TlsPolicy.isMainDocument(PAGE+"?_ronin_refresh=old#fragment",PAGE+"?_ronin_refresh=new",PAGE));
    }
    @Test public void documentComparisonHandlesDefaultPortAndCase() {
        assertTrue(TlsPolicy.isMainDocument("https://TRLSECURITY.SILVERTRACKER.NET:443/admincustomer/issues/monitor.aspx",PAGE,PAGE));
    }
    @Test public void failedImageIsNotTheDocument() {
        assertFalse(TlsPolicy.isMainDocument("https://trlsecurity.silvertracker.net/images/logo.png",PAGE,PAGE));
    }
    @Test public void otherHostResourceIsNotTheDocument() {
        assertFalse(TlsPolicy.isMainDocument("https://cdn.example.test/font.woff2",PAGE,PAGE));
    }
    @Test public void unknownAndMalformedTargetsFailConservatively() {
        assertTrue(TlsPolicy.isMainDocument(null,PAGE,PAGE));
        assertTrue(TlsPolicy.isMainDocument("not a URL",PAGE,PAGE));
        assertTrue(TlsPolicy.isMainDocument(PAGE,null,"about:blank"));
    }
    @Test public void eitherRequestedOrVisibleDocumentCanFail() {
        assertTrue(TlsPolicy.isMainDocument("https://prod.silvertracker.net/login.aspx",PAGE,"https://prod.silvertracker.net/login.aspx"));
    }
    @Test public void differentPortIsNotSameDocument() {
        assertFalse(TlsPolicy.isMainDocument("https://trlsecurity.silvertracker.net:444/AdminCustomer/Issues/Monitor.aspx",PAGE,PAGE));
    }
    @Test public void diagnosticsExcludeCredentialsAndQueryStrings() {
        assertEquals("example.test",TlsPolicy.safeHost("https://private:secret@example.test/path?token=secret#secret"));
        assertEquals("unavailable",TlsPolicy.safeHost("not a URL"));
    }
    @Test public void retryRecoverablePageFailuresNotSignInOrBlockedRedirects() {
        for(String s:new String[]{"TLS_ERROR","OFFLINE","HTTP_ERROR","REFRESH_TIMEOUT","WEBVIEW_ERROR"})
            assertTrue(s,TlsPolicy.retriesFailedDocument(s));
        for(String s:new String[]{"SIGN_IN_REQUIRED","BLOCKED","READ_OK","CHECKING","EXITED"})
            assertFalse(s,TlsPolicy.retriesFailedDocument(s));
    }
}
