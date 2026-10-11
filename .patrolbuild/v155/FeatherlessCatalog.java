package au.com.roningroup.patrollink;
import java.util.*;
import org.json.*;

/** Featherless model + voice catalogue. Live discovery wins; static presets are fallback only. */
final class FeatherlessCatalog {
 static final String[] MODEL_IDS={
   "hexgrad/Kokoro-82M",
   "ResembleAI/chatterbox-turbo",
   "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
   "canopylabs/orpheus-3b-0.1-ft"
 };
 static final String[] MODEL_LABELS={
   "Kokoro 82M · multilingual presets",
   "Chatterbox Turbo · conversational English",
   "Qwen3-TTS · multilingual presets",
   "Orpheus 3B · expressive English"
 };
 static final Map<String,List<String>> FALLBACKS=new LinkedHashMap<>();
 static{
   FALLBACKS.put(MODEL_IDS[0],Arrays.asList(
     "bf_alice","bf_emma","bf_isabella","bf_lily","bm_daniel","bm_fable","bm_george","bm_lewis",
     "af_alloy","af_aoede","af_bella","af_heart","af_jessica","af_kore","af_nicole","af_nova","af_river","af_sarah","af_sky",
     "am_adam","am_echo","am_eric","am_fenrir","am_liam","am_michael","am_onyx","am_puck","am_santa",
     "jf_alpha","jf_gongitsune","jf_nezumi","jf_tebukuro","jm_kumo",
     "zf_xiaobei","zf_xiaoni","zf_xiaoxiao","zf_xiaoyi","zm_yunjian","zm_yunxi","zm_yunxia","zm_yunyang",
     "ef_dora","em_alex","em_santa","ff_siwis","hf_alpha","hf_beta","hm_omega","hm_psi","if_sara","im_nicola","pf_dora","pm_alex","pm_santa"
   ));
   FALLBACKS.put(MODEL_IDS[2],Arrays.asList("Aiden","Ryan","Vivian","Serena","Uncle_Fu","Dylan","Eric","Ono_anna","Sohee"));
   FALLBACKS.put(MODEL_IDS[3],Arrays.asList("tara","leah","jess","leo","dan","mia","zac","zoe"));
 }
 static List<String> fallback(String model){
   List<String> x=FALLBACKS.get(model);return x==null?Collections.emptyList():new ArrayList<>(x);
 }
 static String description(String model,String id){
   if(id==null||id.trim().isEmpty())return "Select a voice and use Test voice to hear it.";
   if(MODEL_IDS[0].equals(model)){
     String accent="Kokoro preset";
     if(id.startsWith("af_")||id.startsWith("am_"))accent="American English";
     else if(id.startsWith("bf_")||id.startsWith("bm_"))accent="British English";
     else if(id.startsWith("jf_")||id.startsWith("jm_"))accent="Japanese";
     else if(id.startsWith("zf_")||id.startsWith("zm_"))accent="Mandarin Chinese";
     else if(id.startsWith("ef_")||id.startsWith("em_"))accent="Spanish";
     else if(id.startsWith("ff_"))accent="French";
     else if(id.startsWith("hf_")||id.startsWith("hm_"))accent="Hindi";
     else if(id.startsWith("if_")||id.startsWith("im_"))accent="Italian";
     else if(id.startsWith("pf_")||id.startsWith("pm_"))accent="Brazilian Portuguese";
     String gender=id.length()>1?(id.charAt(1)=='f'?"female":id.charAt(1)=='m'?"male":""):"";
     return accent+(gender.isEmpty()?"":" · "+gender)+" · test in-app for actual timbre.";
   }
   if(MODEL_IDS[1].equals(model))return "Conversational English voice. Availability is verified live from Featherless when possible.";
   if(MODEL_IDS[2].equals(model)){
     if("Ono_anna".equals(id))return "Japanese · Qwen3-TTS preset.";
     if("Sohee".equals(id))return "Korean · Qwen3-TTS preset.";
     if("Aiden".equals(id)||"Ryan".equals(id))return "English · Qwen3-TTS preset.";
     return "Qwen3-TTS multilingual preset · test in-app for language/accent behaviour.";
   }
   if(MODEL_IDS[3].equals(model))return "Expressive English · Orpheus preset · test in-app for delivery.";
   return "Saved/custom Featherless voice · test before operational use.";
 }
 static List<String> parseVoices(String raw)throws JSONException{
   JSONObject j=new JSONObject(raw);JSONArray a=j.optJSONArray("voices");
   if(a==null)throw new JSONException("Featherless did not return a voices array");
   ArrayList<String> out=new ArrayList<>();
   for(int i=0;i<a.length();i++){
     Object v=a.opt(i);String id=v instanceof JSONObject?((JSONObject)v).optString("id"):a.optString(i);
     if(id!=null&&!id.trim().isEmpty()&&!out.contains(id))out.add(id.trim());
   }
   return out;
 }
 private FeatherlessCatalog(){}
}