package com.agy.bridge;

import com.agy.shell.ShizukuBridge;

/** Shizuku shell access exposed to the Web UI. */
public final class ShellBridge {

    private final ShizukuBridge shizuku;

    public ShellBridge(ShizukuBridge shizuku) {
        this.shizuku = shizuku;
    }

    /**
     * Runs a command via the Shizuku `rish` bridge and returns combined
     * stdout/stderr.
     *
     * <p>When Shizuku is unavailable, returns a clear error string
     * starting with {@code "error: rish unavailable"} so the Web UI can
     * surface a meaningful message instead of an empty response. The
     * previous implementation silently returned null in that case, which
     * confused downstream callers that did not check the return value.
     */
    public String exec(String cmd) {
        if (shizuku == null || !shizuku.isAvailable()) {
            return "error: rish unavailable (Shizuku not running). "
                    + "The agent needs Shizuku to run arbitrary shell commands; "
                    + "without it, only the native Android API paths work.";
        }
        return shizuku.exec(cmd);
    }

    public boolean available() {
        return shizuku != null && shizuku.isAvailable();
    }
}
