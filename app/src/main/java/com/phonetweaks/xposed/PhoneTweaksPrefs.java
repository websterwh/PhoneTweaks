package com.phonetweaks.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Manages preferences between the Phone Tweaks companion app and the Xposed hook injected
 * into com.google.android.dialer.
 *
 * Implements cross-process preference synchronization via XSharedPreferences with
 * Device-Protected Storage fallback, so toggles are instantly reflected in the dialer
 * without needing a device reboot.
 *
 * Performance-optimized: rate-limits disk reloads to avoid synchronous I/O spikes
 * during rapid phenotype flag evaluations on call startup.
 */
public final class PhoneTweaksPrefs {

    private static final String TAG = "PhoneTweaks/Prefs";

    public static final String PACKAGE_NAME = "com.phonetweaks.xposed";
    public static final String PREFS_NAME = "phone_tweaks_prefs";

    public static final String KEY_HFM_ANY_NUMBER = "hfm_any_number";
    public static final String KEY_HFM_COUNTRY_UNLOCK = "hfm_country_unlock";
    public static final String KEY_CALL_SCREEN = "call_screen";
    public static final String KEY_CALL_RECORDING = "call_recording";
    public static final String KEY_SILENT_CALL_RECORDING = "silent_call_recording";
    public static final String KEY_AUDIO_EMOJI = "audio_emoji";
    public static final String KEY_SCAM_DETECTION = "scam_detection";
    public static final String KEY_DIRECT_MY_CALL = "direct_my_call";
    public static final String KEY_DMC_TOLL_FREE_ONLY = "dmc_toll_free_only";
    public static final String KEY_DMC_EXCLUDE_CONTACTS = "dmc_exclude_contacts";
    public static final String KEY_INITIALIZED = "prefs_initialized_v2";

    // Throttle disk reloads to at most once per 1000ms per process
    private static final long RELOAD_THROTTLE_MS = 1000;
    private long lastReloadTimestamp = 0;

    private final XSharedPreferences xPrefs;
    private final SharedPreferences localPrefs;

    /**
     * Constructor for use inside the Xposed hook (Dialer process).
     */
    public PhoneTweaksPrefs() {
        XSharedPreferences xp = null;
        try {
            xp = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
            xp.makeWorldReadable();
            xp.reload();
            lastReloadTimestamp = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            Log.w(TAG, "Could not initialize XSharedPreferences directly: " + t.getMessage());
        }
        this.xPrefs = xp;
        this.localPrefs = null;
    }

    /**
     * Constructor for use inside the Companion App UI.
     */
    public PhoneTweaksPrefs(Context context) {
        this.xPrefs = null;
        Context targetContext = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                targetContext = context.createDeviceProtectedStorageContext();
            } catch (Throwable ignored) {}
        }
        this.localPrefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void initializeDefaultsIfFirstLaunch() {
        if (localPrefs == null) return;
        SharedPreferences.Editor editor = localPrefs.edit();

        if (!localPrefs.getBoolean(KEY_INITIALIZED, false)) {
            editor.putBoolean(KEY_HFM_ANY_NUMBER, true);
            editor.putBoolean(KEY_HFM_COUNTRY_UNLOCK, true);
            editor.putBoolean(KEY_CALL_SCREEN, true);
            editor.putBoolean(KEY_CALL_RECORDING, true);
            editor.putBoolean(KEY_SILENT_CALL_RECORDING, true);
            editor.putBoolean(KEY_AUDIO_EMOJI, true);
            editor.putBoolean(KEY_SCAM_DETECTION, true);
            editor.putBoolean(KEY_DIRECT_MY_CALL, true);
            editor.putBoolean(KEY_DMC_TOLL_FREE_ONLY, true);
            editor.putBoolean(KEY_DMC_EXCLUDE_CONTACTS, true);
            editor.putBoolean(KEY_INITIALIZED, true);
        } else {
            if (!localPrefs.contains(KEY_DMC_TOLL_FREE_ONLY)) {
                editor.putBoolean(KEY_DMC_TOLL_FREE_ONLY, true);
            }
            if (!localPrefs.contains(KEY_DMC_EXCLUDE_CONTACTS)) {
                editor.putBoolean(KEY_DMC_EXCLUDE_CONTACTS, true);
            }
            if (!localPrefs.contains(KEY_SILENT_CALL_RECORDING)) {
                editor.putBoolean(KEY_SILENT_CALL_RECORDING, true);
            }
        }
        editor.apply();
        makePrefsWorldReadable();
    }

    public void reload() {
        if (xPrefs != null) {
            try {
                xPrefs.reload();
                lastReloadTimestamp = SystemClock.elapsedRealtime();
            } catch (Throwable ignored) {}
        }
    }

    private void reloadIfThrottled() {
        if (xPrefs != null) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastReloadTimestamp > RELOAD_THROTTLE_MS) {
                try {
                    xPrefs.reload();
                    lastReloadTimestamp = now;
                } catch (Throwable ignored) {}
            }
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (xPrefs != null) {
            try {
                reloadIfThrottled();
                return xPrefs.getBoolean(key, defaultValue);
            } catch (Throwable t) {
                return defaultValue;
            }
        }
        if (localPrefs != null) {
            return localPrefs.getBoolean(key, defaultValue);
        }
        return defaultValue;
    }

    public void setBoolean(String key, boolean value) {
        if (localPrefs != null) {
            localPrefs.edit().putBoolean(key, value).apply();
            makePrefsWorldReadable();
        }
    }

    public boolean isHoldForMeAnyNumberEnabled() {
        return getBoolean(KEY_HFM_ANY_NUMBER, true);
    }

    public boolean isHoldForMeCountryUnlockEnabled() {
        return getBoolean(KEY_HFM_COUNTRY_UNLOCK, true);
    }

    public boolean isCallScreenEnabled() {
        return getBoolean(KEY_CALL_SCREEN, true);
    }

    public boolean isCallRecordingEnabled() {
        return getBoolean(KEY_CALL_RECORDING, true);
    }

    public boolean isSilentCallRecordingEnabled() {
        return getBoolean(KEY_SILENT_CALL_RECORDING, true);
    }

    public boolean isAudioEmojiEnabled() {
        return getBoolean(KEY_AUDIO_EMOJI, true);
    }

    public boolean isScamDetectionEnabled() {
        return getBoolean(KEY_SCAM_DETECTION, true);
    }

    public boolean isDirectMyCallEnabled() {
        return getBoolean(KEY_DIRECT_MY_CALL, true);
    }

    public boolean isDirectMyCallTollFreeOnly() {
        return getBoolean(KEY_DMC_TOLL_FREE_ONLY, true);
    }

    public boolean isDirectMyCallExcludeContacts() {
        return getBoolean(KEY_DMC_EXCLUDE_CONTACTS, true);
    }

    /**
     * Ensures the shared preferences file has world-readable permissions so XSharedPreferences
     * inside the Dialer process can read it across SELinux boundaries.
     */
    public void makePrefsWorldReadable() {
        try {
            File[] paths = {
                    new File("/data/user_de/0/" + PACKAGE_NAME + "/shared_prefs/" + PREFS_NAME + ".xml"),
                    new File("/data/data/" + PACKAGE_NAME + "/shared_prefs/" + PREFS_NAME + ".xml")
            };
            for (File file : paths) {
                if (file.exists()) {
                    file.setReadable(true, false);
                }
                File parent = file.getParentFile();
                if (parent != null && parent.exists()) {
                    parent.setReadable(true, false);
                    parent.setExecutable(true, false);
                }
            }
        } catch (Throwable ignored) {}
    }
}
