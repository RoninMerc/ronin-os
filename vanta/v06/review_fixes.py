from pathlib import Path
root=Path(__file__).resolve().parents[1]/'personal'
j=root/'app/src/main/java/com/ronin/vanta'
def patch(path,old,new):
 s=path.read_text()
 if s.count(old)!=1:raise SystemExit(f'{path.name}: expected one review target: {old[:100]} ({s.count(old)})')
 path.write_text(s.replace(old,new,1))
main=j/'MainActivity.java'
patch(j/'ConversationAdapter.java','((ClipboardManager)', '((android.content.ClipboardManager)')
patch(main,'prompt=null;attachmentLabel=null;attachmentChips=null;', 'prompt=null;attachmentChips=null;')
patch(main,'Net.Call c=begin();preview.removeAllViews();status.setText("Generating image · "+m.name);','if(running)return;Net.Call c=begin();preview.removeAllViews();status.setText("Generating image · "+m.name);')
patch(main,'MediaCheck.Image result=MediaCheck.image(bytes);ui.post(()->{if(active!=c)return;finishCall(c);showImage(preview,bytes,result,p,m);});','MediaCheck.Image result=MediaCheck.image(bytes);String warning="";try{new PreviewStore(this).saveImage(bytes,p.toJson(),m.toJson());}catch(Exception storage){warning=" Preview could not be retained; save before leaving this workspace.";}final String savedWarning=warning;ui.post(()->{if(active!=c)return;finishCall(c);showImage(preview,bytes,result,p,m);if(!savedWarning.isEmpty())status.append(savedWarning);});')
patch(main,'body.addView(preview,space(16));','body.addView(preview,space(16));if(!video)restoreImagePreview(preview);')
patch(main,'  private void showImage(\n', '''  private void restoreImagePreview(LinearLayout box) {
    PreviewStore cache=new PreviewStore(this);if(!cache.hasImage())return;
    final int epoch=renderEpoch;io.submit(()->{try{
      PreviewStore.Image data=cache.readImage();MediaCheck.Image decoded=MediaCheck.image(data.bytes);
      ProviderConfig original=ProviderConfig.fromJson(data.provider);ModelInfo originalModel=ModelInfo.fromJson(data.model);
      ui.post(()->{if(!isDestroyed()&&!running&&mode.equals("image")&&mediaPreview==box&&renderEpoch==epoch){showImage(box,data.bytes,decoded,original,originalModel);status.append(" · Previous preview restored without another request");}});
    }catch(Exception e){ui.post(()->{if(!isDestroyed()&&mode.equals("image")&&mediaPreview==box&&!running)error("Previous preview could not be opened. Your exported files are unchanged.");});}});
  }

  private void showImage(
''')
patch(main,'io.submit(()->{File[] files=getCacheDir().listFiles();int removed=0;', 'io.submit(()->{new PreviewStore(this).clear();File[] files=getCacheDir().listFiles();int removed=0;')
patch(main,'"Paused locally. Submitted video and Forge jobs may continue; their checkpoints are"\n              + " retained for Resume."', '(mode.equals("video")||mode.equals("forge"))?"Paused locally. The saved remote job can be resumed without resubmitting.":"Stopped. Received text is retained; your unanswered prompt can be retried."')
patch(j/'VantaDesign.java','w.setGravity(Gravity.BOTTOM);w.setSoftInputMode', 'if(a.getSharedPreferences("vanta_state",0).getBoolean("secure_screen",false))w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);w.setGravity(Gravity.BOTTOM);w.setSoftInputMode')
patch(root/'app/src/main/AndroidManifest.xml','android:allowBackup="false"','android:enableOnBackInvokedCallback="true"\n        android:allowBackup="false"')
(j/'PreviewStore.java').write_text('''package com.ronin.vanta;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
/** Last image preview, not an export. Authenticated encryption; atomic replacement. */
public final class PreviewStore {
  private static final Object LOCK=new Object();
  private static final int MAX=24*1024*1024;
  private static final String AAD="vanta:last-image:v1";
  private final AtomicFile file;
  private final SecureVault vault;
  public static final class Image {
    public final byte[] bytes;public final JSONObject provider,model;
    Image(byte[] b,JSONObject p,JSONObject m){bytes=b;provider=p;model=m;}
  }
  public PreviewStore(Context context){file=new AtomicFile(new File(context.getNoBackupFilesDir(),"image-preview.enc"));vault=new SecureVault(context);}
  public boolean hasImage(){synchronized(LOCK){return file.getBaseFile().exists();}}
  public void clear(){synchronized(LOCK){file.delete();}}
  public void saveImage(byte[] bytes,JSONObject provider,JSONObject model)throws Exception {
    if(bytes==null||bytes.length==0||bytes.length>MAX)throw new IOException("Preview exceeds the 24 MB local retention limit.");
    byte[] metadata=new JSONObject().put("provider",provider).put("model",model).toString().getBytes(StandardCharsets.UTF_8);
    if(metadata.length>100000)throw new IOException("Preview metadata exceeds limit.");
    byte[] payload=ByteBuffer.allocate(4+metadata.length+bytes.length).putInt(metadata.length).put(metadata).put(bytes).array();
    byte[] encrypted=vault.seal(AAD,payload);
    synchronized(LOCK){FileOutputStream out=null;try{out=file.startWrite();out.write(encrypted);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
  }
  public Image readImage()throws Exception {
    byte[] encrypted;
    synchronized(LOCK){try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>MAX+100100)throw new IOException("Preview exceeds inspection limit.");out.write(buffer,0,n);}encrypted=out.toByteArray();}}
    byte[] decoded=vault.unseal(AAD,encrypted);if(decoded.length<5)throw new IOException("Invalid preview.");
    ByteBuffer data=ByteBuffer.wrap(decoded);int length=data.getInt();if(length<2||length>100000||length>=data.remaining())throw new IOException("Invalid preview metadata.");
    byte[] meta=new byte[length];data.get(meta);JSONObject item=new JSONObject(new String(meta,StandardCharsets.UTF_8));byte[] bytes=new byte[data.remaining()];data.get(bytes);
    return new Image(bytes,item.getJSONObject("provider"),item.getJSONObject("model"));
  }
}
''')
(root/'app/src/androidTest/java/com/ronin/vanta/PreviewPersistenceTest.java').write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public class PreviewPersistenceTest {
 @Test public void encryptedPreviewRestoresWithoutProviderCall()throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PreviewStore s=new PreviewStore(c);
  try {byte[] bytes="preview-fixture-not-a-network-response".getBytes(StandardCharsets.UTF_8);s.saveImage(bytes,new JSONObject().put("id","qa"),new JSONObject().put("id","qa-model"));
   assertTrue(s.hasImage());assertArrayEquals(bytes,new PreviewStore(c).readImage().bytes);assertEquals("qa",s.readImage().provider.getString("id"));
   byte[] disk=Files.readAllBytes(new File(c.getNoBackupFilesDir(),"image-preview.enc").toPath());assertFalse(new String(disk,StandardCharsets.UTF_8).contains("preview-fixture"));
   disk[disk.length-1]^=1;Files.write(new File(c.getNoBackupFilesDir(),"image-preview.enc").toPath(),disk);
   try{s.readImage();fail("Tampered preview accepted");}catch(javax.crypto.AEADBadTagException expected){}
  }finally{s.clear();}assertFalse(s.hasImage());
 }
}
''')
print('Applied final review: compiler symbols, secure sheets, duplicate-image guard, encrypted recoverable image preview and regression.')
