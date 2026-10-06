package au.com.ronin.evie;

import android.content.*;

public class SettingsStore {
    private final SharedPreferences p;
    public SettingsStore(Context c){p=c.getSharedPreferences("evie_settings",Context.MODE_PRIVATE);}
    public void put(String k,String v){p.edit().putString(k,v).apply();}
    public void put(String k,boolean v){p.edit().putBoolean(k,v).apply();}
    public void put(String k,float v){p.edit().putFloat(k,v).apply();}
    public String userName(){return p.getString("user_name","Tristan");}
    public String assistantName(){return p.getString("assistant_name","Evie");}
    public String wakePhrase(){return p.getString("wake_phrase","Hey Evie");}
    public String chatTitle(){return p.getString("chat_title","");}
    public String brainBase(){return p.getString("brain_base","https://api.featherless.ai/v1");}
    public String apiKey(){return p.getString("api_key","");}
    public String brainModel(){return p.getString("brain_model","dongfangshuo/Dongfangshuo-Qwen3.8-27B");}
    public String persona(){return p.getString("persona","You are Evie, Tristan's private Android assistant and digital 2IC. Be intelligent, fast, confident, witty, practical and concise when he is driving or on patrol. Use available tools honestly. Never claim an action succeeded unless it did. Ask before consequential external actions. Keep spoken answers short unless detail is requested.");}
    public String ttsEndpoint(){return p.getString("tts_endpoint","https://api.featherless.ai/v1/audio/speech");}
    public String ttsModel(){return p.getString("tts_model","hexgrad/Kokoro-82M");}
    public String ttsVoice(){return p.getString("tts_voice","bf_isabella");}
    public float ttsSpeed(){return p.getFloat("tts_speed",1f);}
    public boolean useAndroidTts(){return p.getBoolean("android_tts",false);}
    public boolean speakReplies(){return p.getBoolean("speak_replies",true);}
    public boolean autoReadChatGpt(){return p.getBoolean("auto_read_chatgpt",true);}
    public boolean overlay(){return p.getBoolean("overlay",true);}
}
