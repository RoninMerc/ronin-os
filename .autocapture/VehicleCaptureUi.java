package au.com.roningroup.patrollink;
import android.app.*;
import android.content.*;
import android.widget.*;
/** Explicit capture of loaded historical results; never publishes them as live patrol activity. */
final class VehicleCaptureUi {
 static void show(Activity a,PatrolEngine engine){
  if(!TabletSettings.tablet(a)){showSinglePage(a,engine);return;}
  TextView state=new TextView(a);state.setTextColor(0xfff7f7f7);int pad=Math.round(16*a.getResources().getDisplayMetrics().density);state.setPadding(pad,pad,pad,pad);state.setTextIsSelectable(true);
  android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());final boolean[] visible={true};
  Runnable tick=new Runnable(){public void run(){if(visible[0]){state.setText(AutoCaptureJob.status(a));handler.postDelayed(this,1000);}}};
  android.widget.ScrollView scroll=new android.widget.ScrollView(a);scroll.addView(state);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Automatic parking report sync").setView(scroll).setNegativeButton("Close",null).setNeutralButton("Pause",(d,w)->AutoCaptureJob.stop(a)).setPositiveButton("Resume / retry",null).create();
  dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{AutoCaptureJob.start(a,engine.webView(),engine.zone(),true);});handler.post(tick);if(!AutoCaptureJob.running())AutoCaptureJob.start(a,engine.webView(),engine.zone(),true);});
  dialog.setOnDismissListener(d->{visible[0]=false;handler.removeCallbacks(tick);});dialog.show();
 }
 static void showSinglePage(Activity a,PatrolEngine engine){
  if(!MasterFeedClient.master(a)){new AlertDialog.Builder(a).setTitle("Master phone required").setMessage("Vehicle reports are captured only on the authorised Master / Supervisor phone.").setPositiveButton("OK",null).show();return;}
  if(!PatrolEngine.monitorPage(engine.webView().getUrl())){new AlertDialog.Builder(a).setTitle("Open Issue Monitor").setMessage("Open the signed-in SilverTrack Issue Monitor and load the parking-breach results first.").setPositiveButton("OK",null).show();return;}
  LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
  int pad=Math.round(16*a.getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);
  TextView note=new TextView(a);note.setText("Reads up to 180 linked parking reports from the loaded results. Capture starts now; keep the Master browser open while it works. Original source dates and duplicate protection are retained. Use SilverTrack paging to load more results.");
  body.addView(note);
  TextView state=new TextView(a);state.setPadding(0,pad,0,pad);state.setTextIsSelectable(true);body.addView(state);
  SharedPreferences prefs=a.getSharedPreferences("patrol_master_feed_v1",0);
  SharedPreferences.OnSharedPreferenceChangeListener listener=(p,k)->{if("vehicle_status".equals(k)||"vehicle_scan_status".equals(k))state.setText(SilverTrackVehicleCollector.status(a));};
  ScrollView scroll=new ScrollView(a);scroll.addView(body);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Parking report capture").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Capture again",null).create();
  dialog.setOnDismissListener(d->prefs.unregisterOnSharedPreferenceChangeListener(listener));
  dialog.setOnShowListener(d->{prefs.registerOnSharedPreferenceChangeListener(listener);state.setText(SilverTrackVehicleCollector.status(a));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{state.setText("Reading the loaded results…");SilverTrackVehicleCollector.captureLoadedPage(a,engine.webView(),engine.zone());});SilverTrackVehicleCollector.captureLoadedPage(a,engine.webView(),engine.zone());});
  dialog.show();
 }
 private VehicleCaptureUi(){}
}
