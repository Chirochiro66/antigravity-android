package com.agy.bridge;

import android.content.Context;

import com.agy.runtime.RuntimeConfig;
import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;

import org.json.JSONObject;

/** Serializes the app config object injected as window.__APP_CONFIG__. */
public final class EngineConfigJson {

    private final Context appContext;
    private final RuntimeManager runtime;
    private final EngineBridge engine;
    private final ShizukuBridge shizuku;
    private final ThemeBridge theme;

    public EngineConfigJson(Context appContext, RuntimeManager runtime, EngineBridge engine,
                            ShizukuBridge shizuku, ThemeBridge theme) {
        this.appContext = appContext;
        this.runtime = runtime;
        this.engine = engine;
        this.shizuku = shizuku;
        this.theme = theme;
    }

    public String get() {
        try {
            JSONObject o = new JSONObject();
            o.put("productName", "antigravity-app");
            o.put("csrfToken", engine.getCsrfToken());
            o.put("appVersion", RuntimeConfig.APP_VERSION_NAME);
            o.put("appVersionCode", RuntimeConfig.APP_VERSION_CODE);
            o.put("coreVersion", RuntimeConfig.CORE_VERSION);
            o.put("releaseChannel", RuntimeConfig.DEBUG ? "dev" : "stable");
            o.put("devMode", RuntimeConfig.DEBUG);
            o.put("enginePort", runtime.getHttpsPort());
            o.put("engineBaseUrl", engine.getBaseUrl());
            o.put("filesDir", appContext.getFilesDir().getAbsolutePath());
            o.put("runtimeDir", runtime.getRuntimeDir().getAbsolutePath());
            o.put("home", appContext.getFilesDir().getAbsolutePath());
            o.put("tmpDir", appContext.getCacheDir().getAbsolutePath());
            o.put("appDataDir", runtime.getAppDataDir().getAbsolutePath());
            // rish availability. We expose the raw boolean and the
            // categorised lists so the Web UI can both render a status pill
            // and pick the right path per command. The two arrays below
            // mirror what IntentBridge.tryNative() and NotificationBridge
            // cover today; expand them as more commands get a native
            // implementation.
            o.put("shizukuAvailable", shizuku.isAvailable());
            org.json.JSONArray nativeFeatures = new org.json.JSONArray();
            // Anything the platform Android API can answer for us; no rish
            // needed. The list mirrors IntentBridge.tryNative() patterns
            // and NotificationBridge.post() fallback.
            nativeFeatures.put("pm install");
            nativeFeatures.put("am start");
            nativeFeatures.put("cmd battery");
            nativeFeatures.put("cmd connectivity");
            nativeFeatures.put("cmd settings get");
            nativeFeatures.put("agy-notify (via broadcast)");
            nativeFeatures.put("file access (via MANAGE_EXTERNAL_STORAGE)");
            o.put("nativeFeatures", nativeFeatures);
            // Anything still gated on rish today. The agent should prompt
            // the user to install Shizuku when one of these is needed.
            // Note: arbitrary shell is the elephant in the room; once a JS
            // path to it exists (via ShellBridge), the same tryNative
            // prefix-match applies and the agent only needs Shizuku for
            // commands the native bridge does not cover.
            org.json.JSONArray needsShizuku = new org.json.JSONArray();
            needsShizuku.put("arbitrary shell (pip / git / pkg / etc.)");
            needsShizuku.put("pm grant / appops set");
            needsShizuku.put("logcat on other packages");
            needsShizuku.put("cmd notification (logo icon)");
            needsShizuku.put("dumpsys notification (rich listing)");
            o.put("needsShizuku", needsShizuku);
            o.put("theme", theme.getTheme());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
