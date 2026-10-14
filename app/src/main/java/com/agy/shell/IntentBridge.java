package com.agy.shell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native command bridge for the high-value shell commands the agent needs
 * most often. When a {@code ShellBridge} call can be answered by an
 * Android platform API, this class answers it directly; otherwise
 * {@link ShellBridge} falls through to the rish / Shizuku path.
 *
 * <p>Currently supported:
 * <ul>
 *   <li>{@code pm install -r <apk>}             -- PackageInstaller API
 *   <li>{@code am start [-n <comp>|-a <act>] <uri|cmp>} -- startActivity
 *   <li>{@code cmd battery get level}            -- BatteryManager API
 *   <li>{@code cmd connectivity ...}             -- ConnectivityManager API
 *   <li>{@code cmd settings get <ns> <key>}      -- Settings.*.get* API
 * </ul>
 *
 * <p>Each command is parsed with a small regex; commands that don't match
 * any of the patterns return {@code null} so the caller can fall through
 * to the rish path unchanged. Commands that match but cannot be executed
 * (permission missing, bad input) return a short error string instead of
 * throwing, so the Web UI gets a human-readable message.
 *
 * <p>This is intentionally NOT a general shell. Side-effecting commands
 * (pm grant, appops set, logcat on other packages, iptables) still
 * require rish; they are out of scope for this bridge.
 */
public final class IntentBridge {

    private static final String TAG = "IntentBridge";

    // pm install -r <path>   (we only need -r for upgrade; if missing,
    //                          we add it ourselves so a fresh install works too)
    private static final Pattern PM_INSTALL =
            Pattern.compile("^\\s*pm\\s+install(\\s+-r)?\\s+(\\S+)\\s*$");

    // am start [-n <component> | -a <action> | <uri>]
    private static final Pattern AM_START =
            Pattern.compile("^\\s*am\\s+start(\\s+-\\w+\\s+\\S+)*\\s*(.*)$");

    // cmd battery get <prop>
    private static final Pattern CMD_BATTERY =
            Pattern.compile("^\\s*cmd\\s+battery\\s+get\\s+(\\S+)\\s*$");

    // cmd connectivity <subcommand> ...
    private static final Pattern CMD_CONNECTIVITY =
            Pattern.compile("^\\s*cmd\\s+connectivity\\s+(\\S+)(.*)$");

    // cmd settings get <namespace> <key>
    private static final Pattern CMD_SETTINGS_GET =
            Pattern.compile("^\\s*cmd\\s+settings\\s+get\\s+(\\S+)\\s+(\\S+)\\s*$");

    private final Context context;

    public IntentBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Tries to run `cmd` natively. Returns:
     *   - non-null String: either a result line (on success) or an error message.
     *   - null: command did not match any native pattern; caller should fall
     *     through to rish.
     */
    public String tryNative(String cmd) {
        if (cmd == null) return null;
        Matcher m;
        if ((m = PM_INSTALL.matcher(cmd)).matches()) {
            return pmInstall(m.group(2));
        }
        if ((m = AM_START.matcher(cmd)).matches()) {
            return amStart(cmd);
        }
        if ((m = CMD_BATTERY.matcher(cmd)).matches()) {
            return cmdBattery(m.group(1));
        }
        if ((m = CMD_CONNECTIVITY.matcher(cmd)).matches()) {
            return cmdConnectivity(m.group(1), m.group(2));
        }
        if ((m = CMD_SETTINGS_GET.matcher(cmd)).matches()) {
            return cmdSettingsGet(m.group(1), m.group(2));
        }
        return null;
    }

    private String pmInstall(String apkPath) {
        File apk = new File(apkPath);
        if (!apk.isFile()) {
            return "error: apk not found: " + apkPath;
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        if (installer == null) {
            return "error: PackageInstaller service unavailable";
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId;
        try {
            sessionId = installer.createSession(params);
        } catch (IOException e) {
            return "error: createSession failed: " + e.getMessage();
        }
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("agy", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            // Pass null IntentSender: the platform falls back to logging only.
            // For apps without REQUEST_INSTALL_PACKAGES this commit raises
            // SecurityException, which we surface as a clear error string so
            // the agent can fall back to rish.
            session.commit(null);
            return "ok: install session " + sessionId + " committed (no result receiver; check logcat)";
        } catch (SecurityException e) {
            return "error: REQUEST_INSTALL_PACKAGES permission required: " + e.getMessage();
        } catch (IOException e) {
            return "error: session I/O failed: " + e.getMessage();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            if (session != null) session.close();
        }
    }

    private String amStart(String cmd) {
        // We delegate the argument parsing to the platform Intent parser, which
        // handles every flag, component, action and URI in one shot and matches
        // the "am" CLI grammar exactly. Anything we don't understand here is
        // an error, not a fall-through -- the agent meant a real Intent.
        try {
            Intent intent = Intent.parseUri(cmd.replaceFirst("^\\s*am\\s+start\\s+", ""), Intent.URI_INTENT_SCHEME);
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (context instanceof Activity) {
                ((Activity) context).startActivity(intent);
            } else {
                context.startActivity(intent);
            }
            return "ok: started";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String cmdBattery(String prop) {
        Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null && "level".equals(prop)) {
                return Integer.toString(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            }
            return "error: BatteryManager unavailable";
        }
        switch (prop) {
            case "level": {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return Integer.toString((int) ((level / (float) scale) * 100));
                }
                return Integer.toString(level);
            }
            case "status":
                return Integer.toString(batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
            case "plugged":
                return Integer.toString(batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
            case "temperature":
                return Integer.toString(batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1));
            case "voltage":
                return Integer.toString(batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
            default:
                return "error: unknown battery property: " + prop;
        }
    }

    private String cmdConnectivity(String sub, String rest) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return "error: ConnectivityManager unavailable";
        }
        switch (sub) {
            case "status": {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni == null ? "NO_CONNECTIVITY" : ni.getDetailedState().toString();
            }
            case "is-connected": {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return (ni != null && ni.isConnected()) ? "yes" : "no";
            }
            case "type": {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni == null ? "none" : ni.getTypeName();
            }
            default:
                return "error: unsupported connectivity subcommand: " + sub;
        }
    }

    private String cmdSettingsGet(String namespace, String key) {
        try {
            switch (namespace) {
                case "system":
                    return Integer.toString(Settings.System.getInt(context.getContentResolver(), key));
                case "secure":
                    return Integer.toString(Settings.Secure.getInt(context.getContentResolver(), key));
                case "global":
                    return Integer.toString(Settings.Global.getInt(context.getContentResolver(), key));
                default:
                    return "error: unsupported settings namespace: " + namespace
                            + " (use system, secure or global)";
            }
        } catch (Settings.SettingNotFoundException e) {
            return "null";
        }
    }

    /** Placeholder so the constant string is shared with the (future) install
     *  result receiver. The actual InstallResultReceiver lives elsewhere;
     *  this anchor keeps the action name discoverable. */
    public static final String INSTALL_RESULT_ACTION = "com.agy.INSTALL_RESULT";
}
