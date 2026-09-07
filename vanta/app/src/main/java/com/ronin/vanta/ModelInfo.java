package com.ronin.vanta;

import org.json.JSONException;
import org.json.JSONObject;

public class ModelInfo {
    public String id;
    public String name;
    public String type;
    public String description;
    public boolean enabled = true;
    public boolean manuallyUncensored = false;
    public boolean heuristicUncensored = false;

    public ModelInfo(String id, String name, String type, String description) {
        this.id = id;
        this.name = name == null || name.isEmpty() ? id : name;
        this.type = type == null || type.isEmpty() ? "text" : type;
        this.description = description == null ? "" : description;
        this.heuristicUncensored = detectUncensored(id + " " + this.name + " " + this.description);
    }

    public boolean isUncensored() { return manuallyUncensored || heuristicUncensored; }

    private static boolean detectUncensored(String s) {
        String x = s.toLowerCase();
        return x.contains("uncensored") || x.contains("unfiltered") || x.contains("abliterated") ||
                x.contains("no-censor") || x.contains("no censor") || x.contains("role play uncensored");
    }

    public String displayName() {
        return (isUncensored() ? "[UNCENSORED] " : "") + name + "  ·  " + type;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("type", type); o.put("description", description);
        o.put("enabled", enabled); o.put("manualUncensored", manuallyUncensored);
        return o;
    }

    public static ModelInfo fromJson(JSONObject o) throws JSONException {
        ModelInfo m = new ModelInfo(o.getString("id"), o.optString("name"), o.optString("type", "text"), o.optString("description"));
        m.enabled = o.optBoolean("enabled", true);
        m.manuallyUncensored = o.optBoolean("manualUncensored", false);
        return m;
    }
}
