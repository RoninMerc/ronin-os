package au.com.roningroup.patrollink;

import android.content.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Android Keystore-backed secret store for the voice API key. */
public final class SecretStore {
    private static final String KEY_ALIAS="patrol_link_voice_api_v1";
    private static final String PREF_KEY="voice_api_key_cipher_v1";
    private final Context context;
    private final SharedPreferences prefs;

    public SecretStore(Context context, SharedPreferences prefs) {
        this.context=context.getApplicationContext();
        this.prefs=prefs;
    }

    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(KEY_ALIAS)) return (SecretKey)ks.getKey(KEY_ALIAS,null);
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    public void put(String value) throws Exception {
        if(value==null||value.trim().isEmpty()) { clear(); return; }
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,key());
        byte[] data=c.doFinal(value.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv=c.getIV();
        byte[] merged=new byte[1+iv.length+data.length];
        merged[0]=(byte)iv.length;
        System.arraycopy(iv,0,merged,1,iv.length);
        System.arraycopy(data,0,merged,1+iv.length,data.length);
        prefs.edit().putString(PREF_KEY,Base64.encodeToString(merged,Base64.NO_WRAP)).apply();
    }

    public String get() {
        try {
            String stored=prefs.getString(PREF_KEY,null);
            if(stored==null||stored.isEmpty()) return "";
            byte[] merged=Base64.decode(stored,Base64.NO_WRAP);
            int ivLen=merged[0]&0xff;
            if(ivLen<8||ivLen>32||merged.length<=1+ivLen) return "";
            byte[] iv=new byte[ivLen];
            byte[] data=new byte[merged.length-1-ivLen];
            System.arraycopy(merged,1,iv,0,ivLen);
            System.arraycopy(merged,1+ivLen,data,0,data.length);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new String(c.doFinal(data),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e) { return ""; }
    }

    public boolean has() { return !get().isEmpty(); }

    public void clear() {
        prefs.edit().remove(PREF_KEY).apply();
        try {
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if(ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch(Exception ignored) {}
    }
}
