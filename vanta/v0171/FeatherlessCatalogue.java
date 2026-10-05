package com.ronin.vanta;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Streaming public model discovery, isolated from every paid generation operation. */
public final class FeatherlessCatalogue {
  static final int MAX_BYTES = 96 * 1024 * 1024;
  static final int MAX_MODELS = 200000;
  private FeatherlessCatalogue() {}

  public static boolean publicEndpoint(ProviderConfig p) {
    return ProviderConfig.FEATHERLESS.equals(p.kind)
        && "https://api.featherless.ai/v1".equals(p.baseUrl);
  }

  static final class Page {
    final JSONObject metadata = new JSONObject();
    int rows;
  }

  public static List<ModelInfo> read(ProviderConfig p, String key, Net.Call call) throws Exception {
    Map<String, String> headers = publicEndpoint(p) ? Collections.emptyMap() : Net.headers(p, key);
    List<ModelInfo> models = new ArrayList<>();
    Set<String> ids = new HashSet<>(), visited = new HashSet<>();
    String url = CataloguePaging.first(p);
    for (int number = 1; number <= 1000; number++) {
      call.check();
      if (!visited.add(url)) throw new IOException("Repeated catalogue cursor; previous cache retained.");
      int before = models.size();
      Page page;
      if (call.transport != null) {
        Net.Response response = call.transport.request("GET", url, headers, null, "application/json", MAX_BYTES, call);
        page = parse(new ByteArrayInputStream(response.bytes), p, models, ids, call);
      } else {
        HttpURLConnection connection = Net.open("GET", url, headers, null, null, call);
        try {
          int status = call.response(connection);
          if (status < 200 || status >= 300) throw Net.error(connection, headers, call);
          call.expectedBytes = connection.getContentLengthLong();
          if (call.expectedBytes > MAX_BYTES) throw new IOException("Catalogue exceeds the mobile safety limit; previous cache retained.");
          page = parse(call.observe(connection.getInputStream()), p, models, ids, call);
        } finally { Net.close(connection, call); }
      }
      String next = CataloguePaging.next(p, page.metadata, page.rows, number);
      if (next == null) {
        if (models.isEmpty()) throw new IOException("The provider returned an empty catalogue; previous cache retained.");
        JSONObject meta = CataloguePaging.metadata(page.metadata);
        long total = meta.optLong("total_items", meta.optLong("total", -1));
        if (total >= 0 && total != models.size()) models.get(0).spec.put("catalogue_warning",
            "Saved all " + models.size() + " unique returned models. The provider reported " + total
                + "; its total differs from the response. No extra pages were invented.");
        call.check();
        return models;
      }
      if (models.size() == before) throw new IOException("Catalogue pagination repeated or stopped; previous cache retained.");
      // Explicit pages only, paced and cancellable. A 429 is persisted by the job engine.
      long until = System.nanoTime() + 2_000_000_000L;
      while (System.nanoTime() < until) { call.check(); Thread.sleep(100); }
      url = next;
    }
    throw new IOException("Catalogue exceeds the page safety limit; previous cache retained.");
  }

  static Page parse(InputStream input, ProviderConfig provider, List<ModelInfo> models,
      Set<String> ids, Net.Call call) throws Exception {
    Page page = new Page();
    InputStream bounded = new FilterInputStream(input) {
      long bytes;
      private void checked(int count) throws IOException {
        call.check();
        if (count > 0) bytes += count;
        if (bytes > MAX_BYTES) throw new IOException("Catalogue exceeds the mobile safety limit; previous cache retained.");
        if (call.transfer != null) call.transfer.bytes(bytes, call.expectedBytes);
      }
      public int read() throws IOException { int value = in.read(); checked(value < 0 ? 0 : 1); return value; }
      public int read(byte[] buffer, int offset, int length) throws IOException { int n = in.read(buffer, offset, length); checked(n); return n; }
    };
    try (JsonReader reader = new JsonReader(new InputStreamReader(bounded, StandardCharsets.UTF_8))) {
      reader.setStrictness(Strictness.STRICT);
      reader.beginObject();
      boolean found = false;
      Set<String> rootKeys = new HashSet<>();
      while (reader.hasNext()) {
        call.check();
        String field = reader.nextName();
        if (!rootKeys.add(field) || rootKeys.size() > 1000) throw new IOException("Duplicate or excessive catalogue fields.");
        if (field.equals("data") || field.equals("models")) {
          if (found || reader.peek() != JsonToken.BEGIN_ARRAY) throw new IOException("Invalid catalogue array; previous cache retained.");
          found = true;
          reader.beginArray();
          while (reader.hasNext()) {
            call.check();
            if (++page.rows > MAX_MODELS || models.size() >= MAX_MODELS) throw new IOException("Catalogue exceeds the record safety limit; previous cache retained.");
            Object record = value(reader, 0, new int[] {0});
            if (!(record instanceof JSONObject)) throw new IOException("Malformed catalogue record; previous cache retained.");
            ModelInfo model = ApiClient.parseModel(provider, (JSONObject) record);
            if (ids.add(model.id)) models.add(model);
          }
          reader.endArray();
        } else page.metadata.put(field, value(reader, 0, new int[] {0}));
      }
      reader.endObject();
      if (!found || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Incomplete or trailing catalogue data; previous cache retained.");
      return page;
    }
  }

  private static Object value(JsonReader reader, int depth, int[] budget) throws Exception {
    if (depth > 24 || ++budget[0] > 262144) throw new IOException("Oversized catalogue record.");
    switch (reader.peek()) {
      case BEGIN_OBJECT:
        JSONObject object = new JSONObject(); reader.beginObject();
        while (reader.hasNext()) {
          String name = reader.nextName(); budget[0] += name.length();
          if (object.has(name)) throw new IOException("Duplicate catalogue record field.");
          object.put(name, value(reader, depth + 1, budget));
        }
        reader.endObject(); return object;
      case BEGIN_ARRAY:
        JSONArray array = new JSONArray(); reader.beginArray();
        while (reader.hasNext()) array.put(value(reader, depth + 1, budget));
        reader.endArray(); return array;
      case STRING:
        String text = reader.nextString(); budget[0] += text.length();
        if (budget[0] > 262144) throw new IOException("Oversized catalogue record.");
        return text;
      case NUMBER:
        String number = reader.nextString();
        if (number.length() > 128) throw new IOException("Invalid catalogue number.");
        BigDecimal decimal = new BigDecimal(number);
        if (!Double.isFinite(decimal.doubleValue())) throw new IOException("Invalid catalogue number.");
        return decimal;
      case BOOLEAN: return reader.nextBoolean();
      case NULL: reader.nextNull(); return JSONObject.NULL;
      default: throw new IOException("Invalid catalogue JSON value.");
    }
  }

  static long retryDelay(Exception error, int failures) {
    if (error instanceof Net.HttpError) {
      Net.HttpError http = (Net.HttpError) error;
      String message = String.valueOf(http.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("insufficient_quota") || message.contains("insufficient credits")
          || message.contains("billing") || message.contains("quota exhausted")) return -1;
      if (!(http.status == 408 || http.status == 429 || http.status == 500 || http.status == 502
          || http.status == 503 || http.status == 504)) return -1;
    } else if (!(error instanceof SocketException || error instanceof UnknownHostException
        || error instanceof InterruptedIOException || error instanceof EOFException)) return -1;
    long delay = Math.min(900000L, 15000L * (1L << Math.min(6, Math.max(0, failures - 1))));
    return error instanceof Net.HttpError ? Math.max(delay, ((Net.HttpError) error).retryAfterMs) : delay;
  }

  static void before(JobEngine engine, VantaJob job) throws Exception {
    long remaining = job.json.optLong("catalogue_retry_at") - System.currentTimeMillis();
    if (remaining > 0) throw new JobEngine.Deferred(false, remaining,
        "Waiting for the catalogue retry deadline. The saved model list is still available; no generation is being submitted.");
  }

  static boolean recover(JobEngine engine, VantaJob job, Exception error, Net.Call call) throws Exception {
    if (!"catalogue".equals(job.type()) || call.isCancelled()) return false;
    int attempts = Math.min(100000, job.json.optInt("catalogue_failures") + 1);
    long delay = retryDelay(error, attempts);
    if (delay < 0) return false;
    long now = System.currentTimeMillis();
    long due = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
    job.json.put("next_run", due).put("catalogue_retry_at", due).put("catalogue_failures", attempts)
        .put("request_started", false);
    job.json.remove("error"); job.json.remove("transfer");
    job.event("catalogue_retry", "WAITING_PROVIDER", ProgressState.unknown("WAITING FOR PROVIDER",
        "Read-only catalogue retry at " + new java.text.SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()).format(new Date(due))
        + ". Your last saved catalogue is retained. No generation is being repeated."));
    engine.store.save(job);
    engine.finalSignal(job.id());
    return true;
  }
}
