package au.com.roningroup.patrollink;
import android.content.*;import android.os.*;import android.graphics.*;import android.webkit.CookieManager;import android.util.Base64;import org.json.*;import java.net.*;import java.io.*;import java.util.concurrent.*;
final class ReportPhotos {
 interface Done {void complete(int uploaded,int failed);}
 private static final ExecutorService IO=Executors.newSingleThreadExecutor();private static final Handler UI=new Handler(Looper.getMainLooper());
 static boolean allowed(String value){return ReportPhotoPolicy.allowed(value);}
 static void upload(Context c,JSONObject source,JSONArray photos,Done done){
  final String cookies=CookieManager.getInstance().getCookie(PatrolEngine.MONITOR);
  IO.execute(()->{
   int uploaded=0,failed=0;
   for(int i=0;i<Math.min(12,photos.length());i++){
    String url=photos.optString(i);
    try{
     if(!MasterFeedClient.master(c)||!MasterFeedClient.ready(c,true)||!allowed(url))throw new IOException("Source session unavailable");
     byte[] bytes=CaptureHooks.image(url);if(bytes==null)bytes=download(url,cookies);
     byte[] jpeg=jpeg(bytes);
     JSONObject vehicle=source.getJSONObject("vehicle");
     String reg=vehicle.optString("plate",vehicle.optString("rego",vehicle.optString("registration")));
     String filename=new URI(url).getPath();filename=filename.substring(filename.lastIndexOf('/')+1);
     JSONObject body=new JSONObject().put("sourceReportId",source.getString("sourceReportId")).put("plate",reg).put("trainingRequested",true).put("filename",filename).put("jpegBase64",Base64.encodeToString(jpeg,Base64.NO_WRAP));
     JSONObject result=MasterFeedClient.evidence(c,body);
     if(!result.optBoolean("ok"))throw new IOException("Photo cloud rejected upload");
     if(!result.optBoolean("duplicate"))uploaded++;else CaptureQueue.add(c,"photo_duplicate",1);
     CaptureQueue.stage(c,"Report photos: "+(i+1)+"/"+Math.min(12,photos.length())+" cloud receipts; new photos "+uploaded+".");
    }catch(Exception e){failed++;CaptureQueue.stage(c,"Photo "+(i+1)+" could not be saved; the source report is retained for retry.");}
   }
   final int yes=uploaded,no=failed;UI.post(()->done.complete(yes,no));
  });
 }
 private static byte[] download(String url,String cookies)throws Exception{
  String current=url;
  for(int hop=0;hop<4;hop++){
   if(!allowed(current))throw new IOException("Unsupported photo host or route");
   javax.net.ssl.HttpsURLConnection con=(javax.net.ssl.HttpsURLConnection)new URL(current).openConnection();
   con.setInstanceFollowRedirects(false);con.setConnectTimeout(15000);con.setReadTimeout(25000);con.setUseCaches(false);
   if(cookies!=null&&new URL(current).getHost().equalsIgnoreCase("trlsecurity.silvertracker.net"))con.setRequestProperty("Cookie",cookies);con.setRequestProperty("Referer",PatrolEngine.MONITOR);
   try{
    int status=con.getResponseCode();
    if(status>=300&&status<400){current=new URL(new URL(current),con.getHeaderField("Location")).toString();continue;}
    if(status!=200)throw new IOException("Source image HTTP "+status);
    ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=con.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0){if(out.size()+n>10000000)throw new IOException("Source photo exceeds 10 MB");out.write(buf,0,n);}}return out.toByteArray();
   }finally{con.disconnect();}
  }
  throw new IOException("Too many photo redirects");
 }
 static byte[] jpeg(byte[] raw)throws Exception{
  if(raw==null||raw.length>10000000)throw new IOException("Invalid source image");
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
  if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>20000||bounds.outHeight>20000)throw new IOException("Invalid source dimensions");
  BitmapFactory.Options opt=new BitmapFactory.Options();while(Math.max(bounds.outWidth,bounds.outHeight)/Math.max(1,opt.inSampleSize)>2048)opt.inSampleSize=Math.max(1,opt.inSampleSize)*2;
  Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,opt);if(bitmap==null)throw new IOException("Image could not decode");
  try{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);if(out.size()>2000000){out.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,70,out);}if(out.size()>2000000)throw new IOException("Photo exceeds cloud bound");return out.toByteArray();}finally{bitmap.recycle();}
 }
 private ReportPhotos(){}
}
