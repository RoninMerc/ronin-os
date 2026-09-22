package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import java.util.*;

/** Local editable speech settings. Raw monitor rows and guard matching are untouched. */
public final class SpeechPreferences {
    public static final String FILE="patrol_speech_editor_v1", ALERT="alert", PLACE="place", PHRASE="phrase";
    public static final class Entry {
        public final String original, replacement, guard, issue, place;
        public final long seen;
        Entry(String original,String replacement,String guard,String issue,String place,long seen) {
            this.original=original;this.replacement=replacement;this.guard=guard;this.issue=issue;this.place=place;this.seen=seen;
        }
        public boolean edited(){return !replacement.trim().isEmpty();}
    }
    private final SharedPreferences prefs;
    private final Map<String,LinkedHashMap<String,Entry>> catalogs=new HashMap<>();
    public SpeechPreferences(Context context) {
        prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
        SharedPreferences.Editor init=prefs.edit();
        if(!prefs.getBoolean("nicknames_seeded",false)) {
            seed(init,"D.DEO","Dylan");seed(init,"D.ROGERS1","Dean");seed(init,"T.MURD","Tristan");
            init.putBoolean("nicknames_seeded",true);
        }
        init.apply();
        for(String type:Arrays.asList(ALERT,PLACE,PHRASE))load(type);
    }
    private void seed(SharedPreferences.Editor edit,String guard,String nickname) {
        String key="nickname:"+SpeechRules.guardKey(guard);
        if(!prefs.contains(key))edit.putString(key,nickname);
    }
    private void load(String type) {
        LinkedHashMap<String,Entry> map=new LinkedHashMap<>();
        try { JSONArray a=new JSONArray(prefs.getString("catalog:"+type,"[]"));
            for(int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String original=o.optString("original");if(original.isEmpty())continue;
                map.put(SpeechRules.key(original),new Entry(original,o.optString("replacement"),o.optString("guard"),o.optString("issue"),o.optString("place"),o.optLong("seen")));
            }
        }catch(JSONException ignored){}
        catalogs.put(type,map);
    }
    private LinkedHashMap<String,Entry> map(String type) {
        LinkedHashMap<String,Entry> m=catalogs.get(type);if(m==null)throw new IllegalArgumentException("Unknown speech list.");return m;
    }
    private void persist(String type) {
        JSONArray a=new JSONArray();
        for(Entry e:map(type).values()) {
            JSONObject o=new JSONObject();try{o.put("original",e.original);o.put("replacement",e.replacement);o.put("guard",e.guard);o.put("issue",e.issue);o.put("place",e.place);o.put("seen",e.seen);}catch(JSONException ex){throw new IllegalStateException(ex);}a.put(o);
        }
        prefs.edit().putString("catalog:"+type,a.toString()).apply();
    }
    public synchronized void remember(Collection<Observation> rows) {
        boolean alerts=false,places=false;
        for(Observation o:rows){alerts|=rememberOne(ALERT,SpeechRules.clean(o.issue),o);places|=rememberOne(PLACE,SpeechRules.clean(o.property),o);}
        if(alerts){prune(ALERT);persist(ALERT);}if(places){prune(PLACE);persist(PLACE);}
    }
    private boolean rememberOne(String type,String original,Observation o) {
        if(original.isEmpty())return false;
        String key=SpeechRules.key(original);Entry old=map(type).get(key);
        // Existing source strings are stable: avoid rewriting storage on every 30-second read.
        if(old!=null&&old.seen>0)return false;
        map(type).put(key,new Entry(original,old==null?"":old.replacement,o.guard,o.issue,o.property,System.currentTimeMillis()));return true;
    }
    private void prune(String type) {
        LinkedHashMap<String,Entry> m=map(type);Iterator<Map.Entry<String,Entry>> it=m.entrySet().iterator();
        while(m.size()>1000&&it.hasNext()){Entry e=it.next().getValue();if(!e.edited())it.remove();}
    }
    public synchronized List<Entry> entries(String type) {
        ArrayList<Entry> list=new ArrayList<>(map(type).values());list.sort(Comparator.comparing((Entry e)->!e.edited()).thenComparing(e->e.original,String.CASE_INSENSITIVE_ORDER));return list;
    }
    public synchronized Entry entry(String type,String original) {return map(type).get(SpeechRules.key(original));}
    public synchronized void save(String type,String original,String replacement) {
        String source=SpeechRules.clean(original),spoken=replacement==null?"":replacement.trim();
        if(source.isEmpty())throw new IllegalArgumentException("Enter the original Silvertracker text.");
        if(source.length()>8000||spoken.length()>8000)throw new IllegalArgumentException("Keep each wording entry within 8,000 characters.");
        if(type.equals(PHRASE)&&(source.length()>100||map(type).size()>=150&&!map(type).containsKey(SpeechRules.key(source))))throw new IllegalArgumentException("Use a phrase of up to 100 characters; maximum 150 pronunciation rules.");
        String key=SpeechRules.key(source);Entry old=map(type).get(key);
        if(old==null&&map(type).size()>=2000)throw new IllegalArgumentException("This speech list is full. Reset unused custom entries first.");
        if(type.equals(PHRASE)&&spoken.isEmpty())map(type).remove(key);
        else map(type).put(key,new Entry(old==null?source:old.original,spoken,old==null?"":old.guard,old==null?"":old.issue,old==null?"":old.place,old==null?0:old.seen));
        persist(type);
    }
    public synchronized Map<String,String> overrides(String type) {
        LinkedHashMap<String,String> result=new LinkedHashMap<>();for(Entry e:map(type).values())if(e.edited())result.put(SpeechRules.key(e.original),e.replacement);return result;
    }
    public String nickname(String id){return prefs.getString("nickname:"+SpeechRules.guardKey(id),"");}
    public Map<String,String> nicknames() {
        Map<String,String> out=new LinkedHashMap<>();for(Map.Entry<String,?> e:prefs.getAll().entrySet())if(e.getKey().startsWith("nickname:"))out.put(e.getKey().substring(9),String.valueOf(e.getValue()));return out;
    }
    public void saveNicknames(Map<String,String> names) {
        SharedPreferences.Editor edit=prefs.edit();
        for(Map.Entry<String,String> e:names.entrySet()) {
            String name=e.getValue()==null?"":e.getValue().trim();if(name.length()>60)throw new IllegalArgumentException("Nicknames must be at most 60 characters.");
            // Retain an explicit blank: a later app start must not restore a default nickname.
            edit.putString("nickname:"+SpeechRules.guardKey(e.getKey()),name);
        }edit.apply();
    }
    public int speedPercent(){return SpeechRules.speedPercent(prefs.getInt("speed_percent",100));}
    public float speed(){return speedPercent()/100f;}
    public String prefix(){return prefs.getString("prefix","Silvertracker update");}
    public void saveDelivery(String prefix,int speed) {
        String s=prefix==null?"":prefix.trim();if(s.length()>150)throw new IllegalArgumentException("Opening phrase must be at most 150 characters.");
        prefs.edit().putString("prefix",s).putInt("speed_percent",SpeechRules.speedPercent(speed)).apply();
    }
    public synchronized String format(Observation row) {
        return SpeechRules.format(row,prefix(),nicknames(),overrides(ALERT),overrides(PLACE),overrides(PHRASE));
    }
    public synchronized String preview(Observation row,String type,String original,String replacement) {
        Map<String,String> alerts=overrides(ALERT),places=overrides(PLACE),phrases=overrides(PHRASE);
        Map<String,String> target=type.equals(ALERT)?alerts:type.equals(PLACE)?places:phrases;
        if(replacement.trim().isEmpty())target.remove(SpeechRules.key(original));else target.put(SpeechRules.key(original),replacement.trim());
        return SpeechRules.format(row,prefix(),nicknames(),alerts,places,phrases);
    }
}
