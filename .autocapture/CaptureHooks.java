package au.com.roningroup.patrollink;
import android.webkit.*;import org.json.*;
/** Package-private debug transport seam. Release builds always use authenticated production transports. */
final class CaptureHooks {
 interface Poster {String post(String path,JSONObject body)throws Exception;}
 interface Pages {WebResourceResponse response(WebResourceRequest request);byte[] image(String url)throws Exception;}
 static Poster poster;static Pages pages;
 static String post(String path,JSONObject body)throws Exception{return BuildConfig.DEBUG&&poster!=null?poster.post(path,body):null;}
 static WebResourceResponse response(WebResourceRequest request){return BuildConfig.DEBUG&&pages!=null?pages.response(request):null;}
 static byte[] image(String url)throws Exception{return BuildConfig.DEBUG&&pages!=null?pages.image(url):null;}
 private CaptureHooks(){}
}
