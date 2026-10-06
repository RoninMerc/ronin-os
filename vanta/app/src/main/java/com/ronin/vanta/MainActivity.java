package com.ronin.vanta;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int GOLD = Color.rgb(199, 163, 71);
    private static final int BG = Color.rgb(8, 8, 8);
    private static final int PANEL = Color.rgb(20, 20, 20);
    private static final int MUTED = Color.rgb(165, 165, 165);
    private static final int MIC_PERMISSION = 7001;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<ProviderConfig> providers = new ArrayList<>();
    private final HashMap<String, List<ModelInfo>> modelCache = new HashMap<>();
    private SecureVault vault;
    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout content;
    private ProgressBar busy;
    private ProviderConfig selectedProvider;
    private ModelInfo selectedModel;
    private String currentMode = "chat";
    private Button providerButton;
    private Button modelButton;
    private Switch uncensoredOnly;
    private JSONArray chatHistory = new JSONArray();
    private TextView chatOutput;
    private EditText promptInput;

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private Voice selectedVoice;
    private TextView converseTranscript;
    private Button micButton;
    private boolean listening = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        vault = new SecureVault(this);
        prefs = getSharedPreferences("vanta_state", MODE_PRIVATE);
        loadProviders();
        loadAllModelCaches();
        loadChatHistory();
        tts = new TextToSpeech(this, this);
        buildShell();
        showMode("chat");
    }

    private void buildShell() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        page.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("RONIN VANTA", 28, GOLD, true);
        title.setLetterSpacing(.16f);
        root.addView(title);
        TextView sub = text("SOVEREIGN AI CONSOLE  ·  PERSONAL BUILD", 11, MUTED, true);
        sub.setLetterSpacing(.1f);
        root.addView(sub, marginTop(dp(2)));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
        String[][] items = {{"CHAT","chat"},{"IMAGE","image"},{"VIDEO","video"},{"CONVERSATE","converse"},{"SETUP","setup"}};
        for (String[] it : items) {
            Button b = button(it[0]);
            b.setOnClickListener(v -> showMode(it[1]));
            nav.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        hsv.addView(nav);
        root.addView(hsv, marginTop(dp(14)));
        busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busy.setIndeterminate(true); busy.setVisibility(View.GONE);
        root.addView(busy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, marginTop(dp(12)));
        setContentView(page);
    }

    private void showMode(String mode) {
        currentMode = mode;
        content.removeAllViews();
        selectedProvider = null; selectedModel = null;
        if ("setup".equals(mode)) { buildSetup(); return; }
        if ("chat".equals(mode)) buildChat(false);
        else if ("converse".equals(mode)) buildChat(true);
        else if ("image".equals(mode)) buildMedia(false);
        else if ("video".equals(mode)) buildMedia(true);
    }

    private void addSelectorRow(String wantedType) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        providerButton = button("Provider"); modelButton = button("Model");
        row.addView(providerButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(modelButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(row);
        providerButton.setOnClickListener(v -> chooseProvider(wantedType));
        modelButton.setOnClickListener(v -> chooseModel(wantedType));
        autoSelectProviderAndModel(wantedType);
    }

    private void buildChat(boolean conversate) {
        addSelectorRow("text");
        uncensoredOnly = new Switch(this);
        uncensoredOnly.setText("Only show UNCENSORED models");
        uncensoredOnly.setTextColor(Color.WHITE);
        uncensoredOnly.setChecked(prefs.getBoolean("uncensored_only", false));
        uncensoredOnly.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("uncensored_only", checked).apply();
            selectedModel = null; refreshSelectorLabels("text");
        });
        content.addView(uncensoredOnly, marginTop(dp(6)));

        if (!conversate) {
            chatOutput = text(renderHistory(), 15, Color.WHITE, false);
            chatOutput.setBackgroundColor(PANEL); chatOutput.setPadding(dp(12), dp(12), dp(12), dp(12));
            chatOutput.setTextIsSelectable(true); chatOutput.setMovementMethod(new ScrollingMovementMethod());
            content.addView(chatOutput, fixedHeight(dp(300), dp(8)));
            promptInput = edit("Talk, research, code, analyse…");
            promptInput.setMinLines(3); promptInput.setMaxLines(8);
            content.addView(promptInput, marginTop(dp(10)));
            LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
            Button send = button("SEND"); Button clear = button("NEW CHAT");
            send.setOnClickListener(v -> sendChat(false, promptInput.getText().toString().trim()));
            clear.setOnClickListener(v -> { chatHistory = new JSONArray(); saveChatHistory(); chatOutput.setText(""); });
            actions.addView(send, new LinearLayout.LayoutParams(0, dp(48), 1));
            actions.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1));
            content.addView(actions, marginTop(dp(8)));
        } else {
            TextView info = text("Hands-free conversation. Vanta listens, sends your words to the selected AI, then speaks the reply using your chosen Android TTS voice.", 14, MUTED, false);
            content.addView(info, marginTop(dp(8)));
            converseTranscript = text("Tap TALK to begin.", 17, Color.WHITE, false);
            converseTranscript.setBackgroundColor(PANEL); converseTranscript.setPadding(dp(14),dp(14),dp(14),dp(14));
            content.addView(converseTranscript, fixedHeight(dp(260), dp(10)));
            micButton = button("TALK"); micButton.setTextSize(18); micButton.setOnClickListener(v -> toggleListen());
            content.addView(micButton, fixedHeight(dp(60), dp(12)));
            Button voice = button("VOICE: " + currentVoiceName()); voice.setOnClickListener(v -> chooseVoice(voice));
            content.addView(voice, fixedHeight(dp(48), dp(8)));
            Button moreVoices = button("INSTALL MORE DEVICE VOICES");
            moreVoices.setOnClickListener(v -> {
                try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                catch (Exception e) { toast("Open Android text-to-speech settings to install voices."); }
            });
            content.addView(moreVoices, fixedHeight(dp(48), dp(8)));
            Button piper = button("PIPER VOICE LIBRARY");
            piper.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/rhasspy/piper-voices"))));
            content.addView(piper, fixedHeight(dp(48), dp(8)));
            TextView voiceNote = text("Piper provides downloadable ONNX voice packs. This Vanta build uses Android-installed TTS voices for live playback; the provider system is structured so a local ONNX voice runtime can be added without changing the AI layer.", 12, MUTED, false);
            content.addView(voiceNote, marginTop(dp(6)));
        }
    }

    private void buildMedia(boolean video) {
        addSelectorRow(video ? "video" : "image");
        TextView desc = text(video ? "Generate video with the selected provider/model." : "Generate an image with the selected provider/model.", 14, MUTED, false);
        content.addView(desc, marginTop(dp(8)));
        EditText prompt = edit(video ? "Describe the video…" : "Describe the image…"); prompt.setMinLines(4);
        content.addView(prompt, marginTop(dp(8)));
        Button generate = button(video ? "GENERATE VIDEO" : "GENERATE IMAGE");
        content.addView(generate, fixedHeight(dp(52), dp(10)));
        TextView status = text("", 13, MUTED, false); content.addView(status, marginTop(dp(6)));
        FrameLayout preview = new FrameLayout(this); preview.setBackgroundColor(PANEL);
        content.addView(preview, fixedHeight(dp(360), dp(10)));
        generate.setOnClickListener(v -> {
            String p = prompt.getText().toString().trim();
            if (p.isEmpty()) { toast("Enter a prompt."); return; }
            if (!ensureSelection(video ? "video" : "image")) return;
            String key = vault.getSecret(selectedProvider.id);
            if (key == null || key.isEmpty()) { editProviderKey(selectedProvider); return; }
            setBusy(true); status.setText("Starting…"); preview.removeAllViews();
            if (!video) io.submit(() -> {
                try {
                    byte[] bytes = ApiClient.generateImage(selectedProvider, key, selectedModel.id, p);
                    saveMedia(bytes, false);
                    Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    runOnUiThread(() -> {
                        setBusy(false); status.setText("Saved to Pictures/Ronin Vanta");
                        ImageView iv = new ImageView(this); iv.setImageBitmap(bm); iv.setAdjustViewBounds(true); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        preview.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    });
                } catch (Exception e) { fail(status, e); }
            }); else io.submit(() -> {
                try {
                    byte[] bytes = ApiClient.generateVideo(selectedProvider, key, selectedModel.id, p, s -> runOnUiThread(() -> status.setText(s)));
                    Uri saved = saveMedia(bytes, true);
                    runOnUiThread(() -> {
                        setBusy(false); status.setText("Saved to Movies/Ronin Vanta");
                        VideoView vv = new VideoView(this); vv.setVideoURI(saved); vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
                        preview.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    });
                } catch (Exception e) { fail(status, e); }
            });
        });
    }

    private void buildSetup() {
        content.addView(text("AI PROVIDERS", 20, GOLD, true));
        content.addView(text("Keys are encrypted with Android Keystore. They are never embedded in the APK or committed to source control.", 13, MUTED, false), marginTop(dp(4)));

        LinearLayout add = new LinearLayout(this); add.setOrientation(LinearLayout.HORIZONTAL);
        Button venice = button("+ VENICE"), openai = button("+ OPENAI"), claude = button("+ CLAUDE"), custom = button("+ CUSTOM");
        venice.setOnClickListener(v -> addOrEditPreset(ProviderConfig.VENICE));
        openai.setOnClickListener(v -> addOrEditPreset(ProviderConfig.OPENAI));
        claude.setOnClickListener(v -> addOrEditPreset(ProviderConfig.ANTHROPIC));
        custom.setOnClickListener(v -> addCustomProvider());
        add.addView(venice); add.addView(openai); add.addView(claude); add.addView(custom);
        HorizontalScrollView hsv = new HorizontalScrollView(this); hsv.addView(add); content.addView(hsv, marginTop(dp(10)));

        for (ProviderConfig p : providers) {
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12),dp(12),dp(12),dp(12)); card.setBackgroundColor(PANEL);
            card.addView(text(p.name, 18, Color.WHITE, true));
            TextView meta = text(p.kind.toUpperCase(Locale.US) + "  ·  " + p.baseUrl + "\n" + (vault.getSecret(p.id) == null ? "API key: NOT SET" : "API key: secured") + "  ·  Models cached: " + modelsFor(p).size(), 12, MUTED, false);
            card.addView(meta, marginTop(dp(3)));
            LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
            Button key = button("API KEY"); Button sync = button("SYNC MODELS"); Button manage = button("MANAGE MODELS");
            key.setOnClickListener(v -> editProviderKey(p)); sync.setOnClickListener(v -> syncModels(p, meta)); manage.setOnClickListener(v -> manageModels(p));
            actions.addView(key); actions.addView(sync); actions.addView(manage); card.addView(actions, marginTop(dp(8)));
            if (ProviderConfig.CUSTOM.equals(p.kind)) {
                Button delete = button("REMOVE CUSTOM PROVIDER"); delete.setOnClickListener(v -> confirmDeleteProvider(p)); card.addView(delete, marginTop(dp(6)));
            }
            content.addView(card, marginTop(dp(10)));
        }

        content.addView(text("VOICE", 20, GOLD, true), marginTop(dp(18)));
        content.addView(text("Conversate can use any voice installed in Android's Text-to-Speech engine. Install additional voice data from Android TTS settings, then choose it inside Conversate.", 13, MUTED, false), marginTop(dp(4)));
        Button ttsSettings = button("OPEN TEXT-TO-SPEECH SETTINGS");
        ttsSettings.setOnClickListener(v -> { try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); } });
        content.addView(ttsSettings, fixedHeight(dp(48), dp(8)));
        Button piper = button("OPEN PIPER DOWNLOADABLE VOICES");
        piper.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/rhasspy/piper-voices"))));
        content.addView(piper, fixedHeight(dp(48), dp(8)));
    }

    private void sendChat(boolean speak, String text) {
        if (text == null || text.isEmpty()) return;
        if (!ensureSelection("text")) return;
        String key = vault.getSecret(selectedProvider.id);
        if (key == null || key.isEmpty()) { editProviderKey(selectedProvider); return; }
        try { JSONObject u = new JSONObject(); u.put("role", "user"); u.put("content", text); chatHistory.put(u); saveChatHistory(); } catch (Exception ignored) {}
        if (!speak && chatOutput != null) { chatOutput.setText(renderHistory() + "\n\nVANTA: …"); promptInput.setText(""); }
        if (speak && converseTranscript != null) converseTranscript.setText("YOU: " + text + "\n\nVANTA: …");
        setBusy(true);
        ProviderConfig p = selectedProvider; ModelInfo m = selectedModel;
        io.submit(() -> {
            try {
                String answer = ApiClient.chat(p, key, m.id, chatHistory);
                JSONObject a = new JSONObject(); a.put("role", "assistant"); a.put("content", answer); chatHistory.put(a); saveChatHistory();
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!speak && chatOutput != null) chatOutput.setText(renderHistory());
                    if (speak && converseTranscript != null) { converseTranscript.setText("YOU: " + text + "\n\nVANTA: " + answer); speak(answer); }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); if (speak && converseTranscript != null) converseTranscript.setText("ERROR: " + e.getMessage()); else toast(e.getMessage()); });
            }
        });
    }

    private String renderHistory() {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<chatHistory.length();i++) {
            JSONObject x = chatHistory.optJSONObject(i); if (x == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("assistant".equals(x.optString("role")) ? "VANTA: " : "YOU: ").append(x.optString("content"));
        }
        return sb.toString();
    }

    private void chooseProvider(String wantedType) {
        if (providers.isEmpty()) { toast("Add a provider in Setup."); return; }
        String[] names = new String[providers.size()]; for (int i=0;i<providers.size();i++) names[i] = providers.get(i).name;
        new AlertDialog.Builder(this).setTitle("Choose AI provider").setItems(names, (d, which) -> { selectedProvider = providers.get(which); selectedModel = null; refreshSelectorLabels(wantedType); }).show();
    }

    private void chooseModel(String wantedType) {
        if (selectedProvider == null) { chooseProvider(wantedType); return; }
        List<ModelInfo> candidates = eligibleModels(selectedProvider, wantedType);
        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No models available").setMessage("Sync models for " + selectedProvider.name + " in Setup, or change the current model filters.")
                    .setPositiveButton("SYNC NOW", (d,w) -> syncModels(selectedProvider, null)).setNegativeButton("CANCEL", null).show();
            return;
        }
        String[] items = new String[candidates.size()]; for (int i=0;i<candidates.size();i++) items[i] = candidates.get(i).displayName();
        new AlertDialog.Builder(this).setTitle("Choose model").setItems(items, (d, which) -> { selectedModel = candidates.get(which); refreshSelectorLabels(wantedType); }).show();
    }

    private List<ModelInfo> eligibleModels(ProviderConfig p, String wantedType) {
        List<ModelInfo> out = new ArrayList<>(); boolean unc = uncensoredOnly != null && uncensoredOnly.isChecked() && "text".equals(wantedType);
        for (ModelInfo m : modelsFor(p)) if (m.enabled && wantedType.equals(m.type) && (!unc || m.isUncensored())) out.add(m);
        Collections.sort(out, Comparator.comparing((ModelInfo m) -> !m.isUncensored()).thenComparing(m -> m.name.toLowerCase(Locale.US)));
        return out;
    }

    private void autoSelectProviderAndModel(String type) {
        for (ProviderConfig p : providers) {
            List<ModelInfo> e = eligibleModels(p, type);
            if (!e.isEmpty()) { selectedProvider = p; selectedModel = e.get(0); break; }
        }
        if (selectedProvider == null && !providers.isEmpty()) selectedProvider = providers.get(0);
        refreshSelectorLabels(type);
    }

    private boolean ensureSelection(String type) {
        if (selectedProvider == null) { toast("Choose a provider."); return false; }
        if (selectedModel == null || !type.equals(selectedModel.type)) {
            List<ModelInfo> e = eligibleModels(selectedProvider, type);
            if (e.isEmpty()) { toast("No enabled " + type + " models. Sync/manage models in Setup."); return false; }
            selectedModel = e.get(0); refreshSelectorLabels(type);
        }
        return true;
    }

    private void refreshSelectorLabels(String type) {
        if (providerButton != null) providerButton.setText(selectedProvider == null ? "PROVIDER" : selectedProvider.name.toUpperCase(Locale.US));
        if (selectedProvider != null && (selectedModel == null || !type.equals(selectedModel.type) || (uncensoredOnly != null && uncensoredOnly.isChecked() && type.equals("text") && !selectedModel.isUncensored()))) {
            List<ModelInfo> e = eligibleModels(selectedProvider, type); selectedModel = e.isEmpty() ? null : e.get(0);
        }
        if (modelButton != null) modelButton.setText(selectedModel == null ? "MODEL" : (selectedModel.isUncensored() ? "⚡ " : "") + selectedModel.name);
    }

    private void syncModels(ProviderConfig p, TextView statusView) {
        String key = vault.getSecret(p.id);
        if (key == null || key.isEmpty()) { editProviderKey(p); return; }
        setBusy(true); if (statusView != null) statusView.setText("Syncing models…");
        io.submit(() -> {
            try {
                List<ModelInfo> fresh = ApiClient.listModels(p, key);
                HashMap<String, ModelInfo> old = new HashMap<>(); for (ModelInfo m : modelsFor(p)) old.put(m.id, m);
                for (ModelInfo m : fresh) { ModelInfo prev = old.get(m.id); if (prev != null) { m.enabled = prev.enabled; m.manuallyUncensored = prev.manuallyUncensored; } }
                modelCache.put(p.id, fresh); saveModelCache(p.id);
                runOnUiThread(() -> { setBusy(false); toast("Synced " + fresh.size() + " models from " + p.name); if ("setup".equals(currentMode)) showMode("setup"); });
            } catch (Exception e) { runOnUiThread(() -> { setBusy(false); toast("Model sync failed: " + e.getMessage()); if (statusView != null) statusView.setText("Sync failed: " + e.getMessage()); }); }
        });
    }

    private void manageModels(ProviderConfig p) {
        List<ModelInfo> models = modelsFor(p);
        if (models.isEmpty()) { syncModels(p, null); return; }
        String[] labels = new String[models.size()]; boolean[] checked = new boolean[models.size()];
        for (int i=0;i<models.size();i++) { labels[i]=models.get(i).displayName(); checked[i]=models.get(i).enabled; }
        new AlertDialog.Builder(this).setTitle(p.name + " · enabled models")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> models.get(which).enabled = isChecked)
                .setPositiveButton("SAVE", (d,w) -> { saveModelCache(p.id); toast("Model allow-list saved."); showMode("setup"); })
                .setNeutralButton("UNCENSORED LABELS", (d,w) -> manageUncensoredLabels(p)).setNegativeButton("CANCEL", null).show();
    }

    private void manageUncensoredLabels(ProviderConfig p) {
        List<ModelInfo> models = modelsFor(p); String[] labels = new String[models.size()]; boolean[] checked = new boolean[models.size()];
        for (int i=0;i<models.size();i++) { labels[i]=models.get(i).name; checked[i]=models.get(i).manuallyUncensored; }
        new AlertDialog.Builder(this).setTitle("Manual UNCENSORED labels")
                .setMessage("Vanta automatically detects explicit uncensored/unfiltered model names. Use this to override models whose provider metadata does not say so clearly.")
                .setMultiChoiceItems(labels, checked, (d,which,isChecked) -> models.get(which).manuallyUncensored=isChecked)
                .setPositiveButton("SAVE", (d,w) -> { saveModelCache(p.id); showMode("setup"); }).setNegativeButton("CANCEL", null).show();
    }

    private void addOrEditPreset(String kind) {
        ProviderConfig found = null; for (ProviderConfig p : providers) if (kind.equals(p.kind)) { found = p; break; }
        if (found == null) {
            if (ProviderConfig.VENICE.equals(kind)) found = new ProviderConfig("venice", "Venice", kind, "https://api.venice.ai/api/v1");
            else if (ProviderConfig.OPENAI.equals(kind)) found = new ProviderConfig("openai", "OpenAI", kind, "https://api.openai.com/v1");
            else found = new ProviderConfig("anthropic", "Claude / Anthropic", kind, "https://api.anthropic.com/v1");
            providers.add(found); saveProviders();
        }
        editProviderKey(found);
    }

    private void addCustomProvider() {
        LinearLayout box = dialogBox(); EditText name = edit("Provider name"); EditText url = edit("Base URL, e.g. https://api.example.com/v1"); EditText key = secretEdit("API key");
        box.addView(name); box.addView(url, marginTop(dp(6))); box.addView(key, marginTop(dp(6)));
        new AlertDialog.Builder(this).setTitle("Add OpenAI-compatible provider").setView(box)
                .setPositiveButton("ADD", (d,w) -> {
                    String n=name.getText().toString().trim(), u=url.getText().toString().trim(), k=key.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) { toast("Name and base URL are required."); return; }
                    ProviderConfig p = new ProviderConfig("custom-"+UUID.randomUUID(), n, ProviderConfig.CUSTOM, u); providers.add(p); saveProviders();
                    try { if (!k.isEmpty()) vault.putSecret(p.id,k); } catch(Exception e){ toast("Could not secure API key: "+e.getMessage()); }
                    showMode("setup");
                }).setNegativeButton("CANCEL", null).show();
    }

    private void editProviderKey(ProviderConfig p) {
        EditText input = secretEdit("Paste API key"); String existing = vault.getSecret(p.id); if (existing != null) input.setHint("A key is already secured. Paste a replacement to change it.");
        LinearLayout box = dialogBox(); box.addView(text(p.baseUrl, 12, MUTED, false)); box.addView(input, marginTop(dp(8)));
        new AlertDialog.Builder(this).setTitle(p.name + " API key").setView(box)
                .setPositiveButton("SAVE", (d,w) -> {
                    String k=input.getText().toString().trim(); if(k.isEmpty()){toast("No change made."); return;}
                    try { vault.putSecret(p.id,k); toast("API key secured on this device."); syncModels(p,null); }
                    catch(Exception e){toast("Could not secure key: "+e.getMessage());}
                }).setNegativeButton("CANCEL", null).show();
    }

    private void confirmDeleteProvider(ProviderConfig p) {
        new AlertDialog.Builder(this).setTitle("Remove " + p.name + "?").setMessage("This removes the provider, cached models and secured key from Vanta.")
                .setPositiveButton("REMOVE", (d,w) -> { providers.remove(p); modelCache.remove(p.id); vault.deleteSecret(p.id); prefs.edit().remove("models_"+p.id).apply(); saveProviders(); showMode("setup"); })
                .setNegativeButton("CANCEL", null).show();
    }

    private void loadProviders() {
        try { JSONArray a = new JSONArray(prefs.getString("providers", "[]")); for(int i=0;i<a.length();i++) providers.add(ProviderConfig.fromJson(a.getJSONObject(i))); } catch(Exception ignored) {}
        if (providers.isEmpty()) {
            providers.add(new ProviderConfig("venice", "Venice", ProviderConfig.VENICE, "https://api.venice.ai/api/v1"));
            providers.add(new ProviderConfig("openai", "OpenAI", ProviderConfig.OPENAI, "https://api.openai.com/v1"));
            providers.add(new ProviderConfig("anthropic", "Claude / Anthropic", ProviderConfig.ANTHROPIC, "https://api.anthropic.com/v1"));
            saveProviders();
        }
    }

    private void saveProviders() { try { JSONArray a = new JSONArray(); for(ProviderConfig p:providers)a.put(p.toJson()); prefs.edit().putString("providers",a.toString()).apply(); } catch(Exception ignored) {} }

    private void loadAllModelCaches() {
        for (ProviderConfig p : providers) {
            List<ModelInfo> list = new ArrayList<>();
            try { JSONArray a = new JSONArray(prefs.getString("models_"+p.id,"[]")); for(int i=0;i<a.length();i++)list.add(ModelInfo.fromJson(a.getJSONObject(i))); } catch(Exception ignored) {}
            modelCache.put(p.id,list);
        }
    }

    private List<ModelInfo> modelsFor(ProviderConfig p) { return modelCache.computeIfAbsent(p.id,k->new ArrayList<>()); }
    private void saveModelCache(String pid) { try { JSONArray a=new JSONArray(); List<ModelInfo> list=modelCache.get(pid); if(list!=null)for(ModelInfo m:list)a.put(m.toJson()); prefs.edit().putString("models_"+pid,a.toString()).apply(); } catch(Exception ignored) {} }
    private void loadChatHistory() { try { chatHistory = new JSONArray(prefs.getString("chat_history","[]")); } catch(Exception e){chatHistory=new JSONArray();} }
    private void saveChatHistory() { prefs.edit().putString("chat_history", chatHistory.toString()).apply(); }

    private void toggleListen() {
        if (listening) { if(recognizer!=null) recognizer.stopListening(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION); return; }
        startListening();
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Speech recognition is not available on this device."); return; }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p){listening=true; if(micButton!=null)micButton.setText("LISTENING…");}
                @Override public void onBeginningOfSpeech(){}
                @Override public void onRmsChanged(float rmsdB){}
                @Override public void onBufferReceived(byte[] buffer){}
                @Override public void onEndOfSpeech(){listening=false; if(micButton!=null)micButton.setText("TALK");}
                @Override public void onError(int error){listening=false; if(micButton!=null)micButton.setText("TALK"); if(converseTranscript!=null)converseTranscript.setText("Speech recognition error " + error + ". Tap TALK and try again.");}
                @Override public void onResults(Bundle results){ listening=false; if(micButton!=null)micButton.setText("TALK"); ArrayList<String> r=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(r!=null&&!r.isEmpty())sendChat(true,r.get(0)); }
                @Override public void onPartialResults(Bundle partialResults){ ArrayList<String> r=partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(r!=null&&!r.isEmpty()&&converseTranscript!=null)converseTranscript.setText("YOU: "+r.get(0)); }
                @Override public void onEvent(int eventType, Bundle params){}
            });
        }
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault()); recognizer.startListening(i);
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grantResults){ super.onRequestPermissionsResult(req,permissions,grantResults); if(req==MIC_PERMISSION&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startListening(); }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.getDefault());
            String stored=prefs.getString("tts_voice",null); if(stored!=null) for(Voice v:tts.getVoices()) if(stored.equals(v.getName())) {selectedVoice=v;tts.setVoice(v);break;}
        }
    }

    private void chooseVoice(Button button) {
        if (tts == null || tts.getVoices() == null) { toast("Text-to-speech is still loading."); return; }
        List<Voice> voices = new ArrayList<>(tts.getVoices()); Collections.sort(voices, Comparator.comparing(Voice::getName));
        String[] names=new String[voices.size()]; for(int i=0;i<voices.size();i++){Voice v=voices.get(i);names[i]=v.getName()+" · "+v.getLocale().toLanguageTag()+(v.isNetworkConnectionRequired()?" · online":" · local");}
        new AlertDialog.Builder(this).setTitle("Conversate voice").setItems(names,(d,w)->{selectedVoice=voices.get(w);tts.setVoice(selectedVoice);prefs.edit().putString("tts_voice",selectedVoice.getName()).apply();button.setText("VOICE: "+currentVoiceName());speak("Ronin Vanta voice selected.");}).show();
    }

    private String currentVoiceName(){return selectedVoice==null?"SYSTEM DEFAULT":selectedVoice.getName();}
    private void speak(String s){ if(tts!=null){ if(selectedVoice!=null)tts.setVoice(selectedVoice);tts.speak(s,TextToSpeech.QUEUE_FLUSH,null,"vanta-"+System.currentTimeMillis()); } }

    private Uri saveMedia(byte[] bytes, boolean video) throws Exception {
        String name="Vanta_"+System.currentTimeMillis()+(video?".mp4":".png"); ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,video?"video/mp4":"image/png");
        if(android.os.Build.VERSION.SDK_INT>=29)v.put(MediaStore.MediaColumns.RELATIVE_PATH,(video?"Movies":"Pictures")+"/Ronin Vanta");
        Uri uri=getContentResolver().insert(video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v); if(uri==null)throw new Exception("Could not create media file.");
        try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new Exception("Could not open media destination.");out.write(bytes);} return uri;
    }

    private void fail(TextView status, Exception e){ runOnUiThread(()->{setBusy(false);status.setText("ERROR: "+e.getMessage());toast(e.getMessage());}); }
    private void setBusy(boolean b){busy.setVisibility(b?View.VISIBLE:View.GONE);}
    private void toast(String s){Toast.makeText(this,s==null?"Unknown error":s,Toast.LENGTH_LONG).show();}

    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setAllCaps(false);b.setBackgroundColor(PANEL);b.setPadding(dp(10),0,dp(10),0);return b;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(110,110,110));e.setTextColor(Color.WHITE);e.setBackgroundColor(PANEL);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setTextSize(15);e.setGravity(Gravity.TOP|Gravity.START);return e;}
    private EditText secretEdit(String hint){EditText e=edit(hint);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private LinearLayout dialogBox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(6),dp(20),0);return l;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams marginTop(int px){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.topMargin=px;return p;}
    private LinearLayout.LayoutParams fixedHeight(int h,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h);p.topMargin=top;return p;}

    @Override protected void onDestroy(){super.onDestroy();io.shutdownNow();if(tts!=null){tts.stop();tts.shutdown();}if(recognizer!=null)recognizer.destroy();}
}
