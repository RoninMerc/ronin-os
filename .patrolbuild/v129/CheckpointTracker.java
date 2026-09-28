package au.com.roningroup.patrollink;

import android.content.*;
import org.json.*;
import java.util.*;

/**
 * Persistent per-guard checkpoint tallies for the current patrol shift.
 * A row is counted once by Issue ID and only when it looks like a patrol/scan event.
 */
public final class CheckpointTracker {
    public static final String FILE="patrol_checkpoint_tally_v1";

    public static final class Point {
        public final String id,label,group;
        final String[] aliases;
        Point(String id,String label,String group,String...aliases){this.id=id;this.label=label;this.group=group;this.aliases=aliases;}
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

    private final SharedPreferences prefs;
    private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private final HashMap<String,Integer> counts=new HashMap<>();
    private long shiftStart;

    public CheckpointTracker(Context c){
        prefs=c.getSharedPreferences(FILE,Context.MODE_PRIVATE);
        load();
    }

    private void load(){
        shiftStart=prefs.getLong("shift_start",0);
        try{
            JSONArray a=new JSONArray(prefs.getString("seen","[]"));
            for(int i=0;i<a.length();i++){String id=a.optString(i);if(!id.isEmpty())seen.add(id);}
        }catch(Exception ignored){}
        try{
            JSONObject o=new JSONObject(prefs.getString("counts","{}"));
            Iterator<String> it=o.keys();
            while(it.hasNext()){String k=it.next();counts.put(k,o.optInt(k,0));}
        }catch(Exception ignored){}
    }

    public synchronized long shiftStart(){return shiftStart;}

    public synchronized void observe(Collection<Observation> rows){
        if(rows==null||rows.isEmpty())return;
        long now=System.currentTimeMillis();
        if(shiftStart<=0){
            long earliest=Long.MAX_VALUE;
            for(Observation o:rows)if(o!=null&&o.recordedAt>0&&o.recordedAt<earliest)earliest=o.recordedAt;
            // Pull in the currently visible monitor history on first use, but never start more than 12h back.
            shiftStart=(earliest!=Long.MAX_VALUE&&now-earliest<=12L*60*60*1000)?earliest:now;
        }
        boolean changed=false;
        for(Observation o:rows){
            if(o==null||o.id==null||o.id.trim().isEmpty()||seen.contains(o.id))continue;
            if(o.recordedAt>0&&o.recordedAt+60_000L<shiftStart)continue;
            Point p=match(o);
            if(p==null)continue;
            seen.add(o.id);
            String key=countKey(o.guard,p.id);
            counts.put(key,counts.getOrDefault(key,0)+1);
            changed=true;
        }
        if(seen.size()>2500){
            Iterator<String> it=seen.iterator();
            while(seen.size()>2000&&it.hasNext()){it.next();it.remove();}
            changed=true;
        }
        if(changed)persist();
        else if(!prefs.contains("shift_start"))persist();
    }

    public synchronized int count(String guard,String pointId){return counts.getOrDefault(countKey(guard,pointId),0);}

    public synchronized int groupTotal(String guard,String group){
        int n=0;for(Point p:POINTS)if(p.group.equals(group))n+=count(guard,p.id);return n;
    }

    public synchronized int total(String guard){
        int n=0;for(Point p:POINTS)n+=count(guard,p.id);return n;
    }

    public synchronized void reset(){
        seen.clear();counts.clear();shiftStart=System.currentTimeMillis();persist();
    }

    public static Point match(Observation o){
        if(o==null)return null;
        String issue=norm(o.issue);
        // Do not turn ordinary incident reports at a property into checkpoint scans.
        boolean patrol=issue.contains("general patrol")||issue.contains("guard patrol")||issue.contains("scan point")||issue.contains("patrol scan");
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

    private static String countKey(String guard,String point){return guardKey(guard)+"|"+point;}
    private static String guardKey(String s){return norm(s).replace(" ","");}
    private static String norm(String s){
        if(s==null)return "";
        return s.toLowerCase(Locale.ROOT).replace('&',' ').replaceAll("[^a-z0-9]+"," ").replaceAll("\\s+"," ").trim();
    }

    private void persist(){
        JSONArray a=new JSONArray();for(String id:seen)a.put(id);
        JSONObject o=new JSONObject();for(Map.Entry<String,Integer> e:counts.entrySet())try{o.put(e.getKey(),e.getValue());}catch(Exception ignored){}
        prefs.edit().putLong("shift_start",shiftStart).putString("seen",a.toString()).putString("counts",o.toString()).apply();
    }
}
