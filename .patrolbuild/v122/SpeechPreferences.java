package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small private speech dictionary, kept separately from monitoring and voice profiles. */
public final class SpeechPreferences {
    private static final String RULES="rules",ALIASES="nicknames",SEEN="seen_alerts";
    private static final int MAX_SEEN=2000;
    public static final class Seen {
        public final String issue,guard,property;
        Seen(String issue,String guard,String property){this.issue=issue;this.guard=guard;this.property=property;}
        public Observation observation(){return new Observation("preview",guard,property,issue,"",0);}
    }
    private final SharedPreferences prefs;
    private final List<SpeechRules.Rule> rules=new ArrayList<>();
    private final LinkedHashMap<String,String> aliases=new LinkedHashMap<>();
    private final LinkedHashMap<String,Seen> seen=new LinkedHashMap<>();
    private SpeechRules compiled;
    private int revision;
    private String loadWarning="";
    public SpeechPreferences(Context context) {
        this(context.getSharedPreferences("patrol_spoken_wording_v1",Context.MODE_PRIVATE));
        if(!prefs.getBoolean("defaults_applied",false)) {
            try(InputStream in=context.getAssets().open("speech-defaults.json")) {
                ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
                while((n=in.read(buffer))!=-1)b.write(buffer,0,n);
                JSONObject names=new JSONObject(b.toString(StandardCharsets.UTF_8.name())).optJSONObject("nicknames");
                if(names!=null)for(Iterator<String> it=names.keys();it.hasNext();) {
                    String id=it.next(),key=FeedReducer.key(id),name=SpeechRules.clean(names.optString(id));
                    if(!key.isEmpty()&&!name.isEmpty()&&!aliases.containsKey(key))aliases.put(key,name);
                }
                persistAliases();prefs.edit().putBoolean("defaults_applied",true).apply();
            } catch(Exception e) { /* Defaults are optional; existing saved settings always win. */ }
        }
    }
    SpeechPreferences(SharedPreferences prefs) {
        this.prefs=prefs;
        try {
            JSONArray a=new JSONArray(prefs.getString(RULES,"[]"));
            for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);rules.add(new SpeechRules.Rule(r.getString("id"),r.getString("original"),r.getString("spoken"),r.optBoolean("phrase"),r.optBoolean("enabled",true)));}
        }catch(Exception e){loadWarning="Some saved wording rules could not be loaded.";}
        try {JSONObject a=new JSONObject(prefs.getString(ALIASES,"{}"));for(Iterator<String> it=a.keys();it.hasNext();){String id=it.next();aliases.put(FeedReducer.key(id),a.getString(id));}}
        catch(Exception e){loadWarning="Some saved nicknames could not be loaded.";}
        try {JSONArray a=new JSONArray(prefs.getString(SEEN,"[]"));for(int i=0;i<a.length();i++){JSONObject s=a.getJSONObject(i);Seen item=new Seen(s.getString("issue"),s.optString("guard"),s.optString("property"));seen.put(SpeechRules.key(item.issue),item);}}
        catch(Exception e){loadWarning="The learned alert list could not be fully loaded; saved replacements are separate.";}
        compiled=new SpeechRules(rules);
    }
    public int revision(){return revision;}
    public String warning(){return loadWarning;}
    public List<SpeechRules.Rule> rules(){return new ArrayList<>(rules);}
    public List<Seen> seen(){return new ArrayList<>(seen.values());}
    public Map<String,String> nicknames(){return new LinkedHashMap<>(aliases);}
    public String nickname(String id){return aliases.getOrDefault(FeedReducer.key(id),"");}
    public String issue(String original){return compiled.apply(original);}
    public String readout(Observation o){return compiled.readout(o,aliases);}
    public String preview(String existingId,String original,String spoken,boolean phrase,boolean enabled,Observation example) {
        List<SpeechRules.Rule> temp=new ArrayList<>();for(SpeechRules.Rule r:rules)if(!r.id.equals(existingId))temp.add(r);
        temp.add(new SpeechRules.Rule("preview",original,spoken,phrase,enabled));
        return new SpeechRules(temp).readout(example,aliases);
    }
    public void remember(Collection<Observation> entries) {
        boolean changed=false;
        for(Observation o:entries) {
            String k=SpeechRules.key(o.issue);if(k.isEmpty()||seen.containsKey(k))continue;
            seen.put(k,new Seen(o.issue,o.guard,o.property));changed=true;
            if(seen.size()>MAX_SEEN){Iterator<String> it=seen.keySet().iterator();it.next();it.remove();}
        }
        if(changed){JSONArray a=new JSONArray();for(Seen s:seen.values()){JSONObject o=new JSONObject();try{o.put("issue",s.issue);o.put("guard",s.guard);o.put("property",s.property);}catch(JSONException e){throw new IllegalStateException(e);}a.put(o);}prefs.edit().putString(SEEN,a.toString()).apply();revision++;}
    }
    public void saveRule(String existingId,String original,String spoken,boolean phrase,boolean enabled) {
        String from=SpeechRules.clean(original),to=SpeechRules.clean(spoken);
        if(from.isEmpty())throw new IllegalArgumentException("Enter the original Silvertracker wording.");
        if(to.isEmpty())throw new IllegalArgumentException("Enter what the selected voice should say. Use Restore original to remove a replacement.");
        if(from.length()>4000||to.length()>4000)throw new IllegalArgumentException("Keep each wording below 4,000 characters.");
        for(SpeechRules.Rule r:rules)if(!r.id.equals(existingId)&&r.phrase==phrase&&SpeechRules.key(r.original).equals(SpeechRules.key(from)))throw new IllegalArgumentException("A rule for that wording already exists. Edit the existing rule instead.");
        rules.removeIf(r->r.id.equals(existingId));
        rules.add(new SpeechRules.Rule(existingId==null?UUID.randomUUID().toString():existingId,from,to,phrase,enabled));persistRules();
    }
    public void removeRule(String id){if(rules.removeIf(r->r.id.equals(id)))persistRules();}
    public void nickname(String guard,String nickname) {
        String key=FeedReducer.key(guard),name=SpeechRules.clean(nickname);
        if(key.isEmpty()||key.length()>80)throw new IllegalArgumentException("Enter the exact Silvertracker guard username.");
        if(name.length()>60)throw new IllegalArgumentException("Keep the nickname below 60 characters.");
        if(name.isEmpty())aliases.remove(key);else aliases.put(key,name);persistAliases();revision++;
    }
    private void persistRules() {
        JSONArray a=new JSONArray();for(SpeechRules.Rule r:rules){JSONObject o=new JSONObject();try{o.put("id",r.id);o.put("original",r.original);o.put("spoken",r.spoken);o.put("phrase",r.phrase);o.put("enabled",r.enabled);}catch(JSONException e){throw new IllegalStateException(e);}a.put(o);}
        prefs.edit().putString(RULES,a.toString()).apply();compiled=new SpeechRules(rules);revision++;
    }
    private void persistAliases(){prefs.edit().putString(ALIASES,new JSONObject(aliases).toString()).apply();}
}
