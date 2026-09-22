package au.com.roningroup.patrollink;

import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SpeechEditorTest {
    @Rule public GrantPermissionRule permission=GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS");
    private static View tagged(SpeechSettingsUi ui,String tag){View v=ui.currentDialog().getWindow().getDecorView().findViewWithTag(tag);assertNotNull("Missing UI control: "+tag,v);return v;}
    private static void await(ActivityScenario<MainActivity> s,String why,long timeout,Predicate<VoiceManager> predicate)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> status=new AtomicReference<>();
        while(SystemClock.elapsedRealtime()<end){s.onActivity(a->{VoiceManager v=((PatrolApp)a.getApplication()).engine().voices;ok.set(predicate.test(v));status.set(v.diagnostics());});if(ok.get())return;Thread.sleep(150);}fail(why+"\n"+status.get());
    }
    private static void screenshot(String name)throws Exception{
        Thread.sleep(500);ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /sdcard/Download/"+name);
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[4096];while(in.read(b)!=-1){}}
    }
    @Test public void editAlertsNicknamesAndSpeedActuallyReachTheLocalSpeaker()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE).edit().putBoolean("voice",false).putString("voice_profile_active","felicity").commit();
        context.getSharedPreferences(SpeechPreferences.FILE,Context.MODE_PRIVATE).edit().clear().commit();
        Observation row=new Observation("122001","T.MURD","Impeccable BC","General Patrol - Impeccable BC","",System.currentTimeMillis());
        AtomicReference<SpeechSettingsUi> uiRef=new AtomicReference<>();AtomicInteger start=new AtomicInteger();AtomicReference<String> refHash=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                PatrolEngine e=((PatrolApp)a.getApplication()).engine();a.stopService(new Intent(a,MonitorService.class));e.stopRuntime("EDITOR_TEST","Isolated editor fixture");
                e.prefs.edit().putString("guard0","D.DEO").putString("guard1","D.ROGERS1").putString("guard2","T.MURD").apply();
                SpeechPreferences p=e.voices.speechSettings;p.remember(Collections.singletonList(row));
                assertEquals("Tristan",p.nickname("T.MURD"));assertEquals("Dylan",p.nickname("D.DEO"));assertEquals("Dean",p.nickname("D.ROGERS1"));
                // Remembered even while speech is muted. Duplicate rows do not duplicate the list.
                int n=p.entries(SpeechPreferences.ALERT).size();p.remember(Collections.singletonList(row));assertEquals(n,p.entries(SpeechPreferences.ALERT).size());
                p.save(SpeechPreferences.PLACE,"Impeccable BC","Impeccable body corporate");
                SpeechSettingsUi ui=new SpeechSettingsUi(a,e);uiRef.set(ui);ui.show();
                assertNotNull(tagged(ui,"speech_alerts"));assertNotNull(tagged(ui,"speech_nicknames"));assertNotNull(tagged(ui,"speech_rate_slider"));
            });
            screenshot("PatrolLink-v122-Speech-Controls.png");
            scenario.onActivity(a->{SpeechSettingsUi ui=uiRef.get();tagged(ui,"speech_alerts").performClick();assertNotNull(tagged(ui,"entry:alert:general patrol - impeccable bc"));});
            screenshot("PatrolLink-v122-Alert-List.png");
            scenario.onActivity(a->{SpeechSettingsUi ui=uiRef.get();tagged(ui,"entry:alert:general patrol - impeccable bc").performClick();((EditText)tagged(ui,"speech_replacement")).setText("Guard patrol Impeccable body corporate");String preview=((TextView)tagged(ui,"speech_preview_text")).getText().toString();assertTrue(preview.contains("Tristan"));assertTrue(preview.contains("Guard patrol Impeccable body corporate"));});
            screenshot("PatrolLink-v122-Alert-Editor.png");
            scenario.onActivity(a->{SpeechSettingsUi ui=uiRef.get();tagged(ui,"speech_save_rule").performClick();SpeechPreferences fresh=new SpeechPreferences(context);assertEquals("Guard patrol Impeccable body corporate",fresh.entry(SpeechPreferences.ALERT,row.issue).replacement);assertEquals("Silvertracker update. Tristan. Guard patrol Impeccable body corporate.",fresh.format(row));ui.showNicknames();});
            screenshot("PatrolLink-v122-Guard-Nicknames.png");
            scenario.onActivity(a->{
                SpeechSettingsUi ui=uiRef.get();((EditText)tagged(ui,"nickname:T.MURD")).setText("");tagged(ui,"speech_save_nicknames").performClick();
                SpeechPreferences fresh=new SpeechPreferences(context);assertEquals("",fresh.nickname("T.MURD"));assertTrue(fresh.format(row).contains("T Murd."));assertFalse(fresh.format(row).contains("Tristan"));
                ui.showNicknames();((EditText)tagged(ui,"nickname:T.MURD")).setText("Tristan");tagged(ui,"speech_save_nicknames").performClick();
                ((EditText)tagged(ui,"speech_prefix")).setText("SilverTrack update");((SeekBar)tagged(ui,"speech_rate_slider")).setProgress(0);tagged(ui,"speech_save_delivery").performClick();
                SpeechPreferences fresh2=new SpeechPreferences(context);assertEquals(75,fresh2.speedPercent());assertEquals("SilverTrack update",fresh2.prefix());
                VoiceManager voice=((PatrolApp)a.getApplication()).engine().voices;start.set(voice.completedCount());voice.preview("Voice preview. "+fresh2.format(row),fresh2.speed());
            });
            await(scenario,"Slow edited announcement actually played",180000,v->v.completedCount()>start.get());
            scenario.onActivity(a->{
                VoiceManager v=((PatrolApp)a.getApplication()).engine().voices;
                assertEquals(.75f,v.lastPlaybackRate(),.01f);assertEquals("felicity",v.activeProfile().id);
                assertEquals("Voice preview. SilverTrack update. Tristan. Guard patrol Impeccable body corporate.",v.lastSpoken());
                try{refHash.set(VoiceReference.hash(new File(context.getFilesDir(),"local_voice_v121/felicity.wav")));}catch(Exception ex){throw new AssertionError(ex);}
                uiRef.get().show();((SeekBar)tagged(uiRef.get(),"speech_rate_slider")).setProgress(15);tagged(uiRef.get(),"speech_save_delivery").performClick();
                start.set(v.completedCount());v.preview("Voice preview. "+v.speechSettings.format(row),v.speechSettings.speed());
            });
            await(scenario,"Same selected voice plays faster",120000,v->v.completedCount()>start.get());
            scenario.onActivity(a->{
                VoiceManager v=((PatrolApp)a.getApplication()).engine().voices;assertEquals(1.5f,v.lastPlaybackRate(),.01f);assertEquals("felicity",v.activeProfile().id);
                try{assertEquals(refHash.get(),VoiceReference.hash(new File(context.getFilesDir(),"local_voice_v121/felicity.wav")));}catch(Exception ex){throw new AssertionError(ex);}
                assertEquals("General Patrol - Impeccable BC",row.issue);assertEquals("Impeccable BC",row.property);assertEquals("T.MURD",row.guard);
                // Clearing an override persists the original, rather than silently dropping an alert.
                v.speechSettings.save(SpeechPreferences.ALERT,row.issue,"");v.speechSettings.save(SpeechPreferences.PLACE,row.property,"");v.speechSettings.saveDelivery("Silvertracker update",100);
                assertEquals(row.issue,SpeechRules.exact(row.issue,new SpeechPreferences(context).overrides(SpeechPreferences.ALERT)));v.stop();
                if(uiRef.get().currentDialog()!=null)uiRef.get().currentDialog().dismiss();
            });
        }
    }
}
