package au.com.roningroup.remotelink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
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

public class MainActivity extends Activity {
    private static final String ENGINE_PACKAGE = "com.carriez.flutter_hbb";
    private static final String ENGINE_APK =
            "https://github.com/rustdesk/rustdesk/releases/download/1.4.9/rustdesk-1.4.9-aarch64-signed.apk";
    private static final int BG = Color.rgb(14, 16, 18);
    private static final int PANEL = Color.rgb(24, 27, 30);
    private static final int FIELD = Color.rgb(34, 38, 42);
    private static final int MUTED = Color.rgb(166, 172, 178);
    private static final int RED = Color.rgb(197, 45, 50);

    private EditText id;
    private EditText server;
    private EditText key;
    private EditText password;
    private CheckBox remember;
    private TextView status;
    private SecretStore secrets;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        secrets = new SecretStore(this);
        setContentView(buildUi());
        load();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        boolean large = getResources().getConfiguration().smallestScreenWidthDp >= 600 || BuildConfig.TABLET_BUILD;
        int side = dp(large ? 44 : 22);

        LinearLayout root = vertical();
        root.setPadding(side, dp(24), side, dp(28));
        scroll.addView(root);

        TextView title = text("RONIN REMOTE LINK", large ? 30 : 25, Color.WHITE, true);
        root.addView(title);
        TextView sub = text(
                BuildConfig.TABLET_BUILD ? "TABLET CONTROL · v0.1.0" : "MOBILE CONTROL · v0.1.0",
                12, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        LinearLayout card = vertical();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(PANEL);
        root.addView(card, matchWrap(dp(0), dp(0)));

        id = field("Main desktop ID");
        server = field("Self-hosted ID server (e.g. remote.example.com)");
        key = field("Server public key");
        password = field("Access password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        addLabelField(card, "MAIN DESKTOP", id);
        addLabelField(card, "ID SERVER", server);
        addLabelField(card, "SERVER PUBLIC KEY", key);
        addLabelField(card, "ACCESS PASSWORD", password);

        remember = new CheckBox(this);
        remember.setText("Remember password encrypted on this device");
        remember.setTextColor(MUTED);
        remember.setButtonTintList(android.content.res.ColorStateList.valueOf(RED));
        remember.setPadding(0, dp(2), 0, dp(12));
        card.addView(remember);

        LinearLayout actions = large ? horizontal() : vertical();
        Button remote = action("REMOTE DESKTOP");
        Button files = action("FILES");
        Button save = action("SAVE DEVICE");
        remote.setOnClickListener(v -> launch(false));
        files.setOnClickListener(v -> launch(true));
        save.setOnClickListener(v -> save());

        actions.addView(remote, large ? weight() : matchWrap(0, dp(8)));
        actions.addView(files, large ? weight() : matchWrap(0, dp(8)));
        actions.addView(save, large ? weight() : matchWrap(0, dp(8)));
        card.addView(actions);

        LinearLayout utility = large ? horizontal() : vertical();
        Button core = secondary("OPEN REMOTE ENGINE");
        Button install = secondary("INSTALL PINNED ENGINE");
        core.setOnClickListener(v -> openCore());
        install.setOnClickListener(v -> openEngineDownload());
        utility.addView(core, large ? weight() : matchWrap(0, dp(8)));
        utility.addView(install, large ? weight() : matchWrap(0, dp(8)));
        root.addView(utility, matchWrap(dp(0), dp(14)));

        status = text("Ready.", 12, MUTED, false);
        status.setPadding(2, dp(14), 2, 0);
        root.addView(status);

        TextView note = text(
                "Phase 1 uses an unmodified RustDesk remote engine. Ronin Remote Link sends connection requests directly to that installed package and does not expose an RDP/VNC port.",
                11, Color.rgb(135, 141, 147), false);
        note.setPadding(2, dp(14), 2, 0);
        root.addView(note);
        return scroll;
    }

    private void launch(boolean fileTransfer) {
        try {
            save();
            String peer = clean(id.getText().toString());
            if (peer.length() < 3) throw new IllegalArgumentException("Enter the main desktop ID.");

            String host = server.getText().toString().trim();
            String pub = key.getText().toString().trim();
            String pass = password.getText().toString();

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
            setStatus(fileTransfer ? "Opening file transfer..." : "Opening remote desktop...");
        } catch (ActivityNotFoundException e) {
            showEngineMissing();
        } catch (Exception e) {
            error(e.getMessage());
        }
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
                .setMessage("Install the pinned RustDesk 1.4.9 ARM64 engine, then return to Ronin Remote Link.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open official download", (d, w) -> openEngineDownload())
                .show();
    }

    private void openEngineDownload() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ENGINE_APK)));
    }

    private void save() {
        try {
            SharedPreferences p = getSharedPreferences("ronin_settings", MODE_PRIVATE);
            p.edit()
                    .putString("id", id.getText().toString().trim())
                    .putString("server", server.getText().toString().trim())
                    .putString("key", key.getText().toString().trim())
                    .putBoolean("remember", remember.isChecked())
                    .apply();

            if (remember.isChecked() && !password.getText().toString().isEmpty()) {
                secrets.save(password.getText().toString());
            } else if (!remember.isChecked()) {
                secrets.clear();
            }
            setStatus("Device settings saved.");
        } catch (Exception e) {
            error("Could not save encrypted password: " + e.getMessage());
        }
    }

    private void load() {
        SharedPreferences p = getSharedPreferences("ronin_settings", MODE_PRIVATE);
        id.setText(p.getString("id", ""));
        server.setText(p.getString("server", ""));
        key.setText(p.getString("key", ""));
        boolean r = p.getBoolean("remember", false);
        remember.setChecked(r);
        if (r) {
            try { password.setText(secrets.load()); }
            catch (Exception e) { setStatus("Saved password could not be decrypted; enter it again."); }
        }
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
