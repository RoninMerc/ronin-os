"""Focused privacy hardening for the private Ronan Drive build. GPL-3.0."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / 'fermata/src/main/java/me/aap/fermata/provider/RonanPublishedUris.java'
p.write_text('''package me.aap.fermata.provider;

import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import me.aap.fermata.FermataApplication;

/** Only explicitly published artwork/exports may be opened through the external provider. GPL-3.0. */
final class RonanPublishedUris {
    private static final String PREFS = "ronan_published_media_uris";
    private static String key(Uri uri) {
        try {
            return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(
                    uri.toString().getBytes(StandardCharsets.UTF_8)), Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static Uri publish(Uri uri) {
        var preferences = FermataApplication.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = key(uri);
        if (!preferences.getBoolean(key, false)) preferences.edit().putBoolean(key, true).apply();
        return uri;
    }
    static void requirePublished(Context context, Uri uri) {
        if (Binder.getCallingUid() == Process.myUid()) return;
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(uri), false)) return;
        throw new SecurityException("This media URI has not been published by Ronan Drive");
    }
    static File imageFile(Context context, Uri uri) throws FileNotFoundException {
        try {
            if (uri.getPath() == null) throw new FileNotFoundException("Missing image path");
            File file = new File(uri.getPath()).getCanonicalFile();
            if (Binder.getCallingUid() != Process.myUid()) {
                File data = context.getDataDir().getCanonicalFile();
                File deviceData = context.createDeviceProtectedStorageContext().getDataDir().getCanonicalFile();
                boolean internal = inside(file, data) || inside(file, deviceData);
                boolean artwork = inside(file, new File(context.getCacheDir(), "images").getCanonicalFile())
                        || inside(file, new File(context.getCacheDir(), "icons").getCanonicalFile());
                if (internal && !artwork) throw new FileNotFoundException("Private app data is not exported");
            }
            return file;
        } catch (IOException error) {
            FileNotFoundException e = new FileNotFoundException("Image path is not available for sharing");
            e.initCause(error);
            throw e;
        }
    }
    private static boolean inside(File file, File parent) {
        return file.equals(parent) || file.getPath().startsWith(parent.getPath() + File.separator);
    }
}
''')
p = root / 'fermata/src/main/java/me/aap/fermata/provider/FermataContentProvider.java'
s = p.read_text()
s = s.replace('if (u.startsWith(IMG_PREF)) return uri;', 'if (u.startsWith(IMG_PREF)) return RonanPublishedUris.publish(uri);')
s = s.replace('if (u.startsWith(ADDON_PREF)) return uri;', 'if (u.startsWith(ADDON_PREF)) return RonanPublishedUris.publish(uri);')
s = s.replace('return Uri.parse(IMG_PREF + enc);', 'return RonanPublishedUris.publish(Uri.parse(IMG_PREF + enc));')
s = s.replace('return Uri.parse(u);', 'return RonanPublishedUris.publish(Uri.parse(u));')
s = s.replace('u.getBytes(US_ASCII), URL_SAFE)', 'u.getBytes(US_ASCII), URL_SAFE | Base64.NO_WRAP)')
assert s.count('UriInfo info = UriInfo.parse(uri);') == 3
s = s.replace('UriInfo info = UriInfo.parse(uri);', 'RonanPublishedUris.requirePublished(getContext(), uri);\n\t\tUriInfo info = UriInfo.parse(uri);')
s = s.replace('ParcelFileDescriptor.open(new File(u.toString().substring(6)), MODE_READ_ONLY)', 'ParcelFileDescriptor.open(RonanPublishedUris.imageFile(getContext(), u), MODE_READ_ONLY)')
p.write_text(s)
p = root / 'fermata/src/auto/AndroidManifest.xml'
s = p.read_text().replace('android:name="android.hardware.type.automotive"\n        android:required="true"', 'android:name="android.hardware.type.automotive"\n        android:required="false"')
s = s.replace('android:name="me.aap.fermata.auto.ProjectionActivity"\n            android:excludeFromRecents="true"\n            android:exported="true"', 'android:name="me.aap.fermata.auto.ProjectionActivity"\n            android:excludeFromRecents="true"\n            android:exported="false"')
s = s.replace('    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n','')
s = s.replace('    <uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />\n','')
p.write_text(s)
p = root / 'fermata/src/auto/java/me/aap/fermata/auto/AdaptiveMirrorRenderer.java'
s = p.read_text().replace('''                detachWindow();
            }
        }, handler);''', '''                try { detachWindow(); }
                catch (RuntimeException lostContext) { Log.e(lostContext, "Ronan EGL context lost; reconnect mirror"); }
            }
        }, handler);''')
p.write_text(s)
p = root / 'fermata/src/main/res/values/strings.xml'
s = p.read_text().replace('<H1 align="center">Fermata Media Player</H1>', '<H1 align="center">Ronan Drive</H1>\n        <p>Private test fork with adaptive vehicle-screen fitting. Based on Fermata Media Player, GPL-3.0.</p>')
s = s.replace('<p><b>Author:</b> Andrey Pavlenko</p>', '<p><b>Original Fermata author:</b> Andrey Pavlenko</p>')
p.write_text(s)
# Disable the ONNX 1DS auto-start provider in both the base app and the dynamic feature.
# Keep the translation engine itself. OrtEnvironment has a supported setTelemetry API.
for rel in ['fermata/src/main/AndroidManifest.xml','modules/opusmt/src/main/AndroidManifest.xml']:
    p = root / rel
    s = p.read_text()
    if 'xmlns:tools=' not in s:
        s = s.replace('<manifest ', '<manifest xmlns:tools="http://schemas.android.com/tools" ', 1)
    removal = '        <provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />\n'
    if '</application>' in s:
        s = s.replace('</application>', removal + '    </application>', 1)
    else:
        s = s.replace('</manifest>', '    <application>\n' + removal + '    </application>\n</manifest>', 1)
    p.write_text(s)
p = root / 'modules/opusmt/src/main/java/me/aap/fermata/opusmt/OpusMtModel.java'
s = p.read_text()
old = 'private static final OrtEnvironment env = OrtEnvironment.getEnvironment();'
new = '''private static final OrtEnvironment env = createPrivateEnvironment();

    private static OrtEnvironment createPrivateEnvironment() {
        var environment = OrtEnvironment.getEnvironment();
        try {
            environment.setTelemetry(false);
        } catch (ai.onnxruntime.OrtException error) {
            throw new IllegalStateException("Ronan Drive could not disable ONNX telemetry", error);
        }
        return environment;
    }'''
assert old in s
p.write_text(s.replace(old,new,1))
p = root / 'RONAN-DRIVE-README.md'
p.write_text(p.read_text() + '''
## Additional focused review
External image/export provider requests are restricted to URIs the app explicitly published.
Private app data is not exported as artwork; canonical paths are checked, with only the designated artwork cache allowed internally.
The screen-projection permission activity is private to the app. Android Auto service entrypoints remain available.
Unused REQUEST_INSTALL_PACKAGES and REQUEST_DELETE_PACKAGES permissions have been removed; the fork never uninstalls Fermata or invokes its old updater.
ONNX Runtime automatic telemetry initialization is removed from the base and translation-feature manifests. ONNX telemetry is also explicitly disabled when the local translation engine is initialized. The translation feature is retained.
Other upstream SDKs and websites remain; these targeted changes are not a complete network/security audit or a claim of zero outbound data.
''')
(root / 'RONAN-HARDEN.py').write_text(Path(__file__).read_text())
print('Applied provider, permission-activity, renderer lifecycle and ONNX telemetry hardening')
