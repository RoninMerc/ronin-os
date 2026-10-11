package au.com.roningroup.patrollink;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Master-owned checkpoint configuration sync over the existing Master Feed relay. */
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
        if(key.isEmpty())return;
        IO.execute(()->{
            String status="Checkpoint sync published";
            try{
                JSONObject body=new JSONObject().put("checkpoints",checkpoints);
                JSONObject response=new JSONObject(request("POST","/v1/checkpoints","x-ronin-master-key",key,body,7_000_000));
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit()
                    .putLong("last_publish_at",System.currentTimeMillis())
                    .putLong("generation",response.optLong("generation",0))
                    .putString("status",status).apply();
            }catch(Exception e){
                status="Checkpoint sync publish failed ("+e.getClass().getSimpleName()+")";
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit().putString("status",status).apply();
            }
        });
    }

    static void poll(Context ctx,Callback cb){
        if(!subscriber(ctx)){UI.post(()->cb.done(null,"Not a patrol subscriber"));return;}
        Context c=ctx.getApplicationContext();
        String key=new MasterFeedSecrets(c).load(false);
        if(key.isEmpty()){UI.post(()->cb.done(null,"Patrol Reader Key required"));return;}
        IO.execute(()->{
            JSONObject result=null;String error=null;
            try{
                result=new JSONObject(request("GET","/v1/checkpoints","x-ronin-reader-key",key,null,7_000_000));
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit()
                    .putLong("last_poll_at",System.currentTimeMillis())
                    .putLong("generation",result.optLong("generation",0))
                    .putString("status","Checkpoint sync connected").apply();
            }catch(Exception e){
                error="Checkpoint sync unavailable ("+e.getClass().getSimpleName()+")";
                c.getSharedPreferences("patrol_checkpoint_sync_v1",0).edit().putString("status",error).apply();
            }
            final JSONObject r=result;final String err=error;
            UI.post(()->cb.done(r,err));
        });
    }

    static long generation(Context c){
        return c.getSharedPreferences("patrol_checkpoint_sync_v1",0).getLong("generation",0);
    }

    static String status(Context c){
        return c.getSharedPreferences("patrol_checkpoint_sync_v1",0).getString("status",
            master(c)?"Checkpoint sync waiting to publish":"Checkpoint sync waiting for Master");
    }

    private static String request(String method,String path,String header,String token,JSONObject body,int maxBytes)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(MasterFeedClient.BASE+path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(11000);c.setReadTimeout(15000);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty(header,token);
        c.setUseCaches(false);c.setInstanceFollowRedirects(false);
        if(body!=null){
            byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
            if(data.length>6_500_000)throw new IOException("Checkpoint payload too large");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            c.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=c.getOutputStream()){out.write(data);}
        }
        int status=c.getResponseCode();
        try(InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream()){
            if(status!=200)throw new IOException("HTTP "+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))>0){
                if(out.size()+n>maxBytes)throw new IOException("Response too large");
                out.write(buf,0,n);
            }
            return out.toString("UTF-8");
        }finally{c.disconnect();}
    }

    private CheckpointSyncClient(){}
}