package au.com.roningroup.patrollink;
import android.app.*;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
/** Activity-backed picker for the visible monitor's existing HTML select controls. */
final class MonitorFilterUi {
 private static WeakReference<Activity> owner=new WeakReference<>(null);
 private static WeakReference<WebView> browser=new WeakReference<>(null);
 private static AlertDialog picker;
 private static boolean pending,active;
 private static long touched;
 private MonitorFilterUi(){}
 static void prepare(WebView web){web.addJavascriptInterface(new Bridge(),"TRLMonitorFilters");}
 static void bind(Activity activity,WebView web){
  owner=new WeakReference<>(activity);browser=new WeakReference<>(web);active=true;touched=0;
  web.setFocusable(true);web.setFocusableInTouchMode(true);
  web.setOnTouchListener((view,event)->{touched=SystemClock.elapsedRealtime();return false;});
  prepare(web);
  pageReady(web);
 }
 static void unbind(Activity activity){
  if(owner.get()!=activity)return;
  if(picker!=null)picker.dismiss();
  picker=null;pending=false;active=false;owner.clear();
  WebView web=browser.get();if(web!=null){web.evaluateJavascript(IssueFilterScripts.CLEAR,null);web.removeJavascriptInterface("TRLMonitorFilters");}
  browser.clear();
 }
 static void pause(Activity activity){if(owner.get()!=activity)return;active=false;pending=false;if(picker!=null)picker.dismiss();picker=null;touched=0;}
 static void resume(Activity activity){if(owner.get()==activity){active=true;WebView web=browser.get();if(web!=null)pageReady(web);}}
 static boolean interacting(WebView web){return active&&browser.get()==web&&owner.get()!=null&&(pending||(picker!=null&&picker.isShowing())||SystemClock.elapsedRealtime()-touched<8000);}
 static boolean ready(Activity activity,WebView web){return active&&activity!=null&&!activity.isFinishing()&&!activity.isDestroyed()&&browser.get()==web&&PatrolEngine.monitorPage(web.getUrl());}
 static void pageReady(WebView web){
  Activity activity=owner.get();if(!ready(activity,web))return;
  String selected=activity.getSharedPreferences("patrol_silvertrack_issue_filters_v155",0).getString("selection","null");
  try{if(!selected.equals("null"))new JSONObject(selected);}catch(Exception e){selected="null";}
  web.evaluateJavascript("("+HOOK+")("+selected+")",null);
 }
 static void show(Activity activity,WebView web){
  if(!ready(activity,web)){Toast.makeText(activity,"Open the signed-in Issue Monitor first.",Toast.LENGTH_LONG).show();return;}
  showAt(activity,web,-1);
 }
 public static final class Bridge{
  @JavascriptInterface public void open(int index){
   Activity activity=owner.get();WebView web=browser.get();if(activity==null||web==null)return;
   activity.runOnUiThread(()->{if(ready(activity,web))showAt(activity,web,index);});
  }
 }
 private static void showAt(Activity activity,WebView web,int requested){
  if(pending||(picker!=null&&picker.isShowing()))return;
  pending=true;touched=SystemClock.elapsedRealtime();
  web.evaluateJavascript("JSON.stringify(("+READ+")("+requested+"))",encoded->{
   pending=false;if(!ready(activity,web))return;
   try{
    Object decoded=new JSONTokener(encoded).nextValue();JSONObject data=new JSONObject(String.valueOf(decoded));
    JSONArray filters=data.optJSONArray("filters");
    if(filters!=null){
     String[] labels=new String[filters.length()];for(int i=0;i<labels.length;i++)labels[i]=filters.getJSONObject(i).getString("title");
     if(labels.length==0){Toast.makeText(activity,"No selectable filters were found on this page.",Toast.LENGTH_LONG).show();return;}
     picker=new AlertDialog.Builder(activity).setTitle("Choose a SilverTrack filter").setItems(labels,(dialog,which)->{dialog.dismiss();picker=null;showAt(activity,web,filters.optJSONObject(which).optInt("index",-1));}).setNegativeButton("Cancel",null).create();
    }else{
     int index=data.getInt("index");JSONArray options=data.getJSONArray("options");String[] labels=new String[options.length()];boolean[] checked=new boolean[labels.length];int selected=-1;
     for(int i=0;i<labels.length;i++){JSONObject item=options.getJSONObject(i);labels[i]=item.getString("text");checked[i]=item.optBoolean("selected");if(checked[i])selected=i;}
     if(labels.length==0){Toast.makeText(activity,"This filter has no enabled options.",Toast.LENGTH_LONG).show();return;}
     AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle(data.optString("title","Reported issue"));
     if(data.optBoolean("multiple")||data.optBoolean("reported")){
      int all=-1;for(int i=0;i<options.length();i++)if(options.optJSONObject(i).optBoolean("all"))all=i;
      final int allChoice=all;
      if(data.optBoolean("reported")&&!data.optBoolean("multiple")){try{JSONObject saved=new JSONObject(activity.getSharedPreferences("patrol_silvertrack_issue_filters_v155",0).getString("selection","{}"));JSONArray values=saved.optJSONArray("values");if(data.optString("key").equals(saved.optString("key"))&&values!=null&&values.length()>1){Arrays.fill(checked,false);for(int i=0;i<options.length();i++)for(int j=0;j<values.length();j++)if(options.optJSONObject(i).optString("value").equals(values.optString(j)))checked[i]=true;}}catch(Exception ignored){}}
      builder.setTitle(data.optBoolean("reported")?"Reported issues · multiple":data.optString("title","Filter"));
      builder.setMultiChoiceItems(labels,checked,(dialog,which,isChecked)->{
       checked[which]=isChecked;
       if(data.optBoolean("reported")&&allChoice>=0&&isChecked){android.widget.ListView list=((AlertDialog)dialog).getListView();if(which==allChoice){for(int i=0;i<checked.length;i++)if(i!=allChoice){checked[i]=false;list.setItemChecked(i,false);}}else{checked[allChoice]=false;list.setItemChecked(allChoice,false);}}
      }).setPositiveButton("Apply",(dialog,which)->{
       JSONArray indices=new JSONArray();for(int i=0;i<checked.length;i++)if(checked[i])indices.put(options.optJSONObject(i).optInt("index"));apply(activity,web,index,indices,data.optBoolean("reported"));
      });
     }else builder.setSingleChoiceItems(labels,selected,(dialog,which)->{JSONArray indices=new JSONArray().put(options.optJSONObject(which).optInt("index"));apply(activity,web,index,indices,data.optBoolean("reported"));dialog.dismiss();});
     picker=builder.setNegativeButton("Cancel",null).create();
    }
    AlertDialog shown=picker;shown.setOnDismissListener(dialog->{if(picker==shown)picker=null;touched=SystemClock.elapsedRealtime();});
    shown.show();
   }catch(Exception e){Toast.makeText(activity,"The page changed. Open the filter again.",Toast.LENGTH_LONG).show();}
  });
 }
 private static void apply(Activity activity,WebView web,int index,JSONArray selected,boolean reported){
  if(!ready(activity,web))return;
  touched=SystemClock.elapsedRealtime();
  web.evaluateJavascript("JSON.stringify(("+APPLY+")("+index+","+selected.toString()+","+reported+"))",result->{
   try{
    Object decoded=new JSONTokener(result).nextValue();JSONObject response=new JSONObject(String.valueOf(decoded));
    if(!response.optBoolean("ok")){Toast.makeText(activity,response.optString("reason","The filter changed. Please open it again."),Toast.LENGTH_LONG).show();return;}
    Object selection=response.opt("selection");
    activity.getSharedPreferences("patrol_silvertrack_issue_filters_v155",0).edit().putString("selection",selection instanceof JSONObject?selection.toString():"null").apply();
   }catch(Exception e){Toast.makeText(activity,"Wait for SilverTrack to finish loading, then select again.",Toast.LENGTH_LONG).show();}
  });
 }
 private static final String HOOK=IssueFilterScripts.HOOK;
 private static final String READ=IssueFilterScripts.READ;
 private static final String APPLY=IssueFilterScripts.APPLY;
}
