package au.com.roningroup.patrollink;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SiteVoiceCoverageTest {
    @Before public void resetSpeechPrefs(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        c.getSharedPreferences(SpeechPreferences.FILE,Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void suppliedGuardUsernamesSeedToSpokenNames(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SpeechPreferences p=new SpeechPreferences(c);
        Map<String,String> expected=new LinkedHashMap<>();
        expected.put("A.KINSELLA","Adam");
        expected.put("B.EYERS","Blake");
        expected.put("SNP","Broadbeach SNP");
        expected.put("C.ISMAY","Caley");
        expected.put("CHRIS.HENRY","Christopher");
        expected.put("C.FITZ","Connor");
        expected.put("D.SAHU1","Dave");
        expected.put("D.HENRY1","David");
        expected.put("D.ROGERS1","Dean");
        expected.put("D.DEO","Dylan");
        expected.put("HSECURITY","Hilton Guard");
        expected.put("J.BELL1","Jack");
        expected.put("J.GRILLO","Jack");
        expected.put("JACK.T","Jack");
        expected.put("J.BRYANT1","Jackson");
        expected.put("INFO.1","Jazna");
        expected.put("K.DAVIES","Kieran");
        expected.put("K.DAVIES1","Kieran");
        expected.put("L.MHALL","Lochlan");
        expected.put("M.GRAVES","Maarino");
        expected.put("M.GRAVES1","Maarino");
        expected.put("M.PETRO","Mitch");
        expected.put("N.BILL","Nic");
        expected.put("PETER.D","Peter");
        expected.put("S.GALLO","Samuel");
        expected.put("S.ASHENDEN","Sebastian");
        expected.put("T.EDWARDS","Thomas");
        expected.put("T.GRAVES1","Tohi");
        expected.put("T.MURD","Tristan");
        expected.put("T.ROGERS","Troy");
        for(Map.Entry<String,String> e:expected.entrySet()) assertEquals(e.getKey(),e.getValue(),p.nickname(e.getKey()));
    }

    @Test public void copiedPackScriptContainsAllSuppliedIncidentTypesAndWelcome(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences app=c.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE);
        VoiceManager v=new VoiceManager(c,app);
        String script=v.exactPackScript(Collections.emptyList(),Arrays.asList("T.MURD","D.DEO","D.ROGERS1"));
        String[] incidents={
            "Alarm Response","Animal Control","Assault","Attempted Break & Enter","Breach - GCCC",
            "Breach- Body Corporate By-laws","Break and Enter","Car Accident","Common Property Damage",
            "Customer assistance","Customer Complaint","Danger/Threat","Disturbance","Domestic Violence",
            "Door Found Open","Environmental Issue","Fire Incident","First Aid Applied","Garage Door Found Open",
            "Gate Access - Emergency","Gate Access - Resident/Visitor","General Issue","Graffiti","Hazard","Hooning",
            "Injury","Interaction - Resident","Lost and Found","Minor Flooding","Noise Complaint","Owner Response",
            "Pedestrian Gate Unsecured","Phone Call Received","Physical Altercation","Physical Violence",
            "Property Damage","Public Assistance","Public nuisance","QAS On Site","QFS On Site","QPS On Site",
            "Resident Issue/Concern","Robbery","Security Gate Unsecured","Security Patrol","Solicitation","Stolen Vehicle",
            "Suspicious Activity","Tampering","Theft","Trespassing","Unauthorized Visitor","Under The Influence",
            "Vandalism","Vehicle Patrol Completed","Vehicle Unsecured","Verbal Altercation","Welfare Check","Window Found Open"
        };
        for(String incident:incidents) assertTrue("Missing incident from exact pack: "+incident,script.contains(incident));
        assertTrue(script.contains("Good evening, Tristan. Let's have a good shift"));
        assertTrue(script.contains("Adam Kinsella"));
        assertTrue(script.contains("Tristan Murdoch"));
        assertTrue(script.contains("[pause 3]"));
    }
}
