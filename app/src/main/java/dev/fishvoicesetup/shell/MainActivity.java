package dev.fishvoicesetup.shell;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final Uri API_KEYS_URI = Uri.parse("https://fish.audio/app/api-keys/");
    private static final Uri DESIGN_FALLBACK_URI = Uri.parse("https://fish.audio/app/create-voice/");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FishApi.Candidate[] candidates = new FishApi.Candidate[2];
    private final LinkedHashMap<String, File> auditions = new LinkedHashMap<>();

    private Config config;
    private LinearLayout root;
    private TextView status;
    private Button keyButton, generateButton, playA, chooseA, playB, chooseB, neither, keep, retry, forget;
    private String apiKey;
    private String modelId;
    private int selected = -1;
    private boolean waitingForKey;
    private volatile boolean busy;
    private AudioPlayer audio;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { config = Config.load(this); }
        catch (Exception e) { config = null; }
        audio = new AudioPlayer(main);
        buildUi();
        if (config == null || !config.configured) {
            setStatus("This build has no private voice configuration. Do not use this unsigned build shell.");
            setAllEnabled(false);
            return;
        }
        try { apiKey = SecureStore.load(this); } catch (Exception ignored) { apiKey = null; }
        if (TextUtils.isEmpty(apiKey)) {
            setStatus("One account step is unavoidable: create a Fish API key, tap Copy, then return here. Everything after that is automated.");
        } else {
            generateButton.setVisibility(View.VISIBLE);
            forget.setVisibility(View.VISIBLE);
            setStatus("Fish API key is securely stored on this phone. Tap Generate 2 voices.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForKey && !busy) main.postDelayed(this::readClipboardKey, 250);
    }

    @Override protected void onDestroy() {
        audio.stop();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText(config != null && config.title != null ? config.title : "Fish Voice Setup");
        title.setTextSize(25f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, params());
        TextView scope = new TextView(this);
        scope.setText("Standalone voice setup only. It does not connect to or modify another app/runtime.");
        scope.setTextSize(15f);
        scope.setPadding(0, dp(8), 0, dp(12));
        root.addView(scope, params());
        status = new TextView(this);
        status.setTextSize(17f);
        status.setPadding(dp(10), dp(12), dp(10), dp(14));
        root.addView(status, params());

        keyButton = button("CREATE / COPY FISH API KEY", v -> openKeyPage());
        generateButton = button("GENERATE 2 VOICES", v -> generate());
        playA = button("▶ PLAY A", v -> audio.play(candidates[0] == null ? null : candidates[0].audio));
        chooseA = button("CHOOSE A", v -> choose(0));
        playB = button("▶ PLAY B", v -> audio.play(candidates[1] == null ? null : candidates[1].audio));
        chooseB = button("CHOOSE B", v -> choose(1));
        neither = button("NEITHER — GENERATE 2 MORE", v -> generate());
        if (config != null) {
            for (String label : config.tests.keySet()) {
                Button b = button("▶ " + label, v -> audio.play(auditions.get(label)));
                b.setTag("audition");
                b.setVisibility(View.GONE);
            }
        }
        keep = button("KEEP THIS VOICE", v -> keepVoice());
        retry = button("TRY ANOTHER", v -> tryAnother());
        forget = button("FORGET STORED API KEY", v -> forgetKey());
        generateButton.setVisibility(View.GONE);
        forget.setVisibility(View.GONE);
        candidateControls(false);
        keep.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        setContentView(scroll);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16f);
        b.setMinHeight(dp(54));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = params();
        p.setMargins(0, dp(5), 0, dp(5));
        root.addView(b, p);
        return b;
    }

    private LinearLayout.LayoutParams params() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void setStatus(String text) { main.post(() -> status.setText(text)); }

    private void setAllEnabled(boolean enabled) {
        main.post(() -> {
            for (int i=0; i<root.getChildCount(); i++) root.getChildAt(i).setEnabled(enabled);
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        main.post(() -> {
            keyButton.setEnabled(!value); generateButton.setEnabled(!value);
            chooseA.setEnabled(!value); chooseB.setEnabled(!value); neither.setEnabled(!value);
            keep.setEnabled(!value); retry.setEnabled(!value); forget.setEnabled(!value);
        });
    }

    private void openKeyPage() {
        waitingForKey = true;
        setStatus("On Fish: tap Create API Key, then Copy. Return here; the copied key will be detected automatically.");
        startActivity(new Intent(Intent.ACTION_VIEW, API_KEYS_URI));
    }

    private void readClipboardKey() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        if (cs == null) return;
        String proposed = cs.toString().trim();
        if (proposed.length() < 16 || proposed.length() > 4096) return;
        verifyKey(proposed);
    }

    private void verifyKey(String proposed) {
        if (busy) return;
        setBusy(true);
        setStatus("Checking the copied Fish key…");
        executor.execute(() -> {
            boolean autoStart = false;
            try {
                if (!new FishApi(proposed).verify()) {
                    setStatus("Fish rejected that copied value as an API key. Tap Create / Copy Fish API Key and copy the key itself.");
                    return;
                }
                SecureStore.save(this, proposed);
                apiKey = proposed;
                waitingForKey = false;
                main.post(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.clearPrimaryClip();
                    generateButton.setVisibility(View.VISIBLE);
                    forget.setVisibility(View.VISIBLE);
                });
                if (LocalDate.now().isAfter(config.freeCutoff)) {
                    main.post(() -> generateButton.setText("GENERATE 2 VOICES (current Fish pricing applies)"));
                    setStatus("Fish key verified. The configured free-runtime window has ended, so nothing will auto-generate. Tap Generate only if you want to continue under Fish’s current pricing.");
                } else {
                    autoStart = true;
                }
            } catch (Exception e) {
                setStatus("Couldn’t verify the Fish key. Nothing was generated or stored unless Fish accepted the key.");
            } finally {
                setBusy(false);
            }
            if (autoStart) main.post(this::generate);
        });
    }

    private void generate() {
        if (busy) return;
        if (TextUtils.isEmpty(apiKey)) { openKeyPage(); return; }
        hideAuditions(); candidateControls(false); selected = -1;
        setBusy(true);
        setStatus("Generating two original synthetic voices…");
        executor.execute(() -> {
            try {
                FishApi.Candidate[] pair = new FishApi(apiKey).designTwo(getFilesDir(), config.voicePrompt, config.neutral);
                candidates[0] = pair[0]; candidates[1] = pair[1];
                setStatus("Two candidates are ready. A and B will play automatically. Tap the one you prefer.");
                main.post(() -> { candidateControls(true); audio.playSequence(new File[]{pair[0].audio, pair[1].audio}); });
            } catch (FishApi.ApiException e) {
                if (e.code == 402) {
                    setStatus("Fish returned 402 for Voice Design. No automatic retry was made. A fallback button can open Fish Voice Design with the private prompt copied.");
                    main.post(this::addFallbackButton);
                } else if (e.code == 401) {
                    setStatus("The stored Fish key is no longer authorized. Tap Forget Stored API Key and create/copy a new one.");
                } else {
                    setStatus("Fish Voice Design failed (HTTP " + e.code + "). No automatic retry was made.");
                }
            } catch (Exception e) {
                setStatus("Voice Design failed before a usable pair was produced. No automatic retry was made.");
            } finally { setBusy(false); }
        });
    }

    private void addFallbackButton() {
        Button b = button("OPEN FISH VOICE DESIGN FALLBACK", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Voice description", config.voicePrompt));
            startActivity(new Intent(Intent.ACTION_VIEW, DESIGN_FALLBACK_URI));
            Toast.makeText(this, "Voice description copied", Toast.LENGTH_SHORT).show();
        });
    }

    private void choose(int index) {
        if (busy || candidates[index] == null) return;
        selected = index; candidateControls(false); setBusy(true);
        setStatus("Saving the selected candidate as a private Fish voice, then generating the audition clips…");
        executor.execute(() -> {
            FishApi fish = new FishApi(apiKey);
            try {
                modelId = fish.createPrivateModel(candidates[index].audio);
                fish.waitUntilTrained(modelId);
                if (LocalDate.now().isAfter(config.freeCutoff)) {
                    setStatus("The private voice was saved, but TTS stopped because the configured free-runtime window has ended. Voice ID: " + modelId);
                    return;
                }
                auditions.clear();
                for (Map.Entry<String,String> e : config.tests.entrySet()) {
                    setStatus("Generating audition clip: " + e.getKey() + "…");
                    auditions.put(e.getKey(), fish.tts(getFilesDir(), e.getKey(), e.getValue(), modelId));
                }
                setStatus("Audition ready. All clips will play automatically. If the same speaker still sounds right, tap KEEP THIS VOICE.");
                main.post(() -> { showAuditions(); audio.playSequence(auditions.values().toArray(new File[0])); });
            } catch (Exception e) {
                setStatus("Fish could not finish saving/testing that voice. No automatic retry was attempted. " + safe(e));
            } finally { setBusy(false); }
        });
    }

    private void candidateControls(boolean show) {
        main.post(() -> {
            int v = show ? View.VISIBLE : View.GONE;
            playA.setVisibility(v); chooseA.setVisibility(v); playB.setVisibility(v); chooseB.setVisibility(v); neither.setVisibility(v);
        });
    }

    private void showAuditions() {
        for (int i=0; i<root.getChildCount(); i++) if ("audition".equals(root.getChildAt(i).getTag())) root.getChildAt(i).setVisibility(View.VISIBLE);
        keep.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
    }

    private void hideAuditions() {
        main.post(() -> {
            for (int i=0; i<root.getChildCount(); i++) if ("audition".equals(root.getChildAt(i).getTag())) root.getChildAt(i).setVisibility(View.GONE);
            keep.setVisibility(View.GONE); retry.setVisibility(View.GONE);
        });
    }

    private void tryAnother() {
        if (busy) return;
        String old = modelId; modelId = null; auditions.clear(); hideAuditions();
        if (!TextUtils.isEmpty(old)) executor.execute(() -> new FishApi(apiKey).deleteModel(old));
        generate();
    }

    private void keepVoice() {
        if (TextUtils.isEmpty(modelId) || selected < 0) return;
        try {
            JSONObject j = new JSONObject();
            j.put("schema_version", 1);
            j.put("provider", "fish_audio");
            j.put("reference_id", modelId);
            j.put("visibility", "private");
            j.put("source", "voice_design_original_synthetic");
            j.put("candidate_id", candidates[selected].id);
            j.put("voice_prompt", config.voicePrompt);
            j.put("neutral_reference_text", config.neutral);
            j.put("tts_model_validated", "s2.1-pro-free");
            j.put("selected_at", OffsetDateTime.now().toString());
            j.put("credential_exported", false);
            exportMetadata(j.toString(2));
            setStatus("Voice kept. Non-secret voice metadata was saved under Downloads/AI Companion/Voice. The API key stayed encrypted inside this app.");
            keep.setEnabled(false); retry.setVisibility(View.GONE);
        } catch (Exception e) {
            setStatus("Voice is selected, but Android could not export the metadata file. The private Fish voice still exists as " + modelId + ".");
        }
    }

    private void exportMetadata(String json) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, "companion_fish_voice.json");
        v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AI Companion/Voice");
        ContentResolver r = getContentResolver();
        Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("No Downloads URI");
        try (OutputStream out = r.openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("No Downloads stream");
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void forgetKey() {
        if (busy) return;
        SecureStore.clear(this); apiKey = null; waitingForKey = false;
        generateButton.setVisibility(View.GONE); forget.setVisibility(View.GONE);
        setStatus("Stored Fish API key removed from this app. Tap Create / Copy Fish API Key when you want to continue.");
    }

    private static String safe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "Try again once if you want to continue.";
        return m.length() > 180 ? m.substring(0,180) : m;
    }

    private static final class Config {
        final boolean configured;
        final String title;
        final String voicePrompt;
        final String neutral;
        final LocalDate freeCutoff;
        final LinkedHashMap<String,String> tests;

        Config(boolean configured, String title, String voicePrompt, String neutral, LocalDate freeCutoff, LinkedHashMap<String,String> tests) {
            this.configured = configured; this.title = title; this.voicePrompt = voicePrompt; this.neutral = neutral; this.freeCutoff = freeCutoff; this.tests = tests;
        }

        static Config load(Context context) throws Exception {
            byte[] bytes;
            try (InputStream in = context.getAssets().open("voice_config.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                bytes = out.toByteArray();
            }
            JSONObject j = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            LinkedHashMap<String,String> tests = new LinkedHashMap<>();
            JSONArray a = j.getJSONArray("tests");
            for (int i=0; i<a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                tests.put(t.getString("label"), t.getString("text"));
            }
            return new Config(j.optBoolean("configured", false), j.optString("title", "Fish Voice Setup"),
                    j.getString("voice_prompt"), j.getString("neutral"), LocalDate.parse(j.getString("free_cutoff")), tests);
        }
    }
}
