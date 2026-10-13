package au.com.roningroup.patrollink;
import android.app.*;
import android.content.*;
import android.widget.*;
/** Explicit capture of loaded historical results; never publishes them as live patrol activity. */
final class VehicleCaptureUi {
 static void show(Activity a,PatrolEngine engine){
  if(!MasterFeedClient.master(a)){new AlertDialog.Builder(a).setTitle("Master phone required").setMessage("Vehicle reports are captured only on the authorised Master / Supervisor phone.").setPositiveButton("OK",null).show();return;}
  if(!PatrolEngine.monitorPage(engine.webView().getUrl())){new AlertDialog.Builder(a).setTitle("Open Issue Monitor").setMessage("Open the signed-in SilverTrack Issue Monitor and load the parking-breach results first.").setPositiveButton("OK",null).show();return;}
  LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
  int pad=Math.round(16*a.getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);
  TextView note=new TextView(a);note.setText("Captures up to 30 eligible reports from the loaded results page. Leave this screen open while it reads the reports. July reports keep their original dates. Repeat on each results page.");
  body.addView(note);
  TextView state=new TextView(a);state.setPadding(0,pad,0,pad);state.setTextIsSelectable(true);body.addView(state);
  SharedPreferences prefs=a.getSharedPreferences("patrol_master_feed_v1",0);
  SharedPreferences.OnSharedPreferenceChangeListener listener=(p,k)->{if("vehicle_status".equals(k)||"vehicle_scan_status".equals(k))state.setText(SilverTrackVehicleCollector.status(a));};
  ScrollView scroll=new ScrollView(a);scroll.addView(body);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Parking report capture").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Capture this page",null).create();
  dialog.setOnDismissListener(d->prefs.unregisterOnSharedPreferenceChangeListener(listener));
  dialog.setOnShowListener(d->{prefs.registerOnSharedPreferenceChangeListener(listener);state.setText(SilverTrackVehicleCollector.status(a));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{state.setText("Reading the loaded results…");engine.inspectNow();});});
  dialog.show();
 }
 private VehicleCaptureUi(){}
}
