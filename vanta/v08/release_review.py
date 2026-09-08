from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
j=root/'app/src/main/java/com/ronin/vanta'
def edit(path,old,new):
 s=path.read_text();assert s.count(old)==1,(path.name,s.count(old),old[:90]);path.write_text(s.replace(old,new))
# Policy opt-in must not contaminate the all-catalogue discovery entry point.
edit(j/'ModelPicker.java','    uncensored.setChecked(prefs.getBoolean("only_unc_" + mode, false));','    uncensored.setChecked(!category.equals("all") && prefs.getBoolean("only_unc_" + mode, false));')
edit(j/'ModelPicker.java','          prefs.edit().putBoolean("only_unc_" + mode, on).apply();','          if (!category.equals("all")) prefs.edit().putBoolean("only_unc_" + mode, on).apply();')
# Credential presence is cheap SharedPreferences metadata; do not decrypt all keys on each picker open.
# Refresh the view to include credentials changed by another application component, not only this Activity.
edit(j/'MainActivity.java','  private Set<String> connectedProviders() {\n    return new HashSet<>(connectedCache);\n  }','''  private Set<String> connectedProviders() {
    Set<String> available=new HashSet<>();
    for(ProviderConfig p:providers)if(vault.containsSecret(p.id))available.add(p.id);
    return available;
  }''')
# Model-count tests count records, not the scrollable filter header and helper footer.
t=root/'app/src/androidTest/java/com/ronin/vanta/ExperienceDeviceTest.java'
edit(t,'count.set(list.getAdapter().getCount());','count.set(list.getAdapter().getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());')
# Latest product contract makes low-refusal an explicit route, independent of Auto coding quality.
t=root/'app/src/test/java/com/ronin/vanta/MasterRebuildTest.java'
edit(t,'''        PromptStrategy.recommendForge(
            Arrays.asList(
                new VantaRouter.Candidate(provider("openai"), standard, 0),
                new VantaRouter.Candidate(provider("featherless"), shadow, 0)),
            "Android app that compiles and passes tests");''','''        ForgeRouting.select(
            Arrays.asList(
                new VantaRouter.Candidate(provider("openai"), standard, 0),
                new VantaRouter.Candidate(provider("featherless"), shadow, 0)),
            "Android app that compiles and passes tests", "Low-refusal", "");''')
# Repair an inherited literal NUL inside the equivalent Java escaped character, without changing the test.
for p in (root/'app/src').rglob('*.java'):
 raw=p.read_bytes()
 if b'\0' in raw:p.write_bytes(raw.replace(b'\0',b'\\0'))
print('Reviewed explicit routing contract, catalogue filter isolation, credential-view refresh and scroll-header-aware record assertions.')
