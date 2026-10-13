package au.com.roningroup.patrollink;
import android.content.Context;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.io.*;
import org.json.*;
import java.time.ZoneId;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Read-only detail capture in the existing Master cookie session. No credentials or cookies are uploaded. */
final class SilverTrackVehicleCollector{
 private static final Handler UI=new Handler(Looper.getMainLooper());
 private static final ArrayDeque<JSONObject> pending=new ArrayDeque<>();
 private static final Map<String,Long> completed=new HashMap<>();
 private static WebView detail;
 private static Context context;
 private static ZoneId timezone;
 private static JSONObject active;
 private static String identity="";
 private static int epoch,attempts,pageRead;
 private static boolean uploading,awaitingReceipt;
 private static boolean restored;
 private static WeakReference<WebView> monitorOwner=new WeakReference<>(null);
 static boolean busy(){return detail!=null||!pending.isEmpty();}
 static void restore(Context c,WebView monitor,ZoneId zone){
  context=c.getApplicationContext();timezone=zone;monitorOwner=new WeakReference<>(monitor);
  if(!restored){restored=true;JSONArray saved=CaptureQueue.load(c);for(int i=0;i<saved.length();i++){JSONObject item=saved.optJSONObject(i);if(item!=null&&CaptureLinkPolicy.allowed(item.optString("url")))pending.add(item);}}
  next();
 }
 private static void persist(){if(context!=null)CaptureQueue.save(context,pending,active);}
 static void retryFailures(Context c){
  try{JSONArray failed=new JSONArray(CaptureQueue.prefs(c).getString("failed_queue","[]"));for(int i=0;i<failed.length();i++){JSONObject item=failed.optJSONObject(i);if(item!=null&&CaptureLinkPolicy.allowed(item.optString("url"))){item.put("captureAttempts",0);pending.add(item);}}CaptureQueue.prefs(c).edit().putString("failed_queue","[]").commit();persist();}catch(Exception ignored){}
 }
 static void resetForDebug(){if(!BuildConfig.DEBUG)return;if(detail!=null){detail.stopLoading();if(detail.getParent() instanceof ViewGroup)((ViewGroup)detail.getParent()).removeView(detail);detail.destroy();detail=null;}++epoch;pending.clear();completed.clear();active=null;identity="";restored=false;uploading=false;}
 static void captureLoadedPage(Context c,WebView monitor,ZoneId zone){
  if(!eligible(c,monitor)){AutoCaptureJob.failedPage(c,"Master Publisher pairing and the signed-in Issue Monitor are required.");return;}context=c.getApplicationContext();timezone=zone;monitorOwner=new WeakReference<>(monitor);
  final int token=++pageRead;scanNote(c,"Reading the current parking results directly…");readLoaded(c,monitor,zone,token,0);
 }
 private static boolean eligible(Context c,WebView monitor){
  if(!MasterFeedClient.master(c)){scanNote(c,"Capture requires the authorised Master collector; Reader mode cannot publish vehicle reports.");return false;}
  if(!MasterFeedClient.ready(c,true)){scanNote(c,"Set the Master feed Publisher key on this collector. The Reader key cannot upload vehicle reports.");return false;}
  if(monitor==null||!PatrolEngine.monitorPage(monitor.getUrl())){scanNote(c,"Load the signed-in SilverTrack Issue Monitor first.");return false;}return true;
 }
 private static void readLoaded(Context c,WebView monitor,ZoneId zone,int token,int attempt){
  if(token!=pageRead||!MasterFeedClient.master(c)||!PatrolEngine.monitorPage(monitor.getUrl()))return;
  String reader;try(InputStream in=c.getAssets().open("extract.js")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);reader=out.toString("UTF-8").replace("__GUARD_IDS__","[]");}
  catch(Exception e){scanNote(c,"The installed monitor reader could not be loaded.");return;}
  monitor.evaluateJavascript(CaptureCatalog.wrap(reader),encoded->{
   if(token!=pageRead||!MasterFeedClient.master(c))return;
   try{
    Object raw=new JSONTokener(encoded).nextValue();JSONObject page=new JSONObject(String.valueOf(raw));
    if(page.optBoolean("login")){scanNote(c,"Sign into SilverTrack again on this Master phone.");AutoCaptureJob.failedPage(c,"SilverTrack requires sign-in.");return;}
    JSONArray rows=page.optJSONArray("allRows");
    if(page.optBoolean("loading")||!page.optBoolean("recognized")){
     if(attempt<8){scanNote(c,"Waiting for SilverTrack results to finish loading…");UI.postDelayed(()->readLoaded(c,monitor,zone,token,attempt+1),1000);return;}
     scanNote(c,"SilverTrack is still loading or this page has no readable Issue Monitor results.");AutoCaptureJob.failedPage(c,"Issue Monitor results could not be read.");return;
    }
    collect(c,monitor,rows,zone,true);
   }catch(Exception e){scanNote(c,"The current page could not be decoded. Load the Issue Monitor results and try again.");AutoCaptureJob.failedPage(c,"Current results could not be decoded.");}
  });
 }
 static void collect(Context c,WebView monitor,JSONArray rows,ZoneId zone){collect(c,monitor,rows,zone,false);}
 private static void collect(Context c,WebView monitor,JSONArray rows,ZoneId zone,boolean force){
  if(!force&&(AutoCaptureJob.running()||(TabletSettings.tablet(c)&&((PatrolApp)c.getApplicationContext()).engine().browserVisible)))return;
  if(!MasterFeedClient.master(c)||monitor==null)return;
  if(!MasterFeedClient.ready(c,true)){if(force)scanNote(c,"Publisher pairing is required before capture.");return;}
  if(rows==null||rows.length()==0){scanNote(c,"No matching rows are loaded on this page.");AutoCaptureJob.pageRead(c,0);return;}
  if(!PatrolEngine.monitorPage(monitor.getUrl()))return;
  context=c.getApplicationContext();timezone=zone;monitorOwner=new WeakReference<>(monitor);
  int eligible=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optString("issue").matches("(?is).*\\b(?:parking\\s+breach|vehicle\\s+breach|parking\\s+violation)\\b.*"))eligible++;}
  final int parkingRows=eligible;if(parkingRows==0){scanNote(c,"No parking-breach rows in the loaded results. Select parking issues and check the page/date.");AutoCaptureJob.pageRead(c,0);return;}
  scanNote(c,"Reading "+rows.length()+" loaded rows ("+parkingRows+" parking-breach rows)…");
  monitor.evaluateJavascript("JSON.stringify("+LINKS+"("+rows+"))",encoded->{
   if(!MasterFeedClient.master(c)||!MasterFeedClient.ready(c,true))return;
   try{
    Object raw=new JSONTokener(encoded).nextValue();JSONArray targets=new JSONArray(String.valueOf(raw));int accepted=0,cached=0;
    for(int i=0;i<targets.length();i++){
     JSONObject item=targets.getJSONObject(i);String key=item.getString("identity");
     if(!CaptureLinkPolicy.allowed(item.optString("url")))continue;
     long last=completed.containsKey(key)?completed.get(key):0;
     if(!force&&System.currentTimeMillis()-last<300000){cached++;continue;}if(key.equals(identity))continue;
     boolean queued=false;for(JSONObject q:pending)if(key.equals(q.optString("identity")))queued=true;
     if(!queued&&pending.size()<180){pending.add(item);accepted++;}
    }
    scanNote(c,"Parking rows: "+parkingRows+" · linked reports: "+targets.length()+" · queued: "+accepted+" · already captured: "+cached+" · waiting: "+pending.size()+(targets.length()==0?"\\nNo stable, read-only report links were found. Capture does not follow edit/delete or unidentified actions.":""));
    if(targets.length()==0&&parkingRows>0){CaptureQueue.add(c,"unlinked",parkingRows);CaptureQueue.prefs(c).edit().putString("last","Parking rows found, but no stable read-only detail links could be resolved on this page. No import is claimed.").commit();}
    persist();AutoCaptureJob.pageRead(c,targets.length());next();
   }catch(Exception e){scanNote(c,"Report links could not be read. Wait for the results to finish loading and capture again.");AutoCaptureJob.failedPage(c,"Report links could not be read.");}
  });
 }
 private static void next(){
  if(detail!=null||pending.isEmpty()||context==null||!MasterFeedClient.master(context)||!MasterFeedClient.ready(context,true))return;
  active=pending.remove();persist();identity=active.optString("identity");attempts=0;uploading=false;awaitingReceipt=false;final int token=++epoch;
  try{
   detail=new WebView(context);detail.getSettings().setJavaScriptEnabled(true);detail.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);detail.getSettings().setDomStorageEnabled(true);detail.getSettings().setOffscreenPreRaster(true);SilverTrackBrowserUi.prepare(detail);
   WebView owner=monitorOwner.get();ViewParent parent=owner==null?null:owner.getParent();while(parent instanceof View&&! (parent instanceof FrameLayout))parent=parent.getParent();
   if(!(parent instanceof FrameLayout)){finish(token,false,"Open the SilverTrack browser to render and capture its report details.");return;}
   detail.setAlpha(0f);detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);((FrameLayout)parent).addView(detail,0,new FrameLayout.LayoutParams(-1,-1));
   detail.getSettings().setAllowFileAccess(false);detail.getSettings().setAllowContentAccess(false);
   detail.setWebViewClient(new WebViewClient(){
    @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){WebResourceResponse fixture=CaptureHooks.response(r);return fixture!=null?fixture:super.shouldInterceptRequest(w,r);}
    @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){if(!r.isForMainFrame())return false;boolean ok="GET".equalsIgnoreCase(r.getMethod())&&allowed(r.getUrl().toString());if(!ok)finish(token,false,"The report redirected outside a read-only detail page; sign in on the Master browser if the session expired.");return !ok;}
    @Override public void onReceivedError(WebView w,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())finish(token,false,"The report could not load. Check this Master phone’s connection.");}
    @Override public void onReceivedHttpError(WebView w,WebResourceRequest r,WebResourceResponse response){if(r.isForMainFrame()&&response.getStatusCode()>=400)finish(token,false,"Report server returned HTTP "+response.getStatusCode()+".");}
    @Override public void onReceivedSslError(WebView w,android.webkit.SslErrorHandler handler,android.net.http.SslError e){handler.cancel();finish(token,false);}
    @Override public boolean onRenderProcessGone(WebView w,RenderProcessGoneDetail d){finish(token,false);return true;}
    @Override public void onPageFinished(WebView w,String url){if(token==epoch&&allowed(url))read(token);}
   });
   String url=active.getString("url");if(!allowed(url)){finish(token,false);return;}
   detail.loadUrl(url);UI.postDelayed(()->{if(token==epoch&&!uploading)finish(token,false);},18000);
  }catch(Exception e){finish(token,false);}
 }
 private static boolean allowed(String url){return CaptureLinkPolicy.allowed(url);}
 private static void read(int token){
  if(token!=epoch||detail==null||uploading)return;
  if(!MasterFeedClient.master(context)||!MasterFeedClient.ready(context,true)){finish(token,false,"Capture stopped because the collector role or Publisher pairing changed.");return;}
  if(attempts++>=6){finish(token,false,"The report did not expose labelled registration details.");return;}
  detail.evaluateJavascript("JSON.stringify("+FIELDS+"())",encoded->{
   if(token!=epoch||detail==null||uploading)return;
   try{
    Object text=new JSONTokener(encoded).nextValue();if(!(text instanceof String))throw new JSONException("No report fields");
    JSONObject result=new JSONObject((String)text);
    if(result.optBoolean("login")){finish(token,false,"Report opened a sign-in page. Sign into SilverTrack on this Master phone again.");return;}
    JSONObject vehicle=result.optJSONObject("vehicle");
    if(vehicle==null){if(attempts>=6){finish(token,false,"Report opened, but labelled registration details were not found.");return;}UI.postDelayed(()->read(token),1200);return;}
    JSONObject source=new JSONObject(active.getJSONObject("source").toString());
    source.put("sourceReportId",sourceId(identity)).put("vehicle",vehicle);
    String date=source.optString("date",""),reportedDate=result.optString("reportedDate","");
    if(!date.matches("(?s).*\\b20\\d{2}\\b.*")&&reportedDate.matches("(?s).*\\b20\\d{2}\\b.*"))date=reportedDate;
    if(date.matches("(?s).*\\b20\\d{2}\\b.*")){
     long when=TimeParser.parse(date,timezone,System.currentTimeMillis());
     if(when>0&&when<=System.currentTimeMillis()+86400000L)source.put("occurredAt",java.time.Instant.ofEpochMilli(when).toString());
    }
    uploading=true;awaitingReceipt=true;
    UI.postDelayed(()->{if(token==epoch&&awaitingReceipt)finish(token,false,"Vehicle upload timed out; capture this report again.");},90000);
    MasterFeedClient.publishVehicle(context,source,(data,error)->{
     if(token!=epoch)return;awaitingReceipt=false;
     if(error!=null||data==null||!data.optBoolean("ok")){finish(token,false,error==null?"Vehicle report upload was rejected by the cloud.":error);return;}
     JSONObject imports=data.optJSONObject("imports");
     if(imports==null){finish(token,false,"Cloud reply did not include an import result.");return;}
     String note="Cloud result — created: "+imports.optInt("created")+", updated: "+imports.optInt("updated")+", duplicate: "+imports.optInt("duplicate")+", review: "+imports.optInt("review")+", ignored: "+imports.optInt("ignored");
     AutoCaptureJob.receipt(context,imports);
     CaptureQueue.prefs(context).edit().putString("last",note).commit();
     if(imports.optInt("review")+imports.optInt("ignored")>0){finish(token,true,note+" · no photo upload for unimported reports");return;}
     JSONArray photos=result.optJSONArray("photos");
     if(!TabletSettings.tablet(context)||!CaptureQueue.prefs(context).getBoolean("capture_photos",true)||photos==null||photos.length()==0){finish(token,true,note+" · no eligible report photos");return;}
     CaptureQueue.stage(context,"Vehicle cloud receipt confirmed; reading and uploading "+photos.length()+" report photos…");
     ReportPhotos.upload(context,source,photos,(uploaded,failed)->{
      if(token!=epoch)return;
      CaptureQueue.add(context,"photos",uploaded);CaptureQueue.add(context,"photo_failed",failed);
      finish(token,failed==0,note+" · photos confirmed: "+uploaded+" · photo failures: "+failed);
     });
    });
   }catch(Exception e){finish(token,false);}
  });
 }
 private static String sourceId(String identity)throws Exception{
  byte[] hash=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
  StringBuilder out=new StringBuilder("SilverTrack:");for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
 }
 static String status(Context c){
  android.content.SharedPreferences p=c.getSharedPreferences("patrol_master_feed_v1",0);
  return p.getString("vehicle_scan_status","No vehicle capture attempted yet.")+"\n\nLatest report: "+p.getString("vehicle_status","Waiting for capture.");
 }
 private static void scanNote(Context c,String note){c.getSharedPreferences("patrol_master_feed_v1",0).edit().putString("vehicle_scan_status",note).apply();}
 private static void finish(int token,boolean ok){finish(token,ok,ok?"Vehicle report received by cloud":"Report capture stopped: page timed out, could not load, or could not be read.");}
 private static void finish(int token,boolean ok,String note){
  if(token!=epoch)return;++epoch;
  if(ok)completed.put(identity,System.currentTimeMillis());
  else if(active!=null&&context!=null){
   CaptureQueue.add(context,"failed",1);
   int tries=active.optInt("captureAttempts")+1;
   try{active.put("captureAttempts",tries);}catch(Exception ignored){}
   if(tries<3)pending.addLast(active);
   else{try{JSONArray failures=new JSONArray(CaptureQueue.prefs(context).getString("failed_queue","[]"));if(failures.length()<500)failures.put(active);CaptureQueue.prefs(context).edit().putString("failed_queue",failures.toString()).commit();}catch(Exception ignored){}}
  }
  if(completed.size()>1000)completed.clear();
  if(context!=null)context.getSharedPreferences("patrol_master_feed_v1",0).edit().putString("vehicle_status",note).apply();
  if(detail!=null){detail.stopLoading();if(detail.getParent() instanceof ViewGroup)((ViewGroup)detail.getParent()).removeView(detail);detail.destroy();detail=null;}active=null;identity="";uploading=false;persist();
  UI.postDelayed(SilverTrackVehicleCollector::next,2000);
 }
 private static final String LINKS=VehicleCaptureScripts.LINKS;
 private static final String FIELDS=VehicleCaptureScripts.FIELDS;
 private SilverTrackVehicleCollector(){}
}
