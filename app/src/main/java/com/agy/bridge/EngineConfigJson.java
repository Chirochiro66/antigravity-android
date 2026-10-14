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
            // shizukuAvailable stays as the raw boolean for back-compat. The
            // nativeFeatures array tells the Web UI which high-value commands
            // work without rish so the agent can pick the right path and
            // surface a clean status to the user.
            boolean shizukuOn = shizuku.isAvailable();
            o.put("shizukuAvailable", shizukuOn);
            org.json.JSONArray nativeFeatures = new org.json.JSONArray();
            // Anything that the platform Android API can answer for us, no
            // rish needed. The list mirrors IntentBridge.tryNative() and
            // NotificationBridge.post().
            nativeFeatures.put("pm install");
            nativeFeatures.put("am start");
            nativeFeatures.put("cmd battery");
            nativeFeatures.put("cmd connectivity");
            nativeFeatures.put("cmd settings get");
            nativeFeatures.put("agy-notify (via broadcast)");
            nativeFeatures.put("file access (via MANAGE_EXTERNAL_STORAGE)");
            o.put("nativeFeatures", nativeFeatures);
            // Anything still gated on rish. The agent should prompt the user
            // to install Shizuku when one of these is needed.
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
