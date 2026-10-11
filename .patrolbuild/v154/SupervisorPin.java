package au.com.roningroup.patrollink;

import android.content.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local supervisor PIN verifier. Stores only a salted PBKDF2 verifier, never the PIN itself. */
public final class SupervisorPin {
    private static final String PREFS="patrol_settings", SALT="supervisor_pin_salt_v1", HASH="supervisor_pin_hash_v1";
    private static final String FAILS="supervisor_pin_fails_v1", LOCK="supervisor_pin_lock_until_v1";
    private SupervisorPin(){}

    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static boolean configured(Context c){return !p(c).getString(HASH,"").isEmpty();}
    public static boolean validFormat(String pin){return pin!=null&&pin.matches("\\d{4,8}");}

    public static boolean set(Context c,String pin){
        if(!validFormat(pin))return false;
        try{
            byte[] salt=new byte[20];new SecureRandom().nextBytes(salt);
            byte[] hash=derive(pin,salt);
            p(c).edit().putString(SALT,Base64.encodeToString(salt,Base64.NO_WRAP))
                .putString(HASH,Base64.encodeToString(hash,Base64.NO_WRAP))
                .putInt(FAILS,0).putLong(LOCK,0).apply();
            return true;
        }catch(Exception e){return false;}
    }

    public static long lockedSeconds(Context c){
        long remain=p(c).getLong(LOCK,0)-System.currentTimeMillis();
        return remain<=0?0:(remain+999)/1000;
    }

    public static boolean verify(Context c,String pin){
        SharedPreferences s=p(c);if(lockedSeconds(c)>0)return false;
        try{
            String a=s.getString(SALT,""),h=s.getString(HASH,"");
            if(a.isEmpty()||h.isEmpty())return false;
            byte[] salt=Base64.decode(a,Base64.NO_WRAP), expected=Base64.decode(h,Base64.NO_WRAP), got=derive(pin==null?"":pin,salt);
            boolean ok=MessageDigest.isEqual(expected,got);
            if(ok){s.edit().putInt(FAILS,0).putLong(LOCK,0).apply();return true;}
            int fails=s.getInt(FAILS,0)+1;SharedPreferences.Editor e=s.edit().putInt(FAILS,fails);
            if(fails>=5)e.putInt(FAILS,0).putLong(LOCK,System.currentTimeMillis()+30_000L);
            e.apply();return false;
        }catch(Exception e){return false;}
    }

    private static byte[] derive(String pin,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,120000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
}