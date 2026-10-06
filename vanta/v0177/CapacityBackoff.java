package com.ronin.vanta;

import org.json.JSONObject;

/** Pure policy. No requests, sleeping, model changes, counter resets or billing assumptions. */
final class CapacityBackoff {
  static final int MAX_RETRIES = 3;
  static final long MAX_WAIT_MS = 600000;
  static final class Decision {
    final boolean retry;
    final String reason;
    final long due, delay;
    final int count;
    final JSONObject ledger;
    Decision(boolean retry,String reason,long due,long delay,int count,JSONObject ledger) {
      this.retry=retry;this.reason=reason;this.due=due;this.delay=delay;this.count=count;this.ledger=ledger;
    }
  }
  static Decision plan(JSONObject policy,JSONObject recovery,JSONObject previous,String scope,
      long now,long retryAfterMs,long jitterMs) throws Exception {
    JSONObject ledger=previous==null ? new JSONObject() : new JSONObject(previous.toString());
    JSONObject records=ledger.optJSONObject("scopes");
    if (records==null) { records=new JSONObject();ledger.put("scopes",records); }
    JSONObject entry=records.optJSONObject(scope);
    if (entry==null) entry=new JSONObject().put("retries",0).put("wait_ms",0);
    int count=entry.optInt("retries");
    String reason=null;
    if (policy==null || !policy.optBoolean("enabled")) reason="Automatic recovery is not enabled for this task.";
    else if (recovery.optInt("calls")>=policy.optInt("max_calls",120)) reason="The approved AI-request limit is reached.";
    else if (recovery.optInt("actions")>=ForgeRecovery.MAX_ACTIONS) reason="The eight-action automatic recovery limit is reached.";
    else if (count>=MAX_RETRIES) reason="Three delayed retries for this model and request phase have been used.";
    long base=30000L*(1L<<Math.min(2,Math.max(0,count)));
    long delay=Math.max(Math.max(0,retryAfterMs),base+Math.max(0,Math.min(5000,jitterMs)));
    if (reason==null && (delay>MAX_WAIT_MS || entry.optLong("wait_ms")>MAX_WAIT_MS-delay))
      reason="The provider's retry delay exceeds the remaining ten-minute automatic-wait allowance.";
    if (reason!=null) return new Decision(false,reason,0,0,count,ledger);
    long due=now>Long.MAX_VALUE-delay ? Long.MAX_VALUE : now+delay;
    entry.put("retries",count+1).put("wait_ms",entry.optLong("wait_ms")+delay)
        .put("next_run",due).put("last_rejection_at",now);
    records.put(scope,entry);
    return new Decision(true,"",due,delay,count+1,ledger);
  }
}
