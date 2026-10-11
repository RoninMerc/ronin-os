package com.ronin.vanta;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

public class Agentic180RegressionTest {
  static JSONObject project() throws Exception {
    JSONObject p = Pipeline090Test.project();
    JSONArray f = p.getJSONArray("files");
    f.put(Pipeline090Test.file("app/src/main/java/com/ronin/forge/verification/B.java",
        "package com.ronin.forge.verification; public class B {}"));
    f.put(Pipeline090Test.file("app/src/main/java/com/ronin/forge/verification/C.java",
        "package com.ronin.forge.verification; public class C {}"));
    f.put(Pipeline090Test.file("app/src/main/java/com/ronin/forge/verification/D.java",
        "package com.ronin.forge.verification; public class D {}"));
    f.put(Pipeline090Test.file("app/src/test/java/com/ronin/forge/verification/MainActivityTest.java",
        "package com.ronin.forge.verification; import org.junit.Test; public class MainActivityTest { @Test public void works(){} }"));
    return p;
  }

  static String log() {
    return "e: file:///work/project/app/src/main/java/com/ronin/forge/verification/B.java:8:1 Unresolved reference: X\n"
        + "e: file:///work/project/app/src/main/java/com/ronin/forge/verification/C.java:2:1 Redeclaration: C\n"
        + "e: file:///work/project/app/src/main/java/com/ronin/forge/verification/D.java:3:1 Unresolved reference: Y\n"
        + "e: file:///work/project/app/src/main/java/com/ronin/forge/verification/MainActivity.java:4:1 Expecting a top level declaration\n";
  }

  @Test public void compilerTargetsPrioritiseRootSyntaxAndLimitBatch() throws Exception {
    List<String> targets = AgenticRepair.targets(project(), log());
    assertEquals("app/src/main/java/com/ronin/forge/verification/MainActivity.java", targets.get(0));
    assertTrue(targets.contains("app/src/main/java/com/ronin/forge/verification/C.java"));
    assertTrue(targets.size() >= 4);
    assertEquals(3, AgenticRepair.MAX_FILES_PER_PASS);
  }

  @Test public void testFilesAreNeverDirectRepairTargets() throws Exception {
    String test = "e: file:///work/project/app/src/test/java/com/ronin/forge/verification/MainActivityTest.java:2:1 Unresolved reference: MainActivity\n";
    List<String> targets = AgenticRepair.targets(project(), test);
    assertFalse(targets.contains("app/src/test/java/com/ronin/forge/verification/MainActivityTest.java"));
  }

  @Test public void repairUsesNoPlanningInferenceAndTouchesAtMostThreeFiles() throws Exception {
    JSONObject source = project();
    Pipeline090Test.Docs docs = new Pipeline090Test.Docs();
    AtomicInteger writes = new AtomicInteger();
    List<String> phases = new ArrayList<>();
    JSONObject repaired = AgenticRepair.author(
        "repair_files_2", "android", "Keep the app working", source, log(), 90000,
        (phase, system, user, tokens) -> {
          phases.add(phase);
          assertFalse("Repair must not buy a planning call", phase.contains("plan"));
          writes.incrementAndGet();
          String marker = phase.contains("agent_file_0") ? "A" : phase.contains("agent_file_1") ? "B" : "C";
          String path = phase.contains("agent_file_0")
              ? "app/src/main/java/com/ronin/forge/verification/MainActivity.java"
              : phase.contains("agent_file_1")
                  ? "app/src/main/java/com/ronin/forge/verification/C.java"
                  : "app/src/main/java/com/ronin/forge/verification/B.java";
          JSONObject old = AgenticRepair.file(source, path);
          return old.getString("content") + "\n// repair-" + marker + "\n";
        },
        docs,
        (path, done, total) -> {});
    assertEquals(3, writes.get());
    assertTrue(phases.stream().noneMatch(x -> x.contains("plan")));
    assertNotEquals(ProjectArchive.fingerprint(source), ProjectArchive.fingerprint(repaired));
    String testPath = "app/src/test/java/com/ronin/forge/verification/MainActivityTest.java";
    assertEquals(AgenticRepair.file(source, testPath).getString("content"),
        AgenticRepair.file(repaired, testPath).getString("content"));
  }

  @Test public void completedAgenticRepairDoesNotPurchaseAnotherCall() throws Exception {
    JSONObject source = project();
    Pipeline090Test.Docs docs = new Pipeline090Test.Docs();
    JSONObject ready = new JSONObject(source.toString());
    docs.put("repair_files_2_agent_ready", ready);
    JSONObject returned = AgenticRepair.author(
        "repair_files_2", "android", "x", source, log(), 90000,
        (a,b,c,d) -> { fail("No repeated request"); return ""; }, docs, (a,b,c)->{});
    assertEquals(ProjectArchive.fingerprint(ready), ProjectArchive.fingerprint(returned));
  }

  @Test public void initialGenerationStillDelegatesToExistingScaffoldPath() throws Exception {
    JSONObject original = Pipeline090Test.project();
    Pipeline090Test.Docs docs = new Pipeline090Test.Docs();
    AtomicInteger calls = new AtomicInteger();
    JSONObject result = AgenticRepair.author(
        "author", "android", "Create app", null, null, 60000,
        (phase, system, user, tokens) -> {
          if (phase.contains("plan")) return Pipeline090Test.plan(original).toString();
          int i = calls.getAndIncrement();
          return original.getJSONArray("files").getJSONObject(i).getString("content");
        },
        docs, (a,b,c)->{});
    assertEquals(5, result.getJSONArray("files").length());
    assertTrue(docs.data.containsKey("author_plan"));
  }
}
