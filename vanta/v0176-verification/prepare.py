from pathlib import Path
p=Path('source/app/src/androidTest/java/com/ronin/vanta/Vanta175UpgradeDeviceTest.java')
s=p.read_text()
old='    // A project-owned colliding script must remain intact after upgrade, not be overwritten.'
new='''    // 0.17.5 seeds an explicit managed-ownership receipt; 0.17.1 did not.
    // This fixture intentionally represents a FOREIGN script. Remove its managed receipt
    // before replacing it, otherwise the test asks the app to restore an owned file and
    // simultaneously asserts that it must remain foreign. Never change production ownership.
    project.remove(ManagedBuildFiles.RECEIPTS);
    assertFalse(project.has(ManagedBuildFiles.RECEIPTS));
    // A project-owned colliding script must remain intact after upgrade, not be overwritten.'''
assert s.count(old)==1
s=s.replace(old,new)
old2='    ArtifactSigner.Result signed=ArtifactSigner.sign(c,fixture(),new Net.Call());'
new2='''    JSONObject retained=store.document(job.id(),"build").getJSONObject("project");
    assertFalse(retained.has(ManagedBuildFiles.RECEIPTS));
    assertEquals("ext.ownerValue = 'keep-this'\\n",AndroidBuildFoundation.files(retained).get("app/vanta-quality.gradle").getString("content"));
    ArtifactSigner.Result signed=ArtifactSigner.sign(c,fixture(),new Net.Call());'''
assert s.count(old2)==2
s=s.replace(old2,new2,1)
p.write_text(s,encoding='utf-8')
print('Corrected test seed only: foreign script no longer carries a contradictory Vanta-owned receipt. Production APK bytes remain unchanged.')
