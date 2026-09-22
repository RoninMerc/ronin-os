package au.com.roningroup.patrollink;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bound, non-exported :voice process. No HTTP client, account, API key or system TTS. */
public final class LocalVoiceService extends Service {
    public static final int GENERATE = 1, AUDIO = 2, ERROR = 3, CANCEL = 4, WARM = 5, READY = 6, PROGRESS = 7;
    private final AtomicLong epoch = new AtomicLong();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "local-voice-model");
        return t;
    });
    private OfflineTts model;
    private Messenger endpoint;

    /**
     * JNI resolves invoke(float[]) returning Integer, not the erased Function1.invoke(Object).
     * An explicitly typed method is required: a Java lambda only supplies the erased method.
     */
    public static final class GenerationCallback implements kotlin.jvm.functions.Function1<float[], Integer> {
        private final AtomicLong epoch;
        private final long generation;
        public GenerationCallback(AtomicLong epoch, long generation) {
            this.epoch = epoch;
            this.generation = generation;
        }
        @Override public Integer invoke(float[] samples) {
            return Integer.valueOf(generation == epoch.get() ? 1 : 0);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        endpoint = new Messenger(new Handler(Looper.getMainLooper(), m -> {
            if (m.what == CANCEL) { epoch.incrementAndGet(); return true; }
            if (m.what != GENERATE && m.what != WARM) return false;
            final Bundle data = new Bundle(m.getData()); final Messenger target = m.replyTo;
            final boolean warm = m.what == WARM; final long generation = epoch.get();
            worker.execute(() -> runRequest(data, target, generation, warm));
            return true;
        }));
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private void loadModel() {
        if (model != null) return;
        OfflineTtsPocketModelConfig pocket = new OfflineTtsPocketModelConfig();
        pocket.setLmFlow("pocket/lm_flow.int8.onnx"); pocket.setLmMain("pocket/lm_main.int8.onnx");
        pocket.setEncoder("pocket/encoder.onnx"); pocket.setDecoder("pocket/decoder.int8.onnx");
        pocket.setTextConditioner("pocket/text_conditioner.onnx"); pocket.setVocabJson("pocket/vocab.json");
        pocket.setTokenScoresJson("pocket/token_scores.json"); pocket.setVoiceEmbeddingCacheCapacity(3);
        OfflineTtsModelConfig mc = new OfflineTtsModelConfig(); mc.setPocket(pocket); mc.setNumThreads(2); mc.setDebug(false); mc.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig(); config.setModel(mc); config.setMaxNumSentences(1); config.setSilenceScale(.15f);
        model = new OfflineTts(getAssets(), config);
    }
    private void runRequest(Bundle data, Messenger reply, long generation, boolean warm) {
        String token = data.getString("token", ""); String profile = data.getString("profile", "");
        long started = SystemClock.elapsedRealtime();
        try {
            // Model readiness survives speech cancellation and voice switching during load.
            if (!warm && generation != epoch.get()) return;
            send(reply, PROGRESS, token, profile, "Loading local voice model", "", 0);
            loadModel();
            if (warm) { send(reply, READY, token, profile, "Local model loaded", "", SystemClock.elapsedRealtime() - started); return; }
            if (generation != epoch.get()) return;
            String text = data.getString("text", "");
            if (text.trim().isEmpty() || text.length() > 12000) throw new IOException("The activity text is empty or too long to speak safely.");
            File reference = new File(data.getString("reference", ""));
            String allowed = new File(getFilesDir(), "local_voice_v121").getCanonicalPath() + File.separator;
            if (!reference.getCanonicalPath().startsWith(allowed) || !reference.isFile()) throw new IOException("The selected voice reference is unavailable.");
            String refHash = VoiceReference.hash(reference);
            File dir = new File(getCacheDir(), "local_speech_v121"); if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot open local speech cache.");
            File audio = new File(dir, digest("pocket-int8-v121-5\n" + refHash + "\n" + text) + ".wav");
            if (!audio.isFile() || audio.length() < 1024) {
                send(reply, PROGRESS, token, profile, "Generating complete announcement locally", "", 0);
                GenerationConfig gc = new GenerationConfig(); gc.setReferenceAudio(VoiceReference.read(reference)); gc.setReferenceSampleRate(VoiceReference.RATE);
                gc.setNumSteps(5); gc.setSpeed(1f); gc.setSilenceScale(.15f);
                GeneratedAudio generated = model.generateWithConfigAndCallback(text, gc, new GenerationCallback(epoch, generation));
                if (generation != epoch.get()) return;
                if (generated.getSamples() == null || generated.getSamples().length < generated.getSampleRate() / 2) throw new IOException("The local model returned no usable speech.");
                File temporary = new File(dir, audio.getName() + ".tmp");
                if (!generated.save(temporary.getAbsolutePath()) || temporary.length() < 1024) { temporary.delete(); throw new IOException("Could not save generated speech."); }
                if (!temporary.renameTo(audio)) { temporary.delete(); throw new IOException("Could not finalise generated speech."); }
            }
            if (generation != epoch.get()) return;
            audio.setLastModified(System.currentTimeMillis());
            prune(dir, audio);
            send(reply, AUDIO, token, profile, "Complete announcement generated", audio.getAbsolutePath(), SystemClock.elapsedRealtime() - started);
        } catch (Exception | LinkageError e) {
            android.util.Log.e("PatrolLocalVoice", "Offline voice request failed", e);
            if (warm || generation == epoch.get()) send(reply, ERROR, token, profile, "Local voice generation failed: " + e.getClass().getSimpleName(), "", SystemClock.elapsedRealtime() - started);
        }
    }
    private static void send(Messenger target, int type, String token, String profile, String status, String path, long ms) {
        if (target == null) return;
        Message m = Message.obtain(null, type); Bundle b = new Bundle(); b.putString("token", token); b.putString("profile", profile);
        b.putString("status", status); b.putString("path", path); b.putLong("generationMs", ms); b.putInt("voicePid", android.os.Process.myPid()); m.setData(b);
        try { target.send(m); } catch (RemoteException ignored) { }
    }
    private static String digest(String s) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static void prune(File dir, File keep) {
        File[] entries = dir.listFiles((d, n) -> n.endsWith(".wav")); if (entries == null) return;
        Arrays.sort(entries, Comparator.comparingLong(File::lastModified)); long total = 0; for (File f : entries) total += f.length();
        int count = entries.length;
        for (File f : entries) { if (count <= 200 && total <= 64L * 1024 * 1024) break; if (f.equals(keep)) continue; long size = f.length(); if (f.delete()) { total -= size; count--; } }
    }
    @Override public void onDestroy() {
        epoch.incrementAndGet();
        worker.execute(() -> { if (model != null) { model.release(); model = null; } }); worker.shutdown();
        super.onDestroy();
    }
}
