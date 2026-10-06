package au.com.ronin.evie;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class BrainClient {
    public interface Callback { void ok(String s); void fail(String e); }
    private static final ExecutorService EXEC=Executors.newCachedThreadPool();

    public static void ask(Context c,String prompt,Callback cb){
        EXEC.execute(()->{
            SettingsStore s=new SettingsStore(c);
            if(s.apiKey().trim().isEmpty()){cb.fail("No API key configured.");return;}
            try{
                String base=s.brainBase();
                if(base.endsWith("/"))base=base.substring(0,base.length()-1);
                URL u=new URL(base.endsWith("/chat/completions")?base:base+"/chat/completions");
                HttpURLConnection h=(HttpURLConnection)u.openConnection();
                h.setConnectTimeout(20000);h.setReadTimeout(90000);h.setRequestMethod("POST");h.setDoOutput(true);
                h.setRequestProperty("Authorization","Bearer "+s.apiKey());
                h.setRequestProperty("Content-Type","application/json");
                JSONObject root=new JSONObject();
                root.put("model",s.brainModel());
                root.put("temperature",0.65);
                JSONArray msgs=new JSONArray();
                msgs.put(new JSONObject().put("role","system").put("content",s.persona()));
                msgs.put(new JSONObject().put("role","user").put("content",prompt));
                root.put("messages",msgs);
                byte[] data=root.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream o=h.getOutputStream()){o.write(data);}
                int code=h.getResponseCode();
                InputStream in=code>=200&&code<300?h.getInputStream():h.getErrorStream();
                String body=read(in);
                if(code<200||code>=300){cb.fail("HTTP "+code+"\n"+body);return;}
                JSONObject j=new JSONObject(body);
                JSONArray choices=j.optJSONArray("choices");
                if(choices==null||choices.length()==0){cb.fail("No response choices returned.");return;}
                String text=choices.getJSONObject(0).getJSONObject("message").optString("content","").trim();
                if(text.isEmpty())cb.fail("Empty model response."); else cb.ok(text);
            }catch(Exception e){cb.fail(e.getClass().getSimpleName()+": "+e.getMessage());}
        });
    }
    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1)b.write(buf,0,n);
        return b.toString(StandardCharsets.UTF_8.name());
    }
}
