package au.com.roningroup.patrollink;

import android.content.*;
import android.net.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class LocalSpeechTest {
 private static void shell(String command)throws Exception{try(ParcelFileDescriptor p=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(p.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
 private static void copy(InputStream in,File f)throws Exception{try(InputStream input=in;OutputStream out=new FileOutputStream(f)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)out.write(b,0,n);}}
 @Test public void generatesFullMatterOfflineAndUsesOnlyRequestedReference()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  HandlerThread callbacks=new HandlerThread("voice-test-replies");callbacks.start();
  BlockingQueue<Bundle> events=new LinkedBlockingQueue<>();CountDownLatch connected=new CountDownLatch(1);AtomicReference<Messenger> remote=new AtomicReference<>();
  Messenger replies=new Messenger(new Handler(callbacks.getLooper(),m->{events.add(new Bundle(m.getData()));return true;}));
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){remote.set(new Messenger(b));connected.countDown();}public void onServiceDisconnected(ComponentName n){}};
  boolean bound=false;
  try{
   shell("svc wifi disable");shell("svc data disable");Thread.sleep(2500);
   ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
   assertTrue("Offline generation test must have no active Internet network",caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
   bound=c.bindService(new Intent(c,LocalVoiceService.class),connection,Context.BIND_AUTO_CREATE);assertTrue(bound);assertTrue(connected.await(20,TimeUnit.SECONDS));
   send(remote.get(),replies,LocalVoiceService.INIT,"init","","");await(events,"init","READY",90000);
   String text=VoiceReadout.format("T.MURD","Third warning parking breach","Impeccable");
   send(remote.get(),replies,LocalVoiceService.SPEAK,"default",text,"");Bundle first=await(events,"default","DONE",120000);
   File primary=new File(first.getString("file"));assertTrue(primary.length()>24000);assertFalse(first.getString("referenceHash").isEmpty());
   copy(new FileInputStream(primary),new File(c.getCacheDir(),"voice-test-felicity.wav"));
   File alternate=new File(c.getFilesDir(),"test-alternate-reference.wav");copy(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("alternate-reference.wav"),alternate);
   send(remote.get(),replies,LocalVoiceService.SPEAK,"alternate",text,alternate.getPath());Bundle second=await(events,"alternate","DONE",120000);
   assertNotEquals("Different selected references must not use a shared speaker identity",first.getString("referenceHash"),second.getString("referenceHash"));
   assertNotEquals(VoiceAudio.hash(new File(c.getCacheDir(),"voice-test-felicity.wav")),VoiceAudio.hash(new File(second.getString("file"))));
   copy(new FileInputStream(second.getString("file")),new File(c.getCacheDir(),"voice-test-alternate.wav"));
   // Missing references must fail explicitly, not silently revert to Felicity or system TTS.
   send(remote.get(),replies,LocalVoiceService.SPEAK,"missing",text,new File(c.getFilesDir(),"does-not-exist.wav").getPath());await(events,"missing","ERROR",30000);
   System.out.println("LOCAL_SPEECH_TEST: no active Internet; full new matter generated; selected reference hashes differ; missing reference rejected without fallback.");
  }finally{
   if(remote.get()!=null)remote.get().send(Message.obtain(null,LocalVoiceService.STOP));if(bound)c.unbindService(connection);callbacks.quitSafely();shell("svc wifi enable");shell("svc data enable");
  }
 }
 private static void send(Messenger remote,Messenger reply,int kind,String id,String text,String reference)throws Exception{
  Message m=Message.obtain(null,kind);Bundle b=new Bundle();b.putString("id",id);b.putString("text",text);b.putString("reference",reference);b.putBoolean("renderOnly",true);m.setData(b);m.replyTo=reply;remote.send(m);
 }
 private static Bundle await(BlockingQueue<Bundle> events,String id,String target,long millis)throws Exception{
  long end=SystemClock.elapsedRealtime()+millis;
  while(SystemClock.elapsedRealtime()<end){Bundle b=events.poll(1,TimeUnit.SECONDS);if(b==null||!id.equals(b.getString("id")))continue;System.out.println("VOICE_EVENT "+id+" "+b.getString("state")+" "+b.getString("detail"));
   if(target.equals(b.getString("state")))return b;if("ERROR".equals(b.getString("state")))fail(b.getString("detail"));}
  throw new AssertionError("Timed out awaiting "+id+" "+target);
 }
}
