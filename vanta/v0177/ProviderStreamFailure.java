package com.ronin.vanta;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Structured provider errors are not HTTP success or permission to replay partial generation. */
public final class ProviderStreamFailure extends IOException {
  public final String code, providerType;
  public final boolean outputObserved;
  public final long retryAfterMs;

  ProviderStreamFailure(String code, String type, String message, boolean output, long retryAfterMs) {
    super("Provider stream rejected the request [" + safe(code) + "]: " + Errors.redact(message));
    this.code = code == null ? "" : code;
    this.providerType = type == null ? "" : type;
    this.outputObserved = output;
    this.retryAfterMs = retryAfterMs;
  }

  private static String safe(String value) {
    return value != null && value.matches("[A-Za-z0-9_.-]{1,80}") ? value : "unclassified";
  }
  private static String string(JSONObject o, String key) {
    Object value = o == null ? null : o.opt(key);
    return value instanceof String ? (String)value : "";
  }
  static ProviderStreamFailure decode(JSONObject event, Net.Call call, boolean textReceived) {
    JSONObject response = event.optJSONObject("response");
    JSONObject error = event.optJSONObject("error");
    if (error == null && response != null) error = response.optJSONObject("error");
    if (error == null) error = event;
    String message = string(error, "message");
    if (message.isEmpty()) message = "The provider returned a structured stream error.";
    long advertised = call.responseRetryAfterMs;
    Object raw = error.opt("retry_after");
    if (raw instanceof Number || raw instanceof String) {
      long parsed = Net.retryAfter(String.valueOf(raw), System.currentTimeMillis());
      if (parsed >= 0) advertised = Math.max(advertised, parsed);
    }
    return new ProviderStreamFailure(string(error,"code"), string(error,"type"), message,
        textReceived || call.generation.observedOutput() || producedOutput(event), advertised);
  }

  /** Only the exact known capacity code before ALL output qualifies; text matching is not enough. */
  boolean capacityBeforeOutput() {
    return "capacity_exhausted".equals(code)
        && (providerType.isEmpty() || "server_error".equals(providerType))
        && !outputObserved && !ForgeRecovery.refusal(getMessage());
  }

  static ProviderStreamFailure find(Throwable failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = failure; t != null && visited.add(t); t = t.getCause())
      if (t instanceof ProviderStreamFailure) return (ProviderStreamFailure)t;
    return null;
  }

  private static boolean nonempty(Object value) {
    if (value == null || value == JSONObject.NULL) return false;
    if (value instanceof String) return !((String)value).isEmpty();
    if (value instanceof JSONArray) return ((JSONArray)value).length() > 0;
    if (value instanceof JSONObject) return ((JSONObject)value).length() > 0;
    return false;
  }
  private static boolean payload(JSONObject o) {
    if (o == null) return false;
    for (String name : new String[]{"content","text","output_text","reasoning","reasoning_content",
        "thinking","refusal","tool_calls","function_call","arguments","partial_json","signature"})
      if (nonempty(o.opt(name))) return true;
    return false;
  }
  private static boolean generatedUsage(JSONObject usage) {
    if (usage == null) return false;
    if (usage.optLong("completion_tokens",0) > 0 || usage.optLong("output_tokens",0) > 0
        || usage.optLong("reasoning_tokens",0) > 0) return true;
    JSONObject details=usage.optJSONObject("completion_tokens_details");
    if (details == null) details=usage.optJSONObject("output_tokens_details");
    return details != null && details.optLong("reasoning_tokens",0) > 0;
  }

  /** A replay-safety observation, not a liveness signal and never a store of reasoning content. */
  static boolean producedOutput(JSONObject event) {
    if (payload(event) || generatedUsage(event.optJSONObject("usage"))) return true;
    Object rawDelta = event.opt("delta");
    if (rawDelta instanceof String && !((String)rawDelta).isEmpty()) return true;
    if (rawDelta instanceof JSONObject && payload((JSONObject)rawDelta)) return true;
    JSONObject block=event.optJSONObject("content_block");
    if (payload(block) || block != null && "tool_use".equals(block.optString("type"))) return true;
    JSONArray choices=event.optJSONArray("choices");
    if (choices != null) for (int i=0;i<choices.length();i++) {
      JSONObject choice=choices.optJSONObject(i);
      if (choice == null) continue;
      if (payload(choice.optJSONObject("delta")) || payload(choice.optJSONObject("message"))
          || payload(choice)) return true;
    }
    JSONObject response=event.optJSONObject("response");
    if (response != null && (payload(response) || nonempty(response.opt("output"))
        || generatedUsage(response.optJSONObject("usage")))) return true;
    return nonempty(event.opt("output"));
  }

  JSONObject diagnostic() throws Exception {
    return new JSONObject().put("code",safe(code)).put("type",safe(providerType))
        .put("output_observed",outputObserved).put("capacity_before_output",capacityBeforeOutput())
        .put("retry_after_ms",retryAfterMs);
  }
}
