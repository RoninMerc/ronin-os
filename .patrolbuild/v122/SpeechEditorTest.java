package au.com.roningroup.patrollink;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.*;
@RunWith(AndroidJUnit4.class)
public class SpeechEditorTest {
    private static Object field(Object o,String name){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private static void invoke(Object o,String name){try{java.lang.reflect.Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}catch(Exception e){throw new AssertionError(e);}}
    private static void inputs(View v,List<EditText> out){if(v instanceof EditText)out.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)inputs(((ViewGroup)v).getChildAt(i),out);}
    private static void idle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    @Test public void editorSavesNicknamesAndRulesWhileBrowserStaysAttached(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(c.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        AtomicReference<SpeechSettingsUi> shown=new AtomicReference<>();AtomicReference<PatrolEngine> engine=new AtomicReference<>();AtomicReference<String> savedId=new AtomicReference<>();
        AtomicReference<WebView> browser=new AtomicReference<>();AtomicReference<List<String>> guards=new AtomicReference<>();AtomicReference<String> voice=new AtomicReference<>();
        String alert="Parking Breach 3 - UI test";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            idle();scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();engine.set(e);browser.set(e.peekWebView());guards.set(new ArrayList<>(e.guards()));voice.set(e.voices.activeProfile().id);
                e.voices.wording.remember(List.of(new Observation("991122","T.MURD","Impeccable",alert,"",0)));
                invoke(a,"speechWording");shown.set((SpeechSettingsUi)field(a,"speechEditor"));
            });idle();
            scenario.onActivity(a->{SpeechSettingsUi ui=shown.get();((EditText)field(ui,"search")).setText("UI test");ListView list=(ListView)field(ui,"list");assertEquals("Filtered source alerts",1,list.getAdapter().getCount());list.performItemClick(null,0,0);});idle();
            scenario.onActivity(a->{SpeechSettingsUi ui=shown.get();AlertDialog editor=(AlertDialog)field(ui,"editor");List<EditText> fields=new ArrayList<>();inputs(editor.getWindow().getDecorView(),fields);assertEquals("Alert editor inputs",2,fields.size());fields.get(1).setText("Third warning parking breach");editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                for(SpeechRules.Rule r:engine.get().voices.wording.rules())if(r.original.equals(alert))savedId.set(r.id);
                assertNotNull("Saved speech rule",savedId.get());assertEquals("Third warning parking breach",engine.get().voices.wording.issue(alert));
                ((Button)field(ui,"guards")).performClick();
            });idle();
            scenario.onActivity(a->{SpeechSettingsUi ui=shown.get();ListView list=(ListView)field(ui,"list");int index=engine.get().guards().indexOf("T.MURD");assertTrue("Fixture includes T.MURD",index>=0);list.performItemClick(null,index,index);});idle();
            scenario.onActivity(a->{SpeechSettingsUi ui=shown.get();AlertDialog editor=(AlertDialog)field(ui,"editor");List<EditText> fields=new ArrayList<>();inputs(editor.getWindow().getDecorView(),fields);assertEquals("Nickname editor inputs",2,fields.size());fields.get(1).setText("Tristan");editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                PatrolEngine e=engine.get();assertEquals("Tristan",e.voices.wording.nickname("T.MURD"));assertSame("Browser instance retained",browser.get(),e.peekWebView());assertTrue("Browser remains attached while editing",e.peekWebView().isAttachedToWindow());assertEquals(guards.get(),e.guards());assertEquals(voice.get(),e.voices.activeProfile().id);
            });idle();
            // Recreate with the settings overlay still open, exercising production cleanup.
            scenario.recreate();idle();scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();SpeechPreferences disk=new SpeechPreferences(a);assertEquals("Tristan",disk.nickname("T.MURD"));assertEquals("Third warning parking breach",disk.issue(alert));assertTrue("Recreated browser attached",e.peekWebView().isAttachedToWindow());shown.get().close();shown.get().close();invoke(a,"speechWording");shown.set((SpeechSettingsUi)field(a,"speechEditor"));});idle();
            scenario.onActivity(a->{shown.get().close();shown.get().close();});
        }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(shown.get()!=null)shown.get().close();if(engine.get()!=null){engine.get().voices.wording.nickname("T.MURD","");if(savedId.get()!=null)engine.get().voices.wording.removeRule(savedId.get());}});}
    }
}
