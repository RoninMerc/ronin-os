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
  WebView web=browser.get();if(web!=null)web.removeJavascriptInterface("TRLMonitorFilters");
  browser.clear();
 }
 static void pause(Activity activity){if(owner.get()!=activity)return;active=false;pending=false;if(picker!=null)picker.dismiss();picker=null;touched=0;}
 static void resume(Activity activity){if(owner.get()==activity){active=true;WebView web=browser.get();if(web!=null)pageReady(web);}}
 static boolean interacting(WebView web){return active&&browser.get()==web&&owner.get()!=null&&(pending||(picker!=null&&picker.isShowing())||SystemClock.elapsedRealtime()-touched<8000);}
 static boolean ready(Activity activity,WebView web){return active&&activity!=null&&!activity.isFinishing()&&!activity.isDestroyed()&&browser.get()==web&&PatrolEngine.monitorPage(web.getUrl());}
 static void pageReady(WebView web){
  Activity activity=owner.get();if(!ready(activity,web))return;
  web.evaluateJavascript(HOOK,null);
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
     if(data.optBoolean("multiple")){
      builder.setMultiChoiceItems(labels,checked,(dialog,which,isChecked)->checked[which]=isChecked).setPositiveButton("Apply",(dialog,which)->{
       JSONArray indices=new JSONArray();for(int i=0;i<checked.length;i++)if(checked[i])indices.put(options.optJSONObject(i).optInt("index"));apply(activity,web,index,indices);
      });
     }else builder.setSingleChoiceItems(labels,selected,(dialog,which)->{JSONArray indices=new JSONArray().put(options.optJSONObject(which).optInt("index"));apply(activity,web,index,indices);dialog.dismiss();});
     picker=builder.setNegativeButton("Cancel",null).create();
    }
    AlertDialog shown=picker;shown.setOnDismissListener(dialog->{if(picker==shown)picker=null;touched=SystemClock.elapsedRealtime();});
    shown.show();
   }catch(Exception e){Toast.makeText(activity,"The page changed. Open the filter again.",Toast.LENGTH_LONG).show();}
  });
 }
 private static void apply(Activity activity,WebView web,int index,JSONArray selected){
  if(!ready(activity,web))return;
  touched=SystemClock.elapsedRealtime();
  web.evaluateJavascript("("+APPLY+")("+index+","+selected.toString()+")",result->{
   if(!"true".equals(result))Toast.makeText(activity,"The filter changed while it was open. Please select it again.",Toast.LENGTH_LONG).show();
  });
 }
 private static final String HOOK="(function(){if(window.__trlFiltersInstalled)return;window.__trlFiltersInstalled=true;function open(e){var el=e.target;if(!el||el.tagName!=='SELECT'||el.disabled||typeof TRLMonitorFilters==='undefined')return;if(e.type==='keydown'&&e.key!=='Enter'&&e.key!==' ')return;e.preventDefault();e.stopPropagation();if(e.type==='touchstart'||e.type==='mousedown')return;window.__trlFilterClicks=(window.__trlFilterClicks||0)+1;var i=Array.from(document.querySelectorAll('select')).indexOf(el);if(i>=0)TRLMonitorFilters.open(i);}document.addEventListener('touchstart',open,{capture:true,passive:false});document.addEventListener('touchend',open,{capture:true,passive:false});document.addEventListener('mousedown',open,true);document.addEventListener('click',open,true);document.addEventListener('keydown',open,true);})()";
 private static final String READ="function(requested){var selects=Array.from(document.querySelectorAll('select'));function title(s){var label=s.labels&&s.labels.length?s.labels[0].innerText:'';if(!label&&s.parentElement){var p=s.parentElement.cloneNode(true);p.querySelectorAll('select').forEach(x=>x.remove());label=p.innerText;}return String(label||s.getAttribute('aria-label')||s.name||s.id||'SilverTrack filter').replace(/\\s+/g,' ').trim().slice(0,100);}var index=requested;if(index<0){var matches=selects.map((s,i)=>({s:s,i:i})).filter(x=>!x.s.disabled&&(/reported.*issue|issue.*type/i.test(title(x.s)+' '+x.s.id+' '+x.s.name)||Array.from(x.s.options).some(o=>/parking.*breach/i.test(o.text))));if(matches.length===1)index=matches[0].i;else return {filters:selects.map((s,i)=>({index:i,title:title(s)})).filter(x=>!selects[x.index].disabled)};}var s=selects[index];if(!s||s.disabled)return {filters:[]};return {index:index,title:title(s),multiple:s.multiple,options:Array.from(s.options).map((o,i)=>({index:i,text:o.text,selected:o.selected,disabled:o.disabled||(o.parentElement.tagName==='OPTGROUP'&&o.parentElement.disabled)})).filter(o=>!o.disabled)};}";
 private static final String APPLY="function(index,indices){var s=document.querySelectorAll('select')[index];if(!s||s.disabled||indices.some(i=>!s.options[i]||s.options[i].disabled))return false;var old=Array.from(s.options).map(o=>o.selected);Array.from(s.options).forEach((o,i)=>o.selected=indices.includes(i));if(old.some((v,i)=>v!==s.options[i].selected)){s.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('change',{bubbles:true}));}return true;}";
}
