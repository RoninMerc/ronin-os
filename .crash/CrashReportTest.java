package au.com.roningroup.patrollink;
import android.app.*;import android.content.*;import android.os.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)public final class CrashReportTest{
 Context c;Instrumentation ins;
 @Before public void setup(){ins=InstrumentationRegistry.getInstrumentation();c=ins.getTargetContext();CrashReport.prefs(c).edit().clear().commit();}
 @After public void cleanup(){CrashReport.prefs(c).edit().clear().commit();}
 @Test public void MessagesAndSecretsAreNotRecordedAndAcknowledgementRetainsReport(){
  Throwable error=new IllegalStateException("SYNTHETIC_SECRET_DO_NOT_RECORD",new IllegalArgumentException("SYNTHETIC_REPORT_DO_NOT_RECORD"));
  CrashReport.record(c,error);String report=CrashReport.read(c);assertTrue(report.contains("IllegalStateException"));assertTrue(report.contains("IllegalArgumentException"));assertFalse(report.contains("SYNTHETIC_SECRET"));assertFalse(report.contains("SYNTHETIC_REPORT"));assertTrue(CrashReport.pending(c));CrashReport.acknowledge(c);assertFalse(CrashReport.pending(c));assertEquals(report,CrashReport.read(c));
 }
 @Test public void RecorderDelegatesToOriginalHandlerExactlyOnce(){
  final int[] calls={0};CrashReport.Recorder recorder=new CrashReport.Recorder(c,(t,e)->calls[0]++);
  recorder.uncaughtException(Thread.currentThread(),new NullPointerException("SYNTHETIC_PRIVATE_MESSAGE"));assertEquals(1,calls[0]);assertTrue(CrashReport.pending(c));assertFalse(CrashReport.read(c).contains("SYNTHETIC_PRIVATE_MESSAGE"));
 }
 @Test public void RecoveryScreenAllowsCopyWithoutStartingEngineAndKeepsPairing()throws Exception{
  CrashReport.record(c,new IllegalStateException("SYNTHETIC_TEST_ONLY"));String before=new MasterFeedSecrets(c).load(false);
  Activity a=ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(450);ins.waitForIdleSync();
  ins.runOnMainSync(()->{assertNotNull(a.findViewById(158001));assertEquals(before,new MasterFeedSecrets(c).load(false));a.finish();});
 }
}
