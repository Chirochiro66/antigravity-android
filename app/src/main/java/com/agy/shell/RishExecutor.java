package com.agy.shell;

import android.content.Context;

import com.agy.install.EnvPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;

/** Runs a shell command through the bundled rish bridge. */
public final class RishExecutor {

    private final Context appContext;
    private final RishLocator locator;

    public RishExecutor(Context context) {
        this.appContext = context.getApplicationContext();
        this.locator = new RishLocator(context);
    }

    /**
     * Runs `cmd` as shell via rish. Returns combined stdout/stderr; errors are text.
     *
     * <p>The shell is started in the AGY Bionic environment so the agent's
     * bare calls to `pkg`, `python`, `git` resolve:
     *   1. PREFIX is exported to the installed prefix.
     *   2. $PREFIX/bin and $PREFIX/libexec/pkg are prepended to PATH.
     *   3. $PREFIX/etc/profile is sourced (which exports SSL_CERT_FILE,
     *      GIT_SSL_CAINFO, AGY_APPLICATION_ID, LANG, etc.) but LD_PRELOAD
     *      and LD_LIBRARY_PATH are stripped because the bionic shell we are
     *      invoking cannot consume the glibc libs in $PREFIX/lib -- that
     *      would crash /system/bin/sh. (See CoreEnvironment for the
     *      longer writeup on why LD_PRELOAD is fatal for the bionic linker.)
     *   4. The original system PATH is re-appended so base utilities
     *      (ls, cat, env, ...) still resolve after the profile overwrites
     *      PATH="$PREFIX/bin" outright.
     */
    public String exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return "error: empty command";
        }
        File rish = locator.rishBinary();
        if (!rish.isFile()) {
            return "error: bundled rish not found at " + rish.getAbsolutePath();
        }
        if (!rish.canExecute()) {
            return "error: rish not executable: " + rish.getAbsolutePath()
                    + " (Shizuku may be unavailable)";
        }
        File prefix = EnvPaths.prefix(appContext);
        String prefixPath = prefix.getAbsolutePath();
        String qPrefix = quote(prefixPath);
        // Capture the original system PATH so we can re-append it after the
        // profile overwrites it. We do this with a parameter expansion that
        // is a no-op for sh that is not bash, and falls back to a static
        // /system/bin search list otherwise.
        // The fallback for $HOME is the parent of $PREFIX (i.e. the app files
        // dir). POSIX ${VAR:=default} inside double-quotes preserves spaces
        // in the default, so a path like "/path with space" stays one token.
        String shellWrapper =
                "_system_path=\"${PATH:-/system/bin:/system/xbin:/vendor/bin}\"\n"
                + "export PREFIX=" + qPrefix + "\n"
                + ": \"${HOME:=" + parentDir(prefixPath) + "}\"\n"
                + "export HOME\n"
                + "if [ -r " + quote(prefixPath + "/etc/profile") + " ]; then\n"
                + "  . " + quote(prefixPath + "/etc/profile") + " 2>/dev/null || true\n"
                + "fi\n"
                + "unset LD_PRELOAD LD_LIBRARY_PATH\n"
                + "export PATH=\"" + prefixPath + "/bin:" + prefixPath + "/libexec/pkg:$_system_path\"\n"
                + "env -u LD_LIBRARY_PATH " + cmd;
        ProcessBuilder pb = new ProcessBuilder(
                rish.getAbsolutePath(),
                "-c",
                shellWrapper);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("LD_LIBRARY_PATH");
        env.remove("LD_PRELOAD");
        env.put("RISH_APPLICATION_ID", appContext.getPackageName());
        env.put("PREFIX", prefixPath);
        pb.directory(appContext.getFilesDir());
        Process process = null;
        try {
            process = pb.start();
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            int code = process.waitFor();
            String text = out.toString().trim();
            if (code != 0 && text.isEmpty()) {
                return "error: rish exited " + code;
            }
            return text;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** POSIX-shell single-quote a string: wrap in '...' and escape embedded '. */
    private static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Parent dir of an absolute path, e.g. /data/user/0/com.agy/files/usr -> /data/user/0/com.agy/files. */
    private static String parentDir(String absolute) {
        int idx = absolute.lastIndexOf('/');
        return idx > 0 ? absolute.substring(0, idx) : "/";
    }
}
