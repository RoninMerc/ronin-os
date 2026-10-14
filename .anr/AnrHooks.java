package au.com.roningroup.patrollink;
import android.os.*;
final class AnrHooks{
 static volatile long cryptoDelay,photoDelay;static volatile boolean cryptoMain,photoMain;
 static final java.util.concurrent.atomic.AtomicInteger mainCryptoCalls=new java.util.concurrent.atomic.AtomicInteger();
 static void crypto(){if(!BuildConfig.DEBUG)return;cryptoMain=Looper.myLooper()==Looper.getMainLooper();if(cryptoMain)mainCryptoCalls.incrementAndGet();delay(cryptoDelay);}
 static void photo(){if(!BuildConfig.DEBUG)return;photoMain=Looper.myLooper()==Looper.getMainLooper();delay(photoDelay);}
 private static void delay(long millis){if(millis>0)try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
 private AnrHooks(){}
}
