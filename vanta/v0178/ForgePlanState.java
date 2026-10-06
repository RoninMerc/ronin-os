package com.ronin.vanta;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Pure migration policy. The caller commits archives, scope and queue state in one transaction. */
final class ForgePlanState {
  static final String REVISION = "178-direct-planning-v1";
  interface Reader { JSONObject get(String name) throws Exception; }
  static final class Change {
    final Map<String,JSONObject> writes = new LinkedHashMap<>();
    final List<String> deletes = new ArrayList<>();
    int archived;
    boolean changed() { return !writes.isEmpty(); }
  }
  private ForgePlanState() {}

  static String prefix(JSONObject job, JSONObject build) {
    if (build != null && "repair".equals(build.optString("phase")))
      return "repair_files_" + build.optInt("attempt", 1);
    String saved = job.optString("source_prefix");
    if (saved.matches("(?:author|repair_files_[1-6]|validation_files_[1-6]_[0-2](?:_handover_[0-9]+)?)")) return saved;
    String phase = job.optString("phase");
    Matcher match = Pattern.compile("^(.+)_plan_(?:response|correction)(?:_compact_[12])?$").matcher(phase);
    return match.matches() ? match.group(1) : "author";
  }

  static JSONObject selection(JSONObject input, JSONObject build) throws Exception {
    if (input == null) throw new IOException("Saved task input is missing. No checkpoint was changed.");
    for (JSONObject candidate : new JSONObject[]{input, input.optJSONObject("target")}) {
      if (candidate == null || candidate.optJSONObject("provider") == null || candidate.optJSONObject("model") == null) continue;
      if (build == null || build.optString("model").isEmpty()
          || build.optString("provider").equals(candidate.getJSONObject("provider").optString("id"))
          && build.optString("model").equals(candidate.getJSONObject("model").optString("id"))) return candidate;
    }
    throw new IOException("The saved build and repair-model selections do not match. No source was changed.");
  }

  static JSONObject scope(JSONObject selected) throws Exception {
    ProviderConfig provider = ProviderConfig.fromJson(selected.getJSONObject("provider"));
    return new JSONObject().put("revision", REVISION).put("provider", provider.id)
        .put("kind", provider.kind).put("endpoint", provider.baseUrl)
        .put("model", selected.getJSONObject("model").getString("id"));
  }
  static boolean same(JSONObject old, JSONObject fresh) {
    if (old == null) return false;
    for (String key : new String[]{"revision","provider","kind","endpoint","model"})
      if (!old.optString(key).equals(fresh.optString(key))) return false;
    return true;
  }

  static Change prepare(Reader docs, String prefix, JSONObject selected, boolean explicitlyRenew) throws Exception {
    if (!prefix.matches("[A-Za-z0-9_]{1,120}")) throw new IOException("Invalid internal source checkpoint prefix.");
    Change change = new Change(); String scopeKey = prefix + "_planning_scope";
    JSONObject fresh = scope(selected), old = docs.get(scopeKey);
    if (!explicitlyRenew && same(old, fresh)) return change;
    Set<String> clear = new LinkedHashSet<>();
    JSONObject plan = docs.get(prefix + "_plan"), ready = docs.get(prefix + "_ready");
    if (ready == null && plan == null)
      clear.addAll(ForgeOutputRecovery.records(prefix + "_plan_response", prefix + "_plan_correction"));
    if (ready == null && plan != null) {
      JSONArray files = plan.optJSONArray("files");
      if (files != null) for (int i=0;i<files.length();i++) {
        String record = prefix + "_file_" + i;
        if (docs.get(record) != null) continue;
        for (int part=0;part<4;part++) {
          // A stored continuation chunk is validated progress, even when the file is unfinished.
          if (docs.get(record + "_chunk_" + part) != null) continue;
          String phase = record + "_part_" + part;
          clear.add(phase); clear.add(phase + "_output_limit");
          clear.add(phase + "_expanded_1"); clear.add(phase + "_expanded_1_output_limit");
        }
      }
    }
    String archive = prefix + "_planning_archive_" + UUID.randomUUID().toString();
    JSONArray index = new JSONArray();
    for (String name : clear) {
      JSONObject value = docs.get(name);
      if (value == null) continue;
      String key = archive + "_" + change.archived++;
      // One archive document per response keeps the existing document-size boundary unchanged.
      change.writes.put(key, new JSONObject().put("original", name).put("value", new JSONObject(value.toString())));
      index.put(new JSONObject().put("original", name).put("archive", key)); change.deletes.add(name);
    }
    fresh.put("epoch", old == null ? 1 : old.optInt("epoch", 0)+1);
    fresh.put("updated_at", System.currentTimeMillis());
    fresh.put("reason", explicitlyRenew ? "explicit_model_choice" : old == null ? "version_migration" : "selection_or_policy_changed");
    if (change.archived > 0) change.writes.put(archive, new JSONObject().put("index", index)
        .put("old_scope", old == null ? JSONObject.NULL : old).put("new_scope", fresh));
    change.writes.put(scopeKey, fresh);
    return change;
  }
}
