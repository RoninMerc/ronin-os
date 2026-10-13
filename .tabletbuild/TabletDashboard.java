package au.com.roningroup.patrollink;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import android.net.*;
import org.json.*;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.*;
import org.osmdroid.views.*;
import org.osmdroid.views.overlay.*;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Operational tablet view. FleetLoader is injectable for Android tests; production always uses FleetRelay. */
final class TabletDashboard {
 interface Actions {void run(String action);}
 interface FleetLoader {void load(FleetRelay.Callback callback);}
 private static final int BG=0xff0e1015,PANEL=0xff191c24,LINE=0xff303440,WHITE=0xfff4f5f7,MUTED=0xffa6aab5,RED=0xffd9404b,AMBER=0xffffbc66;
 private static final int[] COLOURS={0xff62cddd,0xff829fff,0xffedb873};
 private final Activity activity;
 private final PatrolEngine engine;
 private final CheckpointProximityUi checkpoints;
 private final Actions actions;
 private final FleetLoader loader;
 final LinearLayout view;
 final MapView map;
 private final TextView source,fleetStatus,gpsStatus,checkpointTitle,checkpointDetail,checkpointState;
 private final TextView[] guardName=new TextView[3],guardActivity=new TextView[3],guardTime=new TextView[3],guardProgress=new TextView[3],guardState=new TextView[3],guardArea=new TextView[3];
 private final FrameLayout mapHost;
 private final LinearLayout checkpointPanel;
 private final Marker[] patrolMarkers=new Marker[3];
 private Marker ownMarker;
 private final ArrayList<Marker> checkpointMarkers=new ArrayList<>();
 private final Map<Integer,FleetRelay.Patrol> fleet=new LinkedHashMap<>();
 private final Set<Integer> receivedSlots=new HashSet<>();
 private final Map<String,BitmapDrawable> icons=new HashMap<>();
 private final CheckpointGate gate=new CheckpointGate();
 private boolean resumed,closed,loading,positioned,userPanned;
 private long nextFleet,lastConnection,localStamp;
 private String error="",checkpointSignature="",activeCheckpoint="";
 private AlertDialog settingsDialog;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable poll=()->{if(!closed&&resumed){refresh();schedule();}};
 static boolean enabled(Activity a){return TabletSettings.tablet(a);}
 private final boolean compact;
 TabletDashboard(Activity a,PatrolEngine e,CheckpointProximityUi c,Actions x){this(a,e,c,x,cb->FleetRelay.load(a,cb));}
 TabletDashboard(Activity a,PatrolEngine e,CheckpointProximityUi c,Actions x,FleetLoader l){
  activity=a;engine=e;checkpoints=c;actions=x;loader=l;
  android.content.res.Configuration screen=a.getResources().getConfiguration();compact=Math.min(screen.screenWidthDp,screen.screenHeightDp)<520;
  org.osmdroid.config.IConfigurationProvider config=Configuration.getInstance();
  config.setUserAgentValue(a.getPackageName()+"/"+BuildConfig.VERSION_NAME+" (TRL patrol map)");
  config.setOsmdroidBasePath(new File(a.getFilesDir(),"street-maps"));
  config.setOsmdroidTileCache(new File(a.getCacheDir(),"street-map-tiles"));
  config.setTileFileSystemCacheMaxBytes(80L*1024*1024);config.setTileFileSystemCacheTrimBytes(60L*1024*1024);
  view=new LinearLayout(a);view.setOrientation(LinearLayout.HORIZONTAL);view.setBackgroundColor(BG);view.setPadding(dp(compact?6:12),dp(compact?6:12),dp(compact?6:12),dp(compact?6:12));view.setId(153001);
  LinearLayout left=column();left.setId(153002);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,.35f);lp.rightMargin=dp(compact?6:12);view.addView(left,lp);
  TextView title=text("TRL  /  PATROL LINK",compact?13:18,WHITE,true);title.setPadding(dp(8),0,0,dp(4));left.addView(title);
  source=text("TABLET OPERATIONS",11,MUTED,false);source.setMaxLines(1);source.setPadding(dp(8),0,0,dp(compact?2:8));left.addView(source);
  for(int i=0;i<3;i++){
   final int index=i;LinearLayout card=column();card.setPadding(dp(compact?6:12),dp(compact?3:8),dp(compact?6:12),dp(compact?3:8));card.setBackground(background(PANEL,COLOURS[i],12));card.setId(153010+i);
   LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,0,1);cp.bottomMargin=dp(compact?4:8);left.addView(card,cp);
   guardArea[i]=text("PATROL "+(i+1)+"  /  AREA UNAVAILABLE",12,COLOURS[i],true);guardArea[i].setMaxLines(1);if(!compact)card.addView(guardArea[i]);
   guardName[i]=text("N/A",22,WHITE,true);guardName[i].setMaxLines(1);guardName[i].setAutoSizeTextTypeUniformWithConfiguration(14,22,1,android.util.TypedValue.COMPLEX_UNIT_SP);guardName[i].setEllipsize(TextUtils.TruncateAt.END);if(compact){guardName[i].setTextSize(13);guardName[i].setAutoSizeTextTypeUniformWithConfiguration(11,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);guardArea[i].setTextSize(10);
    LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(guardArea[i],new LinearLayout.LayoutParams(0,-2,1));heading.addView(guardName[i],new LinearLayout.LayoutParams(0,-2,1));card.addView(heading);
   }else card.addView(guardName[i]);
   guardActivity[i]=text("Waiting for activity",18,WHITE,false);guardActivity[i].setMaxLines(2);guardActivity[i].setEllipsize(TextUtils.TruncateAt.END);guardActivity[i].setAutoSizeTextTypeUniformWithConfiguration(13,18,1,android.util.TypedValue.COMPLEX_UNIT_SP);
   if(compact){guardActivity[i].setMaxLines(1);guardActivity[i].setAutoSizeTextTypeUniformWithConfiguration(11,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);}
   card.addView(guardActivity[i],new LinearLayout.LayoutParams(-1,0,1));
   guardTime[i]=text("Activity time unavailable",14,MUTED,false);guardTime[i].setMaxLines(1);card.addView(guardTime[i]);
   guardProgress[i]=text("Recorded hits: 0",14,MUTED,false);guardProgress[i].setMaxLines(1);card.addView(guardProgress[i]);
   guardState[i]=text("GPS UNAVAILABLE",13,AMBER,true);guardState[i].setMaxLines(1);card.addView(guardState[i]);
   if(compact){guardTime[i].setTextSize(10);guardProgress[i].setVisibility(View.GONE);guardState[i].setTextSize(11);}
   card.setOnClickListener(v->actions.run("guard:"+index));
  }
  LinearLayout bottom=new LinearLayout(a);bottom.setGravity(Gravity.CENTER_VERTICAL);
  Button cog=button("\u2699",()->actions.run("settings"));cog.setId(153003);cog.setTextSize(27);cog.setContentDescription("Open tablet settings");
  bottom.addView(cog,new LinearLayout.LayoutParams(dp(56),dp(compact?48:54)));
  TextView hint=text("LIVE GPS + VERIFIED ACTIVITY",compact?9:11,MUTED,true);hint.setMaxLines(2);hint.setPadding(dp(10),0,0,0);bottom.addView(hint,new LinearLayout.LayoutParams(0,-2,1));left.addView(bottom);
  mapHost=new FrameLayout(a);mapHost.setId(153004);mapHost.setBackground(background(PANEL,LINE,12));view.addView(mapHost,new LinearLayout.LayoutParams(0,-1,.65f));
  map=new MapView(a);map.setId(153005);
  map.setTileSource(new XYTileSource("OpenStreetMap",0,19,256,".png",new String[]{"https://tile.openstreetmap.org/"},"© OpenStreetMap contributors"));
  map.setMultiTouchControls(true);map.setBuiltInZoomControls(false);map.setTilesScaledToDpi(true);map.setMinZoomLevel(3d);map.setMaxZoomLevel(19d);map.getController().setZoom(3d);
  map.getOverlayManager().getTilesOverlay().setColorFilter(TilesOverlay.INVERT_COLORS);
  map.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)userPanned=true;return false;});
  mapHost.addView(map,new FrameLayout.LayoutParams(-1,-1));
  LinearLayout top=new LinearLayout(a);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(10),dp(8),dp(10),dp(8));top.setBackground(background(0xed191c24,LINE,10));
  fleetStatus=text("FLEET: CONNECTING",12,AMBER,true);fleetStatus.setMaxLines(2);top.addView(fleetStatus,new LinearLayout.LayoutParams(0,-2,1));
  Button locate=button("My location",this::recenter);locate.setContentDescription("Return map to this device location");top.addView(locate,new LinearLayout.LayoutParams(dp(compact?90:112),dp(48)));
  Button fit=button("Fleet",this::fitFleet);top.addView(fit,new LinearLayout.LayoutParams(dp(compact?55:65),dp(48)));
  FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);tp.setMargins(dp(10),dp(10),dp(10),0);mapHost.addView(top,tp);
  LinearLayout footer=column();footer.setPadding(dp(10),dp(6),dp(10),dp(6));footer.setBackground(background(0xed191c24,LINE,10));
  gpsStatus=text("THIS DEVICE: GPS UNAVAILABLE",12,AMBER,true);gpsStatus.setMaxLines(2);footer.addView(gpsStatus);
  TextView credit=text("© OpenStreetMap contributors · Street tiles need internet or a cached area",10,MUTED,false);credit.setMaxLines(2);credit.setOnClickListener(v->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")));}catch(Exception ignored){}});
  footer.addView(credit);FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);fp.setMargins(dp(10),0,dp(10),dp(10));mapHost.addView(footer,fp);
  checkpointPanel=column();checkpointPanel.setId(153020);checkpointPanel.setPadding(dp(14),dp(12),dp(14),dp(12));checkpointPanel.setBackground(background(0xfa191c24,RED,14));checkpointPanel.setVisibility(View.GONE);
  checkpointTitle=text("",compact?14:20,WHITE,true);checkpointTitle.setMaxLines(compact?1:2);checkpointPanel.addView(checkpointTitle);
  checkpointDetail=text("",13,MUTED,false);checkpointDetail.setMaxLines(compact?1:2);checkpointPanel.addView(checkpointDetail);
  checkpointState=text("Awaiting physical QR scan",13,AMBER,true);checkpointPanel.addView(checkpointState);
  LinearLayout row=new LinearLayout(a);Button qr=button("Open checkpoint QR",()->checkpoints.openTabletQr(activeCheckpoint));qr.setId(153021);row.addView(qr,new LinearLayout.LayoutParams(0,dp(48),1));
  Button dismiss=button("Dismiss",()->{gate.dismiss(activeCheckpoint);checkpointPanel.setVisibility(View.GONE);});row.addView(dismiss,new LinearLayout.LayoutParams(dp(88),dp(48)));checkpointPanel.addView(row);
  FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);pp.setMargins(dp(compact?8:24),0,dp(compact?8:24),dp(compact?72:92));mapHost.addView(checkpointPanel,pp);
  restoreCache();refresh();
 }
 private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
 private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
 private TextView text(String s,int size,int colour,boolean bold){TextView t=new TextView(activity);t.setText(s);t.setTextColor(colour);t.setTextSize(size);t.setIncludeFontPadding(false);t.setEllipsize(TextUtils.TruncateAt.END);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
 private GradientDrawable background(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
 private Button button(String s,Runnable run){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);b.setTextColor(WHITE);b.setTextSize(13);b.setPadding(dp(8),0,dp(8),0);b.setMinHeight(dp(48));b.setMinWidth(dp(48));b.setBackground(background(PANEL,LINE,8));b.setOnClickListener(v->run.run());return b;}
 void resume(){if(closed||resumed)return;resumed=true;map.onResume();nextFleet=0;handler.removeCallbacks(poll);handler.post(poll);}
 void pause(){resumed=false;handler.removeCallbacks(poll);map.onPause();}
 void close(){if(closed)return;closed=true;pause();handler.removeCallbacksAndMessages(null);checkpoints.closeTabletQr();if(settingsDialog!=null)settingsDialog.dismiss();map.onDetach();}
 private void schedule(){handler.removeCallbacks(poll);if(resumed&&!closed)handler.postDelayed(poll,1000);}
 void requestFleet(){nextFleet=0;refresh();}
 void refresh(){
  if(closed)return;long now=System.currentTimeMillis();
  source.setText(engine.healthy()?"ACTIVITY FEED CONNECTED":engine.sourceStatus());
  source.setTextColor(engine.healthy()?MUTED:AMBER);
  if(resumed&&FleetRelay.configured(activity)&&FleetRelay.master(activity)&&!loading&&SystemClock.elapsedRealtime()>=nextFleet){
   loading=true;nextFleet=SystemClock.elapsedRealtime()+TabletSettings.refresh(activity)*1000L;
   loader.load((received,err)->{if(closed)return;loading=false;error=err==null?"":err;
    if(err==null){lastConnection=System.currentTimeMillis();merge(received);saveCache();}
    render();});
  }
  render();
 }
 private boolean connected(){return error.isEmpty()&&lastConnection>0&&System.currentTimeMillis()-lastConnection<Math.max(45000,TabletSettings.refresh(activity)*3000L);}
 private void merge(Map<Integer,FleetRelay.Patrol> received){
  receivedSlots.clear();if(received==null)return;receivedSlots.addAll(received.keySet());
  for(Map.Entry<Integer,FleetRelay.Patrol> x:received.entrySet()){
   if(x.getKey()<1||x.getKey()>3||x.getValue()==null)continue;
   FleetRelay.Patrol p=x.getValue(),previous=fleet.get(x.getKey());
   if(p.hasFix&&previous!=null&&previous.hasFix&&p.time<previous.time){previous.onDuty=p.onDuty;previous.retained=true;fleet.put(x.getKey(),previous);continue;}
   if(!p.hasFix&&previous!=null&&previous.hasFix){p.lat=previous.lat;p.lon=previous.lon;p.accuracy=previous.accuracy;p.time=previous.time;p.hasFix=true;p.retained=true;p.state=(p.onDuty?"GPS unavailable — retained":"Off duty — retained");}
   fleet.put(x.getKey(),p);
  }
 }
 private void render(){
  if(closed)return;if(!FleetRelay.master(activity)){fleet.clear();receivedSlots.clear();for(int i=0;i<3;i++)if(patrolMarkers[i]!=null){patrolMarkers[i].closeInfoWindow();map.getOverlays().remove(patrolMarkers[i]);patrolMarkers[i]=null;}}long now=System.currentTimeMillis();
  fleetStatus.setText((connected()?"FLEET CONNECTED":"FLEET "+(loading?"CONNECTING":"OFFLINE / CACHED"))+" · "+TabletSettings.refresh(activity)+"s\n"+(lastConnection>0?"Last received "+time(lastConnection):"No fleet response yet"));
  if(!FleetRelay.configured(activity))fleetStatus.setText("DEVICE SETUP REQUIRED — USE SETTINGS");
  else if(!FleetRelay.master(activity))fleetStatus.setText("FULL FLEET VIEW REQUIRES MASTER / SUPERVISOR ROLE");
  fleetStatus.setTextColor(connected()?WHITE:AMBER);
  List<CheckpointProximityUi.Checkpoint> all=checkpoints.tabletCheckpoints(false);
  renderCheckpointMarkers(all);
  List<String> guards=engine.guards();
  for(int i=0;i<3;i++){
   String g=guards.size()>i?guards.get(i):"N/A";boolean unused=GuardDirectory.inactive(g);Observation o=engine.latest.get(FeedReducer.key(g));FleetRelay.Patrol p=fleet.get(i+1);
   guardName[i].setText(unused?"N/A — unused patrol":g);
   String area=area(p,all);if(area.isEmpty()&&o!=null){String previous=TabletPolicy.area(o.property);if(!previous.isEmpty())area="LAST "+previous;}
   guardArea[i].setText("PATROL "+(i+1)+"  /  "+(area.isEmpty()?"AREA UNAVAILABLE":area));
   guardActivity[i].setText(unused?"Unused patrol slot":o==null?"Waiting for activity":o.activity()+" · "+o.locationLine());
   guardTime[i].setText(o==null?"Activity time unavailable":"Activity: "+(o.recordedAt>0?time(o.recordedAt):o.recordedText));
   guardProgress[i].setText(unused?"Recorded hits: N/A":"Recorded checkpoint hits: "+engine.checkpoints.total(g));
   if(compact){String stamp=o==null?"No activity time":o.recordedAt>0?DateTimeFormatter.ofPattern("HH:mm d/M").format(Instant.ofEpochMilli(o.recordedAt).atZone(engine.zone())):o.recordedText;
    guardTime[i].setText(stamp+" · Hits: "+(unused?"N/A":engine.checkpoints.total(g)));
    guardTime[i].setContentDescription((o==null?"Activity time unavailable":"Activity: "+o.recordedText)+" · "+guardProgress[i].getText());}
   String status=unused?"N/A":p==null?"GPS UNAVAILABLE":!p.onDuty?"OFF DUTY":!p.hasFix?"GPS UNAVAILABLE":!connected()||!receivedSlots.contains(i+1)||p.retained||!TabletPolicy.fresh(p.time,now)?"STALE GPS — "+age(p.time):p.state+" · GPS "+age(p.time);
   guardState[i].setText(status);guardState[i].setTextColor(unused?MUTED:p!=null&&p.onDuty&&p.hasFix&&connected()&&receivedSlots.contains(i+1)&&!p.retained&&TabletPolicy.fresh(p.time,now)?COLOURS[i]:AMBER);
   if(p!=null&&p.hasFix&&TabletPolicy.point(p.lat,p.lon)){
    boolean stale=!connected()||!receivedSlots.contains(i+1)||p.retained||!TabletPolicy.fresh(p.time,now)||!p.onDuty;
    Marker marker=patrolMarkers[i];if(marker==null){marker=new Marker(map);marker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);map.getOverlays().add(marker);patrolMarkers[i]=marker;}
    GeoPoint point=new GeoPoint(p.lat,p.lon);if(!point.equals(marker.getPosition()))marker.setPosition(point);
    marker.setTitle("Patrol "+(i+1)+(stale?" — STALE / OFF DUTY":" — "+p.state));marker.setSnippet("Fix "+time(p.time)+" · "+accuracy(p.accuracy));marker.setIcon(icon("P"+(i+1),stale?0xff737887:COLOURS[i],44));
   }
  }
  renderLocal();
  renderProximity();
  if(!positioned&&!userPanned){ArrayList<GeoPoint> points=validFleetPoints();if(points.isEmpty())for(CheckpointProximityUi.Checkpoint c:all)if(c.mapped()&&TabletPolicy.point(c.lat,c.lon))points.add(new GeoPoint(c.lat,c.lon));if(!points.isEmpty()){positioned=true;centre(points);}}
  map.invalidate();
 }
 private String area(FleetRelay.Patrol p,List<CheckpointProximityUi.Checkpoint> list){
  if(p==null||!p.hasFix||!TabletPolicy.fresh(p.time,System.currentTimeMillis())||!connected())return "";
  float best=Float.MAX_VALUE;String group="";
  for(CheckpointProximityUi.Checkpoint c:list)if(c.mapped()){float d=distance(p.lat,p.lon,c.lat,c.lon);if(d<best&&d<=c.radius){best=d;group="NEAR "+c.group;}}
  return group;
 }
 private void renderLocal(){
  SharedPreferences gp=LocalGpsService.prefs(activity);long fix=gp.getLong("fix_time",0),age=LocalGpsService.fixAgeSeconds(activity);
  double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));float accuracy=gp.getFloat("accuracy",Float.NaN);
  boolean mock=gp.getBoolean("mock",false),valid=fix>0&&gp.contains("lat")&&gp.contains("lon")&&TabletPolicy.point(lat,lon);
  boolean current=valid&&age>=0&&age<=90&&LocalGpsService.hasPermission(activity)&&LocalGpsService.locationEnabled(activity)&&gp.getBoolean("enabled",false);
  String state=!LocalGpsService.hasPermission(activity)?"LOCATION PERMISSION REQUIRED":!LocalGpsService.locationEnabled(activity)?"LOCATION SERVICES OFF":!gp.getBoolean("enabled",false)?"GPS STOPPED — USE SETTINGS":!valid?"WAITING FOR GPS":mock?"MOCK LOCATION — PROXIMITY DISABLED":!current?"STALE LOCAL GPS":"THIS DEVICE · ±"+Math.round(accuracy)+"m · "+age+"s ago";
  gpsStatus.setText(state);gpsStatus.setTextColor(current&&!mock?WHITE:AMBER);
  if(!valid)return;
  if(ownMarker==null){ownMarker=new Marker(map);ownMarker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);map.getOverlays().add(ownMarker);}
  GeoPoint point=new GeoPoint(lat,lon);if(!point.equals(ownMarker.getPosition()))ownMarker.setPosition(point);
  ownMarker.setIcon(icon(mock?"TEST":"ME",current&&!mock?RED:0xff737887,48));ownMarker.setTitle(mock?"MOCK / TEST LOCATION":"This device"+(current?"":" — STALE"));ownMarker.setSnippet("Fix "+time(fix)+" · ±"+Math.round(accuracy)+" m");
  map.getOverlays().remove(ownMarker);map.getOverlays().add(ownMarker);
  if(current&&!mock&&fix!=localStamp){localStamp=fix;if(!userPanned&&(!positioned||TabletSettings.prefs(activity).getBoolean("follow_local",false))){map.getController().setCenter(point);if(!positioned)map.getController().setZoom(17d);positioned=true;}}
 }
 private void renderCheckpointMarkers(List<CheckpointProximityUi.Checkpoint> list){
  StringBuilder sig=new StringBuilder();for(CheckpointProximityUi.Checkpoint c:list)sig.append(c.id).append(c.name).append(c.group).append(c.lat).append(c.lon).append(c.radius);
  if(sig.toString().equals(checkpointSignature))return;checkpointSignature=sig.toString();
  map.getOverlays().removeAll(checkpointMarkers);checkpointMarkers.clear();
  for(CheckpointProximityUi.Checkpoint c:list)if(c.mapped()&&TabletPolicy.point(c.lat,c.lon)){
   Marker m=new Marker(map);m.setPosition(new GeoPoint(c.lat,c.lon));m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);m.setTitle(c.name+" · "+c.group);m.setSnippet("QR checkpoint "+c.id+" · "+Math.round(c.radius)+"m configured activation");
   m.setIcon(icon("QR",0xffb5c2ac,28));map.getOverlays().add(m);checkpointMarkers.add(m);
  }
 }
 private void renderProximity(){
  SharedPreferences gp=LocalGpsService.prefs(activity);long age=LocalGpsService.fixAgeSeconds(activity);
  double lat=Double.longBitsToDouble(gp.getLong("lat",0)),lon=Double.longBitsToDouble(gp.getLong("lon",0));
  ArrayList<CheckpointGate.Candidate> points=new ArrayList<>();Map<String,CheckpointProximityUi.Checkpoint> byId=new HashMap<>();
  for(CheckpointProximityUi.Checkpoint c:checkpoints.tabletCheckpoints(true))if(c.mapped()&&TabletPolicy.point(c.lat,c.lon)){byId.put(c.id,c);points.add(new CheckpointGate.Candidate(c.id,distance(lat,lon,c.lat,c.lon),TabletSettings.radius(activity,c.radius)));}
  boolean valid=gp.getLong("fix_time",0)>0&&gp.contains("lat")&&gp.contains("lon")&&TabletPolicy.point(lat,lon);
  boolean precise=valid&&LocalGpsService.precise(activity)&&LocalGpsService.locationEnabled(activity)&&gp.getBoolean("enabled",false)&&!gp.getBoolean("mock",false);
  CheckpointGate.Entry entry=gate.update(points,gp.getFloat("accuracy",Float.NaN),age<0?-1:age*1000,gp.getLong("fix_elapsed",gp.getLong("fix_time",0)),precise,System.currentTimeMillis());
  if(entry==null){checkpointPanel.setVisibility(View.GONE);activeCheckpoint="";checkpoints.closeTabletQr();return;}
  CheckpointProximityUi.Checkpoint c=byId.get(entry.checkpoint.id);if(c==null)return;activeCheckpoint=c.id;
  checkpointTitle.setText(c.name+" · "+Math.round(entry.checkpoint.distance)+" m");
  checkpointDetail.setText(c.group+" · QR ID "+c.id+"\nActivation "+Math.round(entry.checkpoint.radius)+"m · GPS ±"+Math.round(gp.getFloat("accuracy",0))+"m");
  long verified=verifiedScan(c,entry.enteredAt);
  if(verified>0){checkpointState.setText("Verified SilverTrack scan received");gate.dismiss(c.id);checkpointPanel.setVisibility(View.GONE);checkpoints.closeTabletQr();return;}
  checkpointState.setText("Awaiting physical QR scan — proximity does not complete it");
  checkpointPanel.setVisibility(View.VISIBLE);
  if(entry.newEntry&&TabletSettings.prefs(activity).getBoolean("proximity_alert",false))checkpointPanel.announceForAccessibility("Checkpoint nearby: "+c.name);
 }
 private long verifiedScan(CheckpointProximityUi.Checkpoint c,long entered){
  if(!FleetRelay.configured(activity)||FleetRelay.master(activity))return 0;
  int slot=FleetRelay.patrol(activity)-1;if(slot<0||slot>=engine.guards().size())return 0;
  String name=c.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""),id=c.id.replaceAll("[^a-z0-9]","");
  for(Observation o:engine.recentForGuard(engine.guards().get(slot))){
   if(o.recordedAt<entered||!engine.checkpoints.containsHit(o))continue;
   String p=o.property.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
   if(checkpoints.tabletMatches(c.id,o))return o.recordedAt;
  }return 0;
 }
 private BitmapDrawable icon(String label,int colour,int size){
  String key=label+":"+colour+":"+size;if(icons.containsKey(key))return icons.get(key);
  int pixels=dp(size);boolean pin=label.equals("ME")||label.equals("TEST");int height=pin?pixels+dp(36):pixels;Bitmap bitmap=Bitmap.createBitmap(pixels,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
  if(pin){p.setColor(colour);Path tail=new Path();tail.moveTo(pixels/2f-dp(6),pixels-dp(5));tail.lineTo(pixels/2f,height-1);tail.lineTo(pixels/2f+dp(6),pixels-dp(5));tail.close();canvas.drawPath(tail,p);}
  p.setColor(0xee11131a);canvas.drawCircle(pixels/2f,pixels/2f,pixels/2f,p);p.setColor(colour);canvas.drawCircle(pixels/2f,pixels/2f,pixels/2f-dp(2),p);
  p.setColor(BG);p.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(label.length()>2?11:13));canvas.drawText(label,pixels/2f,pixels/2f-(p.ascent()+p.descent())/2,p);BitmapDrawable icon=new BitmapDrawable(activity.getResources(),bitmap);icons.put(key,icon);return icon;
 }
 private ArrayList<GeoPoint> validFleetPoints(){ArrayList<GeoPoint> p=new ArrayList<>();for(FleetRelay.Patrol f:fleet.values())if(f.hasFix&&TabletPolicy.point(f.lat,f.lon))p.add(new GeoPoint(f.lat,f.lon));return p;}
 private void centre(List<GeoPoint> p){if(p.isEmpty())return;if(p.size()==1){map.getController().setCenter(p.get(0));map.getController().setZoom(16d);}else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(p),true,dp(70));}
 private void fitFleet(){userPanned=true;ArrayList<GeoPoint> points=validFleetPoints();if(ownMarker!=null)points.add(ownMarker.getPosition());if(points.isEmpty()){Toast.makeText(activity,"No received locations to centre on.",Toast.LENGTH_SHORT).show();return;}positioned=true;centre(points);}
 private void recenter(){if(ownMarker==null){actions.run("gps");return;}userPanned=false;positioned=true;map.getController().animateTo(ownMarker.getPosition());map.getController().setZoom(17d);}
 private String accuracy(float metres){return Float.isFinite(metres)&&metres>0?"±"+Math.round(metres)+" m":"accuracy unavailable";}
 private String time(long ms){return DateTimeFormatter.ofPattern("HH:mm:ss · d MMM").format(Instant.ofEpochMilli(ms).atZone(engine.zone()));}
 private String age(long time){long s=TabletPolicy.age(time,System.currentTimeMillis())/1000;return s>86400?"age unknown":s<60?s+"s ago":s/60+"m ago";}
 private static float distance(double a,double o,double b,double p){float[] r=new float[1];android.location.Location.distanceBetween(a,o,b,p,r);return r[0];}
 private void saveCache(){if(!FleetRelay.master(activity))return;try{JSONArray rows=new JSONArray();for(FleetRelay.Patrol p:fleet.values())if(p.hasFix)rows.put(new JSONObject().put("number",p.number).put("lat",p.lat).put("lon",p.lon).put("accuracy",Float.isFinite(p.accuracy)?p.accuracy:JSONObject.NULL).put("time",p.time).put("duty",p.onDuty).put("state",p.state));TabletSettings.prefs(activity).edit().putString("fleet_cache",rows.toString()).putLong("fleet_last_connection",lastConnection).apply();}catch(Exception ignored){}}
 private void restoreCache(){if(!FleetRelay.configured(activity)||!FleetRelay.master(activity))return;try{JSONArray a=new JSONArray(TabletSettings.prefs(activity).getString("fleet_cache","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);FleetRelay.Patrol p=new FleetRelay.Patrol();p.number=o.getInt("number");p.lat=o.getDouble("lat");p.lon=o.getDouble("lon");p.time=o.getLong("time");p.accuracy=(float)o.optDouble("accuracy");p.onDuty=o.optBoolean("duty");p.state="Retained location";p.retained=true;p.hasFix=p.time>0&&TabletPolicy.point(p.lat,p.lon);if(p.number>=1&&p.number<=3&&p.hasFix)fleet.put(p.number,p);}error="Cached until relay reconnects";lastConnection=TabletSettings.prefs(activity).getLong("fleet_last_connection",0);}catch(Exception ignored){}}
 void settings(){
  LinearLayout form=column();form.setPadding(dp(18),dp(10),dp(18),dp(10));
  section(form,"GENERAL");
  item(form,"Display mode · "+TabletSettings.displayMode(activity),()->{if(settingsDialog!=null)settingsDialog.dismiss();actions.run("display");});
  if(MasterFeedClient.master(activity))item(form,"Guard selection",()->actions.run("guards"));
  item(form,"Patrol duty / assignments",()->actions.run("fleet"));
  section(form,"GPS AND TRACKING");
  item(form,"GPS status, permissions and reporting interval",()->actions.run("gps"));
  item(form,"Refresh fleet now",this::requestFleet);
  TextView rate=text("Fleet display refresh",14,WHITE,false);form.addView(rate);
  Spinner refresh=new Spinner(activity);refresh.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"15 seconds","30 seconds","60 seconds"}));refresh.setSelection(TabletSettings.refresh(activity)==30?1:TabletSettings.refresh(activity)==60?2:0);form.addView(refresh);
  CheckBox follow=new CheckBox(activity);follow.setText("Follow this device until the map is panned");follow.setTextColor(WHITE);follow.setChecked(TabletSettings.prefs(activity).getBoolean("follow_local",false));form.addView(follow);
  section(form,"CHECKPOINTS");
  String[] radii={"Use each checkpoint's configured distance","5 metres","10 metres","15 metres","20 metres","25 metres","50 metres","Custom distance"};
  Spinner radius=new Spinner(activity);radius.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,radii));
  float storedRadius=TabletSettings.prefs(activity).getFloat("radius_metres",20);
  final float value=Float.isFinite(storedRadius)&&storedRadius>=1&&storedRadius<=500?storedRadius:20;int selection=0;
  if(TabletSettings.prefs(activity).getBoolean("radius_override",false)){selection=value==5?1:value==10?2:value==15?3:value==20?4:value==25?5:value==50?6:7;}radius.setSelection(selection);form.addView(radius);
  EditText custom=new EditText(activity);custom.setTextColor(WHITE);custom.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);custom.setHint("Custom activation distance: 1–500 metres");custom.setText(String.valueOf(value));form.addView(custom);
  TextView note=text("A selected distance overrides activation on this tablet only. Central checkpoint settings remain unchanged. Two fresh precise fixes with accuracy no worse than the radius are required. Small radii may remain inactive when GPS accuracy is poor.",12,MUTED,false);form.addView(note);
  CheckBox alert=new CheckBox(activity);alert.setText("Announce new proximity prompts to accessibility services");alert.setTextColor(WHITE);alert.setChecked(TabletSettings.prefs(activity).getBoolean("proximity_alert",false));form.addView(alert);
  item(form,"Individual checkpoints, route, QR photos and cloud sync",()->actions.run("checkpoints"));
  section(form,"CONNECTIONS");
  if(MasterFeedClient.master(activity))item(form,"SilverTrack session and parking report capture",()->actions.run("silvertrack"));
  CheckBox existingMaster=new CheckBox(activity);existingMaster.setText("Read the existing Master phone's feed on this supervisor tablet");existingMaster.setTextColor(WHITE);existingMaster.setChecked(TabletSettings.prefs(activity).getBoolean("read_existing_master",false));
  if(FleetRelay.master(activity)){form.addView(existingMaster);form.addView(text("Reader mode uses the saved Reader key and keeps SilverTrack collection on the existing Master. Saved local credentials and session are retained. Save this setting, then use Master feed pairing.",12,MUTED,false));}
  item(form,"Master feed pairing",()->actions.run("pairing"));
  section(form,"DIAGNOSTICS");
  item(form,"Connection diagnostics",()->actions.run("diagnostics"));
  if(FleetRelay.master(activity)){item(form,"Speech controls",()->actions.run("speech"));item(form,"Voice library",()->actions.run("voices"));item(form,"Featherless voice settings",()->actions.run("featherless"));item(form,"Copy full voice script",()->actions.run("script"));}
  item(form,"Check for app update",()->actions.run("update"));item(form,"App information",()->actions.run("help"));item(form,"Exit / sign out",()->actions.run("exit"));
  ScrollView scroll=new ScrollView(activity);scroll.setBackgroundColor(BG);scroll.addView(form);
  AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Tablet settings").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Save display settings",null).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
   int idx=radius.getSelectedItemPosition();float r=idx==0?value:idx==1?5:idx==2?10:idx==3?15:idx==4?20:idx==5?25:idx==6?50:Float.parseFloat(custom.getText().toString());
   TabletSettings.saveRadius(activity,idx!=0,r);
   TabletSettings.prefs(activity).edit().putInt("fleet_seconds",refresh.getSelectedItemPosition()==1?30:refresh.getSelectedItemPosition()==2?60:15).putBoolean("follow_local",follow.isChecked()).putBoolean("proximity_alert",alert.isChecked()).apply();
   if(FleetRelay.master(activity)){boolean reader=existingMaster.isChecked(),before=TabletSettings.prefs(activity).getBoolean("read_existing_master",false);TabletSettings.prefs(activity).edit().putBoolean("read_existing_master",reader).apply();if(reader!=before)engine.changeFeedMode();}
   nextFleet=0;dialog.dismiss();refresh();
  }catch(Exception e){Toast.makeText(activity,"Enter a valid distance from 1 to 500 metres.",Toast.LENGTH_LONG).show();}}));settingsDialog=dialog;dialog.setOnDismissListener(x->settingsDialog=null);dialog.show();
 }
 private void section(LinearLayout f,String label){TextView t=text(label,12,RED,true);t.setPadding(0,dp(20),0,dp(8));f.addView(t);}
 private void item(LinearLayout f,String label,Runnable action){Button b=button(label,action);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(5);f.addView(b,lp);}
 String diagnostics(){return "Fleet display: "+TabletSettings.refresh(activity)+"s\nLast successful fleet connection: "+(lastConnection>0?time(lastConnection):"none")+"\nFleet state: "+(connected()?"connected":"offline / retained")+"\n"+gpsStatus.getText()+"\n"+CheckpointSyncClient.status(activity)+"\nNo GPS approach is recorded as a completed QR scan.";}
}