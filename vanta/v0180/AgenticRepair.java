package com.ronin.vanta;

import java.io.IOException;
import java.util.*;
import org.json.*;

/**
 * Compiler-led Forge repair controller.
 *
 * <p>Initial project creation still uses the existing scaffold path. Once a real project exists and
 * the compiler has produced source coordinates, repairs are deliberately small: no architecture
 * planning call, at most a few production files, then compile again. The compiler remains the
 * authority.
 */
public final class AgenticRepair {
  private AgenticRepair() {}

  static final int MAX_FILES_PER_PASS = 3;

  public static JSONObject author(
      String prefix,
      String platform,
      String specification,
      JSONObject existing,
      String diagnostics,
      int inputChars,
      ForgeAuthor.Writer writer,
      ForgeAuthor.Documents documents,
      ForgeAuthor.Progress progress)
      throws Exception {
    if (diagnostics == null || existing == null || !"android".equals(platform))
      return ForgeAuthor.author(
          prefix,
          platform,
          specification,
          existing,
          diagnostics,
          inputChars,
          writer,
          documents,
          progress);

    JSONObject ready = documents.get(prefix + "_agent_ready");
    if (ready != null) {
      SourceProject.validate(ready);
      ForgeRepairGuard.check(existing, ready);
      return ready;
    }

    List<String> targets = targets(existing, diagnostics);
    if (targets.isEmpty())
      throw new IOException(
          "AGENTIC_REPAIR_NO_TARGET: compiler diagnostics did not identify an editable production"
              + " source/configuration file. The source and full diagnostics are retained; no broad"
              + " rewrite was attempted.");

    JSONObject current = new JSONObject(existing.toString());
    int total = Math.min(MAX_FILES_PER_PASS, targets.size());
    for (int i = 0; i < total; i++) {
      String path = targets.get(i);
      if (ManagedBuildFiles.protectedPath(existing, path)) continue;
      String record = prefix + "_agent_file_" + i;
      progress.file(path, i, total);

      JSONObject file = documents.get(record);
      if (file != null && !path.equals(file.optString("path")))
        throw new IOException(
            "Saved agentic repair checkpoint does not match the compiler-selected target.");

      if (file == null) {
        JSONObject target = file(current, path);
        if (target == null)
          throw new IOException("Compiler-selected repair target disappeared: " + path);
        if (!"utf-8".equals(target.optString("encoding", "utf-8")))
          throw new IOException("Binary files are not editable repair targets: " + path);

        String compilerFacts =
            ForgeCodeDiagnostics.compact(
                diagnostics, path, Math.min(14000, Math.max(4000, inputChars / 5)));
        String inventory =
            ForgeCodeDiagnostics.inventory(
                current, Math.min(7000, Math.max(2000, inputChars / 10)));
        JSONArray needs = new JSONArray();
        for (String related : ForgeCodeDiagnostics.related(current, path)) needs.put(related);

        String framing =
            "USER REQUIREMENTS (reference only):\n"
                + ForgeAuthor.bounded(specification, Math.min(7000, inputChars / 8))
                + "\n\nCOMPILER FACTS FOR THIS REPAIR:\n"
                + compilerFacts
                + "\n\nEXISTING DECLARATIONS:\n"
                + inventory
                + "\n\nREPAIR EXACTLY THIS FILE: "
                + path
                + ForgeRepairContext.fileGuidance(path)
                + "\n\nSOURCE CONTEXT:\n";

        int contextBudget =
            inputChars - framing.length() - ForgeAuthor.ANDROID_CONTRACT.length() - 800;
        if (contextBudget < 1500)
          throw new IOException(
              "LOCAL_CONTEXT_BUDGET: the compiler-led repair framing leaves too little source"
                  + " context. Select a longer-context coding model; saved source is unchanged.");

        String sourceContext = ForgeAuthor.context(current, path, needs, contextBudget);
        String system =
            "You are operating one step of a compiler-driven coding agent. The compiler has"
                + " selected ONE production file for repair. Return the COMPLETE replacement"
                + " contents of exactly that file as raw source text only. Do not return JSON,"
                + " markdown fences, analysis, explanations or other files. Preserve requested"
                + " behaviour and public contracts unless the compiler proves they are invalid."
                + " Do not edit/delete/disable tests, do not weaken lint or quality gates, do not"
                + " replace working functionality with stubs, mocks or TODOs. Prefer the smallest"
                + " coherent correction that addresses the supplied compiler facts. "
                + ForgeAuthor.ANDROID_CONTRACT;

        String contents =
            ForgeAuthor.writeFile(record, system, framing + sourceContext, writer, documents);
        file = new JSONObject().put("path", path).put("content", contents);
        ProjectArchive.path(path);
        ProjectArchive.contents(file);
        documents.put(record, file);
      }

      JSONObject oneChange =
          new JSONObject()
              .put("name", existing.optString("name", "Forge project"))
              .put("platform", platform)
              .put("files", new JSONArray().put(file))
              .put("deleted_files", new JSONArray());

      JSONObject candidate = SourceProject.mergeExisting(current, oneChange);
      SourceProject.validate(candidate);
      ForgeRepairGuard.check(existing, candidate);
      current = candidate;
      progress.file(path, i + 1, total);
    }

    if (ProjectArchive.fingerprint(existing).equals(ProjectArchive.fingerprint(current)))
      throw new IOException(
          "AGENTIC_REPAIR_NO_CHANGE: compiler-led repair produced no source change. The same build"
              + " will not be resubmitted blindly.");

    SourceProject.validate(current);
    ForgeRepairGuard.check(existing, current);
    documents.put(prefix + "_agent_ready", current);
    return current;
  }

  static List<String> targets(JSONObject project, String diagnostics) throws Exception {
    Map<String, List<String>> facts = ForgeCodeDiagnostics.errors(diagnostics);
    Map<String, JSONObject> files = AndroidBuildFoundation.files(project);
    LinkedHashSet<String> candidates = new LinkedHashSet<>();

    List<String> ordered = new ArrayList<>(facts.keySet());
    ordered.sort(
        Comparator.comparingInt((String path) -> score(path, facts.get(path)))
            .thenComparingInt(path -> -facts.get(path).size())
            .thenComparing(path -> path));

    for (String path : ordered) {
      if (editableProduction(files, path)) candidates.add(path);
      else if (isTest(path)) {
        for (String related : ForgeCodeDiagnostics.related(project, path))
          if (editableProduction(files, related)) candidates.add(related);
        String counterpart = sourceCounterpart(path);
        if (editableProduction(files, counterpart)) candidates.add(counterpart);
      }
    }

    return new ArrayList<>(candidates);
  }

  static int score(String path, List<String> facts) {
    String joined = String.join("\n", facts).toLowerCase(Locale.ROOT);
    int score = 100;
    if (joined.contains("expecting a top level declaration")
        || joined.contains("expecting '")
        || joined.contains("syntax")
        || joined.contains("parse")) score -= 80;
    if (joined.contains("redeclaration")
        || joined.contains("conflicting overload")
        || joined.contains("platform declaration clash")) score -= 70;
    if (joined.contains("cannot be inherited")
        || joined.contains("annotation argument")
        || joined.contains("overrides nothing")) score -= 55;
    if (joined.contains("unresolved reference")
        || joined.contains("cannot find symbol")
        || joined.contains("not found")) score -= 30;
    if (path.endsWith("build.gradle")
        || path.endsWith("build.gradle.kts")
        || path.endsWith("AndroidManifest.xml")) score -= 20;
    return score;
  }

  static boolean editableProduction(Map<String, JSONObject> files, String path) {
    if (path == null || path.isEmpty() || isTest(path)) return false;
    JSONObject f = files.get(path);
    if (f == null || !"utf-8".equals(f.optString("encoding", "utf-8"))) return false;
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".kt")
        || lower.endsWith(".java")
        || lower.endsWith(".xml")
        || lower.endsWith(".gradle")
        || lower.endsWith(".gradle.kts")
        || lower.endsWith(".properties");
  }

  static boolean isTest(String path) {
    if (path == null) return false;
    return path.matches(".*?/src/(?:test[^/]*|androidTest[^/]*)/.*");
  }

  static String sourceCounterpart(String testPath) {
    if (testPath == null) return "";
    return testPath
        .replaceFirst("/src/test(?:Debug|Release)?/", "/src/main/")
        .replaceFirst("/src/androidTest(?:Debug|Release)?/", "/src/main/")
        .replaceFirst("(?:Test|Tests|TestCase)(\\.(?:java|kt))$", "$1");
  }

  static JSONObject file(JSONObject project, String path) throws Exception {
    JSONArray files = project.getJSONArray("files");
    for (int i = 0; i < files.length(); i++) {
      JSONObject f = files.getJSONObject(i);
      if (path.equals(f.optString("path"))) return f;
    }
    return null;
  }
}
