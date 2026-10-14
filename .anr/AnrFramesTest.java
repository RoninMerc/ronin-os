package au.com.roningroup.patrollink;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.charset.StandardCharsets;
public final class AnrFramesTest{
 String read(String s)throws Exception{return AnrFrames.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));}
 @Test public void OnlyMainMethodsAndLineNumbersAreRetained()throws Exception{
  String result=read("PRIVATE SECRET HEADER\n\"main\" prio=5 tid=1 Waiting\n  at android.security.KeyStore.getKey(KeyStore.java:42)\n  - waiting on SECRET_KEY_CONTENT\n  at au.com.roningroup.patrollink.MasterFeedSecrets.load(MasterFeedSecrets.java:27)\n\"worker\" prio=5\n  at private.Report.secret(Secret.java:99)\n");
  assertTrue(result.contains("KeyStore.getKey:42"));assertTrue(result.contains("MasterFeedSecrets.load:27"));assertFalse(result.contains("SECRET"));assertFalse(result.contains("private.Report"));
 }
 @Test public void NativeAndAbsentTracesDoNotExposeContents()throws Exception{
  assertEquals("No ANR trace available.",AnrFrames.read(null));
  assertEquals("ANR trace had no readable main-thread Java frames.",read("PRIVATE PHOTO\n\"main\" prio=5 tid=1 Native\n native: #00 PRIVATE PATH\n"));
 }
 @Test public void FramesAreBounded()throws Exception{
  StringBuilder s=new StringBuilder("\"main\" prio=5 tid=1 Runnable\n");for(int i=0;i<100;i++)s.append("  at demo.Work.step(Work.java:").append(i).append(")\n");
  String out=read(s.toString());assertTrue(out.contains("step:29"));assertFalse(out.contains("step:30"));
 }
}
