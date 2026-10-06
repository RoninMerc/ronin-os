package com.ronin.vanta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;

/** No model switch and no compiler submission is made by a capacity-recovery decision. */
final class ForgeCapacityRecovery {
  static String scope(ProviderConfig p,ModelInfo m,String phase) {
    return p.id+"\n"+p.kind+"\n"+p.baseUrl+"\n"+m.id+"\n"+phase;
  }

  static void beforeRequest(JobEngine e,VantaJob j,ProviderConfig p,ModelInfo m,String phase,Net.Call call)
      throws Exception {
    if (!ForgeRecoveryController.forge(j)) return;
    ForgeRecoveryController.live(e,j,call);
    JSONObject ledger=e.store.document(j.id(),"capacity_recovery");
    JSONObject scopes=ledger==null ? null : ledger.optJSONObject("scopes");
    JSONObject entry=scopes==null ? null : scopes.optJSONObject(scope(p,m,phase));
    long due=entry==null ? 0 : entry.optLong("next_run");
    long remaining=due-System.currentTimeMillis();
    if (remaining>0) {
      // An explicit Resume must not skip a saved Retry-After deadline for this same request.
      j.json.put("request_started",false).put("capacity_retry_at",due).put("next_run",due);
      j.json.put("progress",ProgressState.transition(j.progress(),"WAITING FOR MODEL CAPACITY",
          "Retaining the saved capacity deadline for "+m.name+". No inference sent yet."));
      e.store.save(j);
      throw new JobEngine.Deferred(false,remaining,"Waiting for the saved same-model retry deadline. Completed files are retained.");
    }
    j.json.remove("capacity_retry_at");
  }

  static boolean recover(JobEngine e,VantaJob j,ProviderStreamFailure error,Net.Call call)
      throws Exception {
    if (!ForgeRecoveryController.forge(j) || !j.json.optBoolean("forge_inference")
        || !error.capacityBeforeOutput() || call.isCancelled() || Thread.currentThread().isInterrupted()) return false;
    JSONObject input=ForgeRecoveryController.input(e,j,null);
    if (!ForgeRecovery.enabled(input)) return false;
    ForgeRecoveryController.live(e,j,call);
    JSONObject policy=input.getJSONObject("recovery_policy"),state=ForgeRecoveryController.state(e,j);
    JSONObject selected=state.optJSONObject("active");
    if (selected==null) selected=input;
    ProviderConfig p=ProviderConfig.fromJson(selected.getJSONObject("provider"));
    ModelInfo m=ModelInfo.fromJson(selected.getJSONObject("model"));
    // This automatic policy is for Featherless's observed exact capacity code, not generic 200 errors.
    if (!ProviderConfig.FEATHERLESS.equals(p.kind) || !ForgeRecovery.approved(policy,p)) return false;
    long now=System.currentTimeMillis();
    CapacityBackoff.Decision decision=CapacityBackoff.plan(policy,state,
        e.store.document(j.id(),"capacity_recovery"),scope(p,m,j.json.optString("phase")),
        now,error.retryAfterMs,ThreadLocalRandom.current().nextLong(5001));
    JSONObject diagnostic=e.store.document(j.id(),"diagnostics");
    if (diagnostic==null) diagnostic=new JSONObject().put("connection",call.diagnostics());
    diagnostic.put("category","MODEL_CAPACITY").put("provider_stream_error",error.diagnostic())
        .put("same_model_retry",decision.count).put("same_model_retry_limit",CapacityBackoff.MAX_RETRIES)
        .put("automatic_retry_scheduled",decision.retry);
    Map<String,JSONObject> writes=new LinkedHashMap<>();
    writes.put("diagnostics",diagnostic);writes.put("capacity_recovery",decision.ledger);
    j.json.put("last_progress",j.progress()).put("request_started",false)
        .put("capacity_retry_count",decision.count);
    j.json.remove("transfer");j.json.remove("question");
    if (decision.retry) {
      state.put("actions",state.optInt("actions")+1);
      j.json.put("next_run",decision.due).put("capacity_retry_at",decision.due).remove("error");
      j.json.remove("action_kind");
      String message=m.name+" rejected this request for capacity before any generated output. Waiting at least "
          +((decision.delay+999)/1000)+" seconds before same-model retry "+decision.count+"/3. "
          +"Saved source is retained; no model change or compiler submission was made.";
      j.event("capacity_backoff","WAITING_PROVIDER",ProgressState.transition(j.progress(),"WAITING FOR MODEL CAPACITY",message));
      diagnostic.put("retry_not_before",decision.due);
    } else {
      String message=m.name+" remains unavailable for this request. "+decision.reason
          +" Saved files and compiler diagnostics are retained. Use Change model & resume to select an alternative, "
          +"or deliberately retry this model later. Automatic model-change limits were not increased.";
      j.json.put("action_kind","MODEL_CAPACITY").put("error",message);
      j.json.remove("next_run");j.json.remove("capacity_retry_at");
      j.event("capacity_paused","ACTION_REQUIRED",ProgressState.transition(j.progress(),"MODEL AT CAPACITY",message));
    }
    ForgeRecoveryController.summary(j,policy,state);writes.put("recovery",state);
    ForgeRecoveryController.live(e,j,call);
    e.store.recoveryCheckpoint(j,writes,Collections.emptyList());
    return true;
  }
}
