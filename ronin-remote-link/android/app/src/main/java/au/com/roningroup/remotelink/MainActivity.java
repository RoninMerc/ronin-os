package au.com.roningroup.remotelink;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String APP_VERSION = "0.2.0";
    private static final String ENGINE_PACKAGE = "com.carriez.flutter_hbb";
    private static final String ENGINE_VERSION = "1.4.9";
    private static final int REQUEST_UNLOCK = 4102;

    private static final int BG = Color.rgb(14, 16, 18);
    private static final int PANEL = Color.rgb(24, 27, 30);
    private static final int FIELD = Color.rgb(34, 38, 42);
    private static final int MUTED = Color.rgb(166, 172, 178);
    private static final int RED = Color.rgb(197, 45, 50);
    private static final int GREEN = Color.rgb(125, 210, 145);
    private static final int AMBER = Color.rgb(235, 174, 93);

    private EditText name;
    private EditText id;
    private EditText server;
    private EditText key;
    private EditText password;
    private CheckBox remember;
    private CheckBox requireUnlock;
    private TextView status;
    private TextView engineState;
    private TextView serverState;
    private SecretStore secrets;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Boolean pendingFileTransfer = null;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        secrets = new SecretStore(this);
        setContentView(buildUi());
        load();
        refreshEngineState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshEngineState();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        boolean large = getResources().getConfiguration().smallestScreenWidthDp >= 600 || BuildConfig.TABLET_BUILD;
        int side = dp(large ? 44 : 20);

        LinearLayout root = vertical();
        root.setPadding(side, dp(24), side, dp(30));
        scroll.addView(root);

        TextView title = text("RONIN REMOTE LINK", large ? 30 : 25, Color.WHITE, true);
        root.addView(title);
        TextView sub = text(
                (BuildConfig.TABLET_BUILD ? "TABLET CONTROL" : "MOBILE CONTROL") + " · v" + APP_VERSION + " · ENGINE " + ENGINE_VERSION,
                12, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        engineState = text("Remote engine: checking...", 12, MUTED, true);
        engineState.setPadding(dp(14), dp(12), dp(14), dp(12));
        engineState.setBackgroundColor(PANEL);
        root.addView(engineState, matchWrap(0, dp(12)));

        LinearLayout card = card();
        root.addView(card, matchWrap(0, dp(12)));

        name = field("Main Desktop");
        id = field("Remote device ID");
        server = field("remote.example.com");
        key = field("Server public key");
        password = field("Access password or leave blank to use saved password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        addLabelField(card, "DEVICE NAME", name);
        addLabelField(card, "REMOTE DEVICE ID", id);
        addLabelField(card, "SELF-HOSTED ID SERVER", server);
        addLabelField(card, "SERVER PUBLIC KEY", key);
        addLabelField(card, "ACCESS PASSWORD", password);

        remember = new CheckBox(this);
        remember.setText("Remember password encrypted in Android Keystore");
        styleCheck(remember);
        card.addView(remember);

        requireUnlock = new CheckBox(this);
        requireUnlock.setText("Require device unlock before every remote session");
        styleCheck(requireUnlock);
        card.addView(requireUnlock);

        LinearLayout actions = large ? horizontal() : vertical();
        Button remote = action("REMOTE DESKTOP");
        Button files = action("FILES");
        Button save = secondary("SAVE DEVICE");
        remote.setOnClickListener(v -> requestLaunch(false));
        files.setOnClickListener(v -> requestLaunch(true));
        save.setOnClickListener(v -> save(true));

        actions.addView(remote, large ? weight() : matchWrap(0, dp(8)));
        actions.addView(files, large ? weight() : matchWrap(0, dp(8)));
        actions.addView(save, large ? weight() : matchWrap(0, dp(8)));
        card.addView(actions);

        LinearLayout diagnostics = card();
        root.addView(diagnostics, matchWrap(0, dp(12)));
        TextView dTitle = text("CONNECTION HEALTH", 13, Color.WHITE, true);
        dTitle.setPadding(0, 0, 0, dp(8));
        diagnostics.addView(dTitle);

        serverState = text("Server: not checked", 12, MUTED, false);
        serverState.setPadding(0, 0, 0, dp(10));
        diagnostics.addView(serverState);

        LinearLayout diagActions = large ? horizontal() : vertical();
        Button test = secondary("TEST SERVER");
        Button refresh = secondary("REFRESH ENGINE");
        Button copy = secondary("COPY DIAGNOSTICS");
        test.setOnClickListener(v -> testServer());
        refresh.setOnClickListener(v -> refreshEngineState());
        copy.setOnClickListener(v -> copyDiagnostics());
        diagActions.addView(test, large ? weight() : matchWrap(0, dp(8)));
        diagActions.addView(refresh, large ? weight() : matchWrap(0, dp(8)));
        diagActions.addView(copy, large ? weight() : matchWrap(0, dp(8)));
        diagnostics.addView(diagActions);

        LinearLayout utility = large ? horizontal() : vertical();
        Button core = secondary("OPEN REMOTE ENGINE");
        Button install = secondary("INSTALL PINNED ENGINE");
        core.setOnClickListener(v -> openCore());
        install.setOnClickListener(v -> openEngineDownload());
        utility.addView(core, large ? weight() : matchWrap(0, dp(8)));
        utility.addView(install, large ? weight() : matchWrap(0, dp(8)));
        root.addView(utility, matchWrap(0, dp(6)));

        status = text("Ready.", 12, MUTED, false);
        status.setPadding(2, dp(10), 2, 0);
        root.addView(status);

        TextView note = text(
                "Ronin Remote Link v0.2 keeps the remote engine isolated. Saved passwords are not placed back into the screen at startup; they are retrieved only when a connection is launched.",
                11, Color.rgb(135, 141, 147), false);
        note.setPadding(2, dp(12), 2, 0);
        root.addView(note);
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(PANEL);
        return card;
    }

    private void styleCheck(CheckBox c) {
        c.setTextColor(MUTED);
        c.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
        c.setPadding(0, dp(2), 0, dp(8));
    }

    private void requestLaunch(boolean fileTransfer) {
        try {
            save(false);
            String peer = clean(id.getText().toString());
            if (peer.length() < 3) throw new IllegalArgumentException("Enter the remote device ID.");
            if (!isEngineInstalled()) {
                showEngineMissing();
                return;
            }

            if (requireUnlock.isChecked()) {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km != null && km.isDeviceSecure()) {
                    Intent unlock = km.createConfirmDeviceCredentialIntent(
                            "Ronin Remote Link",
                            "Unlock this device to start the remote session.");
                    if (unlock != null) {
                        pendingFileTransfer = fileTransfer;
                        startActivityForResult(unlock, REQUEST_UNLOCK);
                        setStatus("Waiting for device authentication...");
                        return;
                    }
                }
            }
            launchNow(fileTransfer);
        } catch (Exception e) {
            error(e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_UNLOCK) return;
        Boolean pending = pendingFileTransfer;
        pendingFileTransfer = null;
        if (resultCode == RESULT_OK && pending != null) {
            launchNow(pending);
        } else {
            setStatus("Remote session cancelled before authentication.");
        }
    }

    private void launchNow(boolean fileTransfer) {
        try {
            String peer = clean(id.getText().toString());
            String host = server.getText().toString().trim();
            String pub = key.getText().toString().trim();
            String pass = password.getText().toString();
            if (pass.isEmpty() && remember.isChecked()) {
                try { pass = secrets.load(); }
                catch (Exception e) { setStatus("Saved password unavailable; the remote engine may prompt for it."); }
            }

            String target = peer;
            if (!host.isEmpty()) target += "/r@" + host;

            StringBuilder u = new StringBuilder("rustdesk://");
            if (fileTransfer) u.append("file-transfer/");
            u.append(target);

            boolean hasQuery = false;
            if (!pub.isEmpty()) {
                u.append("?key=").append(Uri.encode(pub));
                hasQuery = true;
            }
            if (!pass.isEmpty()) {
                u.append(hasQuery ? "&" : "?").append("password=").append(Uri.encode(pass));
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(u.toString()));
            intent.setPackage(ENGINE_PACKAGE);
            startActivity(intent);
            password.setText("");
            String label = name.getText().toString().trim();
            if (label.isEmpty()) label = peer;
            setStatus((fileTransfer ? "Opening files for " : "Opening remote desktop for ") + label + "...");
        } catch (ActivityNotFoundException e) {
            showEngineMissing();
        } catch (Exception e) {
            password.setText("");
            error(e.getMessage());
        }
    }

    private void refreshEngineState() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(ENGINE_PACKAGE, 0);
            String installed = info.versionName == null ? "unknown" : info.versionName;
            engineState.setText("Remote engine: INSTALLED · " + installed + (ENGINE_VERSION.equals(installed) ? " · PINNED" : " · expected " + ENGINE_VERSION));
            engineState.setTextColor(ENGINE_VERSION.equals(installed) ? GREEN : AMBER);
        } catch (PackageManager.NameNotFoundException e) {
            engineState.setText("Remote engine: NOT INSTALLED · required " + ENGINE_VERSION);
            engineState.setTextColor(AMBER);
        }
    }

    private boolean isEngineInstalled() {
        try {
            getPackageManager().getPackageInfo(ENGINE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void testServer() {
        final String host = normalizeHost(server.getText().toString());
        if (host.isEmpty()) {
            setStatus("Enter the self-hosted ID server first.");
            serverState.setText("Server: not configured");
            return;
        }
        serverState.setText("Server: checking " + host + "...");
        serverState.setTextColor(MUTED);
        setStatus("Testing RustDesk server ports...");

        executor.execute(() -> {
            boolean p16 = canConnect(host, 21116, 4000);
            boolean p17 = canConnect(host, 21117, 4000);
            runOnUiThread(() -> {
                serverState.setText("Server: " + host + " · 21116 " + (p16 ? "OK" : "FAILED") + " · 21117 " + (p17 ? "OK" : "FAILED"));
                serverState.setTextColor(p16 && p17 ? GREEN : AMBER);
                setStatus(p16 && p17 ? "Server ports are reachable." : "Server test completed with a failure.");
            });
        });
    }

    private static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(host.contains("://") ? host : "tcp://" + host);
            return uri.getHost() == null ? host : uri.getHost();
        } catch (Exception e) {
            return host;
        }
    }

    private void copyDiagnostics() {
        StringBuilder d = new StringBuilder();
        d.append("Ronin Remote Link v").append(APP_VERSION).append('\n');
        d.append("Build: ").append(BuildConfig.TABLET_BUILD ? "tablet" : "phone").append('\n');
        d.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        d.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        d.append(engineState.getText()).append('\n');
        d.append(serverState.getText()).append('\n');
        d.append("Remote ID configured: ").append(clean(id.getText().toString()).isEmpty() ? "no" : "yes").append('\n');
        d.append("Server configured: ").append(server.getText().toString().trim().isEmpty() ? "no" : "yes").append('\n');
        d.append("Server key configured: ").append(key.getText().toString().trim().isEmpty() ? "no" : "yes").append('\n');
        d.append("Saved password present: ").append(secrets.hasSecret() ? "yes" : "no").append('\n');
        d.append("Require unlock: ").append(requireUnlock.isChecked() ? "yes" : "no");

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Ronin Remote Link diagnostics", d.toString()));
        setStatus("Diagnostics copied. Password and server key value were not included.");
    }

    private void openCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ENGINE_PACKAGE);
        if (launch == null) {
            showEngineMissing();
            return;
        }
        startActivity(launch);
    }

    private void showEngineMissing() {
        new AlertDialog.Builder(this)
                .setTitle("Remote engine required")
                .setMessage("Install the pinned RustDesk " + ENGINE_VERSION + " engine, then return to Ronin Remote Link.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open official download", (d, w) -> openEngineDownload())
                .show();
    }

    private void openEngineDownload() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(engineApkUrl())));
    }

    private String engineApkUrl() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0].toLowerCase(Locale.ROOT) : "";
        String asset;
        if (abi.contains("arm64")) asset = "rustdesk-1.4.9-aarch64-signed.apk";
        else if (abi.contains("armeabi") || abi.contains("armv7")) asset = "rustdesk-1.4.9-armv7-signed.apk";
        else if (abi.contains("x86_64")) asset = "rustdesk-1.4.9-x86_64-signed.apk";
        else asset = "rustdesk-1.4.9-universal-signed.apk";
        return "https://github.com/rustdesk/rustdesk/releases/download/1.4.9/" + asset;
    }

    private void save(boolean showStatus) {
        try {
            SharedPreferences p = getSharedPreferences("ronin_settings", MODE_PRIVATE);
            p.edit()
                    .putString("name", name.getText().toString().trim())
                    .putString("id", id.getText().toString().trim())
                    .putString("server", server.getText().toString().trim())
                    .putString("key", key.getText().toString().trim())
                    .putBoolean("remember", remember.isChecked())
                    .putBoolean("require_unlock", requireUnlock.isChecked())
                    .apply();

            String entered = password.getText().toString();
            if (remember.isChecked() && !entered.isEmpty()) {
                secrets.save(entered);
            } else if (!remember.isChecked()) {
                secrets.clear();
            }
            if (showStatus) setStatus("Device settings saved.");
        } catch (Exception e) {
            error("Could not save encrypted password: " + e.getMessage());
        }
    }

    private void load() {
        SharedPreferences p = getSharedPreferences("ronin_settings", MODE_PRIVATE);
        name.setText(p.getString("name", "Main Desktop"));
        id.setText(p.getString("id", ""));
        server.setText(p.getString("server", ""));
        key.setText(p.getString("key", ""));
        boolean r = p.getBoolean("remember", false);
        remember.setChecked(r);
        requireUnlock.setChecked(p.getBoolean("require_unlock", true));
        if (r && secrets.hasSecret()) password.setHint("Saved securely · leave blank to use saved password");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(" ", "").trim();
    }

    private void addLabelField(LinearLayout parent, String label, EditText input) {
        TextView l = text(label, 11, MUTED, true);
        l.setPadding(0, dp(4), 0, dp(5));
        parent.addView(l);
        parent.addView(input, matchWrap(0, dp(12)));
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(118, 124, 130));
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(FIELD);
        return e;
    }

    private Button action(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundColor(RED);
        b.setMinHeight(dp(48));
        return b;
    }

    private Button secondary(String label) {
        Button b = action(label);
        b.setBackgroundColor(Color.rgb(42, 46, 50));
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1f);
        p.setMargins(0, 0, dp(8), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String s) {
        status.setText(s);
        status.setTextColor(MUTED);
    }

    private void error(String s) {
        setStatus("Error: " + s);
        new AlertDialog.Builder(this)
                .setTitle("Ronin Remote Link")
                .setMessage(s)
                .setPositiveButton("OK", null)
                .show();
    }
}
