package au.com.roningroup.patrollink;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Read-only SilverTrack event relay: Master publishes parsed observations; Patrol phones consume them. */
public final class SilverFeedRelay {
    private static final String BASE="https://ronin-patrol-link-relay.onrender.com";
    private static final String KEY="ronin-patrol-link-2026";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();

    public interface Callback { void done(List<Observation> events,long masterSeenAt,String error); }
    private SilverFeedRelay(){}

    public static boolean relayUser(Context c){return FleetRelay.configured(c)&&!FleetRelay.master(c);}
    public static boolean master(Context c){return FleetRelay.configured(c)&&FleetRelay.master(c);}

    public static void publish(Context c,List<Observation> events){
        if(!master(c))return;
        final ArrayList<Observation> copy=new ArrayList<>();
        if(events!=null)copy.addAll(events);
        Context app=c.getApplicationContext();
        IO.execute(()->{
            String err=null;
            try{
                JSONObject root=new JSONObject();JSONArray a=new JSONArray();
                for(Observation o:copy){
                    if(o==null||o.id.isEmpty()||o.recordedAt<=0)continue;
                    a.put(new JSONObject().put("id",o.id).put("guard",o.guard).put("property",o.property)
                        .put("issue",o.issue).put("recordedText",o.recordedText).put("recordedAt",o.recordedAt));
                }
                root.put("events",a).put("source","MASTER").put("sentAt",System.currentTimeMillis());
                request("POST","/api/silver-feed",root);
            }catch(Exception e){err=e.getClass().getSimpleName();}
            SharedPreferences p=app.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
            p.edit().putLong(err==null?"silver_relay_publish_ok":"silver_relay_publish_fail",System.currentTimeMillis())
                .putString("silver_relay_publish_status",err==null?"Master feed published":"Master relay unavailable").apply();
        });
    }

    public static void poll(Context c,Callback cb){
        Context app=c.getApplicationContext();
        IO.execute(()->{
            ArrayList<Observation> out=new ArrayList<>();long seen=0;String err=null;
            try{
                JSONObject root=new JSONObject(request("GET","/api/silver-feed",null));
                seen=root.optLong("masterSeenAt",0);
                JSONArray a=root.optJSONArray("events");
                if(a!=null)for(int i=0;i<a.length();i++){
                    JSONObject o=a.optJSONObject(i);if(o==null)continue;
                    String id=o.optString("id");long at=o.optLong("recordedAt",0);
                    if(id.isEmpty()||at<=0)continue;
                    out.add(new Observation(id,o.optString("guard"),o.optString("property"),
                        o.optString("issue"),o.optString("recordedText"),at));
                }
            }catch(Exception e){err="Master relay unavailable";}
            SharedPreferences p=app.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
            SharedPreferences.Editor ed=p.edit().putLong("silver_master_seen_at",seen);
            if(err==null)ed.putLong("silver_relay_poll_ok",System.currentTimeMillis()).putString("silver_relay_poll_status","Master feed connected");
            else ed.putLong("silver_relay_poll_fail",System.currentTimeMillis()).putString("silver_relay_poll_status",err);
            ed.apply();
            final long finalSeen=seen;final String finalErr=err;
            new Handler(Looper.getMainLooper()).post(()->cb.done(out,finalSeen,finalErr));
        });
    }

    public static String status(Context c){
        SharedPreferences p=c.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        long seen=p.getLong("silver_master_seen_at",0),now=System.currentTimeMillis();
        if(seen<=0)return "MASTER FEED · waiting";
        long age=Math.max(0,now-seen);
        if(age<30_000)return "MASTER FEED · LIVE · "+(age/1000)+"s";
        if(age<120_000)return "MASTER FEED · DELAYED · "+(age/1000)+"s";
        return "MASTER FEED · OFFLINE · "+(age/60000)+"m";
    }

    private static String request(String method,String path,JSONObject data)throws Exception{
        HttpURLConnection h=(HttpURLConnection)new URL(BASE+path).openConnection();
        h.setConnectTimeout(9000);h.setReadTimeout(9000);h.setRequestMethod(method);
        h.setRequestProperty("Accept","application/json");
        h.setRequestProperty("User-Agent","Ronin-Patrol-Link/1.1.42");
        h.setRequestProperty("X-Ronin-Key",KEY);
        if(data!=null){
            byte[] b=data.toString().getBytes(StandardCharsets.UTF_8);h.setDoOutput(true);
            h.setRequestProperty("Content-Type","application/json; charset=utf-8");h.setFixedLengthStreamingMode(b.length);
            try(OutputStream o=h.getOutputStream()){o.write(b);}
        }
        int code=h.getResponseCode();InputStream in=code>=200&&code<300?h.getInputStream():h.getErrorStream();
        String s=read(in);if(code<200||code>=300)throw new IOException("Relay "+code);return s;
    }
    private static String read(InputStream in)throws Exception{
        if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[4096];
        for(int n;(n=in.read(x))>0;)b.write(x,0,n);return b.toString("UTF-8");
    }
}