package me.aap.fermata.ui.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import java.text.DateFormat;
import java.util.Date;
import me.aap.fermata.BuildConfig;

/** Local diagnostics only. No network, logcat, account data, serials, URLs or notes. */
public final class RonanDiagnostics {
    public static final String AA = "com.google.android.projection.gearhead";
    private static final String STORE = "ronan_diagnostics";
    private RonanDiagnostics() { }

    public static void serviceCreated(Context context, String service) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putLong("service." + service, System.currentTimeMillis()).apply();
    }

    public static void surface(Context context, int width, int height) {
        if (width <= 0 || height <= 0) return;
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putInt("width", width).putInt("height", height)
                .putLong("surface_at", System.currentTimeMillis()).apply();
    }

    public static String surfaceSummary(Context context) {
        SharedPreferences p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        long when = p.getLong("surface_at", 0);
        if (when == 0) return "No car surface has been reported to this build yet.";
        return p.getInt("width", 0) + " × " + p.getInt("height", 0) + " px\nLast reported: "
                + stamp(when) + "\nThis is the app's available surface, not the whole physical screen. "
                + "A saved report is not proof of a current connection.";
    }

    public static String installer(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= 30)
                return value(pm.getInstallSourceInfo(context.getPackageName()).getInstallingPackageName());
            return value(pm.getInstallerPackageName(context.getPackageName()));
        } catch (Exception e) { return "Unavailable (" + e.getClass().getSimpleName() + ")"; }
    }

    public static boolean accessibilityEnabled(Context context) {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return false;
        ComponentName expected = new ComponentName(context.getPackageName(),
                "me.aap.fermata.auto.AccessibilityEventDispatcherService");
        for (String s : services.split(":"))
            if (expected.equals(ComponentName.unflattenFromString(s))) return true;
        return false;
    }

    public static String report(Context context) {
        StringBuilder out = new StringBuilder("Ronan Drive — local connection report\n");
        out.append("Generated: ").append(stamp(System.currentTimeMillis())).append('\n');
        out.append("App: ").append(BuildConfig.VERSION_NAME).append(" / ")
                .append(BuildConfig.VERSION_CODE).append('\n');
        out.append("Package: ").append(context.getPackageName()).append('\n');
        out.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" / API ")
                .append(Build.VERSION.SDK_INT).append('\n');
        out.append("Android Auto: ").append(version(context, AA)).append('\n');
        out.append("Installed by: ").append(installer(context)).append('\n');
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                out.append("Installation initiated by: ").append(value(context.getPackageManager()
                        .getInstallSourceInfo(context.getPackageName()).getInitiatingPackageName())).append('\n');
            } catch (Exception e) { out.append("Installation initiator: unavailable\n"); }
        }
        out.append("Overlay permission: ").append(Settings.canDrawOverlays(context)).append('\n');
        out.append("Accessibility service enabled: ").append(accessibilityEnabled(context)).append('\n');
        out.append("Screen sharing: permission is requested when projection starts; not assumed granted\n");
        out.append("Root preference: ").append(MainActivityPrefs.get()
                .getBooleanPref(MainActivityPrefs.RONAN_ALLOW_ROOT)).append(" (no root check executed)\n");
        out.append("Fit mode: ").append(MainActivityPrefs.get()
                .getIntPref(MainActivityPrefs.RONAN_MIRROR_SCALE)).append('\n');
        out.append("\nPACKAGE REGISTRATION\n");
        for (String s : new String[]{"CarService", "MirrorService", "MirrorServiceFS"})
            out.append(s).append(": ").append(serviceStatus(context, s)).append('\n');
        out.append("\nLAST LOCAL EVENTS\n");
        SharedPreferences p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        for (String s : new String[]{"CarService", "MirrorService", "MirrorServiceFS"})
            out.append(s).append(" created: ").append(stamp(p.getLong("service." + s, 0))).append('\n');
        out.append(surfaceSummary(context)).append('\n');
        out.append("\nIMPORTANT\n")
                .append("Service registration is not proof Android Auto accepts the app.\n")
                .append("This app cannot read Android Auto's private allowlist or launcher decision.\n")
                .append("An installer-name record is not a security certificate or proof of the cause.\n")
                .append("No browsing history, URLs, account tokens, serials, session notes or raw logs are included.\n")
                .append("Nothing is sent automatically. Copy/share only on your request.\n");
        return out.toString();
    }

    public static String serviceStatus(Context context, String shortName) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName name = new ComponentName(context.getPackageName(), "me.aap.fermata.auto." + shortName);
            ServiceInfo info = pm.getServiceInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS);
            int setting = pm.getComponentEnabledSetting(name);
            boolean enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    ? info.enabled : setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            return "present; " + (enabled ? "enabled" : "DISABLED") + "; exported=" + info.exported;
        } catch (Exception e) { return "unavailable (" + e.getClass().getSimpleName() + ")"; }
    }

    private static String version(Context context, String pkg) {
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(pkg, 0);
            return p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (Exception e) { return "not installed or not visible to this app"; }
    }
    private static String value(String s) { return s == null ? "none reported" : s; }
    private static String stamp(long time) {
        return time == 0 ? "not observed" : DateFormat.getDateTimeInstance().format(new Date(time));
    }
}
