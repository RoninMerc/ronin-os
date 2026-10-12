package au.com.roningroup.patrollink;

import android.content.*;
import android.location.Location;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** HTTPS client for Ronin Patrol Link fleet GPS and Master shift configuration relay. */
public final class FleetRelay {
    public static final String BASE="https://ronin-patrol-link-relay.onrender.com";
    public static final String PREFS=LocalGpsService.PREFS;
    private static final String API_KEY="ronin-patrol-link-2026";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();

    public static final class Patrol {
        public int number; public boolean onDuty; public double lat,lon; public float accuracy,speed; public long time;
        public String state="Waiting for relay"; public JSONArray segments=new JSONArray();
        public long age(){return time<=0?Long.MAX_VALUE:Math.max(0,System.currentTimeMillis()-time);}
    }
    public interface Callback{void done(Map<Integer,Patrol> fleet,String error);}
    public interface GuardCallback{void done(boolean changed,String error);}

    private FleetRelay(){}
    public static SharedPreferences prefs(Context c){return LocalGpsService.prefs(c);}
    public static boolean configured(Context c){return prefs(c).getBoolean("device_mode_configured",false);}
    public static boolean master(Context c){return "master".equals(prefs(c).getString("device_mode",""));}
    public static int patrol(Context c){return prefs(c).getInt("patrol_number",1);}
    public static boolean onDuty(Context c){return prefs(c).getBoolean("gps_on_duty",false);}
    public static String label(Context c){return master(c)?"Master / Supervisor":"Patrol "+patrol(c);}

    public static void configure(Context c,boolean master,int patrol){
        prefs(c).edit().putBoolean("device_mode_configured",true).putString("device_mode",master?"master":"user")
            .putInt("patrol_number",Math.max(1,Math.min(3,patrol))).apply();
        if(master)setDuty(c,false);
    }
    public static void setDuty(Context c,boolean duty){
        prefs(c).edit().putBoolean("gps_on_duty",duty).apply();
        if(!duty&&!master(c))offDuty(c);
    }

    public static void onLocalFix(Context c,Location fix){
        if(master(c)||!configured(c)||!onDuty(c)||fix==null)return;
        publish(c,fix);
    }
    public static void publishLatest(Context c){
        if(master(c)||!configured(c)||!onDuty(c))return;
        SharedPreferences p=prefs(c);if(p.getLong("fix_time",0)==0)return;
        JSONObject o=payload(c,Double.longBitsToDouble(p.getLong("lat",0)),Double.longBitsToDouble(p.getLong("lon",0)),
            p.getFloat("accuracy",0),0f,p.getLong("fix_time",0));
        post(c,"/api/fleet",o);
    }
    private static void publish(Context c,Location l){
        post(c,"/api/fleet",payload(c,l.getLatitude(),l.getLongitude(),l.hasAccuracy()?l.getAccuracy():0,l.hasSpeed()?l.getSpeed():0,l.getTime()));
    }
    private static JSONObject payload(Context c,double lat,double lon,float accuracy,float speed,long time){
        JSONObject o=new JSONObject();
        try{
            int n=patrol(c);
            String device=android.provider.Settings.Secure.getString(c.getContentResolver(),android.provider.Settings.Secure.ANDROID_ID);
            if(device==null)device="patrol-"+n;
            o.put("patrol",n).put("patrol_number",n).put("patrolId","P"+n)
             .put("device",device).put("device_id",device).put("device_label","Patrol "+n)
             .put("lat",lat).put("latitude",lat).put("lon",lon).put("lng",lon).put("longitude",lon)
             .put("accuracy",accuracy).put("speed",speed)
             .put("time",time).put("timestamp",time).put("recorded_at",time)
             .put("on_duty",true).put("onDuty",true).put("role","user").put("deviceMode","USER");
        }catch(Exception ignored){}
        return o;
    }
    public static void offDuty(Context c){
        JSONObject o=new JSONObject();
        try{
            int n=patrol(c);
            o.put("patrol",n).put("patrol_number",n).put("patrolId","P"+n).put("on_duty",false).put("onDuty",false);
        }catch(Exception ignored){}
        post(c,"/api/off-duty",o);
    }

    private static void post(Context c,String path,JSONObject data){
        Context app=c.getApplicationContext();
        IO.execute(()->{
            String error=null;
            try{request("POST",path,data);}
            catch(Exception e){error=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
            prefs(app).edit()
                .putLong(error==null?"gps_relay_last_ok":"gps_relay_last_fail",System.currentTimeMillis())
                .putString("gps_relay_status",error==null?"Fleet relay connected":"Fleet relay unavailable").apply();
        });
    }

    public static void load(Context c,Callback cb){
        IO.execute(()->{
            Map<Integer,Patrol> out=new LinkedHashMap<>();String err=null;
            try{
                String text=request("GET","/api/fleet",null);
                parse(text,out);
                applyGuardsFromFleet(c,new JSONObject(text));
            }catch(Exception e){err="Unable to reach fleet relay · "+e.getClass().getSimpleName();}
            String finalErr=err;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->cb.done(out,finalErr));
        });
    }

    public static void publishGuards(Context c,List<String> guards){
        if(!master(c)||guards==null||guards.size()!=3)return;
        JSONObject body=new JSONObject();
        try{body.put("guards",new JSONArray(guards));}catch(Exception e){return;}
        Context app=c.getApplicationContext();
        IO.execute(()->{
            try{
                request("POST","/api/guards",body);
                prefs(app).edit().putLong("guard_sync_last_publish",System.currentTimeMillis()).putString("guard_sync_status","Guard shift synced").apply();
            }catch(Exception e){
                prefs(app).edit().putString("guard_sync_status","Guard sync failed").apply();
            }
        });
    }

    public static void syncGuards(Context c,GuardCallback cb){
        Context app=c.getApplicationContext();
        IO.execute(()->{
            boolean changed=false;String error=null;
            try{
                JSONObject root=new JSONObject(request("GET","/api/guards",null));
                changed=applyGuards(app,root.optJSONArray("guards"),root.optLong("updatedAt",0));
            }catch(Exception e){error="Guard sync unavailable";}
            final boolean ch=changed;final String err=error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->cb.done(ch,err));
        });
    }

    private static void applyGuardsFromFleet(Context c,JSONObject root){
        applyGuards(c,root.optJSONArray("guards"),root.optLong("guardsUpdatedAt",0));
    }
    private static boolean applyGuards(Context c,JSONArray a,long updatedAt){
        if(a==null||a.length()!=3)return false;
        SharedPreferences p=c.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        long previous=p.getLong("guard_sync_updated_at",0);
        if(updatedAt>0&&updatedAt<previous)return false;
        String[] incoming=new String[3];
        for(int i=0;i<3;i++){incoming[i]=a.optString(i,"N/A").trim();if(incoming[i].isEmpty())incoming[i]="N/A";}
        boolean changed=false;
        for(int i=0;i<3;i++)if(!incoming[i].equals(p.getString("guard"+i,i==0?"D.DEO":i==1?"D.ROGERS1":"T.MURD")))changed=true;
        if(changed||updatedAt>previous){
            SharedPreferences.Editor e=p.edit();
            for(int i=0;i<3;i++)e.putString("guard"+i,incoming[i]);
            e.putLong("guard_sync_updated_at",Math.max(updatedAt,System.currentTimeMillis())).putString("guard_sync_status","Guards synced from Master").apply();
        }
        return changed;
    }

    private static String request(String method,String path,JSONObject data)throws Exception{
        HttpURLConnection h=(HttpURLConnection)new URL(BASE+path).openConnection();
        h.setConnectTimeout(9000);h.setReadTimeout(9000);h.setRequestMethod(method);
        h.setRequestProperty("Accept","application/json");
        h.setRequestProperty("User-Agent","Ronin-Patrol-Link/1.1.47");
        h.setRequestProperty("X-Ronin-Key",API_KEY);
        if(data!=null){
            byte[] b=data.toString().getBytes(StandardCharsets.UTF_8);
            h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json; charset=utf-8");
            h.setFixedLengthStreamingMode(b.length);
            try(OutputStream o=h.getOutputStream()){o.write(b);}
        }
        int code=h.getResponseCode();
        InputStream in=code>=200&&code<300?h.getInputStream():h.getErrorStream();
        String s=read(in);
        if(code<200||code>=300)throw new IOException("Fleet relay returned "+code+" "+s);
        return s;
    }
    private static String read(InputStream in)throws Exception{
        if(in==null)return "";
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[4096];
        for(int n;(n=in.read(x))>0;)b.write(x,0,n);
        return b.toString("UTF-8");
    }
    private static void parse(String text,Map<Integer,Patrol> out)throws Exception{
        Object root=new JSONTokener(text).nextValue();JSONArray a=null;
        if(root instanceof JSONArray)a=(JSONArray)root;
        else if(root instanceof JSONObject){
            JSONObject o=(JSONObject)root;a=o.optJSONArray("patrols");
            if(a==null)a=o.optJSONArray("fleet");if(a==null)a=o.optJSONArray("data");
        }
        if(a==null)return;
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;
            Patrol p=new Patrol();
            String pid=o.optString("patrolId","");
            p.number=o.optInt("patrol",o.optInt("patrol_number",pid.matches("P[123]")?Integer.parseInt(pid.substring(1)):i+1));
            p.onDuty=o.has("on_duty")?o.optBoolean("on_duty"):o.optBoolean("onDuty",true);
            p.lat=num(o,"lat","latitude");p.lon=num(o,"lon","lng","longitude");
            p.accuracy=(float)num(o,"accuracy");p.speed=(float)num(o,"speed");p.time=time(o);
            p.state=o.optString("state",p.speed>0.8?"Moving":"Stationary");
            JSONArray seg=o.optJSONArray("segments");if(seg==null)seg=o.optJSONArray("history");if(seg!=null)p.segments=seg;
            if(p.number>=1&&p.number<=3)out.put(p.number,p);
        }
    }
    private static double num(JSONObject o,String...keys){for(String k:keys)if(o.has(k))return o.optDouble(k,0);return 0;}
    private static long time(JSONObject o){
        for(String k:new String[]{"time","timestamp","recorded_at","updated_at","receivedAt","fixTime"}){
            if(!o.has(k))continue;Object v=o.opt(k);
            if(v instanceof Number){long n=((Number)v).longValue();return n<100000000000L?n*1000:n;}
            if(v instanceof String)try{return java.time.Instant.parse((String)v).toEpochMilli();}catch(Exception ignored){}
        }
        return 0;
    }
}