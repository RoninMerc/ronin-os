package com.ronin.vanta;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureVault {
    private static final String KEY_ALIAS = "ronin_vanta_master";
    private static final String PREF = "vanta_vault";
    private final SharedPreferences prefs;

    public SecureVault(Context c) { prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return kg.generateKey();
    }

    public void putSecret(String alias, String secret) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = c.getIV();
        byte[] enc = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString(alias + ".iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(alias + ".ct", Base64.encodeToString(enc, Base64.NO_WRAP)).apply();
    }

    public String getSecret(String alias) {
        try {
            String ivs = prefs.getString(alias + ".iv", null);
            String cts = prefs.getString(alias + ".ct", null);
            if (ivs == null || cts == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(ivs, Base64.NO_WRAP)));
            byte[] out = c.doFinal(Base64.decode(cts, Base64.NO_WRAP));
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    public void deleteSecret(String alias) {
        prefs.edit().remove(alias + ".iv").remove(alias + ".ct").apply();
    }
}
