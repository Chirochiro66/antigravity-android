package com.agy.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Handles in-app notification requests via the {@code com.agy.NOTIFY}
 * broadcast.
 *
 * <p>Used by {@code env/overlay/bin/agy-notify} as a rish-free fallback so
 * the agent can post status / progress / completion notifications to the
 * Android status bar even when Shizuku is unavailable. The platform
 * {@link NotificationManager} does the actual posting; we just translate
 * the broadcast extras into a {@link Notification.Builder} call.
 *
 * <p>Extras:
 * <ul>
 *   <li>{@code title}  -- string, defaults to "Antigravity"</li>
 *   <li>{@code message} -- string, required for post</li>
 *   <li>{@code tag}    -- string used as the notification id (so post-then-
 *       cancel maps to the same row)</li>
 *   <li>{@code subtitle} -- string, shown in bigtext summary if set</li>
 * </ul>
 *
 * <p>Cancel action: {@code com.agy.NOTIFY_CANCEL} with the same {@code tag}
 * extra removes the previously-posted notification.
 *
 * <p>Implementation note: this code uses only the platform
 * {@link android.app.Notification} API (no androidx) so it compiles with
 * just {@code android.jar} on the classpath, which is how the host
 * build's javac phase is wired (see build/phases/07-javac.sh).
 */
public class NotifyReceiver extends BroadcastReceiver {

    private static final String TAG = "NotifyReceiver";
    static final String CHANNEL_ID = "agy_default";
    static final String CHANNEL_NAME = "Antigravity";
    static final String CHANNEL_DESC = "Background activity, builds and task results.";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        String tag = intent.getStringExtra("tag");
        if (tag == null || tag.isEmpty()) {
            // Stable, unique-per-call id so we never collide with another call.
            tag = "agy_" + System.currentTimeMillis();
        }
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.w(TAG, "NotificationManager unavailable; dropping " + action);
            return;
        }
        ensureChannel(nm);
        if ("com.agy.NOTIFY_CANCEL".equals(action)) {
            nm.cancel(tag, stableId(tag));
            Log.d(TAG, "cancelled: " + tag);
            return;
        }
        if (!"com.agy.NOTIFY".equals(action)) {
            return;
        }
        String title = intent.getStringExtra("title");
        if (title == null || title.isEmpty()) {
            title = "Antigravity";
        }
        String message = intent.getStringExtra("message");
        if (message == null) {
            message = "";
        }
        String subtitle = intent.getStringExtra("subtitle");

        Notification.Builder b = new Notification.Builder(context)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(message)
                        .setSummaryText(subtitle != null ? subtitle : ""));
        // Channels are required on API 26+; bind to our channel id now.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setChannelId(CHANNEL_ID);
        }
        try {
            nm.notify(tag, stableId(tag), b.build());
            Log.d(TAG, "posted: tag=" + tag + " title=" + title);
        } catch (SecurityException e) {
            // Android 13+ POST_NOTIFICATIONS not granted; drop with a log line
            // so the agent can surface the failure instead of crashing.
            Log.w(TAG, "POST_NOTIFICATIONS denied: " + e.getMessage());
        }
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(CHANNEL_DESC);
            nm.createNotificationChannel(ch);
        }
    }

    /**
     * NotificationManager requires an int id; we hash the tag so cancel
     * with the same tag targets the same row.
     */
    private static int stableId(String tag) {
        return (tag.hashCode() & 0x7fffffff);
    }
}
