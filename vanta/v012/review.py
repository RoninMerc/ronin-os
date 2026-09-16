from pathlib import Path
import os,re

root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))

# Retained 0.11 upgrade probe must validate the installed 0.12 package.
p=root/'app/src/androidTest/java/com/ronin/vanta/UnifiedUpgradeProbe.java'
s=p.read_text()
old='''    assertEquals(
        110, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
new='''    assertEquals(
        120, c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());'''
assert old in s,(p,'expected the retained 0.11 probe version assertion')
p.write_text(s.replace(old,new,1))

# 0.12 intentionally falls back to a complete source ZIP when the Android compiler connection
# is missing or cannot authenticate. Verify that decision happens BEFORE the first paid model call,
# rather than retaining the obsolete 0.11 expectation that Forge must stop before source creation.
p=root/'app/src/androidTest/java/com/ronin/vanta/PipelineDeviceTest.java'
s=p.read_text()
pat=r'''  @Test\n  public void forgeChecksMissingBuildConnectionBeforeSpendingAnyModelCall\(\) throws Exception \{.*?\n  \}\n\n(?=  @Test\n  public void workerAuthenticationErrorDoesNotBecomeAnAiModelError)'''
replacement='''  @Test
  public void missingBuildConnectionSelectsSourceZipBeforeFirstModelCall() throws Exception {
    ProviderConfig p = h.seed("venice");
    JSONObject in =
        h.input(p)
            .put("auto_build", true)
            .put("platform", "Android")
            .put("source_protocol", "files-v1");
    VantaJob j = job("forge_source", in);
    e.vault.deleteSecret("github_forge_pat");
    AtomicInteger ai = new AtomicInteger();
    e.overrideChat(
        j.id(),
        (a, b, d, f, g, k, l, m, n, o) -> {
          ai.incrementAndGet();
          assertFalse(in.getBoolean("auto_build"));
          assertEquals("zip", in.getString("delivery"));
          assertTrue(in.getString("build_fallback_reason").contains("not connected"));
          throw new IOException("stop after verified build preflight fallback");
        });
    try {
      JobOperations.run(e, j, in, new Net.Call());
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("verified build preflight fallback"));
    }
    assertEquals(1, ai.get());
    assertNull(e.store.document(j.id(), "author_plan"));
  }

'''
s,n=re.subn(pat,lambda _m: replacement,s,count=1,flags=re.S)
assert n==1,(p,'missing-build test replacement',n)
pat=r'''  @Test\n  public void workerAuthenticationErrorDoesNotBecomeAnAiModelError\(\) throws Exception \{.*?\n  \}\n\n(?=  byte\[\] image\()'''
replacement='''  @Test
  public void workerAuthenticationFailureSelectsZipBeforeFirstModelCall() throws Exception {
    ProviderConfig p = h.seed("venice");
    JSONObject in = h.input(p).put("auto_build", true).put("platform", "Android");
    VantaJob j = job("forge_source", in);
    Net.Call c = new Net.Call();
    c.transport =
        new Transport() {
          public Net.Response request(
              String method,
              String url,
              Map<String, String> headers,
              byte[] body,
              String ct,
              int limit,
              Net.Call call)
              throws Exception {
            throw new Net.HttpError(403, "denied");
          }
        };
    AtomicInteger ai = new AtomicInteger();
    e.overrideChat(
        j.id(),
        (a, b, d, f, g, k, l, m, n, o) -> {
          ai.incrementAndGet();
          assertFalse(in.getBoolean("auto_build"));
          assertEquals("zip", in.getString("delivery"));
          assertTrue(in.getString("build_fallback_reason").contains("HTTP 403"));
          throw new IOException("stop after verified authentication fallback");
        });
    try {
      JobOperations.run(e, j, in, c);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("verified authentication fallback"));
    }
    assertEquals(1, ai.get());
    assertNull(e.store.document(j.id(), "author_plan"));
  }

'''
s,n=re.subn(pat,lambda _m: replacement,s,count=1,flags=re.S)
assert n==1,(p,'worker-auth test replacement',n)
p.write_text(s)

# UI recreation of the low-refusal control is already covered independently. This continuity test
# verifies the stronger persistence boundary: close the active UI, reopen encrypted storage through
# a fresh vault/store instance, and prove the routing preference and complete conversation survived.
p=root/'app/src/androidTest/java/com/ronin/vanta/ProjectContinuityDeviceTest.java'
s=p.read_text()
pat=r'''  @Test\n  public void normalThenLowRefusalKeepsConversationAndPreferenceAcrossRestart\(\) throws Exception \{.*?\n  \}\n\n(?=  @Test\n  public void automaticTechnicalHandoverPreservesHistoryAndUsesOnlyApprovedProvider)'''
replacement='''  @Test
  public void normalThenLowRefusalKeepsConversationAndPreferenceAcrossRestart() throws Exception {
    List<String> picked = new ArrayList<>();
    MainActivity.ChatGateway gateway =
        (p, k, m, msg, system, max, np, web, c, stream) -> {
          picked.add(m);
          if (picked.size() > 1) assertTrue(msg.toString().contains("First code question"));
          return "```java\\nint value=" + picked.size() + ";\\n```";
        };
    try (ActivityScenario<MainActivity> a = ActivityScenario.launch(MainActivity.class)) {
      compose(a, "First code question: write code to format a report", gateway);
      confirm(a);
      VantaJob first = last("chat");
      assertEquals("COMPLETED", h.h.waitDone(first.id(), 15000).status());
      h.h.awaitText(a, "int value=1");
      compose(a, "Write code to add date formatting", gateway);
      a.onActivity(
          host -> {
            try {
              ((CheckBox) TaskChoiceDeviceTest.view(host, "task-low-refusal")).setChecked(true);
            } catch (Exception x) {
              throw new AssertionError(x);
            }
          });
      confirm(a);
      long until = SystemClock.elapsedRealtime() + 15000;
      VantaJob second;
      do {
        second = last("chat");
        if (!second.id().equals(first.id())) break;
        Thread.sleep(60);
      } while (SystemClock.elapsedRealtime() < until);
      assertNotEquals(first.id(), second.id());
      assertEquals("COMPLETED", h.h.waitDone(second.id(), 15000).status());
      h.h.awaitText(a, "int value=2");
      assertEquals(Arrays.asList("qa-normal", "qa-low"), picked);
      assertTrue(ProjectConversation.low(threads.get(h.threadId)));
      assertEquals(4, threads.get(h.threadId).getJSONArray("messages").length());
    }
    SecureVault reopenedVault = new SecureVault(h.h.ctx());
    ThreadStore reopenedStore = new ThreadStore(reopenedVault);
    JSONObject persisted = reopenedStore.get(h.threadId);
    assertNotNull(persisted);
    assertTrue(ProjectConversation.low(persisted));
    assertEquals("qa-low", persisted.getString("model"));
    assertEquals(4, persisted.getJSONArray("messages").length());
    assertTrue(persisted.getJSONArray("messages").getJSONObject(0).getString("content").contains("First code question"));
  }

'''
s,n=re.subn(pat,lambda _m: replacement,s,count=1,flags=re.S)
assert n==1,(p,'durable-store continuity test replacement',n)
p.write_text(s)

print('Reviewed Vanta 0.12: ZIP fallback is verified before paid source generation; normal/low-refusal conversation continuity is verified through a fresh encrypted-store reload.')
