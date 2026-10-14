package au.com.roningroup.patrollink;
import java.util.*;
/** Two distinct accurate fixes arm a prompt. Entry radius is never enlarged by GPS accuracy. */
final class CheckpointGate {
 static final class Candidate {final String id;final float distance,radius;Candidate(String i,float d,float r){id=i;distance=d;radius=r;}}
 static final class Entry {final Candidate checkpoint;final long enteredAt;final boolean newEntry;Entry(Candidate c,long t,boolean n){checkpoint=c;enteredAt=t;newEntry=n;}}
 private String active="",pending="";
 private int samples;
 private long lastFix,enteredAt;
 private final Set<String> suppressed=new HashSet<>();
 private float exitRadius(Candidate c){return c.radius+Math.max(2,Math.min(10,c.radius*.25f));}
 Entry update(List<Candidate> points,float accuracy,long ageMs,long fixStamp,boolean precise,long now){
  if(!precise||ageMs<0||ageMs>10000||!Float.isFinite(accuracy)||accuracy<=0){pending="";samples=0;return null;}
  Map<String,Candidate> byId=new HashMap<>();
  for(Candidate c:points)if(c!=null&&c.id!=null&&Float.isFinite(c.distance)&&c.distance>=0&&Float.isFinite(c.radius)&&c.radius>=1&&c.radius<=500)byId.put(c.id,c);
  suppressed.removeIf(id->{Candidate c=byId.get(id);return c==null||(accuracy<=c.radius&&c.distance>exitRadius(c));});
  Candidate current=byId.get(active);
  if(current!=null&&!suppressed.contains(active)&&current.distance<=exitRadius(current)){
   if(accuracy>current.radius||current.distance>current.radius)return null;
   return new Entry(current,enteredAt,false);
  }
  active="";
  Candidate nearest=null;
  for(Candidate c:byId.values())if(!suppressed.contains(c.id)&&c.distance<=c.radius&&accuracy<=c.radius&&(nearest==null||c.distance<nearest.distance))nearest=c;
  if(nearest==null){pending="";samples=0;return null;}
  if(!nearest.id.equals(pending)){pending=nearest.id;samples=0;lastFix=Long.MIN_VALUE;}
  if(fixStamp>lastFix){samples++;lastFix=fixStamp;}
  if(samples<2)return null;
  active=nearest.id;enteredAt=now;pending="";samples=0;
  return new Entry(nearest,enteredAt,true);
 }
 void dismiss(String id){if(id!=null&&!id.isEmpty())suppressed.add(id);if(active.equals(id))active="";pending="";samples=0;}
}