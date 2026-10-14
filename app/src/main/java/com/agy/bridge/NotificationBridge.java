package com.agy.bridge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.agy.shell.ShizukuBridge;

/**
 * Notification bridge exposed to the Web UI / Agent runtime.
 *
 * <p>Posts go through the highest-available path:
 * <ol>
 *   <li>Native {@code NotificationManager} via {@code NotifyReceiver}
 *       (always available; the agent's primary path).</li>
 *   <li>Elevated {@code cmd notification post} via rish / Shizuku, if
 *       available. Used as a fallback only when the native path is
 *       unavailable (e.g. POST_NOTIFICATIONS not yet granted on
 *       Android 13+).</li>
 * </ol>
 *
 * <p>The previous version hard-required rish, so any agent action that
 * wanted to notify the user (agy-notify, agy-daemon task complete,
 * etc.) silently no-op'd when Shizuku was off. Now they always work.
 */
public final class NotificationBridge {

    private static final String TAG = "NotificationBridge";

    private final Context context;
    private final ShizukuBridge shizuku;

    public NotificationBridge(Context context, ShizukuBridge shizuku) {
        this.context = context.getApplicationContext();
        this.shizuku = shizuku;
    }

    /** Posts a notification. Returns true if at least one path was tried. */
    public boolean post(String title, String message, String tag) {
        if (title == null) title = "Antigravity";
        if (message == null) message = "";
        String cleanTag = tag != null ? tag : "agy_" + System.currentTimeMillis();

        // 1) Native broadcast path. Goes through NotifyReceiver -> platform
        //    NotificationManager. Works on every Android version we support,
        //    and survives Shizuku being absent.
        if (postNative(title, message, cleanTag)) {
            return true;
        }

        // 2) rish fallback for the rare case the native channel cannot be
        //    created (e.g. Android 13+ POST_NOTIFICATIONS denied, or a
        //    system policy that blocks broadcast receivers on the device).
        if (shizuku != null && shizuku.isAvailable()) {
            String safeTitle = title.replace("'", "'\\''");
            String safeMessage = message.replace("'", "'\\''");
            String safeTag = cleanTag.replace("'", "'\\''");
            String cmd = "cmd notification post -S bigtext -t '"
                    + safeTitle + "' '" + safeTag + "' '" + safeMessage + "'";
            shizuku.exec(cmd);
            return true;
        }
        Log.w(TAG, "no notification path available (native failed, rish unavailable)");
        return false;
    }

    /**
     * Posts a notification by sending a {@code com.agy.NOTIFY} broadcast
     * to {@code NotifyReceiver}. Returns true if the broadcast was sent
     * (the actual posting happens asynchronously in the receiver, which
     * is the right place to handle NotificationManager creation and the
     * SDK 33+ POST_NOTIFICATIONS check).
     */
    private boolean postNative(String title, String message, String tag) {
        try {
            Intent intent = new Intent("com.agy.NOTIFY");
            intent.setPackage(context.getPackageName());
            intent.putExtra("title", title);
            intent.putExtra("message", message);
            intent.putExtra("tag", tag);
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "native broadcast failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Cancels a previously-posted notification. Tries the native path
     * first; falls back to rish if the native one fails.
     */
    public boolean cancel(String tag) {
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        try {
            Intent intent = new Intent("com.agy.NOTIFY_CANCEL");
            intent.setPackage(context.getPackageName());
            intent.putExtra("tag", tag);
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "native cancel broadcast failed: " + t.getMessage());
        }
        if (shizuku != null && shizuku.isAvailable()) {
            String safeTag = tag.replace("'", "'\\''");
            shizuku.exec("cmd notification cancel '" + safeTag + "'");
            return true;
        }
        return false;
    }

    /**
     * Lists recently posted notifications. Returns a JSON-ish string the
     * Web UI can consume. The native path returns a list derived from the
     * active platform notifications for our channel; the rish path parses
     * {@code dumpsys notification --noredact}.
     */
    public String list() {
        String nativeList = listNative();
        if (nativeList != null) {
            return nativeList;
        }
        if (shizuku != null && shizuku.isAvailable()) {
            return shizuku.exec("dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 40");
        }
        return "[]";
    }

    @SuppressLint("NewApi")
    private String listNative() {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return null;
            }
            // Best-effort list. NotificationManager.getActiveNotifications is
            // a system-private API; we synthesize a simple JSON-ish summary
            // from the active status-bar entries that target our channel.
            StatusBarNotification[] active = nm.getActiveNotifications();
            if (active == null) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (StatusBarNotification sbn : active) {
                if (!context.getPackageName().equals(sbn.getPackageName())) {
                    continue;
                }
                if (!first) sb.append(',');
                first = false;
                String key = sbn.getKey();
                String title = "";
                String text = "";
                Notification n = sbn.getNotification();
                if (n != null && n.extras != null) {
                    CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence m = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (t != null) title = t.toString();
                    if (m != null) text = m.toString();
                }
                sb.append('{')
                  .append("\"key\":\"").append(key).append("\",")
                  .append("\"title\":\"").append(jsonEscape(title)).append("\",")
                  .append("\"text\":\"").append(jsonEscape(text)).append('"')
                  .append('}');
            }
            sb.append(']');
            return sb.toString();
        } catch (Throwable t) {
            Log.d(TAG, "native list failed: " + t.getMessage());
            return null;
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
