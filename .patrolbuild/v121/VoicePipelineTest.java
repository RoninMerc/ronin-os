package au.com.roningroup.patrollink;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoicePipelineTest {
    @Rule public GrantPermissionRule permission=GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS");
    private static void await(ActivityScenario<MainActivity> scenario,String message,long timeout,Predicate<PatrolEngine> check)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+timeout;AtomicBoolean ok=new AtomicBoolean();AtomicReference<String> report=new AtomicReference<>("");
        while(SystemClock.elapsedRealtime()<deadline){scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();ok.set(check.test(e));report.set(e.voices.diagnostics());});if(ok.get())return;Thread.sleep(200);}
        fail(message+"\n"+report.get());
    }
    private static String digest(String s)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    private static void copy(InputStream in,File file)throws Exception{try(InputStream source=in;OutputStream out=new FileOutputStream(file)){byte[] b=new byte[65536];int n;while((n=source.read(b))!=-1)out.write(b,0,n);}}
    @Test public void actualOfflineVoiceReadsThreeNewRowsWhileMonitorKeepsRefreshing()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("patrol_settings",Context.MODE_PRIVATE).edit().putBoolean("voice",false).putString("voice_profile_active","felicity").commit();
        AtomicInteger requests=new AtomicInteger(),ticks=new AtomicInteger();
        String[] guards={"D.DEO","D.ROGERS1","T.MURD"};String[] locations={"Solo","Serenade","Impeccable"};
        String[] issues={"General patrol scan point","Recreation centre locked","Third warning parking breach"};
        long recorded=System.currentTimeMillis();String date=DateTimeFormatter.ofPattern("EEE M/d h:mm a",Locale.US).format(Instant.ofEpochMilli(recorded).atZone(ZoneId.of("Australia/Brisbane")));
        Handler heartbeat=new Handler(Looper.getMainLooper());Runnable tick=new Runnable(){public void run(){ticks.incrementAndGet();heartbeat.postDelayed(this,100);}};
        long began=SystemClock.elapsedRealtime();heartbeat.post(tick);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                PatrolEngine e=((PatrolApp)a.getApplication()).engine();e.stopRuntime("TEST_SETUP","Local voice fixture");e.lastRead=e.lastReadElapsed=0;
                try{Method m=PatrolEngine.class.getDeclaredMethod("resetRenderer");m.setAccessible(true);m.invoke(e);}catch(Exception ex){throw new AssertionError(ex);}
                e.prefs.edit().putBoolean("voice",true).putString("guard0",guards[0]).putString("guard1",guards[1]).putString("guard2",guards[2]).apply();
                WebView w=e.webView();WebViewClient original=w.getWebViewClient();
                w.setWebViewClient(new WebViewClient(){
                    @Override public void onPageStarted(WebView v,String u,Bitmap b){original.onPageStarted(v,u,b);}
                    @Override public void onPageCommitVisible(WebView v,String u){original.onPageCommitVisible(v,u);}
                    @Override public void onPageFinished(WebView v,String u){original.onPageFinished(v,u);}
                    @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError er){original.onReceivedError(v,r,er);}
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                        String html="<html><body>Fixture resource</body></html>";
                        if(PatrolEngine.monitorPage(r.getUrl().toString())){
                            int n=requests.incrementAndGet(),batch=n>=2?2:1;StringBuilder rows=new StringBuilder();
                            for(int i=0;i<3;i++)rows.append("<tr><td>").append(3300000000L+batch*10+i).append("</td><td>").append(locations[i]).append("</td><td>").append(issues[i]).append("</td><td>").append(date).append("</td><td>").append(guards[i]).append("</td></tr>");
                            html="<html><body><h1>Issue Monitor</h1><table><thead><tr><th>Issue ID</th><th>Property Name</th><th>Reported Issue</th><th>Created Date</th><th>Created By</th></tr></thead><tbody>"+rows+"</tbody></table></body></html>";
                        }
                        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                });e.start();
            });
            await(scenario,"Baseline fixture loaded",25000,e->e.healthy()&&e.selectedCount==3);
            scenario.onActivity(a->assertEquals(0,((PatrolApp)a.getApplication()).engine().voices.completedCount()));
            await(scenario,"Three full announcements actually generated and played",210000,e->e.voices.completedCount()>=3);
            await(scenario,"Automatic 30-second refresh continued during speech",45000,e->requests.get()>=3&&e.healthy());
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();assertEquals(3,e.voices.completedCount());assertEquals(3,e.voices.generatedCount());assertNotEquals(android.os.Process.myPid(),e.voices.workerPid());assertEquals("felicity",e.voices.activeProfile().id);});
            File evidence=new File(context.getFilesDir(),"voice-test-evidence");evidence.mkdirs();
            File ref=new File(context.getFilesDir(),"local_voice_v121/felicity.wav");String hash=VoiceReference.hash(ref);
            StringBuilder audit=new StringBuilder();
            for(int i=0;i<3;i++){
                String text=AnnouncementText.format(new Observation("330000002"+i,guards[i],locations[i],issues[i],date,recorded));
                File wav=new File(context.getCacheDir(),"local_speech_v121/"+digest("pocket-int8-v121-5\n"+hash+"\n"+text)+".wav");assertTrue(wav.toString(),wav.length()>24000);
                copy(new FileInputStream(wav),new File(evidence,"full-update-"+i+".wav"));audit.append(i).append(": ").append(text).append('\n');
            }
            // Connection-status transitions alone must not enqueue any audio.
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();for(int i=0;i<40;i++){e.status("CHECKING","Test status transition");e.status("READ_OK","Test status transition");}});
            Thread.sleep(1500);scenario.onActivity(a->assertEquals(3,((PatrolApp)a.getApplication()).engine().voices.generatedCount()));
            assertTrue("UI heartbeat remained responsive",ticks.get()>(SystemClock.elapsedRealtime()-began)/500);
            File incoming=new File(context.getFilesDir(),"second-voice-test.wav");copy(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("other-voice.wav"),incoming);
            AtomicBoolean imported=new AtomicBoolean();AtomicReference<String> importedMessage=new AtomicReference<>("");
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();a.stopService(new Intent(a,MonitorService.class));e.stopRuntime("TEST_DONE","Voice fixture feed stopped");e.voices.importTrainingAudio(Uri.fromFile(incoming),"Second test voice",(ok,msg)->{imported.set(ok);importedMessage.set(msg);});});
            await(scenario,"Local WAV import and new-voice speech completed: "+importedMessage.get(),180000,e->imported.get()&&e.voices.completedCount()>=4);
            scenario.onActivity(a->{PatrolEngine e=((PatrolApp)a.getApplication()).engine();assertEquals("Second test voice",e.voices.activeName());assertFalse(e.voices.activeProfile().builtIn);e.voices.setActive("felicity");assertEquals("felicity",e.voices.activeProfile().id);e.prefs.edit().putBoolean("voice",false).apply();e.voices.stop();});
            audit.append("Three full rows generated and played; exact IDs deduplicated; status changes produced no new audio; second WAV imported and used.\n");
            audit.append("UI heartbeat ticks: ").append(ticks.get()).append("; monitor requests: ").append(requests.get()).append('\n');
            try(FileOutputStream out=new FileOutputStream(new File(evidence,"PIPELINE.txt"))){out.write(audit.toString().getBytes(StandardCharsets.UTF_8));}
        }finally{heartbeat.removeCallbacks(tick);}
    }
}
