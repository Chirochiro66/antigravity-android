package com.agy.ui.splash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeConfig;
import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Collects diagnostics and core logs for user export and debugging. */
public final class LogExporter {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LOG_BYTES = 300_000;

    private LogExporter() {
    }

    public static String collectDiagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Antigravity Diagnostics ===\n");
        sb.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        String verName = RuntimeConfig.APP_VERSION_NAME;
        long verCode = RuntimeConfig.APP_VERSION_CODE;
        if (context != null) {
            try {
                android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                verName = pi.versionName;
                verCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception ignored) {
            }
        }
        sb.append("App Version: ").append(verName).append(" (versionCode ").append(verCode).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android OS: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Supported ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        sb.append("Last Error: ").append(CoreRuntimeState.lastError != null
                ? SensitiveDataRedactor.redactLine(CoreRuntimeState.lastError) : "none").append("\n");
        sb.append("Server URL: ").append(CoreRuntimeState.serverUrl != null
                ? SensitiveDataRedactor.safeUrl(CoreRuntimeState.serverUrl) : "none").append("\n");
        sb.append("\n=== Core Log (files/logs/core.log) ===\n");

        if (context == null) {
            sb.append("(context is null; cannot locate log file)\n");
            return sb.toString();
        }

        appendLogFile(sb, new File(context.getFilesDir(), "logs/core.log"), "logs/core.log");
        // The bionic prefix install runs on a background thread and is not fatal
        // for the core, so its outcome is only visible here (or in logcat).
        sb.append("\n=== Env Install Log (files/logs/env.log) ===\n");
        appendLogFile(sb, new File(context.getFilesDir(), "logs/env.log"), "logs/env.log");
        return sb.toString();
    }

    /** Appends the tail of a log file with each line redacted; never throws. */
    private static void appendLogFile(StringBuilder sb, File logFile, String label) {
        if (!logFile.exists() || logFile.length() == 0) {
            sb.append("(").append(label).append(" is empty or does not exist)\n");
            return;
        }
        try {
            long len = logFile.length();
            long skipBytes = Math.max(0, len - MAX_LOG_BYTES);
            try (FileInputStream fis = new FileInputStream(logFile)) {
                if (skipBytes > 0) {
                    long skipped = fis.skip(skipBytes);
                    sb.append("... [truncated ").append(skipped).append(" bytes; showing last ")
                            .append(len - skipped).append(" bytes of ").append(len).append(" total] ...\n");
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(fis, UTF8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(SensitiveDataRedactor.redactLine(line)).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("Error reading ").append(label).append(": ").append(e.getMessage()).append("\n");
        }
    }

    public static void copyToClipboard(Context context) {
        if (context == null) {
            return;
        }
        try {
            String report = collectDiagnostics(context);
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("Antigravity Logs", report);
                cm.setPrimaryClip(clip);
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Failed to copy logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static void saveAndShare(Context context) {
        if (context == null) {
            return;
        }
        try {
            String report = collectDiagnostics(context);
            File savedFile = null;

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                File target = new File(downloadsDir, "antigravity-core.log");
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), UTF8)) {
                    writer.write(report);
                    savedFile = target;
                } catch (Exception ignored) {
                }
            }

            if (savedFile == null) {
                File direct = new File("/storage/emulated/0/Download/antigravity-core.log");
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(direct), UTF8)) {
                    writer.write(report);
                    savedFile = direct;
                } catch (Exception ignored) {
                }
            }

            if (savedFile == null) {
                File appExternal = context.getExternalFilesDir(null);
                if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
                    File target = new File(appExternal, "antigravity-core.log");
                    try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), UTF8)) {
                        writer.write(report);
                        savedFile = target;
                    } catch (Exception ignored) {
                    }
                }
            }

            if (savedFile != null) {
                Toast.makeText(context, "Logs saved to " + savedFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "Could not write logs to Downloads", Toast.LENGTH_SHORT).show();
            }

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Antigravity Logs");
            if (report.length() < MAX_LOG_BYTES) {
                sendIntent.putExtra(Intent.EXTRA_TEXT, report);
            }
            sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(sendIntent, "Share logs");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(context, "Failed to export logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
