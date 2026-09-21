package me.aap.fermata.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.addon.AddonInfo;

/** Phone-side dashboard. The original car services, browser and player remain in place. */
public class RonanHubActivity extends Activity {
    private static final int BG = 0xff090c10, PANEL = 0xff121820, BORDER = 0xff2b333f;
    private static final int GOLD = 0xffd8ab55, TEXT = 0xfff4f1e9, MUTED = 0xffb1bac8;
    private static final String WEB = "me.aap.fermata.addon.web.WebBrowserAddon";
    private LinearLayout root, body, tabs;
    private SharedPreferences prefs;
    private String page = "Home";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("ronan_hub", MODE_PRIVATE);
        if (state != null) page = state.getString("page", "Home");
        root = column(); root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = column(); header.setPadding(dp(24), dp(20), dp(24), dp(12));
        header.addView(text("RONIN GROUP AUSTRALIA", 11, GOLD, true));
        TextView name = text("RONAN DRIVE", 30, TEXT, true); name.setLetterSpacing(0.03f);
        header.addView(name);
        header.addView(text("MEDIA  /  MIRROR  /  LOCAL CONTROL", 10, MUTED, false));
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); body.setPadding(dp(20), dp(8), dp(20), dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        tabs = new LinearLayout(this); tabs.setPadding(dp(8), dp(8), dp(8), dp(8));
        tabs.setBackgroundColor(PANEL); root.addView(tabs);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,
                () -> { if (!page.equals("Home")) show("Home"); else finish(); });
        show(page);
    }
    @Override protected void onResume() { super.onResume(); if (body != null) show(page); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("page", page); super.onSaveInstanceState(state); }
    @Override public void onBackPressed() { if (!page.equals("Home")) show("Home"); else super.onBackPressed(); }

    private void show(String selected) {
        page = selected; body.removeAllViews(); tabs.removeAllViews();
        for (String name : new String[]{"Home", "Add-ons", "Display", "Diagnose"}) {
            Button b = button(name, () -> show(name)); b.setTextSize(12);
            b.setTextColor(name.equals(page) ? BG : MUTED);
            b.setBackground(background(name.equals(page) ? GOLD : PANEL, name.equals(page) ? GOLD : PANEL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(dp(2), 0, dp(2), 0); tabs.addView(b, lp);
        }
        switch (page) {
            case "Add-ons": addons(); break;
            case "Display": display(); break;
            case "Diagnose": diagnose(); break;
            default: home(); break;
        }
    }

    private void home() {
        title("Your drive. Your controls.", "Fermata's original playback and mirroring components, with Ronin controls.");
        LinearLayout status = card("ANDROID AUTO", "Installed by: " + RonanDiagnostics.installer(this)
                + "\nMirror availability is not yet verified on your vehicle.");
        status.addView(button("Connection diagnostics", () -> show("Diagnose")));
        action("Media library", "Local media, favourites and playlists", () -> open(R.id.folders_fragment, null, null));
        action("Web browser", "Use the original browser, including its Desktop site setting", () -> open(R.id.web_browser_fragment, WEB, null));
        action("YouTube", "Open the existing YouTube add-on", () -> open(R.id.youtube_fragment,
                "me.aap.fermata.addon.web.yt.YoutubeAddon", null));
        if (prefs.getBoolean("web_shortcuts", true)) {
            LinearLayout c = card("WEB SHORTCUTS", "Saved on this phone. Long-press a shortcut to remove it.");
            JSONArray items = shortcuts();
            if (items.length() == 0) c.addView(text("No shortcuts saved yet.", 14, MUTED, false));
            for (int n = 0; n < items.length(); n++) {
                JSONObject item = items.optJSONObject(n); if (item == null) continue;
                final int index = n; final String url = item.optString("url");
                Button b = button(item.optString("name", url), () -> open(R.id.web_browser_fragment, WEB, url));
                b.setOnLongClickListener(v -> { removeShortcut(index); return true; }); c.addView(b);
            }
            c.addView(button("+ Add website", this::addShortcut));
        }
        if (prefs.getBoolean("session_notes", false))
            action("Session notes", "Private app storage; excluded from the diagnostic report", this::notes);
        action("All original settings", "Playback, browser, subtitles, add-ons, import and export", () -> open(R.id.settings_fragment, null, null));
        body.addView(text("Build " + BuildConfig.VERSION_NAME + "  ·  Video and mirror testing while parked.", 12, MUTED, false));
    }

    private void addons() {
        title("Add-ons & local tools", "Existing Fermata modules are retained. Network features still contact their providers when used.");
        LinearLayout local = card("RONIN LOCAL TOOLS", "These tools add no network endpoint or account requirement.");
        local.addView(toggle("Web shortcuts", prefs.getBoolean("web_shortcuts", true),
                on -> prefs.edit().putBoolean("web_shortcuts", on).apply()));
        local.addView(toggle("Session notes", prefs.getBoolean("session_notes", false),
                on -> prefs.edit().putBoolean("session_notes", on).apply()));
        AddonInfo[] entries = BuildConfig.ADDONS.clone();
        Arrays.sort(entries, Comparator.comparing(this::addonName));
        for (AddonInfo info : entries) {
            boolean installed = info.isInstalled();
            boolean enabled = FermataApplication.get().getPreferenceStore().getBooleanPref(info.enabledPref);
            LinearLayout c = card(addonName(info), description(info.className));
            c.addView(text(installed ? "Bundled in this build" : "Not bundled / unavailable", 12, GOLD, false));
            Switch sw = toggle("Enabled", enabled, on -> {
                try {
                    FermataApplication.get().getPreferenceStore().applyBooleanPref(info.enabledPref, on);
                    toast(on ? "Enabled. Configure through the original settings." : "Disabled.");
                } catch (Exception e) { alert("Add-on", "The change could not be applied: " + e.getClass().getSimpleName()); }
            });
            sw.setEnabled(installed); c.addView(sw);
        }
        action("Configure original add-ons", "Open Settings → Add-ons for provider keys, sources and detailed options", () -> open(R.id.settings_fragment, null, null));
        body.addView(text("Google Drive remains unconfigured, as in the previous build. No streaming subscriptions, DRM overrides or new AI providers are included.", 13, MUTED, false));
    }

    private void display() {
        title("Adaptive display", "Uses the surface Android Auto supplies. It cannot claim the system bar or change a video's aspect ratio without borders, cropping or distortion.");
        LinearLayout c = card("SCREEN FIT", "Changes use the existing adaptive renderer. Reconnect the mirror if the current session does not update.");
        RadioGroup group = new RadioGroup(this);
        String[] modes = {"Auto Fit — full image, correct proportions", "Auto Fill — fill the area, crop the edges",
                "Stretch — fill the area, change proportions", "Compatibility — original rendering path"};
        int selected = MainActivityPrefs.get().getIntPref(MainActivityPrefs.RONAN_MIRROR_SCALE);
        for (int n = 0; n < modes.length; n++) {
            RadioButton rb = new RadioButton(this); rb.setId(100 + n); rb.setText(modes[n]);
            rb.setTextColor(TEXT); rb.setTextSize(14); rb.setMinHeight(dp(60));
            rb.setPadding(dp(6), dp(8), dp(6), dp(8)); rb.setButtonTintList(ColorStateList.valueOf(GOLD));
            group.addView(rb);
        }
        group.check(100 + Math.max(0, Math.min(3, selected)));
        group.setOnCheckedChangeListener((g, id) -> MainActivityPrefs.get()
                .applyIntPref(MainActivityPrefs.RONAN_MIRROR_SCALE, id - 100)); c.addView(group);
        card("LAST REPORTED CAR SURFACE", RonanDiagnostics.surfaceSummary(this));
        action("Apply Ronin dark theme", "Charcoal, warm gold and high-contrast text in the original player and car interface", () -> {
            MainActivityPrefs.get().applyIntPref(MainActivityPrefs.THEME_MAIN, MainActivityPrefs.THEME_DARK);
            MainActivityPrefs.get().applyIntPref(MainActivityPrefs.THEME_AA, MainActivityPrefs.THEME_DARK);
            toast("Ronin dark theme selected. Reopen the player to apply.");
        });
        action("Detailed display settings", "Keep access to Fermata's original size, orientation, layout and subtitle controls", () -> open(R.id.settings_fragment, null, null));
    }

    private void diagnose() {
        title("Connection diagnostics", "Local checks, not an automatic repair or a guarantee of Android Auto approval.");
        card("INSTALLATION ROUTE", "Fermata installed through AAAD and Ronan Drive opened as a normal APK do not necessarily have the same installation metadata. A manifest change alone cannot fix an installation-source restriction.\n\nNo need to remove other apps or root this phone.");
        action("Android Auto settings", "Open Android Auto's settings, or its App info page if the direct screen is unavailable", this::androidAutoSettings);
        action("Accessibility settings", "Enable Ronan Drive only when you choose to use mirror touch control", () -> settings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        action("Overlay permission", Settings.canDrawOverlays(this) ? "Allowed" : "Not currently allowed",
                () -> settings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        LinearLayout report = card("LOCAL REPORT", "No automatic upload. The report excludes browsing URLs, account data and session notes.");
        TextView details = text(RonanDiagnostics.report(this), 12, MUTED, false);
        details.setTypeface(Typeface.MONOSPACE); details.setTextIsSelectable(true); report.addView(details);
        report.addView(button("Copy diagnostic report", () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("Ronan Drive diagnostics", RonanDiagnostics.report(this))); toast("Copied."); }
        }));
        report.addView(button("Share diagnostic report", () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, RonanDiagnostics.report(this));
            settings(Intent.createChooser(send, "Share local diagnostic report"));
        }));
        report.addView(button("Refresh checks", () -> show("Diagnose")));
    }

    private void open(int target, String addon, String url) {
        if (addon != null) {
            AddonInfo found = null;
            for (AddonInfo i : BuildConfig.ADDONS) if (i.className.equals(addon)) { found = i; break; }
            if (found == null || !found.isInstalled()) { alert("Module unavailable", "This module is not available in the installed build."); return; }
            if (!FermataApplication.get().getPreferenceStore().getBooleanPref(found.enabledPref)) {
                final AddonInfo info = found;
                new AlertDialog.Builder(this).setTitle("Enable " + addonName(info) + "?")
                        .setMessage("This uses the bundled Fermata module. Online features contact the website or provider you choose.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Enable", (d, w) -> {
                            FermataApplication.get().getPreferenceStore().applyBooleanPref(info.enabledPref, true);
                            launch(target, url);
                        }).show(); return;
            }
        }
        launch(target, url);
    }
    private void launch(int target, String url) {
        Intent intent = new Intent(this, MainActivity.class).setAction("au.com.ronin.drive.OPEN")
                .putExtra("ronan_target", target);
        if (url != null) intent.putExtra("ronan_url", url);
        settings(intent);
    }
    private void androidAutoSettings() {
        try { startActivity(new Intent().setComponent(new ComponentName(RonanDiagnostics.AA,
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"))); }
        catch (Exception e) { settings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + RonanDiagnostics.AA))); }
    }
    private void settings(Intent intent) {
        try { startActivity(intent); }
        catch (Exception e) { alert("Unable to open", "Android did not provide this screen. Open it manually in phone settings.\n" + e.getClass().getSimpleName()); }
    }

    private JSONArray shortcuts() {
        try { return new JSONArray(prefs.getString("shortcuts", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void addShortcut() {
        if (shortcuts().length() >= 20) { alert("Shortcut limit", "Remove a shortcut before adding another. Maximum: 20."); return; }
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText name = field("Name", "", false), url = field("https://website.example", "", false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name); form.addView(url);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save website shortcut").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String label = name.getText().toString().trim(), address = url.getText().toString().trim();
            Uri uri = Uri.parse(address);
            if (label.isEmpty() || label.length() > 60) { name.setError("Enter 1–60 characters"); return; }
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || address.length() > 2048) {
                url.setError("Enter a complete http:// or https:// URL without embedded login details"); return;
            }
            try {
                JSONArray items = shortcuts(); items.put(new JSONObject().put("name", label).put("url", address));
                prefs.edit().putString("shortcuts", items.toString()).apply(); dialog.dismiss(); show("Home");
            } catch (Exception e) { alert("Not saved", "The shortcut could not be saved."); }
        })); dialog.show();
    }
    private void removeShortcut(int index) {
        new AlertDialog.Builder(this).setTitle("Remove this shortcut?").setMessage("This does not clear browser history or log out of the website.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> {
                    JSONArray items = shortcuts(); items.remove(index);
                    prefs.edit().putString("shortcuts", items.toString()).apply(); show("Home");
                }).show();
    }
    private void notes() {
        EditText note = field("Session notes", prefs.getString("notes", ""), true);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8000)});
        LinearLayout form = column(); form.setPadding(dp(20), dp(8), dp(20), dp(8)); form.addView(note);
        new AlertDialog.Builder(this).setTitle("Session notes — on this phone").setView(form)
                .setMessage("Stored in private app storage, not included in diagnostics. Uninstalling or clearing app data removes these notes.")
                .setNegativeButton("Cancel", null).setPositiveButton("Save locally", (d, w) -> {
                    prefs.edit().putString("notes", note.getText().toString()).apply(); toast("Saved locally.");
                }).show();
    }

    private String addonName(AddonInfo info) {
        try { return getString(info.addonName); } catch (Exception e) { return info.moduleName; }
    }
    private String description(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("youtube")) return "Existing YouTube web interface. Internet required.";
        if (n.contains("webbrowser")) return "Browser, desktop site mode and bookmarks. Sites receive normal browser traffic.";
        if (n.contains("chat")) return "Existing chat integration. Requires your chosen provider setup; messages may leave the device.";
        if (n.contains("whisper")) return "Existing speech/subtitle engine. Model downloads may be required.";
        if (n.contains("mlkit") || n.contains("opusmt")) return "Existing translation tools. Model and language downloads may be required.";
        if (n.contains("smb") || n.contains("sftp")) return "Network media access. Connects to servers you configure.";
        if (n.contains("cast")) return "Existing cast support for compatible receivers.";
        if (n.contains("tv")) return "Existing TV/IPTV tools. Provide your own authorised sources.";
        if (n.contains("poi")) return "Existing points-of-interest module.";
        return "Original Fermata module. Detailed options remain in Settings → Add-ons.";
    }
    private void title(String name, String subtitle) {
        body.addView(text(name, 23, TEXT, true));
        TextView sub = text(subtitle, 14, MUTED, false); sub.setPadding(0, dp(6), 0, dp(18)); body.addView(sub);
    }
    private LinearLayout card(String title, String subtitle) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(17), dp(18), dp(17)); c.setBackground(background(PANEL, BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(12)); body.addView(c, lp);
        c.addView(text(title, 15, GOLD, true)); TextView t = text(subtitle, 14, MUTED, false); t.setPadding(0, dp(7), 0, dp(8)); c.addView(t);
        return c;
    }
    private void action(String title, String subtitle, Runnable run) {
        LinearLayout c = card(title, subtitle); c.setFocusable(true); c.setClickable(true);
        c.setContentDescription(title + ". " + subtitle); c.setOnClickListener(v -> run.run());
        TextView cue = text("OPEN  →", 11, TEXT, true); cue.setPadding(0, dp(5), 0, 0); c.addView(cue);
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setLineSpacing(dp(3), 1f); return t;
    }
    private Button button(String label, Runnable run) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setTextColor(TEXT); b.setAllCaps(false);
        b.setMinHeight(dp(52)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setBackground(background(0xff1b2430, BORDER)); b.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(9); b.setLayoutParams(lp); return b;
    }
    private Switch toggle(String label, boolean checked, Toggle listener) {
        Switch s = new Switch(this); s.setText(label); s.setTextSize(15); s.setTextColor(TEXT);
        s.setMinHeight(dp(56)); s.setChecked(checked); s.setSwitchPadding(dp(16));
        s.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{GOLD,MUTED}));
        s.setOnCheckedChangeListener((v, on) -> listener.changed(on)); return s;
    }
    private EditText field(String hint, String value, boolean multiline) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextColor(TEXT); e.setHintTextColor(MUTED);
        e.setTextSize(16); e.setMinHeight(dp(60)); e.setSingleLine(!multiline);
        if (multiline) { e.setMinLines(5); e.setMaxLines(10); e.setGravity(Gravity.TOP); }
        return e;
    }
    private GradientDrawable background(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(15)); d.setStroke(dp(1), stroke); return d;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void alert(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close", null).show(); }
    private interface Toggle { void changed(boolean enabled); }
}
