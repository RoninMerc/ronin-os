from pathlib import Path
import sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'

def rep(path,old,new):
    s=path.read_text()
    if old not in s:
        raise RuntimeError('Patch anchor missing in '+str(path)+': '+old[:180])
    path.write_text(s.replace(old,new,1))

rep(root/'app/build.gradle','versionCode 143','versionCode 144')
rep(root/'app/build.gradle',"versionName '1.1.33'","versionName '1.1.34'")

# Fixed spoken opening, independent of all user-editable wording/place names.
p=java/'SpeechPreferences.java'; s=p.read_text()
old='public String prefix(){return prefs.getString("prefix","Silvertracker update");}'
if old in s:
    s=s.replace(old,'public String prefix(){return "Silver Track update";}',1)
elif 'public String prefix(){return "Silver Track update";}' not in s:
    raise RuntimeError('Speech prefix method not found')

old_fmt='''    public synchronized String format(Observation row) {
        Map<String,String> alerts=overrides(ALERT);
        String replacement=SpeechRules.clean(alerts.get(SpeechRules.key(row.issue)));
        if(!replacement.isEmpty()) {
            // User explicitly chose "say this instead": say exactly this and nothing else.
            return SpeechRules.phrases(replacement,overrides(PHRASE));
        }
        return SpeechRules.format(row,prefix(),nicknames(),alerts,overrides(PLACE),overrides(PHRASE));
    }'''
new_fmt='''    public synchronized String format(Observation row) {
        Map<String,String> alerts=overrides(ALERT);
        String replacement=SpeechRules.clean(alerts.get(SpeechRules.key(row.issue)));
        if(!replacement.isEmpty()) {
            // Alert edits replace only the body. Operational prefix + spoken guard are always retained.
            String body=SpeechRules.phrases(replacement,overrides(PHRASE));
            String guard=SpeechRules.guard(row.guard,nicknames());
            String out="Silver Track update. "+guard+". "+body;
            if(!out.matches("(?s).*[.!?]$"))out+=".";
            return out;
        }
        return SpeechRules.format(row,"Silver Track update",nicknames(),alerts,overrides(PLACE),overrides(PHRASE));
    }'''
if old_fmt not in s:
    raise RuntimeError('Current live SpeechPreferences.format anchor not found')
s=s.replace(old_fmt,new_fmt,1)

old_save='''    public void saveDelivery(String prefix,int speed) {
        String s=prefix==null?"":prefix.trim();if(s.length()>150)throw new IllegalArgumentException("Opening phrase must be at most 150 characters.");
        prefs.edit().putString("prefix",s).putInt("speed_percent",SpeechRules.speedPercent(speed)).apply();
    }'''
new_save='''    public void saveDelivery(String prefix,int speed) {
        prefs.edit().putString("prefix","Silver Track update").putInt("speed_percent",SpeechRules.speedPercent(speed)).apply();
    }'''
if old_save in s:
    s=s.replace(old_save,new_save,1)
p.write_text(s)

# PBC2/3 checkpoint operational route.
p=java/'CheckpointProximityUi.java'; s=p.read_text()
s=s.replace('    private static final float DEFAULT_TRIGGER=70f, CLEAR_RADIUS=140f;','''    private static final float DEFAULT_TRIGGER=70f, CLEAR_RADIUS=140f;
    /** PBC2/3 operational route order. IDs stay stable even when display labels are edited later. */
    private static final List<String> ROUTE_IDS=Arrays.asList(
        "seal-circuit","rec-centre","slipstream","bobsled","christina","solo-place",
        "impeccable","quest","eolo","elusive","serenade"
    );''',1)

s=s.replace('    private Checkpoint find(String id){for(Checkpoint c:checkpoints)if(c.id.equals(id))return c;return null;}','''    private Checkpoint find(String id){for(Checkpoint c:checkpoints)if(c.id.equals(id))return c;return null;}
    private static int routeRank(Checkpoint c){int i=ROUTE_IDS.indexOf(c.id);return i<0?1000:i;}
    private static ArrayList<Checkpoint> routeSorted(Collection<Checkpoint> source){ArrayList<Checkpoint> out=new ArrayList<>(source);out.sort(Comparator.comparingInt(CheckpointProximityUi::routeRank).thenComparing(c->c.name,String.CASE_INSENSITIVE_ORDER));return out;}''',1)

old_load='private void load(){checkpoints.clear();try{JSONArray a=new JSONArray(prefs.getString(KEY,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)checkpoints.add(new Checkpoint(o.optString("id"),o.optString("name"),o.optDouble("lat"),o.optDouble("lon"),(float)o.optDouble("radius",DEFAULT_TRIGGER)));}}catch(Exception ignored){}}'
new_load='private void load(){checkpoints.clear();try{JSONArray a=new JSONArray(prefs.getString(KEY,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null){String name=o.optString("name"),id=o.optString("id");String canonical=canonicalId(name);if(ROUTE_IDS.contains(canonical))id=canonical;checkpoints.add(new Checkpoint(id,name,o.optDouble("lat"),o.optDouble("lon"),(float)o.optDouble("radius",DEFAULT_TRIGGER)));}}}catch(Exception ignored){}}'
if old_load not in s: raise RuntimeError('Checkpoint load anchor not found')
s=s.replace(old_load,new_load,1)

old_loop='for(Checkpoint c:new ArrayList<>(checkpoints)){LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(6),0,dp(10));row.addView(text(c.name,16,0xffeef4f9,true));row.addView(text(String.format(Locale.ROOT,"%.6f, %.6f · trigger %.0f m",c.lat,c.lon,c.radius),12,0xff9daebb,false));if(supervisor())row.addView(button("Update location to my current GPS",()->register(c.name,c.id)));form.addView(row);}'
new_loop='''for(Checkpoint c:routeSorted(checkpoints)){
            int rank=routeRank(c);String routeLabel=rank<1000?String.format(Locale.ROOT,"%02d",rank+1):"—";
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(6),0,dp(10));
            row.addView(text(routeLabel+"  "+c.name,16,0xffeef4f9,true));
            row.addView(text(String.format(Locale.ROOT,"%.6f, %.6f · trigger %.0f m",c.lat,c.lon,c.radius),12,0xff9daebb,false));
            if(supervisor())row.addView(button("Update location to my current GPS",()->register(c.name,c.id)));form.addView(row);
        }'''
if old_loop not in s: raise RuntimeError('Checkpoint manager loop anchor not found')
s=s.replace(old_loop,new_loop,1)

old_opts='Spinner names=new Spinner(activity);ArrayList<String> options=new ArrayList<>(Arrays.asList("Ceil 001","Slipstream 001","Boambillee 001","General Patrol - Christina BC","General Patrol - Solo BC","Serenade 001","Eolo BC 001","General Patrol - Bobsled BC","Elusive BC 001","Custom checkpoint…"));ArrayAdapter<String> ad=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,options);ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);names.setAdapter(ad);form.addView(names);'
new_opts='''Spinner names=new Spinner(activity);ArrayList<String> options=new ArrayList<>(Arrays.asList(
                "Seal Circuit","Rec Centre","Slipstream","Bobsled Lane","Christina","Solo Place","Impeccable","Quest","Eolo","Elusive","Serenade","Custom checkpoint…"));
            ArrayAdapter<String> ad=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,options);ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);names.setAdapter(ad);form.addView(names);'''
if old_opts not in s: raise RuntimeError('Checkpoint options anchor not found')
s=s.replace(old_opts,new_opts,1)
s=s.replace('register(n,slug(n));','register(n,canonicalId(n));',1)

old_slug='    private static String slug(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");}'
new_slug='''    private static String canonicalId(String name){
        String k=slug(name);
        if(k.startsWith("general-patrol-"))k=k.substring("general-patrol-".length());
        if(k.startsWith("seal")||k.startsWith("ceil"))return "seal-circuit";
        if(k.startsWith("rec-centre")||k.startsWith("rec-center"))return "rec-centre";
        if(k.startsWith("slipstream"))return "slipstream";
        if(k.startsWith("bobsled"))return "bobsled";
        if(k.startsWith("christina"))return "christina";
        if(k.startsWith("solo"))return "solo-place";
        if(k.startsWith("impeccable"))return "impeccable";
        if(k.startsWith("quest"))return "quest";
        if(k.startsWith("eolo"))return "eolo";
        if(k.startsWith("elusive"))return "elusive";
        if(k.startsWith("serenade"))return "serenade";
        return k;
    }
    private static String slug(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");}'''
if old_slug not in s: raise RuntimeError('Checkpoint slug anchor not found')
s=s.replace(old_slug,new_slug,1)
p.write_text(s)

print('Applied v1.1.34: fixed Silver Track update + guard opening and PBC2/3 route order.')
