package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.graphics.*;import android.view.*;import android.view.accessibility.*;import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;import androidx.test.runner.lifecycle.Stage;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;
/** Exercises production MainActivity on phone-sized emulators, using saved test state, never live fleet. */
@RunWith(AndroidJUnit4.class)
public final class DisplayModeTest {
 @Test public void PhoneCanSelectTabletKeepPanelsRestartAndReturnToPhone()throws Exception{
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  TabletSettings.prefs(c).edit().putString("display_mode","auto").commit();
  LocalGpsService.prefs(c).edit().putBoolean("intro_seen",true).putBoolean("background_explained",true).putBoolean("device_mode_configured",true).putString("device_mode","patrol").putBoolean("enabled",false).remove("lat").remove("lon").remove("fix_time").commit();
  Activity first=ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  Activity phone=await(ins,153100);assertTrue(phone.getResources().getConfiguration().smallestScreenWidthDp<600);
  ins.runOnMainSync(()->phone.findViewById(153101).performClick());choose(ins,"Tablet — landscape map dashboard");
  Activity wide=await(ins,153005);assertEquals("tablet",TabletSettings.displayMode(c));
  assertTrue(wide.getResources().getDisplayMetrics().widthPixels>wide.getResources().getDisplayMetrics().heightPixels);
  ins.runOnMainSync(()->{
   for(int i=0;i<3;i++){ViewGroup card=wide.findViewById(153010+i);assertNotNull(card);assertTrue(card.getHeight()>0);checkText(card,card);}
   View cog=wide.findViewById(153003);assertTrue(cog.getHeight()>=48*wide.getResources().getDisplayMetrics().density-1);
  });
  Bitmap captured=ins.getUiAutomation().takeScreenshot();assertNotNull(captured);Bitmap shot=captured.copy(Bitmap.Config.ARGB_8888,true);
  Canvas canvas=new Canvas(shot);Paint paint=new Paint();paint.setColor(0xff9e202a);canvas.drawRect(0,0,shot.getWidth(),32,paint);paint.setColor(Color.WHITE);paint.setTextSize(20);canvas.drawText("TEST FIXTURE — PHONE LANDSCAPE; NO LIVE FLEET",8,24,paint);
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(c.getFilesDir(),"phone-landscape-TEST-FIXTURE.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}
  ins.runOnMainSync(()->wide.recreate());ins.waitForIdleSync();Activity restored=await(ins,153005,wide);assertEquals("tablet",TabletSettings.displayMode(c));
  ins.runOnMainSync(()->restored.findViewById(153003).performClick());choose(ins,"Display mode · tablet");choose(ins,"Phone — normal dashboard");
  Activity normal=await(ins,153100);assertEquals("phone",TabletSettings.displayMode(c));assertNull(normal.findViewById(153005));
  ins.runOnMainSync(()->normal.findViewById(153101).performClick());choose(ins,"Auto — follow device screen size");
  Activity auto=await(ins,153100);assertEquals("auto",TabletSettings.displayMode(c));assertNull(auto.findViewById(153005));
  ins.runOnMainSync(auto::finish);ins.waitForIdleSync();
 }
 private static Activity await(Instrumentation ins,int id)throws Exception{return await(ins,id,null);}
 private static Activity await(Instrumentation ins,int id,Activity previous)throws Exception{
  for(int n=0;n<120;n++){final Activity[] current={null};ins.runOnMainSync(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof MainActivity&&a!=previous&&a.findViewById(id)!=null&&a.findViewById(id).getWidth()>0)current[0]=a;});if(current[0]!=null){Thread.sleep(250);return current[0];}Thread.sleep(50);}
  throw new AssertionError("Production dashboard did not become ready: "+id);
 }
 private static void choose(Instrumentation ins,String text)throws Exception{
  for(int n=0;n<80;n++){AccessibilityNodeInfo root=ins.getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
   android.graphics.Rect rect=new android.graphics.Rect();node.getBoundsInScreen(rect);if(!node.isVisibleToUser()||rect.isEmpty())continue;
   long now=android.os.SystemClock.uptimeMillis();
   android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0);
   android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0);
   down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
   try{assertTrue(ins.getUiAutomation().injectInputEvent(down,true));assertTrue(ins.getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}
   ins.waitForIdleSync();Thread.sleep(350);return;
  }Thread.sleep(50);}
  throw new AssertionError("Visible setting could not be tapped: "+text);
 }
 private static void checkText(ViewGroup root,ViewGroup group){
  for(int n=0;n<group.getChildCount();n++){View child=group.getChildAt(n);if(child.getVisibility()!=View.VISIBLE)continue;if(child instanceof ViewGroup)checkText(root,(ViewGroup)child);else if(child instanceof TextView){
   android.graphics.Rect r=new android.graphics.Rect();child.getDrawingRect(r);root.offsetDescendantRectToMyCoords(child,r);
   assertTrue("Patrol geometry: card="+root.getWidth()+"x"+root.getHeight()+", label="+child.getWidth()+"x"+child.getHeight()+", font="+((TextView)child).getTextSize()+", density="+root.getResources().getDisplayMetrics().density+", sw="+root.getResources().getConfiguration().smallestScreenWidthDp+", w="+root.getResources().getConfiguration().screenWidthDp+", h="+root.getResources().getConfiguration().screenHeightDp,child.getHeight()>0);
   assertTrue("Patrol geometry overflow: cardHeight="+root.getHeight()+", labelTop="+r.top+", labelBottom="+r.bottom,r.top>=0&&r.bottom<=root.getHeight()+1);
  }}
 }
}
