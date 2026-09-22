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
    private static void inputs(View v,List<EditText> out){if(v instanceof EditText)out.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)inputs(((ViewGroup)v).getChildAt(i),out);}
    @Test public void editorSavesNicknamesAndRulesWhileBrowserStaysAttached(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(c.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        AtomicReference<SpeechSettingsUi> shown=new AtomicReference<>();AtomicReference<PatrolEngine> engine=new AtomicReference<>();AtomicReference<String> savedId=new AtomicReference<>();
        String alert="Parking Breach 3 - UI test";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();engine.set(e);WebView w=e.peekWebView();List<String> ids=new ArrayList<>(e.guards());String voice=e.voices.activeProfile().id;
                e.voices.wording.remember(List.of(new Observation("991122","T.MURD","Impeccable",alert,"",0)));
                SpeechSettingsUi ui=SpeechSettingsUi.show(a,e);shown.set(ui);((EditText)field(ui,"search")).setText("UI test");ListView list=(ListView)field(ui,"list");assertEquals(1,list.getAdapter().getCount());list.performItemClick(null,0,0);
                AlertDialog editor=(AlertDialog)field(ui,"editor");List<EditText> fields=new ArrayList<>();inputs(editor.getWindow().getDecorView(),fields);assertEquals(2,fields.size());fields.get(1).setText("Third warning parking breach");editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                for(SpeechRules.Rule r:e.voices.wording.rules())if(r.original.equals(alert))savedId.set(r.id);
                assertNotNull(savedId.get());assertEquals("Third warning parking breach",e.voices.wording.issue(alert));
                ((Button)field(ui,"guards")).performClick();list=(ListView)field(ui,"list");int index=e.guards().indexOf("T.MURD");assertTrue(index>=0);list.performItemClick(null,index,index);
                editor=(AlertDialog)field(ui,"editor");fields.clear();inputs(editor.getWindow().getDecorView(),fields);fields.get(1).setText("Tristan");editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertEquals("Tristan",e.voices.wording.nickname("T.MURD"));assertSame(w,e.peekWebView());assertTrue(w.isAttachedToWindow());assertEquals(ids,e.guards());assertEquals(voice,e.voices.activeProfile().id);ui.close();
            });
            scenario.recreate();scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();SpeechPreferences disk=new SpeechPreferences(a);assertEquals("Tristan",disk.nickname("T.MURD"));assertEquals("Third warning parking breach",disk.issue(alert));assertTrue(e.peekWebView().isAttachedToWindow());});
        }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(shown.get()!=null)shown.get().close();if(engine.get()!=null){engine.get().voices.wording.nickname("T.MURD","");if(savedId.get()!=null)engine.get().voices.wording.removeRule(savedId.get());}});}
    }
}
