package au.com.roningroup.patrollink;
/** Fault injection is inert in the signed operational APK. */
final class StartupHooks {
 static volatile boolean failBrowser,failMap,failEngine;
 static void check(String phase){
  if(BuildConfig.DEBUG&&(("browser".equals(phase)&&failBrowser)||("map".equals(phase)&&failMap)||("engine".equals(phase)&&failEngine)))throw new IllegalStateException("Synthetic startup fault");
 }
 private StartupHooks(){}
}
