package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import java.time.*;
import java.util.*;

/**
 * Persistent checkpoint tallies for the active patrol shift.
 *
 * v1.1.20 defines the active shift from the most recent 18:00 in the configured
 * site time zone. A monitor row is counted once using BOTH its Issue ID and a
 * guard/checkpoint/timestamp identity, so revisiting the same Silvertracker page
 * cannot double-count it and duplicate issue rows at the same checkpoint/time
 * are also rejected.
 */
public final class CheckpointTracker {
    public static final String FILE="patrol_checkpoint_tally_v1";
    private static final String MIGRATED_V130="v130_shift_start_migrated";
    private static final int MAX_SEEN=5000;

    public static final class Point {
        public final String id,label,group;
        final String[] aliases;
        Point(String id,String label,String group,String...aliases){
            this.id=id;this.label=label;this.group=group;this.aliases=aliases;
        }
    }

    public static final List<Point> POINTS = Collections.unmodifiableList(Arrays.asList(
        new Point("ceil","Seal Circuit","PBC 2","seal circuit","ceil circuit","ceil bc","seal bc","general patrol ceil"),
        new Point("slipstream","Slipstream","PBC 2","slipstream"),
        new Point("boambillee","Boambillee","PBC 2","boambillee","bowen billy","bowenbilly","boam billee"),
        new Point("christina","Christina","PBC 2","christina","christine","christina bc"),
        new Point("rec1","Recreation Centre 1","PBC 2","recreational facility 1","recreation centre 1","rec centre 1"),
        new Point("rec2","Recreation Centre 2","PBC 2","recreational facility 2","recreation centre 2","rec centre 2","recreational facility gym","recreation centre gym"),
        new Point("solo","Solo Place","PBC 2","solo place","solo bc","general patrol solo"),
        new Point("impeccable","Impeccable","PBC 2","impeccable","impeccable bc"),
        new Point("quest","Quest","PBC 3","quest","quest bc"),
        new Point("eolo","Eolo","PBC 3","eolo","eolo bc","yolo"),
        new Point("bobsled","Bobsled","PBC 3","bobsled","bobsled bc"),
        new Point("elusive","Elusive","PBC 3","elusive","elusive bc"),
        new Point("serenade","Serenade","PBC 3","serenade","serenade bc")
    ));

    private final Context context;
    private final SharedPreferences prefs;
    private final LinkedHashSet<String> seenIds=new LinkedHashSet<>();
    private final LinkedHashSet<String> seenEvents=new LinkedHashSet<>();
    private final HashMap<String,Integer> counts=new HashMap<>();
    private long shiftStart;

    public CheckpointTracker(Context c){
        context=c.getApplicationContext();
        prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
        load();
        long expected=currentShiftStart(System.currentTimeMillis());
        boolean firstV130=!prefs.getBoolean(MIGRATED_V130,false);
        if(firstV130 || shiftStart<=0 || expected>shiftStart+60_000L){
            clearTo(expected);
            prefs.edit().putBoolean(MIGRATED_V130,true).apply();
        }
    }

    private ZoneId zone(){
        String id=context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE)
                .getString("zone","Australia/Brisbane");
        try{return ZoneId.of(id);}catch(Exception ignored){return ZoneId.of("Australia/Brisbane");}
    }

    long currentShiftStart(long now){
        ZoneId zone=zone();
        ZonedDateTime z=Instant.ofEpochMilli(now).atZone(zone);
        LocalDate date=z.toLocalDate();
        LocalTime start=LocalTime.of(18,0);
        if(z.toLocalTime().isBefore(start))date=date.minusDays(1);
        return ZonedDateTime.of(date,start,zone).toInstant().toEpochMilli();
    }

    private void load(){
        shiftStart=prefs.getLong("shift_start",0);
        loadSet("seen_ids",seenIds);
        if(seenIds.isEmpty())loadSet("seen",seenIds);
        loadSet("seen_events",seenEvents);
        try{
            JSONObject o=new JSONObject(prefs.getString("counts","{}"));
            Iterator<String> it=o.keys();
            while(it.hasNext()){String k=it.next();counts.put(k,o.optInt(k,0));}
        }catch(Exception ignored){}
    }

    private void loadSet(String key,LinkedHashSet<String> target){
        try{
            JSONArray a=new JSONArray(prefs.getString(key,"[]"));
            for(int i=0;i<a.length();i++){String value=a.optString(i);if(!value.isEmpty())target.add(value);}
        }catch(Exception ignored){}
    }

    public synchronized long shiftStart(){return shiftStart;}

    public synchronized void observe(Collection<Observation> rows){
        rollShiftIfNeeded();
        if(rows==null||rows.isEmpty())return;
        boolean changed=false;

        for(Observation o:rows){
            if(o==null || o.recordedAt<=0 || o.recordedAt<shiftStart)continue;
            Point p=match(o);
            if(p==null)continue;

            String id=o.id==null?"":o.id.trim();
            String event=eventKey(o,p);
            if((!id.isEmpty()&&seenIds.contains(id)) || seenEvents.contains(event))continue;

            if(!id.isEmpty())seenIds.add(id);
            seenEvents.add(event);
            String key=countKey(o.guard,p.id);
            counts.put(key,counts.getOrDefault(key,0)+1);
            changed=true;
        }

        changed|=prune(seenIds);
        changed|=prune(seenEvents);
        if(changed)persist();
    }

    private void rollShiftIfNeeded(){
        long expected=currentShiftStart(System.currentTimeMillis());
        if(expected>shiftStart+60_000L)clearTo(expected);
    }

    private static boolean prune(LinkedHashSet<String> set){
        boolean changed=false;Iterator<String> it=set.iterator();
        while(set.size()>MAX_SEEN&&it.hasNext()){it.next();it.remove();changed=true;}
        return changed;
    }

    public synchronized int count(String guard,String pointId){
        return counts.getOrDefault(countKey(guard,pointId),0);
    }

    public synchronized int groupTotal(String guard,String group){
        int n=0;for(Point p:POINTS)if(p.group.equals(group))n+=count(guard,p.id);return n;
    }

    public synchronized int total(String guard){
        int n=0;for(Point p:POINTS)n+=count(guard,p.id);return n;
    }

    /** Clear the tally and return it to the active 18:00 shift boundary. */
    public synchronized void reset(){
        clearTo(currentShiftStart(System.currentTimeMillis()));
    }

    synchronized void clearTo(long start){
        seenIds.clear();seenEvents.clear();counts.clear();shiftStart=start;persist();
    }

    public static Point match(Observation o){
        if(o==null)return null;
        String issue=norm(o.issue);
        boolean patrol=issue.contains("general patrol")||issue.contains("guard patrol")
                ||issue.contains("scan point")||issue.contains("patrol scan");
        if(!patrol)return null;
        String combined=" "+norm((o.issue==null?"":o.issue)+" "+(o.property==null?"":o.property))+" ";
        for(Point p:POINTS){
            for(String alias:p.aliases){
                String a=norm(alias);
                if(!a.isEmpty()&&combined.contains(" "+a+" "))return p;
            }
        }
        return null;
    }

    private static String eventKey(Observation o,Point p){
        return guardKey(o.guard)+"|"+p.id+"|"+o.recordedAt;
    }
    private static String countKey(String guard,String point){return guardKey(guard)+"|"+point;}
    private static String guardKey(String s){return norm(s).replace(" ","");}
    private static String norm(String s){
        if(s==null)return "";
        return s.toLowerCase(Locale.ROOT).replace('&',' ')
                .replaceAll("[^a-z0-9]+"," ").replaceAll("\\s+"," ").trim();
    }

    private void persist(){
        JSONArray ids=new JSONArray();for(String id:seenIds)ids.put(id);
        JSONArray events=new JSONArray();for(String event:seenEvents)events.put(event);
        JSONObject o=new JSONObject();
        for(Map.Entry<String,Integer> e:counts.entrySet())try{o.put(e.getKey(),e.getValue());}catch(Exception ignored){}
        prefs.edit().putLong("shift_start",shiftStart)
                .putString("seen_ids",ids.toString())
                .putString("seen_events",events.toString())
                .putString("counts",o.toString())
                .putBoolean(MIGRATED_V130,true)
                .remove("seen")
                .apply();
    }
}
