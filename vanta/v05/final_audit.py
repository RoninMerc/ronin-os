from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "personal"
JAVA = ROOT / "app/src/main/java/com/ronin/vanta"

def change(path, old, new):
    source = path.read_text()
    if source.count(old) != 1:
        raise SystemExit(f"Expected exactly one final-audit match in {path.name}: {old[:70]!r}")
    path.write_text(source.replace(old, new, 1))

voice = JAVA / "VoiceStudio.java"
change(voice, '''    dialog.setOnDismissListener(
        d -> {
          closed = true;
          generation++;
          if (call != null) call.cancel();
          engine.stop();
          io.shutdownNow();
        });''', '    dialog.setOnDismissListener(d -> close());')
change(voice, '  private void load(boolean refresh) {', '''  public boolean isOpen() {
    return !closed && dialog != null && dialog.isShowing();
  }

  /** The owning activity closes both the dialog and its asynchronous work on stop. */
  public void close() {
    if (closed) return;
    closed = true;
    generation++;
    if (call != null) call.cancel();
    engine.stop();
    io.shutdownNow();
    if (dialog != null && dialog.isShowing()) dialog.dismiss();
  }

  private void load(boolean refresh) {''')
main = JAVA / "MainActivity.java"
change(main, '  private VoiceEngine voiceEngine;', '  private VoiceEngine voiceEngine;\n  private VoiceStudio voiceStudioController;\n  private HorizontalScrollView navigationScroll;')
change(main, '    HorizontalScrollView navScroll = new HorizontalScrollView(this);', '    HorizontalScrollView navScroll = new HorizontalScrollView(this);\n    navigationScroll = navScroll;')
change(main, '    if (mode.equals("setup")) {', '''    Button selectedTab = tabs.get(mode);
    if (selectedTab != null) navigationScroll.post(() -> {
      int left = selectedTab.getLeft();
      int right = selectedTab.getRight();
      int current = navigationScroll.getScrollX();
      if (left < current) navigationScroll.smoothScrollTo(left, 0);
      else if (right > current + navigationScroll.getWidth())
        navigationScroll.smoothScrollTo(right - navigationScroll.getWidth(), 0);
    });
    if (mode.equals("setup")) {''')
change(main, '''    new VoiceStudio(
            this,''', '''    if (voiceStudioController != null) voiceStudioController.close();
    voiceStudioController = new VoiceStudio(
            this,''')
change(main, '''              if (foreground) showMode(mode);
            })
        .show();''', '''              if (foreground) showMode(mode);
            });
    voiceStudioController.show();''')
change(main, '''    foreground = false;
    if (playingVideo != null) playingVideo.pause();''', '''    foreground = false;
    if (voiceStudioController != null) voiceStudioController.close();
    if (playingVideo != null) playingVideo.pause();''')
change(main, '''    if (voiceEngine != null) voiceEngine.close();''', '''    ui.removeCallbacksAndMessages(null);
    if (voiceStudioController != null) voiceStudioController.close();
    if (voiceEngine != null) voiceEngine.close();''')
api = JAVA / "ApiClient.java"
change(api, '''    JSONObject b = speechBody(model, voice, text, speed, format);
    Net.Response r =''', '''    JSONObject b = speechBody(model, voice, text, speed, format);
    Map<String, String> audioHeaders = Net.headers(p, key);
    audioHeaders.put("Accept", "audio/*, application/octet-stream;q=0.9, application/json;q=0.1");
    Net.Response r =''')
change(api, '''            p.baseUrl + "/audio/speech",
            Net.headers(p, key),''', '''            p.baseUrl + "/audio/speech",
            audioHeaders,''')
change(ROOT / "app/src/main/AndroidManifest.xml", 'android:label="Ronin Vanta"', 'android:label="@string/app_name"')
change(main, "                          vault.deleteSecret(target.id);", "                          try { vault.deleteSecret(target.id); } catch (Exception error) { error(error.getMessage()); return; }")
change(main, "              vault.deleteSecret(p.id);", "              try { vault.deleteSecret(p.id); } catch (Exception error) { error(error.getMessage()); return; }")
# Credential removal requires an acknowledged disk commit, not an unacknowledged apply().
change(JAVA / "SecureVault.java", '''    prefs.edit().remove(alias + ".iv").remove(alias + ".ct").remove(alias + ".version").commit();''', '''    if (!prefs.edit().remove(alias + ".iv").remove(alias + ".ct").remove(alias + ".version").commit())
      throw new IllegalStateException("Credential removal could not be committed. Retry before handing over this device.");''')

TEST = ROOT / "app/src/androidTest/java/com/ronin/vanta/FinalAuditTest.java"
TEST.write_text('''package com.ronin.vanta;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FinalAuditTest {
  @Test public void voiceDialogAndJobsCloseWhenActivityStops() throws Exception {
    AtomicReference<VoiceStudio> studio = new AtomicReference<>();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        try {
          activity.showMode("setup");
          Method open = MainActivity.class.getDeclaredMethod("voiceStudio");
          open.setAccessible(true); open.invoke(activity);
          Field field = MainActivity.class.getDeclaredField("voiceStudioController");
          field.setAccessible(true); studio.set((VoiceStudio) field.get(activity));
          assertTrue(studio.get().isOpen());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
      });
      scenario.moveToState(Lifecycle.State.CREATED);
      assertFalse(studio.get().isOpen());
      scenario.moveToState(Lifecycle.State.RESUMED);
      scenario.onActivity(activity -> activity.showMode("chat"));
    }
  }
  @Test public void deletingCredentialRemovesEveryEnvelopeField() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SecureVault vault = new SecureVault(context);
    vault.putSecret("qa-final-delete", "fixture-not-a-provider-key");
    vault.deleteSecret("qa-final-delete");
    assertNull(vault.getSecret("qa-final-delete"));
    assertFalse(vault.containsSecret("qa-final-delete"));
    SharedPreferences prefs = context.getSharedPreferences("vanta_vault", Context.MODE_PRIVATE);
    assertFalse(prefs.contains("qa-final-delete.version"));
  }
}
''')
print("Applied final audit: voice lifecycle, visible navigation, audio content negotiation, durable credential deletion")
