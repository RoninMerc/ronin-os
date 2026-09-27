package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SingleWavCanonicalTest {
    private Context context;
    private android.content.SharedPreferences prefs;

    @Before public void setup(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        prefs.edit()
            .remove("voice_profiles_single_wav_v128")
            .remove("voice_profiles_v5")
            .remove("voice_profiles_v4")
            .remove("voice_profile_active")
            .commit();
    }

    @Test public void cleanInstallStartsWithEvelynOnlyAndNoFelicity(){
        VoiceManager v=new VoiceManager(context,prefs);
        assertEquals(1,v.profiles().size());
        assertEquals("Evelyn",v.activeName());
        for(VoiceManager.Profile p:v.profiles())assertFalse(p.name.equalsIgnoreCase("Felicity"));
    }

    @Test public void canonicalManifestIsFixedAcrossDifferentPhoneState(){
        VoiceManager v=new VoiceManager(context,prefs);
        String a=v.exactPackScript(Collections.emptyList(),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        List<Observation> rows=Arrays.asList(
            new Observation("9001","WHATEVER.USER","Totally Different Place","Totally Different Alert","",System.currentTimeMillis()),
            new Observation("9002","ANOTHER.USER","Another Place","Another Alert","",System.currentTimeMillis())
        );
        String b=v.exactPackScript(rows,Arrays.asList("A","B","C"));
        assertEquals("Per-device feed state must never change the WAV manifest",a,b);
        assertFalse(a.contains("Totally Different Alert"));
        assertFalse(a.contains("Another Place"));
    }

    @Test public void canonicalManifestContainsCurrentSiteCoverageAndExactWarningVariant(){
        VoiceManager v=new VoiceManager(context,prefs);
        List<String> p=v.canonicalPhrases();
        assertEquals(359,p.size());
        assertTrue(p.contains("Good evening, Tristan. Let's have a good shift"));
        assertTrue(p.contains("Parking Breach - 1st Warning"));
        assertTrue(p.contains("Parking Breach - 2nd Warning"));
        assertTrue(p.contains("Parking Breach - 3rd Warning"));
        assertTrue(p.contains("Western Bay BC / Serenity Bay"));
        assertTrue(p.contains("Welfare Check"));
        assertTrue(p.contains("Tristan Murdoch"));
    }

    @Test public void canAddAndDeleteProfilesButSoleProfileIsProtected(){
        VoiceManager v=new VoiceManager(context,prefs);
        AtomicBoolean ok=new AtomicBoolean(false);
        v.importTrainingAudio(null,"Derek",(success,msg)->ok.set(success));
        assertTrue(ok.get());
        assertEquals(2,v.profiles().size());
        String derek=v.activeProfile().id;
        assertTrue(v.delete("evelyn"));
        assertEquals(1,v.profiles().size());
        assertEquals("Derek",v.activeName());
        assertFalse(v.delete(derek));
    }

    @Test public void transferredScriptHasNoTrailingPause(){
        VoiceManager v=new VoiceManager(context,prefs);
        String script=v.exactPackScript(Collections.emptyList(),Collections.emptyList());
        assertTrue(script.contains("[pause 3]"));
        assertFalse(script.endsWith("[pause 3]\n"));
        assertTrue(script.endsWith("Parking Breach - 3rd Warning\n"));
    }
}
