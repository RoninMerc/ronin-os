package com.ronin.vanta;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import org.json.*;

public final class CataloguePaging {
  private CataloguePaging() {}
  public static String first(ProviderConfig p) {
    return p.baseUrl + "/models" + (p.kind.equals(ProviderConfig.FEATHERLESS)
        ? "?status=active,pending_deploy,not_deployed"
        : p.kind.equals(ProviderConfig.VENICE) ? "?type=all"
        : p.kind.equals(ProviderConfig.ANTHROPIC) ? "?limit=1000" : "");
  }
  static JSONObject metadata(JSONObject root) {
    JSONObject meta = root.optJSONObject("pagination");
    if (meta == null) meta = root.optJSONObject("meta");
    return meta == null ? root : meta;
  }
  public static String next(ProviderConfig p, JSONObject root, JSONArray rows, int page) throws IOException {
    if (p.kind.equals(ProviderConfig.FEATHERLESS)) return next(p, root, rows.length(), page);
    if (!root.optBoolean("has_more")) return null;
    JSONObject last = rows.length() == 0 ? null : rows.optJSONObject(rows.length() - 1);
    String after = root.optString("last_id", last == null ? "" : last.optString("id", ""));
    if (after.isEmpty() || rows.length() == 0) throw new IOException("Model pagination cursor was missing.");
    return p.baseUrl + "/models?limit=1000&after_id=" + URLEncoder.encode(after, "UTF-8")
        + (p.kind.equals(ProviderConfig.VENICE) ? "&type=all" : "");
  }
  static String next(ProviderConfig p, JSONObject root, int count, int page) throws IOException {
    JSONObject meta = metadata(root);
    int current = meta.optInt("current_page", meta.optInt("page", page));
    int last = meta.optInt("last_page", meta.optInt("total_pages", 0));
    boolean more = meta.optBoolean("has_more", root.optBoolean("has_more", false)) || last > current;
    Object supplied = meta.opt("next_page_url");
    if (supplied == null || supplied == JSONObject.NULL) supplied = meta.opt("next_page");
    if (supplied == null || supplied == JSONObject.NULL) supplied = meta.opt("next");
    JSONObject links = root.optJSONObject("links");
    if ((supplied == null || supplied == JSONObject.NULL) && links != null) supplied = links.opt("next");
    String explicit = supplied == null || supplied == JSONObject.NULL ? "" : supplied.toString();
    if (!more && explicit.isEmpty()) return null; // Numeric totals alone are not continuation instructions.
    if (count == 0 || current != page || (last > 0 && last < current)) throw new IOException("Inconsistent catalogue pagination; previous cache retained.");
    if (!explicit.isEmpty() && !explicit.matches("[0-9]+")) return checked(p, explicit);
    int next = page + 1;
    if (!explicit.isEmpty()) {
      try { next = Integer.parseInt(explicit); } catch (NumberFormatException e) { throw new IOException("Invalid catalogue page.", e); }
      if (next != page + 1) throw new IOException("Catalogue skipped or repeated a page; previous cache retained.");
    }
    int size = meta.optInt("per_page", 1000);
    if (size < 1 || size > 1000) size = 1000;
    return first(p) + "&per_page=" + size + "&page=" + next;
  }
  private static String checked(ProviderConfig provider, String next) throws IOException {
    try {
      URI base = new URI(provider.baseUrl + "/models");
      URI target = next.startsWith("?") ? new URI(base.toString() + next) : base.resolve(next);
      if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null
          || target.getFragment() != null || !base.getHost().equalsIgnoreCase(target.getHost())
          || base.getPort() != target.getPort() || !base.getPath().equals(target.getPath()))
        throw new IOException("Unsafe catalogue continuation URL.");
      return target.toASCIIString();
    } catch (java.net.URISyntaxException | IllegalArgumentException | NullPointerException error) {
      throw new IOException("Invalid catalogue continuation URL.", error);
    }
  }
}
