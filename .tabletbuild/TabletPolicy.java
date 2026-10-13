package au.com.roningroup.patrollink;
/** Validation shared by the view, fleet parser and proximity policy. No Android dependencies. */
final class TabletPolicy {
 static boolean point(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180;}
 static float radius(float r){if(!Float.isFinite(r)||r<1||r>500)throw new IllegalArgumentException("Use a distance from 1 to 500 metres.");return r;}
 static long age(long recorded,long now){return recorded<=0||recorded>now+120000?Long.MAX_VALUE:Math.max(0,now-recorded);}
 static boolean fresh(long recorded,long now){return age(recorded,now)<=90000;}
 static int interval(int seconds){return seconds==30||seconds==60?seconds:15;}
 static boolean samePoint(double a,double b,double c,double d){return Double.compare(a,c)==0&&Double.compare(b,d)==0;}
 static String area(String text){String s=String.valueOf(text).toUpperCase().replaceAll("\\s+","");if(s.contains("PBC1"))return "PBC1";if(s.contains("PBC2"))return "PBC2";if(s.contains("PBC3"))return "PBC3";return "";}
 private TabletPolicy(){}
}