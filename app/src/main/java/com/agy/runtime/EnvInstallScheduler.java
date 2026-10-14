package com.agy.runtime;

import android.util.Log;

import com.agy.install.EnvBundleManifest;
import com.agy.install.EnvInstaller;
import com.agy.install.SeedPackageInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 2: extract the bionic toolchain and regenerate runtime/bin bridges on a
 * daemon thread, in parallel with core startup. Best-effort; a failed attempt
 * clears the guard so a later retry can run again.
 *
 * A failure here is not fatal for the core, so it must not be silent either:
 * the outcome is appended to {@code files/logs/env.log}, which the on-device
 * diagnostics export ("Copy Logs") includes. Without the env assets (a missing
 * or stale assets/env/manifest.json, for example) {@code files/usr} is never
 * created and the device keeps only the base runtime.
 */
public final class EnvInstallScheduler {

    private static final String TAG = "EnvInstallScheduler";
    /** Bounded diagnostic trail surfaced by com.agy.ui.splash.LogExporter. */
    private static final String LOG_NAME = "env.log";
    private static final long MAX_LOG_BYTES = 1L << 20;

    private EnvInstallScheduler() {
    }

    public static void schedule(final RuntimeManager rm, final AtomicBoolean started) {
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "env install already started; skipping duplicate");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean fresh = EnvInstaller.install(rm.appContext());
                    EnvBundleManifest manifest = EnvBundleManifest.load(rm.appContext().getAssets());
                    boolean seeded = SeedPackageInstaller.installDefaults(
                            rm.appContext(), EnvInstaller.prefix(rm.appContext()), manifest.seedPackages);
                    // The core is glibc and must not inherit bionic's LD_* vars,
                    // so bridges set the bionic env for each child only. Seeded
                    // packages can add commands, so refresh the bridge inventory after them.
                    ShellBridgeInstaller.install(rm, fresh || seeded);
                    record(rm, "env ready; bundle=" + manifest.bundleId
                            + " fresh=" + fresh + " seeded=" + seeded
                            + "; runtime/bin bridges refreshed");
                    Log.i(TAG, "env ready; bundle=" + manifest.bundleId
                            + " seeded=" + seeded + "; runtime/bin bridges refreshed");
                } catch (Throwable e) {
                    started.set(false);
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    record(rm, "env install failed (" + e.getClass().getSimpleName() + "): " + reason
                            + "; core continues without bionic tools");
                    Log.e(TAG, "env install failed; core continues without bionic tools: "
                            + reason, e);
                }
            }
        }, "EnvInstallThread");
        thread.setDaemon(true);
        thread.start();
    }

    /** Appends one timestamped outcome line; diagnostics must never break the install. */
    private static void record(RuntimeManager rm, String message) {
        try {
            File dir = new File(rm.appContext().getFilesDir(), "logs");
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return;
            }
            File log = new File(dir, LOG_NAME);
            if (log.isFile() && log.length() > MAX_LOG_BYTES) {
                log.delete();
            }
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            Log.w(TAG, "cannot append " + LOG_NAME + ": " + e.getMessage());
        }
    }
}
