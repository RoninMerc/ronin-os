package au.com.roningroup.patrollink;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
/** Master-only SilverTrack activity upload, read-only paired patrol download. No cookies or login data leave the Master. */
final class MasterFeedClient {
 static final String BASE="https://ronin-silvertrack-master-feed.onrender.com";
 static final long FRESH_MS=90000L;
 private static final ExecutorService IO=Executors.newSingleThreadExecutor();
 private static final Handler UI=new Handler(Looper.getMainLooper());
 private static volatile boolean publishing=false;
 interface Callback{void done(JSONObject data,String error);}
 static boolean master(Context c){return FleetRelay.configured(c)&&FleetRelay.master(c);}
 static boolean subscriber(Context c){return FleetRelay.configured(c)&&!FleetRelay.master(c);}
 static boolean ready(Context c,boolean master){return new MasterFeedSecrets(c).ready(master);}
 static void publish(Context ctx,JSONArray original){
   if(!master(ctx)||!ready(ctx,true)||publishing||original==null)return;
   JSONArray rows=new JSONArray();
   for(int i=0;i<Math.min(180,original.length());i++){
     JSONObject x=original.optJSONObject(i);if(x==null)continue;
     JSONObject item=new JSONObject();
     try{
       for(String key:new String[]{"id","guard","property","issue","date"})item.put(key,x.optString(key,""));
       for(String key:new String[]{"sourceReportId","occurredAt","vehicleText"})if(x.has(key))item.put(key,x.optString(key,""));
       JSONObject vehicle=x.optJSONObject("vehicle");
       if(vehicle!=null){
         JSONObject safe=new JSONObject();
         for(String key:new String[]{"plate","rego","registration","make","model","colour","color","description","vehicle","relation","parking","notes","parking position","address","location"})if(vehicle.has(key))safe.put(key,vehicle.optString(key,""));
         item.put("vehicle",safe);
       }
       rows.put(item);
     }catch(JSONException ignored){}
   }
   if(rows.length()==0)return;
   Context c=ctx.getApplicationContext();publishing=true;
   IO.execute(()->{
     String note="Published activity to patrol phones";
     try{
       String key=new MasterFeedSecrets(c).load(true);
       if(key.isEmpty())throw new IOException("Master pairing not configured");
       JSONObject body=new JSONObject();body.put("rows",rows);
       request("POST","/v1/activity","x-ronin-master-key",key,body);
       c.getSharedPreferences("patrol_master_feed_v1",0).edit().putLong("last_published_at",System.currentTimeMillis()).apply();
     }catch(Exception e){note="Master feed upload failed ("+e.getClass().getSimpleName()+")";}
     finally{publishing=false;}
     c.getSharedPreferences("patrol_master_feed_v1",0).edit().putString("publish_status",note).apply();
   });
 }
 static void poll(Context ctx,Callback cb){
   Context c=ctx.getApplicationContext();
   String key=new MasterFeedSecrets(c).load(false);
   if(key.isEmpty()){UI.post(()->cb.done(null,"Patrol pairing key required"));return;}
   IO.execute(()->{
     JSONObject result=null;String error=null;
     try{result=new JSONObject(request("GET","/v1/activity","x-ronin-reader-key",key,null));}
     catch(Exception e){error="Master relay unavailable ("+e.getClass().getSimpleName()+")";}
     final JSONObject r=result;final String err=error;
     UI.post(()->cb.done(r,err));
   });
 }
 private static String request(String method,String path,String header,String token,JSONObject body)throws Exception{
   HttpsURLConnection c=(HttpsURLConnection)new URL(BASE+path).openConnection();
   c.setRequestMethod(method);c.setConnectTimeout(11000);c.setReadTimeout(11000);
   c.setRequestProperty("Accept","application/json");
   c.setRequestProperty(header,token);
   c.setUseCaches(false);c.setInstanceFollowRedirects(false);
   if(body!=null){
      byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
      c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);
      try(OutputStream out=c.getOutputStream()){out.write(data);}
   }
   int status=c.getResponseCode();
   try(InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream()){
      if(status!=200)throw new IOException("HTTP "+status);
      ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
      while((n=in.read(buf))>0){if(out.size()+n>180000)throw new IOException("Response too large");out.write(buf,0,n);}
      return out.toString("UTF-8");
   }finally{c.disconnect();}
 }
 private MasterFeedClient(){}
}