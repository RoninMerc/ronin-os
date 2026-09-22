package au.com.roningroup.patrollink;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.*;
@RunWith(AndroidJUnit4.class)
public class SpeechPreferencesTest {
    @Test public void dictionaryPersistsAndCanBeRestoredWithoutEditingObservations(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();SharedPreferences raw=c.getSharedPreferences("test-wording-"+UUID.randomUUID(),Context.MODE_PRIVATE);
        try{
            SpeechPreferences p=new SpeechPreferences(raw);Observation o=new Observation("999901","T.MURD","Impeccable","Parking Breach 3","",1);
            p.remember(List.of(o,o));assertEquals(1,p.seen().size());
            p.saveRule(null,"Parking Breach 3","Third warning parking breach",false,true);p.nickname("t.murd","Tristan");
            SpeechPreferences reload=new SpeechPreferences(raw);assertEquals("Guard Tristan recorded Third warning parking breach at Impeccable.",reload.readout(o));assertEquals("Parking Breach 3",reload.seen().get(0).issue);
            assertEquals("T.MURD",o.guard);assertEquals("Parking Breach 3",o.issue);assertEquals("Impeccable",o.property);
            String id=reload.rules().get(0).id;reload.saveRule(id,"Parking Breach 3","Third warning parking breach",false,false);assertTrue(reload.readout(o).contains("Parking Breach 3"));
            reload.removeRule(id);reload.nickname("T.MURD","");assertEquals(VoiceReadout.of(o),new SpeechPreferences(raw).readout(o));assertEquals(1,reload.seen().size());
        }finally{raw.edit().clear().commit();}
    }
    @Test public void previewDoesNotSaveAndDuplicateRulesAreRejected(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();SharedPreferences raw=c.getSharedPreferences("test-wording-"+UUID.randomUUID(),Context.MODE_PRIVATE);
        try{
            SpeechPreferences p=new SpeechPreferences(raw);Observation o=new Observation("999901","D.DEO","Solo","Parking Breach 3","",1);
            assertTrue(p.preview(null,o.issue,"Changed",false,true,o).contains("Changed"));assertEquals(0,p.rules().size());
            p.saveRule(null,o.issue,"Changed",false,true);try{p.saveRule(null,"PARKING  BREACH 3","Other",false,true);fail("duplicate allowed");}catch(IllegalArgumentException expected){}
            p.nickname("D.DEO","Dylan");assertEquals("",p.nickname("D.DIO"));assertTrue(p.readout(o).contains("Dylan"));
        }finally{raw.edit().clear().commit();}
    }
}
