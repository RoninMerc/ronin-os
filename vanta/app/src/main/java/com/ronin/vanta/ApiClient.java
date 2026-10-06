package com.ronin.vanta;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ApiClient {
    public interface Status { void onStatus(String text); }

    private static HttpURLConnection conn(String method, String url, ProviderConfig p, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Accept", "application/json");
        if (ProviderConfig.ANTHROPIC.equals(p.kind)) {
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    private static String readText(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (is == null) throw new Exception("HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + sb.toString().trim());
        return sb.toString();
    }

    private static byte[] readBytes(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (code < 200 || code >= 300) {
            if (is == null) throw new Exception("HTTP " + code);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            throw new Exception("HTTP " + code + ": " + sb.toString().trim());
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BufferedInputStream bis = new BufferedInputStream(is)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = bis.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String postJson(String url, ProviderConfig p, String key, JSONObject body) throws Exception {
        HttpURLConnection c = conn("POST", url, p, key);
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (java.io.OutputStream out = c.getOutputStream()) { out.write(b); }
        return readText(c);
    }

    private static String inferType(String id) {
        String s = id.toLowerCase();
        if (s.contains("image") || s.contains("dall-e")) return "image";
        if (s.contains("sora") || s.contains("video")) return "video";
        if (s.contains("tts") || s.contains("speech")) return "tts";
        if (s.contains("transcribe") || s.contains("whisper")) return "asr";
        return "text";
    }

    public static List<ModelInfo> listModels(ProviderConfig p, String key) throws Exception {
        String url = ProviderConfig.VENICE.equals(p.kind) ? p.baseUrl + "/models?type=all" : p.baseUrl + "/models";
        HttpURLConnection c = conn("GET", url, p, key);
        JSONObject root = new JSONObject(readText(c));
        JSONArray data = root.optJSONArray("data");
        if (data == null) data = root.optJSONArray("models");
        List<ModelInfo> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String id = o.optString("id", o.optString("name", "model-" + i));
            String name = id, type = inferType(id), description = "";
            if (ProviderConfig.VENICE.equals(p.kind)) {
                type = o.optString("type", type);
                JSONObject spec = o.optJSONObject("model_spec");
                if (spec != null) {
                    name = spec.optString("name", id); description = spec.optString("description", "");
                    JSONArray traits = spec.optJSONArray("traits"); if (traits != null) description += " " + traits.toString();
                }
            } else if (ProviderConfig.ANTHROPIC.equals(p.kind)) {
                name = o.optString("display_name", id); type = "text";
            }
            out.add(new ModelInfo(id, name, type, description));
        }
        return out;
    }

    public static String chat(ProviderConfig p, String key, String model, JSONArray history) throws Exception {
        if (ProviderConfig.ANTHROPIC.equals(p.kind)) {
            JSONObject body = new JSONObject(); body.put("model", model); body.put("max_tokens", 4096); body.put("messages", history);
            JSONObject r = new JSONObject(postJson(p.baseUrl + "/messages", p, key, body));
            JSONArray content = r.optJSONArray("content");
            if (content != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject x = content.optJSONObject(i);
                    if (x != null && "text".equals(x.optString("type"))) sb.append(x.optString("text"));
                }
                return sb.toString();
            }
            return r.toString(2);
        }
        if (ProviderConfig.OPENAI.equals(p.kind)) {
            JSONObject body = new JSONObject(); body.put("model", model); body.put("input", history);
            JSONObject r = new JSONObject(postJson(p.baseUrl + "/responses", p, key, body));
            String direct = r.optString("output_text", ""); if (!direct.isEmpty()) return direct;
            JSONArray output = r.optJSONArray("output"); StringBuilder sb = new StringBuilder();
            if (output != null) for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i); if (item == null) continue;
                JSONArray content = item.optJSONArray("content"); if (content == null) continue;
                for (int j = 0; j < content.length(); j++) { JSONObject part = content.optJSONObject(j); if (part != null) sb.append(part.optString("text", "")); }
            }
            return sb.length() > 0 ? sb.toString() : r.toString(2);
        }
        JSONObject body = new JSONObject(); body.put("model", model); body.put("messages", history); body.put("stream", false);
        JSONObject r = new JSONObject(postJson(p.baseUrl + "/chat/completions", p, key, body));
        JSONArray choices = r.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg != null) {
                Object content = msg.opt("content");
                if (content instanceof String) return (String) content;
                if (content instanceof JSONArray) {
                    StringBuilder sb = new StringBuilder(); JSONArray a = (JSONArray) content;
                    for (int i = 0; i < a.length(); i++) { JSONObject part = a.optJSONObject(i); if (part != null) sb.append(part.optString("text", "")); }
                    return sb.toString();
                }
            }
        }
        return r.toString(2);
    }

    public static byte[] generateImage(ProviderConfig p, String key, String model, String prompt) throws Exception {
        if (ProviderConfig.ANTHROPIC.equals(p.kind)) throw new Exception("This provider does not expose image generation through Vanta.");
        JSONObject body = new JSONObject(); body.put("model", model); body.put("prompt", prompt);
        if (ProviderConfig.VENICE.equals(p.kind)) {
            body.put("format", "png"); body.put("return_binary", false); body.put("variants", 1);
            JSONObject r = new JSONObject(postJson(p.baseUrl + "/image/generate", p, key, body));
            JSONArray imgs = r.optJSONArray("images");
            if (imgs != null && imgs.length() > 0) return Base64.decode(imgs.getString(0), Base64.DEFAULT);
            throw new Exception("No image returned by Venice.");
        }
        body.put("size", "1024x1024");
        JSONObject r = new JSONObject(postJson(p.baseUrl + "/images/generations", p, key, body));
        JSONArray data = r.optJSONArray("data");
        if (data != null && data.length() > 0) {
            JSONObject first = data.getJSONObject(0);
            String b64 = first.optString("b64_json", ""); if (!b64.isEmpty()) return Base64.decode(b64, Base64.DEFAULT);
            String u = first.optString("url", "");
            if (!u.isEmpty()) { HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection(); c.setConnectTimeout(30000); c.setReadTimeout(180000); return readBytes(c); }
        }
        throw new Exception("No image returned.");
    }

    public static byte[] generateVideo(ProviderConfig p, String key, String model, String prompt, Status status) throws Exception {
        if (ProviderConfig.VENICE.equals(p.kind)) return veniceVideo(p, key, model, prompt, status);
        if (ProviderConfig.OPENAI.equals(p.kind)) return openAiVideo(p, key, model, prompt, status);
        throw new Exception("Video generation is not configured for this provider.");
    }

    private static byte[] veniceVideo(ProviderConfig p, String key, String model, String prompt, Status status) throws Exception {
        JSONObject body = new JSONObject(); body.put("model", model); body.put("prompt", prompt); body.put("duration", "5s"); body.put("resolution", "720p"); body.put("aspect_ratio", "16:9");
        JSONObject q = new JSONObject(postJson(p.baseUrl + "/video/queue", p, key, body));
        String queueId = q.optString("queue_id", q.optString("id", "")); if (queueId.isEmpty()) throw new Exception("Venice did not return a video queue ID.");
        status.onStatus("Video queued · " + queueId);
        for (int i = 0; i < 180; i++) {
            Thread.sleep(5000);
            JSONObject rb = new JSONObject(); rb.put("model", model); rb.put("queue_id", queueId); rb.put("delete_media_on_completion", true);
            HttpURLConnection c = conn("POST", p.baseUrl + "/video/retrieve", p, key); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            byte[] b = rb.toString().getBytes(StandardCharsets.UTF_8); try (java.io.OutputStream out = c.getOutputStream()) { out.write(b); }
            int code = c.getResponseCode(); String ct = c.getContentType();
            if (code >= 200 && code < 300 && ct != null && ct.toLowerCase().contains("video")) { status.onStatus("Video complete"); return readBytesAlreadyOpen(c); }
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line);
            if (code < 200 || code >= 300) throw new Exception("Video retrieve HTTP " + code + ": " + sb);
            JSONObject s = new JSONObject(sb.toString()); String st = s.optString("status", "PROCESSING"); status.onStatus("Venice video · " + st);
            if ("FAILED".equalsIgnoreCase(st) || "ERROR".equalsIgnoreCase(st)) throw new Exception("Venice video generation failed: " + sb);
        }
        throw new Exception("Timed out waiting for video.");
    }

    private static byte[] readBytesAlreadyOpen(HttpURLConnection c) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = c.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n); }
        return bos.toByteArray();
    }

    private static byte[] openAiVideo(ProviderConfig p, String key, String model, String prompt, Status status) throws Exception {
        String boundary = "----RoninVanta" + System.currentTimeMillis();
        HttpURLConnection c = conn("POST", p.baseUrl + "/videos", p, key); c.setDoOutput(true); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            writePart(out, boundary, "model", model); writePart(out, boundary, "prompt", prompt); writePart(out, boundary, "seconds", "4"); writePart(out, boundary, "size", "1280x720"); out.writeBytes("--" + boundary + "--\r\n");
        }
        JSONObject created = new JSONObject(readText(c)); String id = created.optString("id", ""); if (id.isEmpty()) throw new Exception("OpenAI did not return a video ID.");
        status.onStatus("OpenAI video queued · " + id);
        for (int i = 0; i < 180; i++) {
            Thread.sleep(5000); HttpURLConnection g = conn("GET", p.baseUrl + "/videos/" + id, p, key); JSONObject s = new JSONObject(readText(g));
            String st = s.optString("status", "queued"); status.onStatus("OpenAI video · " + st + " " + s.optInt("progress", 0) + "%");
            if ("completed".equalsIgnoreCase(st)) { HttpURLConnection d = conn("GET", p.baseUrl + "/videos/" + id + "/content", p, key); return readBytes(d); }
            if ("failed".equalsIgnoreCase(st) || "cancelled".equalsIgnoreCase(st)) throw new Exception("OpenAI video generation failed: " + s.toString());
        }
        throw new Exception("Timed out waiting for OpenAI video.");
    }

    private static void writePart(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n"); out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"); out.write(value.getBytes(StandardCharsets.UTF_8)); out.writeBytes("\r\n");
    }
}
