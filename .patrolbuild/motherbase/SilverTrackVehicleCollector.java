package au.com.roningroup.patrollink;
import android.content.Context;
import android.os.*;
import android.webkit.*;
import org.json.*;
import java.time.ZoneId;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Read-only detail capture in the existing Master cookie session. No credentials or cookies are uploaded. */
final class SilverTrackVehicleCollector{
 private static final Handler UI=new Handler(Looper.getMainLooper());
 private static final ArrayDeque<JSONObject> pending=new ArrayDeque<>();
 private static final Map<String,Long> completed=new HashMap<>();
 private static WebView detail;
 private static Context context;
 private static ZoneId timezone;
 private static JSONObject active;
 private static String identity="";
 private static int epoch,attempts;
 static void collect(Context c,WebView monitor,JSONArray rows,ZoneId zone){
  if(!MasterFeedClient.master(c)||monitor==null)return;
  if(rows==null||rows.length()==0){scanNote(c,"No monitor rows were read. Load the parking-breach results and capture again.");return;}
  String current=monitor.getUrl();
  if(current==null||!current.startsWith("https://trlsecurity.silvertracker.net/AdminCustomer/Issues/"))return;
  context=c.getApplicationContext();timezone=zone;
  int eligible=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optString("issue").matches("(?is).*\\b(?:parking breach|vehicle breach|parking violation)\\b.*"))eligible++;}
  final int parkingRows=eligible;
  scanNote(c,"Reading "+rows.length()+" monitor rows ("+parkingRows+" parking-breach rows)…");
  String script=LINKS+"("+rows.toString()+")";
  monitor.evaluateJavascript("JSON.stringify("+script+")",encoded->{
   try{
    Object text=new JSONTokener(encoded).nextValue();if(!(text instanceof String))return;
    JSONArray targets=new JSONArray((String)text);
    for(int i=0;i<targets.length();i++){
     JSONObject item=targets.getJSONObject(i);String key=item.getString("identity");long last=completed.containsKey(key)?completed.get(key):0;
     if(System.currentTimeMillis()-last<300000||key.equals(identity))continue;
     boolean queued=false;for(JSONObject q:pending)if(key.equals(q.optString("identity")))queued=true;
     if(!queued&&pending.size()<30)pending.add(item);
    }
    scanNote(c,"Parking-breach rows: "+parkingRows+" | Read-only report links: "+targets.length()+" | Waiting: "+pending.size()+(targets.length()==0?"\nNo eligible detail links found on this page.":""));
    next();
   }catch(Exception ignored){scanNote(c,"Monitor links could not be read. Wait for the results to finish loading and capture again.");}
  });
 }
 private static void next(){
  if(detail!=null||pending.isEmpty()||context==null||!MasterFeedClient.master(context))return;
  active=pending.remove();identity=active.optString("identity");attempts=0;final int token=++epoch;
  try{
   detail=new WebView(context);detail.getSettings().setJavaScriptEnabled(true);detail.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
   detail.getSettings().setAllowFileAccess(false);detail.getSettings().setAllowContentAccess(false);
   detail.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){return !allowed(r.getUrl().toString());}
    @Override public void onReceivedSslError(WebView w,android.webkit.SslErrorHandler handler,android.net.http.SslError e){handler.cancel();finish(token,false);}
    @Override public boolean onRenderProcessGone(WebView w,RenderProcessGoneDetail d){finish(token,false);return true;}
    @Override public void onPageFinished(WebView w,String url){if(token==epoch&&allowed(url))read(token);}
   });
   String url=active.getString("url");if(!allowed(url)){finish(token,false);return;}
   detail.loadUrl(url);UI.postDelayed(()->finish(token,false),18000);
  }catch(Exception e){finish(token,false);}
 }
 private static boolean allowed(String url){
  try{java.net.URI u=new java.net.URI(url);return "https".equalsIgnoreCase(u.getScheme())&&"trlsecurity.silvertracker.net".equalsIgnoreCase(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&u.getPath().matches("(?i)/AdminCustomer/Issues/(?:view(?:issue|action|report)?(?:details?)?|(?:issue|action|report)?details?|(?:issue|action)?reports?|(?:issue|action|report)view)\\.aspx")&&safeMode(url);}catch(Exception e){return false;}
 }
 private static boolean safeMode(String url){
  try{android.net.Uri u=android.net.Uri.parse(url);for(String k:u.getQueryParameterNames())if(k.matches("(?i)action|mode|cmd|operation")&&!String.valueOf(u.getQueryParameter(k)).matches("(?i)view|detail|details|read|report|print"))return false;return true;}catch(Exception e){return false;}
 }
 private static void read(int token){
  if(token!=epoch||detail==null||attempts++>=4)return;
  detail.evaluateJavascript("JSON.stringify("+FIELDS+"())",encoded->{
   if(token!=epoch||detail==null)return;
   try{
    Object text=new JSONTokener(encoded).nextValue();if(!(text instanceof String))throw new JSONException("No report fields");
    JSONObject result=new JSONObject((String)text);
    if(result.optBoolean("login")){finish(token,false,"Report opened a sign-in page. Sign into SilverTrack on this Master phone again.");return;}
    JSONObject vehicle=result.optJSONObject("vehicle");
    if(vehicle==null){if(attempts>=4){finish(token,false,"Report opened, but labelled registration details were not found.");return;}UI.postDelayed(()->read(token),1500);return;}
    JSONObject source=new JSONObject(active.getJSONObject("source").toString());
    source.put("sourceReportId",sourceId(identity)).put("vehicle",vehicle);
    String date=source.optString("date","");
    if(date.matches("(?s).*\\b20\\d{2}\\b.*")){
     long when=TimeParser.parse(date,timezone,System.currentTimeMillis());
     if(when>0&&when<=System.currentTimeMillis()+86400000L)source.put("occurredAt",java.time.Instant.ofEpochMilli(when).toString());
    }
    MasterFeedClient.publishVehicle(context,source,(data,error)->{
     if(error!=null||data==null||!data.optBoolean("ok")){finish(token,false,"Vehicle report upload failed. Check Master feed pairing and internet.");return;}
     JSONObject imports=data.optJSONObject("imports");
     if(imports==null){finish(token,false,"Cloud reply did not include an import result.");return;}
     String note="Cloud result — created: "+imports.optInt("created")+", updated: "+imports.optInt("updated")+", duplicate: "+imports.optInt("duplicate")+", review: "+imports.optInt("review")+", ignored: "+imports.optInt("ignored");
     finish(token,true,note);
    });
   }catch(Exception e){finish(token,false);}
  });
 }
 private static String sourceId(String identity)throws Exception{
  byte[] hash=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
  StringBuilder out=new StringBuilder("SilverTrack:");for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
 }
 static String status(Context c){
  android.content.SharedPreferences p=c.getSharedPreferences("patrol_master_feed_v1",0);
  return p.getString("vehicle_scan_status","No vehicle capture attempted yet.")+"\n\nLatest report: "+p.getString("vehicle_status","Waiting for capture.");
 }
 private static void scanNote(Context c,String note){c.getSharedPreferences("patrol_master_feed_v1",0).edit().putString("vehicle_scan_status",note).apply();}
 private static void finish(int token,boolean ok){finish(token,ok,ok?"Vehicle report received by cloud":"Report capture stopped: page timed out, could not load, or could not be read.");}
 private static void finish(int token,boolean ok,String note){
  if(token!=epoch)return;++epoch;
  if(ok)completed.put(identity,System.currentTimeMillis());
  if(completed.size()>1000)completed.clear();
  if(context!=null)context.getSharedPreferences("patrol_master_feed_v1",0).edit().putString("vehicle_status",note).apply();
  if(detail!=null){detail.stopLoading();detail.destroy();detail=null;}active=null;identity="";
  UI.postDelayed(SilverTrackVehicleCollector::next,2000);
 }
 private static final String LINKS="(function(rows){\n const normal=s=>String(s||'').replace(/\\s+/g,' ').trim().toLowerCase();\n const out=[],seen=new Set();\n for(const row of rows){\n  if(!row||!/\\b(?:parking breach|vehicle breach|parking violation)\\b/i.test(row.issue||''))continue;\n  const matches=Array.from(document.querySelectorAll('tr')).filter(tr=>[row.issue,row.guard,row.property,row.date].filter(Boolean).every(v=>normal(tr.innerText).includes(normal(v))));\n  if(matches.length!==1)continue;\n  const candidates=[];\n  for(const a of matches[0].querySelectorAll('a')){\n   if(a.getAttribute('href'))candidates.push(a.getAttribute('href'));\n   const code=a.getAttribute('onclick')||'';\n   const m=code.match(/(?:window\\.open|location(?:\\.href)?\\s*=)\\s*\\(?\\s*['\"]([^'\"]+)['\"]/i);\n   if(m)candidates.push(m[1]);\n  }\n  for(const raw of candidates){\n   let u;try{u=new URL(raw,location.href);}catch{continue;}\n   if(u.origin!==location.origin||u.username||u.password||u.protocol!=='https:'||!/^\\/AdminCustomer\\/Issues\\/(?:view(?:issue|action|report)?(?:details?)?|(?:issue|action|report)?details?|(?:issue|action)?reports?|(?:issue|action|report)view)\\.aspx$/i.test(u.pathname)||/\\/Monitor\\.aspx$/i.test(u.pathname))continue;\n   if(Array.from(u.searchParams.entries()).some(([k,v])=>/^(?:action|mode|cmd|operation)$/i.test(k)&&!/^(?:view|detail|details|read|report|print)$/i.test(v)))continue;\n   const ids=Array.from(u.searchParams.entries()).filter(([k,v])=>/^(?:issueid|actionid|reportid|id)$/i.test(k)&&/^(?:[0-9]{1,20}|[0-9a-f-]{32,36})$/i.test(v)).sort((a,b)=>a[0].localeCompare(b[0]));\n   if(!ids.length)continue;\n   const identity=u.pathname.toLowerCase()+':'+ids.map(([k,v])=>k.toLowerCase()+'='+v.toLowerCase()).join('&');\n   if(seen.has(identity))continue;\n   seen.add(identity);out.push({url:u.href,identity,source:row});break;\n  }\n }\n return out.slice(0,30);\n})";
 private static final String FIELDS="(function(){\n if(document.querySelector('input[type=\"password\"]'))return {login:true};\n const data={},names={plate:['registration','registration number','vehicle registration','rego','plate'],make:['make','vehicle make'],model:['model','vehicle model'],colour:['colour','color','vehicle colour'],vehicle:['vehicle','make/model','make and model'],address:['address','street address'],relation:['relation'],parking:['parking','parking area','parking location'],description:['vehicle description','description'],notes:['notes','comments']};\n const normal=s=>String(s||'').replace(/\\s+/g,' ').trim().replace(/:$/,'').trim().toLowerCase();\n function value(el){const input=el.querySelector&&el.querySelector('input:not([type=\"password\"]):not([type=\"radio\"]):not([type=\"checkbox\"]),input[type=\"radio\"]:checked,input[type=\"checkbox\"]:checked,textarea,select');return String(input?input.value:el.innerText||'').trim();}\n for(const [key,labels] of Object.entries(names)){\n  for(const label of Array.from(document.querySelectorAll('label,th,td,dt'))){\n   if(!labels.includes(normal(label.innerText)))continue;\n   let target=label.htmlFor?document.getElementById(label.htmlFor):null;\n   if(target&&target.type!=='password')data[key]=String(target.value||target.innerText||'').trim();\n   if(!data[key]&&label.nextElementSibling)data[key]=value(label.nextElementSibling);\n   if(data[key])break;\n  }\n }\n const body=String(document.body&&document.body.innerText||'').slice(0,50000);\n if(!data.plate){const m=body.match(/(?:^|\\n)\\s*(?:rego|registration(?: number)?|vehicle registration)\\s*:\\s*([A-Z0-9 -]{1,30})(?:\\n|$)/i);if(m)data.plate=m[1].trim();}\n if(!data.plate){const m=body.match(/,\\s*registration\\s+([A-Z0-9 -]{1,30}),\\s*in breach of the parking By-Laws\\./i);if(m)data.plate=m[1].trim();}\n const description=body.match(/TRL Security Officer identified (?:an? )(.+?),\\s*registration\\s+/i);\n if(description&&!data.vehicle){\n  const m=description[1].match(/^(white|black|silver|grey|gray|blue|red|orange|yellow|green|brown|gold|beige|purple|maroon)\\s+(.+)$/i);\n  if(m){if(!data.colour)data.colour=m[1].toLowerCase().replace('gray','grey');data.vehicle=m[2].trim();}else data.vehicle=description[1].trim();\n }\n // Recognise the existing TRL report narrative exactly; do not invent a parking category.\n const narrative=body.match(/Vehicle was found (across from|in front of|on|at) ([^\\n]+?), stationed (on common grounds|in the visitor carpark)\\./i);\n if(narrative){if(!data.relation)data.relation=narrative[1].toLowerCase();if(!data.address)data.address=narrative[2].trim();if(!data.parking)data.parking=narrative[3].toLowerCase().includes('visitor')?'visitor':'common';}\n if(data.parking){const p=normal(data.parking);data.parking=/^(?:visitor|visitor carpark|in the visitor carpark)$/.test(p)?'visitor':/^(?:common|common grounds|on common grounds)$/.test(p)?'common':data.parking;}\n for(const key of Object.keys(data))data[key]=data[key].slice(0,key==='notes'?1500:key==='description'?500:key==='address'?240:key==='vehicle'?160:120);\n const p=String(data.plate||'').toUpperCase().replace(/[\\s-]/g,'');\n return /^[A-Z0-9]{1,10}$/.test(p)?{vehicle:data}:{incomplete:true};\n})";
 private SilverTrackVehicleCollector(){}
}
