package com.ronin.vanta;

import org.json.*;

/** Provider-specific request fields, scoped to this internal operation only. */
public final class ForgeRequestPolicy {
  private ForgeRequestPolicy() {}
  static boolean planning(String phase) {
    return phase != null && phase.matches(".+_plan_(?:response|correction)(?:_compact_[12])?");
  }
  static JSONObject options(ProviderConfig p, ModelInfo m, String phase) throws Exception {
    JSONObject out = new JSONObject();
    if (ProviderConfig.FEATHERLESS.equals(p.kind) && planning(phase))
      out.put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
    JSONObject caps = m.spec.optJSONObject("capabilities");
    if (ProviderConfig.VENICE.equals(p.kind)
        && (phase.contains("_compact_") || phase.contains("_expanded_"))
        && caps != null && Boolean.TRUE.equals(caps.opt("supportsReasoningEffort")))
      out.put("reasoning_effort", "low");
    return out;
  }
  static JSONObject diagnostics(ProviderConfig p, ModelInfo m, String phase) throws Exception {
    JSONObject out = new JSONObject().put("revision", ForgePlanState.REVISION)
        .put("structured_plan", planning(phase));
    if (ProviderConfig.FEATHERLESS.equals(p.kind) && planning(phase))
      out.put("thinking_requested", false).put("thinking_control",
          "Featherless chat_template_kwargs.enable_thinking; unsupported model templates may ignore it");
    if (planning(phase)) out.put("reasoning_only_character_limit", GenerationWatchdog.PLAN_REASONING_CHARS)
        .put("reasoning_only_time_limit_ms", GenerationWatchdog.PLAN_REASONING_MS);
    return out;
  }
  static void apply(JSONObject body, ProviderConfig p, Net.Call call) throws Exception {
    JSONObject opts = call.inferenceOptions;
    if (opts == null) return;
    if (ProviderConfig.FEATHERLESS.equals(p.kind)) {
      JSONObject template = opts.optJSONObject("chat_template_kwargs");
      if (template != null && Boolean.FALSE.equals(template.opt("enable_thinking")))
        body.put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
    }
    if (ProviderConfig.VENICE.equals(p.kind) && "low".equals(opts.optString("reasoning_effort")))
      body.put("reasoning_effort", "low");
  }
}
