package au.com.roningroup.patrollink;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Shared checkpoint configuration sync and one-time recovery. */
final class CheckpointSyncClient {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler UI=new Handler(Looper.getMainLooper());

    interface Callback{void done(JSONObject data,String error);}

    static boolean master(Context c){return MasterFeedClient.master(c);}
    static boolean subscriber(Context c){return MasterFeedClient.subscriber(c);}

    static void publish(Context ctx,JSONArray checkpoints){
        if(!master(ctx)||checkpoints==null)return;
        Context c=ctx.getApplicationContext();
        String key=new MasterFeedSecrets(c).load(true);
        if(key.isEmpty()){setStatus(c,"Master Publisher Key required");return;}
        IO.execute(()->{
            try{
                JSONObject body=new JSONObject().put("checkpoints",checkpoints);
                JSONObject response=new JSONObject(request("POST","/v1/checkpoints","x-ronin-master-key",key,body,13_000_000));
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit()
                    .putLong("last_publish_at",System.currentTimeMillis())
                    .putLong("generation",response.optLong("generation",0))
                    .putInt("remote_count",response.optInt("accepted",0))
                    .putString("status","Checkpoint sync published · "+response.optInt("accepted",0)+" checkpoints").apply();
            }catch(Exception e){
                setStatus(c,"Checkpoint publish failed · "+message(e));
            }
        });
    }

    /** Any paired device can download using the Reader key, including the Master. */
    static void download(Context ctx,Callback cb){
        Context c=ctx.getApplicationContext();
        String key=new MasterFeedSecrets(c).load(false);
        if(key.isEmpty()){UI.post(()->cb.done(null,"Patrol Reader Key required"));return;}
        IO.execute(()->{
            JSONObject result=null;String error=null;
            try{
                result=new JSONObject(request("GET","/v1/checkpoints","x-ronin-reader-key",key,null,13_000_000));
                int count=result.optJSONArray("checkpoints")==null?0:result.optJSONArray("checkpoints").length();
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit()
                    .putLong("last_poll_at",System.currentTimeMillis())
                    .putLong("generation",result.optLong("generation",0))
                    .putInt("remote_count",count)
                    .putString("status","Shared checkpoints available · "+count).apply();
            }catch(Exception e){
                error="Checkpoint download failed · "+message(e);setStatus(c,error);
            }
            final JSONObject r=result;final String err=error;
            UI.post(()->cb.done(r,err));
        });
    }

    static void poll(Context ctx,Callback cb){
        if(!subscriber(ctx)){UI.post(()->cb.done(null,"Not a patrol subscriber"));return;}
        download(ctx,cb);
    }

    /** Reader-key recovery is accepted by the backend only while the shared checkpoint store is empty. */
    static void recover(Context ctx,JSONArray checkpoints,Callback cb){
        Context c=ctx.getApplicationContext();
        if(checkpoints==null||checkpoints.length()==0){UI.post(()->cb.done(null,"No local checkpoints found on this phone"));return;}
        String key=new MasterFeedSecrets(c).load(false);
        if(key.isEmpty()){UI.post(()->cb.done(null,"Patrol Reader Key required"));return;}
        IO.execute(()->{
            JSONObject result=null;String error=null;
            try{
                JSONObject body=new JSONObject().put("checkpoints",checkpoints);
                result=new JSONObject(request("POST","/v1/checkpoints/recover","x-ronin-reader-key",key,body,13_000_000));
                int n=result.optInt("accepted",0);
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit()
                    .putLong("last_recovery_at",System.currentTimeMillis())
                    .putLong("generation",result.optLong("generation",0))
                    .putInt("remote_count",n)
                    .putString("status","Checkpoint recovery uploaded · "+n).apply();
            }catch(Exception e){
                error="Recovery upload failed · "+message(e);setStatus(c,error);
            }
            final JSONObject r=result;final String err=error;
            UI.post(()->cb.done(r,err));
        });
    }

    static long generation(Context c){
        return c.getSharedPreferences("patrol_checkpoint_sync_v1",0).getLong("generation",0);
    }

    static int remoteCount(Context c){
        return c.getSharedPreferences("patrol_checkpoint_sync_v1",0).getInt("remote_count",-1);
    }

    static String status(Context c){
        return c.getSharedPreferences("patrol_checkpoint_sync_v1",0).getString("status",
            master(c)?"Checkpoint sync waiting":"Checkpoint sync waiting for Master");
    }

    private static void setStatus(Context c,String s){
        c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit().putString("status",s).apply();
    }

    private static String message(Exception e){
        String m=e.getMessage();return m==null||m.isEmpty()?e.getClass().getSimpleName():m;
    }

    private static String request(String method,String path,String header,String token,JSONObject body,int maxBytes)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(MasterFeedClient.BASE+path).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(11000);c.setReadTimeout(18000);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty(header,token);
        c.setUseCaches(false);c.setInstanceFollowRedirects(false);
        if(body!=null){
            byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
            if(data.length>12_500_000)throw new IOException("Checkpoint payload too large");
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            c.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=c.getOutputStream()){out.write(data);}
        }
        int status=c.getResponseCode();
        InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream();
        String payload="";
        try(InputStream use=in){
            if(use!=null){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                while((n=use.read(buf))>0){if(out.size()+n>maxBytes)throw new IOException("Response too large");out.write(buf,0,n);}
                payload=out.toString("UTF-8");
            }
        }finally{c.disconnect();}
        if(status<200||status>=300){
            String detail="";
            try{detail=new JSONObject(payload).optString("error","");}catch(Exception ignored){}
            throw new IOException("HTTP "+status+(detail.isEmpty()?"":" · "+detail));
        }
        return payload;
    }

    private CheckpointSyncClient(){}
}