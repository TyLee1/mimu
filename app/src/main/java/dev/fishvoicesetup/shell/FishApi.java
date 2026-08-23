package dev.fishvoicesetup.shell;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class FishApi {
    private static final String BASE = "https://api.fish.audio";
    private final String key;

    static final class Candidate {
        final String id;
        final File audio;
        Candidate(String id, File audio) { this.id = id; this.audio = audio; }
    }

    static final class ApiException extends Exception {
        final int code;
        ApiException(String message, int code) { super(message); this.code = code; }
    }

    FishApi(String key) { this.key = key; }

    boolean verify() throws Exception {
        Result r = request("GET", BASE + "/model?self=true&page_size=1", null, null, 30000);
        if (r.code == 200) return true;
        if (r.code == 401) return false;
        throw new ApiException("Fish key check HTTP " + r.code, r.code);
    }

    Candidate[] designTwo(File dir, String prompt, String neutral) throws Exception {
        JSONObject body = new JSONObject();
        body.put("instruction", prompt);
        body.put("reference_text", neutral);
        body.put("language", "en");
        body.put("n", 2);
        body.put("speed", 1.0);
        body.put("num_step", 32);
        body.put("guidance_scale", 2.0);
        body.put("instruct_guidance_scale", 0.0);
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("model", "voice-design-1");
        headers.put("Content-Type", "application/json");
        Result r = request("POST", BASE + "/v1/voice-design", body.toString().getBytes(StandardCharsets.UTF_8), headers, 150000);
        if (r.code != 200) throw new ApiException("Voice Design HTTP " + r.code, r.code);
        JSONArray array = new JSONObject(new String(r.body, StandardCharsets.UTF_8)).getJSONArray("candidates");
        if (array.length() < 2) throw new ApiException("Voice Design returned fewer than two candidates", 200);
        Candidate[] out = new Candidate[2];
        for (int i=0; i<2; i++) {
            JSONObject c = array.getJSONObject(i);
            File f = new File(dir, "candidate_" + i + ".wav");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(Base64.decode(c.getString("audio_base64"), Base64.DEFAULT));
            }
            out[i] = new Candidate(c.optString("id", ""), f);
        }
        return out;
    }

    String createPrivateModel(File wav) throws Exception {
        String boundary = "----FishVoiceSetup" + UUID.randomUUID();
        HttpURLConnection c = open("POST", BASE + "/model", 120000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setDoOutput(true);
        try (BufferedOutputStream out = new BufferedOutputStream(c.getOutputStream())) {
            field(out, boundary, "type", "tts");
            field(out, boundary, "title", "Private Designed Voice Candidate");
            field(out, boundary, "description", "Original synthetic Voice Design candidate.");
            field(out, boundary, "visibility", "private");
            field(out, boundary, "train_mode", "fast");
            file(out, boundary, "voices", "designed_voice.wav", "audio/wav", wav);
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        byte[] data = read(c, code);
        c.disconnect();
        if (code != 200 && code != 201) throw new ApiException("Create voice HTTP " + code, code);
        String id = new JSONObject(new String(data, StandardCharsets.UTF_8)).optString("_id", "");
        if (id.isEmpty()) throw new ApiException("Fish returned no private voice ID", code);
        return id;
    }

    void waitUntilTrained(String id) throws Exception {
        for (int i=0; i<60; i++) {
            Result r = request("GET", BASE + "/model/" + Uri.encode(id), null, null, 30000);
            if (r.code != 200) throw new ApiException("Voice status HTTP " + r.code, r.code);
            String state = new JSONObject(new String(r.body, StandardCharsets.UTF_8)).optString("state", "");
            if ("trained".equals(state)) return;
            if ("failed".equals(state)) throw new ApiException("Fish marked voice training failed", 200);
            Thread.sleep(1500);
        }
        throw new ApiException("Fish voice was not ready after the bounded wait", 200);
    }

    File tts(File dir, String label, String text, String modelId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("reference_id", modelId);
        body.put("temperature", 0.7);
        body.put("top_p", 0.7);
        body.put("format", "mp3");
        body.put("latency", "normal");
        JSONObject prosody = new JSONObject();
        prosody.put("speed", 1.0);
        prosody.put("volume", 0);
        prosody.put("normalize_loudness", true);
        body.put("prosody", prosody);
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("model", "s2.1-pro-free");
        headers.put("Content-Type", "application/json");
        Result r = request("POST", BASE + "/v1/tts", body.toString().getBytes(StandardCharsets.UTF_8), headers, 120000);
        if (r.code != 200) throw new ApiException(label + " TTS HTTP " + r.code, r.code);
        File f = new File(dir, "audition_" + sanitize(label) + ".mp3");
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(r.body); }
        return f;
    }

    void deleteModel(String id) {
        if (id == null || id.isEmpty()) return;
        try { request("DELETE", BASE + "/model/" + Uri.encode(id), null, null, 30000); }
        catch (Exception ignored) {}
    }

    private Result request(String method, String url, byte[] body, Map<String,String> headers, int timeout) throws Exception {
        HttpURLConnection c = open(method, url, timeout);
        if (headers != null) for (Map.Entry<String,String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        int code = c.getResponseCode();
        byte[] bytes = read(c, code);
        c.disconnect();
        return new Result(code, bytes);
    }

    private HttpURLConnection open(String method, String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("User-Agent", "FishVoiceSetup/1.0");
        c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    private static byte[] read(HttpURLConnection c, int code) throws Exception {
        InputStream raw = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        if (raw == null) return new byte[0];
        try (BufferedInputStream in = new BufferedInputStream(raw); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void field(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void file(OutputStream out, String boundary, String name, String filename, String contentType, File file) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static final class Result {
        final int code; final byte[] body;
        Result(int code, byte[] body) { this.code = code; this.body = body; }
    }
}
