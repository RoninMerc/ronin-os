package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PhraseCatalogTest {
    private Context context;
    private android.content.SharedPreferences appPrefs;

    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        appPrefs=context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        context.getSharedPreferences(SpeechPreferences.FILE,Context.MODE_PRIVATE).edit().clear().commit();
        appPrefs.edit()
                .remove("two_part_manifest:evelyn")
                .putString("voice_profile_active","evelyn")
                .commit();
    }

    private Observation balmaraRow(){
        return new Observation("900001","T.MURD","Balmara Pl","Guard Patrol - Balmara Pl","",System.currentTimeMillis());
    }

    private static int occurrences(String text,String needle){
        int count=0,at=0;
        while((at=text.indexOf(needle,at))>=0){count++;at+=needle.length();}
        return count;
    }

    @Test public void fullCatalogIncludesKnownNamesIncidentsPlacesAndLivePhrases(){
        VoiceManager v=new VoiceManager(context,appPrefs);
        List<Observation> recent=Collections.singletonList(balmaraRow());
        List<VoiceManager.PhraseItem> items=v.phraseItems(recent,Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        assertTrue(items.size()>100);
        Set<String> raw=new HashSet<>();
        for(VoiceManager.PhraseItem item:items)raw.add(SpeechRules.key(item.original));
        assertTrue(raw.contains(SpeechRules.key("Tristan")));
        assertTrue(raw.contains(SpeechRules.key("Noise Complaint")));
        assertTrue(raw.contains(SpeechRules.key("General Patrol")));
        assertTrue(raw.contains(SpeechRules.key("Balmara Pl")));
        for(VoiceManager.PhraseItem item:items)assertTrue(item.part==1||item.part==2);
    }

    @Test public void editingReusablePlaceFixesDuplicateLocationBeforeAnnouncementFormatting(){
        VoiceManager v=new VoiceManager(context,appPrefs);
        Observation row=balmaraRow();

        v.savePhraseWording("Balmara Pl","Balmara Place");

        String spoken=v.speechSettings.format(row);
        assertTrue(spoken,spoken.contains("Guard Patrol - Balmara Place"));
        assertEquals("Location should only be spoken once after the reusable phrase correction",1,occurrences(spoken,"Balmara Place"));
        assertFalse(spoken.contains("Balmara Pl. Balmara Pl"));

        List<VoiceManager.PhraseItem> items=v.phraseItems(Collections.singletonList(row),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        VoiceManager.PhraseItem target=null;
        for(VoiceManager.PhraseItem item:items)if(SpeechRules.key(item.original).equals(SpeechRules.key("Balmara Pl"))){target=item;break;}
        assertNotNull(target);
        assertTrue(target.edited);
        assertEquals("Balmara Place",target.spoken);
    }

    @Test public void regeneratedTwoPartScriptsUseEditedSpokenPhrase(){
        VoiceManager v=new VoiceManager(context,appPrefs);
        Observation row=balmaraRow();
        v.savePhraseWording("Balmara Pl","Balmara Place");
        List<VoiceManager.Part> parts=v.prepareParts(Collections.singletonList(row),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        assertEquals(2,parts.size());

        boolean foundReplacement=false;
        boolean foundRawLine=false;
        for(VoiceManager.Part part:parts){
            for(String line:part.script.split("\\n")){
                if(line.equals("Balmara Place"))foundReplacement=true;
                if(line.equals("Balmara Pl"))foundRawLine=true;
            }
        }
        assertTrue("Edited phrase must be recorded in one of the two scripts",foundReplacement);
        assertFalse("Old raw abbreviation must not remain as its own recording line",foundRawLine);
    }

    @Test public void restoreOriginalRemovesOverride(){
        VoiceManager v=new VoiceManager(context,appPrefs);
        v.savePhraseWording("Balmara Pl","Balmara Place");
        assertEquals("Balmara Place",v.speechSettings.overrides(SpeechPreferences.PHRASE).get(SpeechRules.key("Balmara Pl")));
        v.savePhraseWording("Balmara Pl","");
        assertFalse(v.speechSettings.overrides(SpeechPreferences.PHRASE).containsKey(SpeechRules.key("Balmara Pl")));
    }
}
