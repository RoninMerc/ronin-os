package au.com.roningroup.patrollink;
import android.app.*;import android.widget.*;
/** Display only: never changes a device role or pairing. Mirroring uses this Activity's rendered screen. */
final class DisplayModeUi {
 static void show(Activity a,Runnable changed){
  String mode=TabletSettings.displayMode(a);int selected=mode.equals("phone")?0:mode.equals("tablet")?1:2;
  String[] modes={"phone","tablet","auto"};
  new AlertDialog.Builder(a).setTitle("Display mode").setSingleChoiceItems(new String[]{"Phone — normal dashboard","Tablet — landscape map dashboard","Auto — follow device screen size"},selected,(dialog,index)->{
   try{TabletSettings.saveDisplayMode(a,modes[index]);dialog.dismiss();changed.run();}
   catch(IllegalStateException e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
  }).setNegativeButton("Cancel",null).show();
 }
 private DisplayModeUi(){}
}
