package com.agy.bridge;

import android.content.Context;

import com.agy.shell.IntentBridge;
import com.agy.shell.ShizukuBridge;

/**
 * Shizuku shell access with native-first dispatch.
 *
 * <p>{@link #exec(String)} tries {@link IntentBridge#tryNative(String)}
 * first and only falls through to rish / Shizuku for commands the native
 * bridge does not cover. When rish is unavailable, the call returns a
 * clear error string instead of silently returning {@code null}, so the
 * Web UI can surface a meaningful message to the user.
 *
 * <p><b>Wiring status.</b> No current Java caller instantiates this
 * class. The Web UI does not have a {@code @JavascriptInterface exec()}
 * path today (AndroidBridge intentionally does not expose arbitrary
 * shell to embedded frames). The class is kept ready for the next
 * feature that does need a shell sink (e.g. an in-app agent chat UI
 * that wants to run {@code pip / git / pkg} through rish while
 * keeping {@code pm install / am start} rish-free via
 * {@link IntentBridge}). Until then, the live rish path is via
 * {@code NotificationBridge} (fallback for the bigtext notification
 * icon).
 */
public final class ShellBridge {

    private final IntentBridge intent;
    private final ShizukuBridge shizuku;

    public ShellBridge(Context context, ShizukuBridge shizuku) {
        this.intent = new IntentBridge(context);
        this.shizuku = shizuku;
    }

    /**
     * Runs a command, trying the native Android API path first and falling
     * through to rish / Shizuku when the command isn't covered natively.
     *
     * <p>The native path currently handles: {@code pm install},
     * {@code am start}, {@code cmd battery}, {@code cmd connectivity},
     * {@code cmd settings get}. Everything else (arbitrary shell,
     * {@code pm grant}, {@code appops set}, {@code logcat} on other
     * packages, etc.) still requires rish.
     *
     * <p>Returns:
     * <ul>
     *   <li>native result string -- when the command was answered natively</li>
     *   <li>rish exec output   -- when the command was answered via rish</li>
     *   <li>an "error: rish unavailable ..." string -- when neither path
     *       is available (no native match AND rish not running)</li>
     * </ul>
     */
    public String exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return "error: empty command";
        }
        // 1) Try the native path. The bridge answers with either a result
        //    line, a short error string, or null when the command does not
        //    match any native pattern (caller should fall through to rish).
        String nativeResult = intent.tryNative(cmd);
        if (nativeResult != null) {
            return nativeResult;
        }
        // 2) Fall through to rish / Shizuku. Without it, the user gets a
        //    clear error string instead of a silent null.
        if (shizuku == null || !shizuku.isAvailable()) {
            return "error: rish unavailable (Shizuku not running). "
                    + "The agent needs Shizuku to run this command; only "
                    + "pm install / am start / cmd battery / cmd connectivity / "
                    + "cmd settings get work without it.";
        }
        return shizuku.exec(cmd);
    }

    public boolean available() {
        return shizuku != null && shizuku.isAvailable();
    }
}
