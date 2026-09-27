from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); java=root/'app/src/main/java/au/com/roningroup/patrollink'; payload=Path(__file__).parent

def rep(s,old,new):
    if old not in s: raise RuntimeError('v125 patch anchor missing: '+old[:180])
    return s.replace(old,new,1)

p=root/'app/build.gradle';s=p.read_text();s=rep(s,'versionCode 124','versionCode 125');s=rep(s,"versionName '1.1.14'","versionName '1.1.15'");p.write_text(s)

p=java/'PatrolEngine.java';s=p.read_text()
s=rep(s,'private String lastRefreshFailure = "none";','private String lastRefreshFailure = "none";\n    private boolean welcomeSpokenThisSession;')
s=rep(s,'status("READ_OK", selected.size() + " recent activities read for the selected guards.");',
      'status("READ_OK", selected.size() + " recent activities read for the selected guards.");\n                if (running && prefs.getBoolean("voice", true) && !welcomeSpokenThisSession && voices.welcome()) welcomeSpokenThisSession = true;')
s=rep(s,'latest.clear(); present.clear(); recentById.clear(); speechLedger.clear(); lastRead = lastReadElapsed = 0;',
      'latest.clear(); present.clear(); recentById.clear(); speechLedger.clear(); welcomeSpokenThisSession = false; lastRead = lastReadElapsed = 0;')
p.write_text(s)

# Add coverage test.
shutil.copyfile(payload/'SiteVoiceCoverageTest.java',root/'app/src/androidTest/java/au/com/roningroup/patrollink/SiteVoiceCoverageTest.java')

assert 'versionCode 125' in (root/'app/build.gradle').read_text()
assert 'voices.welcome()' in (java/'PatrolEngine.java').read_text()
assert 'welcomeSpokenThisSession = false' in (java/'PatrolEngine.java').read_text()
