package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;

/**
 * Dynamic shift checkpoint tally.
 *
 * A hit is unique ONLY by:
 *   exact guard + canonical area + recorded Silvertracker timestamp.
 *
 * Issue ID is deliberately NOT part of duplicate handling. The same area/time
 * hit by two different guards is two hits; the same guard/area/time seen again
 * while paging is one hit.
 *
 * Known aliases are canonicalised for continuity, but they are never a whitelist:
 * any new valid patrol/scan area from Silvertracker is accepted automatically.
 */
public final class CheckpointTracker {
    public static final String FILE="patrol_checkpoint_tally_v1";
    private static final String MIGRATED_V132="v132_dynamic_area_migrated";
    private static final int MAX_SEEN=8000;

    public static final class AreaCount {
        public final String key,label;
        public final int count;
        public final long lastAt;
        AreaCount(String key,String label,int count,long lastAt){
            this.key=key;this.label=label;this.count=count;this.lastAt=lastAt;
        }
    }

    private static final class Area {
        final String key,label;
        Area(String key,String label){this.key=key;this.label=label;}
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final LinkedHashSet<String> seenEvents=new LinkedHashSet<>();
    private final LinkedHashMap<String,Integer> counts=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> labels=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> lastTimes=new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> lastPageAdded=new LinkedHashMap<>();
    private long shiftStart;

    public CheckpointTracker(Context c){
        context=c.getApplicationContext();
        prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
        load();
        long expected=currentShiftStart(System.currentTimeMillis());

        if(!prefs.getBoolean(MIGRATED_V132,false)){
            migrateLegacyCounts();
            shiftStart=(shiftStart>0 && shiftStart>=expected-60_000L)?shiftStart:expected;
            prefs.edit().putBoolean(MIGRATED_V132,true).apply();
            persist();
        } else if(shiftStart<=0 || expected>shiftStart+60_000L){
            clearTo(expected);
        }
    }

    private ZoneId zone(){
        String id=context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE)
                .getString("zone","Australia/Brisbane");
        try{return ZoneId.of(id);}catch(Exception ignored){return ZoneId.of("Australia/Brisbane");}
    }

    long currentShiftStart(long now){
        ZoneId z=zone();
        ZonedDateTime at=Instant.ofEpochMilli(now).atZone(z);
        LocalDate date=at.toLocalDate();
        LocalTime start=LocalTime.of(18,0);
        if(at.toLocalTime().isBefore(start))date=date.minusDays(1);
        return ZonedDateTime.of(date,start,z).toInstant().toEpochMilli();
    }

    private void load(){
        shiftStart=prefs.getLong("shift_start",0);
        loadSet("seen_events",seenEvents);
        try{
            JSONObject o=new JSONObject(prefs.getString("counts","{}"));
            Iterator<String> it=o.keys();
            while(it.hasNext()){String k=it.next();counts.put(k,o.optInt(k,0));}
        }catch(Exception ignored){}
        try{
            JSONObject o=new JSONObject(prefs.getString("labels","{}"));
            Iterator<String> it=o.keys();
            while(it.hasNext()){String k=it.next();labels.put(k,o.optString(k,k));}
        }catch(Exception ignored){}
        try{
            JSONObject o=new JSONObject(prefs.getString("last_times","{}"));
            Iterator<String> it=o.keys();
            while(it.hasNext()){String k=it.next();lastTimes.put(k,o.optLong(k,0));}
        }catch(Exception ignored){}
    }

    private void loadSet(String key,LinkedHashSet<String> target){
        try{
            JSONArray a=new JSONArray(prefs.getString(key,"[]"));
            for(int i=0;i<a.length();i++){
                String value=a.optString(i);
                if(!value.isEmpty())target.add(value);
            }
        }catch(Exception ignored){}
    }

    private void migrateLegacyCounts(){
        if(counts.isEmpty())return;
        LinkedHashMap<String,Integer> migrated=new LinkedHashMap<>();
        for(Map.Entry<String,Integer> e:new ArrayList<>(counts.entrySet())){
            String old=e.getKey();
            int split=old.indexOf('|');
            if(split<=0){migrated.put(old,e.getValue());continue;}
            String guard=old.substring(0,split);
            String oldArea=old.substring(split+1);

            Area a=legacyArea(oldArea);
            if(a==null){
                // Already dynamic or unknown: keep it.
                migrated.put(old,e.getValue());
                continue;
            }
            String key=guard+"|"+a.key;
            migrated.put(key,migrated.getOrDefault(key,0)+e.getValue());
            labels.put(a.key,a.label);
        }
        counts.clear();
        counts.putAll(migrated);
    }

    private static Area legacyArea(String id){
        switch(id){
            case "ceil": return new Area("seal-circuit","Seal Circuit");
            case "slipstream": return new Area("slipstream","Slipstream");
            case "boambillee": return new Area("boambillee","Boambillee");
            case "christina": return new Area("christina","Christina");
            case "rec1": return new Area("rec-center-2-barbecue-area","Rec Center 2 Barbecue Area");
            case "rec2": return new Area("rec-center-2-gym","Rec Center 2 Gym");
            case "solo": return new Area("solo-place","Solo Place");
            case "impeccable": return new Area("impeccable","Impeccable");
            case "quest": return new Area("quest","Quest");
            case "eolo": return new Area("eolo","Eolo");
            case "bobsled": return new Area("bobsled-lane","Bobsled Lane");
            case "elusive": return new Area("elusive","Elusive");
            case "serenade": return new Area("serenade","Serenade");
            default: return null;
        }
    }

    public synchronized long shiftStart(){return shiftStart;}

    /**
     * Observe one visible Silvertracker page/current feed snapshot.
     * Returns total newly-added checkpoint hits from this call.
     */
    public synchronized int observe(Collection<Observation> rows){
        rollShiftIfNeeded();
        lastPageAdded.clear();
        if(rows==null||rows.isEmpty())return 0;

        ArrayList<Observation> ordered=new ArrayList<>();
        for(Observation o:rows)if(o!=null)ordered.add(o);
        ordered.sort(Comparator.comparingLong(o->o.recordedAt));

        boolean changed=false;
        int added=0;
        for(Observation o:ordered){
            if(o.recordedAt<=0 || o.recordedAt<shiftStart)continue;
            Area area=areaFor(o);
            if(area==null)continue;

            String guard=guardKey(o.guard);
            if(guard.isEmpty())continue;

            String event=guard+"|"+area.key+"|"+o.recordedAt;
            if(seenEvents.contains(event))continue;

            seenEvents.add(event);
            labels.put(area.key,area.label);
            String key=guard+"|"+area.key;
            counts.put(key,counts.getOrDefault(key,0)+1);
            lastTimes.put(key,Math.max(lastTimes.getOrDefault(key,0L),o.recordedAt));
            lastPageAdded.put(guard,lastPageAdded.getOrDefault(guard,0)+1);
            changed=true;
            added++;
        }

        changed|=prune(seenEvents);
        if(changed)persist();
        return added;
    }

    private void rollShiftIfNeeded(){
        long expected=currentShiftStart(System.currentTimeMillis());
        if(expected>shiftStart+60_000L)clearTo(expected);
    }

    private static boolean prune(LinkedHashSet<String> set){
        boolean changed=false;
        Iterator<String> it=set.iterator();
        while(set.size()>MAX_SEEN&&it.hasNext()){
            it.next();it.remove();changed=true;
        }
        return changed;
    }

    public synchronized int lastPageAdded(String guard){
        return lastPageAdded.getOrDefault(guardKey(guard),0);
    }

    public synchronized int total(String guard){
        String prefix=guardKey(guard)+"|";
        int n=0;
        for(Map.Entry<String,Integer> e:counts.entrySet())
            if(e.getKey().startsWith(prefix))n+=e.getValue();
        return n;
    }

    public synchronized List<AreaCount> areas(String guard){
        String prefix=guardKey(guard)+"|";
        ArrayList<AreaCount> out=new ArrayList<>();
        for(Map.Entry<String,Integer> e:counts.entrySet()){
            if(!e.getKey().startsWith(prefix))continue;
            String areaKey=e.getKey().substring(prefix.length());
            out.add(new AreaCount(areaKey,labels.getOrDefault(areaKey,displayFromKey(areaKey)),
                    e.getValue(),lastTimes.getOrDefault(e.getKey(),0L)));
        }
        out.sort(Comparator.comparing((AreaCount a)->a.label,String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Clear and restart at the current 18:00 shift boundary. */
    public synchronized void reset(){clearTo(currentShiftStart(System.currentTimeMillis()));}

    synchronized void clearTo(long start){
        seenEvents.clear();counts.clear();labels.clear();lastTimes.clear();lastPageAdded.clear();
        shiftStart=start;persist();
    }

    static Area areaFor(Observation o){
        if(o==null)return null;
        String issue=clean(o.issue);
        String property=clean(o.property);
        String ni=norm(issue), np=norm(property);

        boolean patrol=ni.contains("general patrol")||ni.contains("guard patrol")
                ||ni.contains("patrol scan")||ni.contains("scan point");
        boolean directCheckpoint=!np.isEmpty() && ni.equals(np);

        if(!patrol && !directCheckpoint)return null;

        String raw=!property.isEmpty()?property:stripPatrolPrefix(issue);
        if(raw.isEmpty())return null;
        return canonicalArea(raw,issue);
    }

    private static Area canonicalArea(String raw,String issue){
        String n=norm(raw+" "+issue);

        if(containsAny(n,"recreational facility gym","recreation centre gym","recreation center gym",
                "rec centre gym","rec center gym","rec center 2 gym","recreation center 2 gym"))
            return new Area("rec-center-2-gym","Rec Center 2 Gym");

        if(containsAny(n,"recreational facility 1","recreational facility bbq","recreational facility barbecue",
                "recreation centre bbq","recreation center bbq","rec centre bbq","rec center bbq",
                "bbq area","barbecue area","rec center 2 barbecue area","recreation center 2 barbecue area"))
            return new Area("rec-center-2-barbecue-area","Rec Center 2 Barbecue Area");

        if(containsAny(n,"seal circuit","ceil circuit","ceil bc","seal bc"))
            return new Area("seal-circuit","Seal Circuit");
        if(n.contains("slipstream"))return new Area("slipstream","Slipstream");
        if(n.contains("boambillee"))return new Area("boambillee","Boambillee");
        if(n.contains("christina"))return new Area("christina","Christina");
        if(containsAny(n,"solo place","solo bc"))return new Area("solo-place","Solo Place");
        if(n.contains("impeccable"))return new Area("impeccable","Impeccable");
        if(n.contains("quest"))return new Area("quest","Quest");
        if(n.contains("eolo"))return new Area("eolo","Eolo");
        if(n.contains("bobsled"))return new Area("bobsled-lane","Bobsled Lane");
        if(n.contains("elusive"))return new Area("elusive","Elusive");
        if(n.contains("serenade"))return new Area("serenade","Serenade");
        if(n.contains("balmara"))return new Area("balmara-place","Balmara Place");
        if(n.contains("tradition"))return new Area("tradition-place","Tradition Place");
        if(n.contains("rampage"))return new Area("rampage-bc","Rampage BC");

        String label=clean(raw);
        return new Area(slug(label),label);
    }

    private static boolean containsAny(String haystack,String...needles){
        for(String n:needles)if(haystack.contains(n))return true;
        return false;
    }

    private static String stripPatrolPrefix(String s){
        String x=clean(s);
        return x.replaceFirst("(?i)^\\s*(general|guard)\\s+patrol\\s*[-:]?\\s*","").trim();
    }

    private static String displayFromKey(String key){
        String[] parts=key.split("-");
        StringBuilder b=new StringBuilder();
        for(String p:parts){
            if(p.isEmpty())continue;
            if(b.length()>0)b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    private static String guardKey(String s){return norm(s).replace(" ","");}
    private static String slug(String s){return norm(s).replace(' ','-');}
    private static String clean(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
    private static String norm(String s){
        return clean(s).toLowerCase(Locale.ROOT).replace('&',' ')
                .replaceAll("[^a-z0-9]+"," ").replaceAll("\\s+"," ").trim();
    }

    private void persist(){
        JSONArray events=new JSONArray();for(String event:seenEvents)events.put(event);
        JSONObject c=new JSONObject(), l=new JSONObject(), t=new JSONObject();
        try{
            for(Map.Entry<String,Integer> e:counts.entrySet())c.put(e.getKey(),e.getValue());
            for(Map.Entry<String,String> e:labels.entrySet())l.put(e.getKey(),e.getValue());
            for(Map.Entry<String,Long> e:lastTimes.entrySet())t.put(e.getKey(),e.getValue());
        }catch(Exception ignored){}

        prefs.edit().putLong("shift_start",shiftStart)
                .putString("seen_events",events.toString())
                .putString("counts",c.toString())
                .putString("labels",l.toString())
                .putString("last_times",t.toString())
                .putBoolean(MIGRATED_V132,true)
                .remove("seen").remove("seen_ids")
                .apply();
    }
}
