package au.com.roningroup.patrollink;
import android.webkit.*;
final class SilverTrackBrowserUi {
 static void prepare(WebView web){WebSettings settings=web.getSettings();settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);settings.setTextZoom(100);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);web.setInitialScale(0);}
 private SilverTrackBrowserUi(){}
}
