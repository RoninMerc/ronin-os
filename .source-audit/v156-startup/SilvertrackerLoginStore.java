package au.com.roningroup.patrollink;

import android.content.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Keystore-backed storage for an explicitly opted-in Silvertracker login. */
public final class SilvertrackerLoginStore {
    private static final String ALIAS="patrol_link_silvertracker_login_v1";
    private static final String CIPHER="silvertracker_login_cipher_v1";
    private static final String REMEMBER="remember_silvertracker_login";
    private final SharedPreferences prefs;
    public SilvertrackerLoginStore(Context c,SharedPreferences prefs){this.prefs=prefs;}
    public boolean remember(){return prefs.getBoolean(REMEMBER,false);}
    public void setRemember(boolean value){prefs.edit().putBoolean(REMEMBER,value).apply();if(!value)clearSecret();}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return kg.generateKey();
    }
    public synchronized void save(String user,String pass){
        if(!remember()||user==null||pass==null||user.trim().isEmpty()||pass.isEmpty())return;
        try{
            JSONObject o=new JSONObject().put("u",user.trim()).put("p",pass);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
            byte[] data=c.doFinal(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),iv=c.getIV();
            byte[] all=new byte[1+iv.length+data.length];all[0]=(byte)iv.length;
            System.arraycopy(iv,0,all,1,iv.length);System.arraycopy(data,0,all,1+iv.length,data.length);
            prefs.edit().putString(CIPHER,Base64.encodeToString(all,Base64.NO_WRAP)).apply();
        }catch(Exception ignored){}
    }
    public synchronized String[] get(){
        try{
            if(!remember())return null;String s=prefs.getString(CIPHER,"");if(s.isEmpty())return null;
            byte[] all=Base64.decode(s,Base64.NO_WRAP);int n=all[0]&255;if(n<8||n>32||all.length<=1+n)return null;
            byte[] iv=new byte[n],data=new byte[all.length-1-n];System.arraycopy(all,1,iv,0,n);System.arraycopy(all,1+n,data,0,data.length);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            JSONObject o=new JSONObject(new String(c.doFinal(data),java.nio.charset.StandardCharsets.UTF_8));
            String u=o.optString("u"),p=o.optString("p");return u.isEmpty()||p.isEmpty()?null:new String[]{u,p};
        }catch(Exception e){return null;}
    }
    public boolean has(){return get()!=null;}
    public synchronized void clear(){prefs.edit().putBoolean(REMEMBER,false).remove(CIPHER).apply();deleteKey();}
    private void clearSecret(){prefs.edit().remove(CIPHER).apply();deleteKey();}
    private void deleteKey(){try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(ALIAS))ks.deleteEntry(ALIAS);}catch(Exception ignored){}}
}
