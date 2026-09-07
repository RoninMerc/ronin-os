package com.ronin.vanta;

import org.json.JSONException;
import org.json.JSONObject;

public class ProviderConfig {
    public static final String VENICE = "venice";
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String CUSTOM = "custom";

    public String id;
    public String name;
    public String kind;
    public String baseUrl;

    public ProviderConfig(String id, String name, String kind, String baseUrl) {
        this.id = id; this.name = name; this.kind = kind; this.baseUrl = trim(baseUrl);
    }

    private static String trim(String u) {
        if (u == null) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("kind", kind); o.put("baseUrl", baseUrl);
        return o;
    }

    public static ProviderConfig fromJson(JSONObject o) throws JSONException {
        return new ProviderConfig(o.getString("id"), o.getString("name"), o.getString("kind"), o.getString("baseUrl"));
    }
}
