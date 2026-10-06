package com.ronin.vanta;

import org.json.*;

/** One normal-gated recompile per exact source, to obtain the worker's actionable fallback log. */
final class ForgeDiagnosticRefresh {
  private ForgeDiagnosticRefresh() {}
  static String key(JSONObject project, int attempt) throws Exception {
    return "compiler_diagnostic_" + attempt + "_" + ProjectArchive.fingerprint(project).substring(0,16);
  }
  static ForgeClient.BuildResult obtain(JobEngine e, VantaJob job, JSONObject build,
      JSONObject project, String token, Net.Call call) throws Exception {
    String fingerprint = ProjectArchive.fingerprint(project);
    int attempt = build.optInt("attempt",1);
    String record = key(project, attempt);
    JSONObject state = e.store.document(job.id(), record);
    if (state == null) state = new JSONObject().put("source_fingerprint", fingerprint)
        .put("id", "diag-" + build.getString("id") + "-" + fingerprint.substring(0,12))
        .put("attempt", attempt).put("build_type", build.optString("build_type","release"));
    if (!fingerprint.equals(state.optString("source_fingerprint")))
      throw new JobOperations.Blocked("Compiler diagnosis: saved request/source identities disagree. Nothing was submitted.");
    if (state.optBoolean("done")) {
      ForgeClient.BuildResult retained = new ForgeClient.BuildResult();
      retained.success = false; retained.runId = state.optLong("run_id"); retained.log = state.optString("log");
      return retained;
    }
    job.json.put("forge_inference", false).put("request_started", false);
    build.put("diagnostic_refresh_pending", true);
    e.store.checkpoint(job,"build",build);
    String owner=build.getString("owner"), repo=build.getString("repo"), branch=build.getString("branch");
    if (state.optString("sha").isEmpty()) {
      e.event(job,"compiler_diagnostic_submit","COLLECTING COMPILER DIAGNOSTICS",
          "The saved kapt error has no file/line details. Recompiling this exact source once with the worker's diagnostic fallback; no AI call or source rewrite.");
      // Persist identity before PUT. uploadRequest recovers an accepted upload with a lost response.
      e.store.document(job.id(),record,state);
      String sha=ForgeClient.uploadRequest(owner,repo,branch,token,state.getString("id"),project,
          attempt,state.getString("build_type"),call);
      state.put("sha",sha).put("submitted_at",System.currentTimeMillis());
      e.store.document(job.id(),record,state);
    }
    e.event(job,"compiler_diagnostic_poll","COLLECTING COMPILER DIAGNOSTICS",
        "Checking the same source-bound diagnostic recompile. Original source and completed repair files remain saved; no duplicate request is submitted.");
    final JSONObject polling=state;
    ForgeClient.BuildResult result=ForgeClient.pollBuild(owner,repo,branch,token,state,
        value->{try{job.json.put("technical_status",value);}catch(Exception ignored){}},call);
    e.store.document(job.id(),record,polling);
    if(result==null) throw new JobEngine.Deferred(false,15000,
        "The source-bound diagnostic recompile is queued or running. Vanta will check this same request again without using a coding-model call.");
    build.put("diagnostic_refresh_pending",false);
    e.store.checkpoint(job,"build",build);
    // A successful normal-gated artifact is re-downloadable from the saved run if signing is interrupted.
    // Failed results can be reused entirely locally; they never become a successful APK.
    if(!result.success){state.put("done",true).put("log",result.log).put("run_id",result.runId);e.store.document(job.id(),record,state);}
    return result;
  }
}
