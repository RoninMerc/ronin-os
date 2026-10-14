package au.com.roningroup.patrollink;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
/** Scoped relay pairing credentials, encrypted locally using Android Keystore. Never SilverTrack login credentials. */
final class MasterFeedSecrets {
 private static final String ALIAS="patrol_link_master_feed_pairing_v1";
 private final SharedPreferences prefs;
 MasterFeedSecrets(Context c){prefs=c.getApplicationContext().getSharedPreferences("patrol_master_feed_v1",0);}
 private SecretKey key()throws Exception{
   KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
   if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
   KeyGenerator kg=KeyGenerator.getInstance("AES","AndroidKeyStore");
   kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());
   return kg.generateKey();
 }
 private String slot(boolean master){return master?"master_cipher":"reader_cipher";}
 synchronized void save(boolean master,String value)throws Exception{
   if(value==null||value.trim().isEmpty())return;
   String input=value.trim();
   if(input.length()<32||input.length()>256||!input.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Pairing key format is invalid");
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
   byte[] iv=c.getIV(),data=c.doFinal(input.getBytes(StandardCharsets.UTF_8)),merged=new byte[1+iv.length+data.length];
   merged[0]=(byte)iv.length;System.arraycopy(iv,0,merged,1,iv.length);System.arraycopy(data,0,merged,1+iv.length,data.length);
   prefs.edit().putString(slot(master),Base64.encodeToString(merged,Base64.NO_WRAP)).apply();
 }
 synchronized String load(boolean master){
   AnrHooks.crypto();
   try{
      String encoded=prefs.getString(slot(master),"");if(encoded.isEmpty())return "";
      byte[] buf=Base64.decode(encoded,Base64.NO_WRAP);int n=buf[0]&255;
      if(n<8||n>32||buf.length<1+n+16)return "";
      byte[] iv=new byte[n];System.arraycopy(buf,1,iv,0,n);
      Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
      return new String(c.doFinal(buf,1+n,buf.length-1-n),StandardCharsets.UTF_8);
   }catch(Exception e){return "";}
 }
 boolean configured(boolean master){
  try{String encoded=prefs.getString(slot(master),"");if(encoded.isEmpty()||encoded.length()>512)return false;byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);int iv=bytes[0]&255;return iv>=8&&iv<=32&&bytes.length>=1+iv+16;}catch(RuntimeException ignored){return false;}
 }
 boolean ready(boolean master){return !load(master).isEmpty();}
 void clear(){prefs.edit().remove("master_cipher").remove("reader_cipher").apply();}
}