package com.phonetweaks.xposed;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspects Google Phone's native configuration files (via root if available, or system features)
 * to determine which features are natively enabled vs locked by Google/carrier/region on this device.
 *
 * Performance-optimized: pre-compiles regex patterns and ensures all spawned processes are destroyed
 * cleanly to prevent process leaks.
 */
public final class NativeDialerDetector {

    private static final String TAG = "PhoneTweaks/Detector";

    // Pre-compiled regex patterns for phenotype flag extraction
    private static final Pattern PATTERN_BOOLEAN = Pattern.compile("<boolean\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]+)\"");
    private static final Pattern PATTERN_STRING = Pattern.compile("<string\\s+name=\"([^\"]+)\">([^<]+)</string>");

    public static class State {
        public boolean holdForMeNative = false;
        public String holdForMeCountries = "US, CA, AU, JP";
        public boolean callScreenNative = false;
        public boolean callRecordingNative = false;
        public boolean audioEmojiNative = false;
        public boolean scamDetectionNative = false;
        public boolean directMyCallNative = false;
        public boolean detectedViaRoot = false;

        @Override
        public String toString() {
            return "NativeDialerState[hfm=" + holdForMeNative
                    + ", cs=" + callScreenNative
                    + ", rec=" + callRecordingNative
                    + ", sonic=" + audioEmojiNative
                    + ", scam=" + scamDetectionNative
                    + ", direct=" + directMyCallNative
                    + ", root=" + detectedViaRoot + "]";
        }
    }

    public static State detect(Context context) {
        State state = new State();

        // 1. Check if the Xposed hook already exported the exact native dialer state
        if (tryReadExportedState(context, state)) {
            state.detectedViaRoot = true;
            Log.i(TAG, "Native state loaded from hook export: " + state);
            return state;
        }

        // 2. Try reading Google Dialer's raw phenotype flags directly via root
        String xmlContent = readDialerPhenotypeXmlViaRoot();
        if (xmlContent != null && !xmlContent.isEmpty()) {
            state.detectedViaRoot = true;
            parsePhenotypeFlags(xmlContent, state);
            Log.i(TAG, "Native state detected via root: " + state);
            return state;
        }

        // 3. Fallback heuristic detection
        detectViaSystemHeuristics(context, state);
        Log.i(TAG, "Native state detected via heuristics: " + state);
        return state;
    }

    private static boolean tryReadExportedState(Context context, State state) {
        if (context == null) return false;
        android.content.SharedPreferences sp = context.getSharedPreferences(
                PhoneTweaksProvider.PREFS_NAME, Context.MODE_PRIVATE);

        if (!sp.getBoolean("reported", false)) return false;

        state.holdForMeNative = sp.getBoolean("hfm_native", false);
        state.callScreenNative = sp.getBoolean("cs_native", false);
        state.callRecordingNative = sp.getBoolean("rec_native", false);
        state.audioEmojiNative = sp.getBoolean("sonic_native", false);
        state.scamDetectionNative = sp.getBoolean("scam_native", false);
        state.directMyCallNative = sp.getBoolean("direct_native", false);

        String countries = sp.getString("countries_native", null);
        if (countries != null && !countries.isEmpty()) {
            state.holdForMeCountries = decodeCountryCodes(countries);
        }
        return true;
    }

    private static String readDialerPhenotypeXmlViaRoot() {
        String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su", "su"};
        for (String su : suPaths) {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        su, "-c", "cat /data/data/com.google.android.dialer/shared_prefs/dialer_phenotype_flags.xml"
                });

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    process.waitFor();
                    if (process.exitValue() == 0 && sb.length() > 0) {
                        return sb.toString();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return null;
    }

    private static void parsePhenotypeFlags(String xml, State state) {
        state.holdForMeNative = extractBoolean(xml, "G__enable_atlas", false);
        state.callScreenNative = extractBoolean(xml, "G__enable_revelio", false);

        boolean sysRec = extractBoolean(xml, "enable_call_recording_system_feature", false);
        boolean geofence = extractBoolean(xml, "G__use_call_recording_geofence_overrides", false);
        state.callRecordingNative = sysRec && !geofence;

        state.audioEmojiNative = extractBoolean(xml, "is_sonic_enabled_switch_key", false);
        state.scamDetectionNative = extractBoolean(xml, "enableAiCoreInferencePixel9", false)
                || extractBoolean(xml, "enableSharpieIdP9Plus", false);
        state.directMyCallNative = extractBoolean(xml, "G__enable_duplex", false)
                || extractBoolean(xml, "enable_sigil", false);

        String b64Countries = extractString(xml, "atlas_enabled_business_number_country_codes");
        if (b64Countries != null && !b64Countries.isEmpty()) {
            state.holdForMeCountries = decodeCountryCodes(b64Countries);
        }
    }

    private static void detectViaSystemHeuristics(Context context, State state) {
        PackageManager pm = context.getPackageManager();
        state.callRecordingNative = pm.hasSystemFeature("com.google.android.apps.dialer.call_recording_audio");

        String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";
        boolean isPixel = model.contains("pixel");
        boolean isPixel9 = isPixel && model.contains("9");

        state.holdForMeNative = isPixel;
        state.audioEmojiNative = isPixel;
        state.scamDetectionNative = isPixel9;
    }

    private static boolean extractBoolean(String xml, String flagName, boolean defaultVal) {
        Matcher matcher = PATTERN_BOOLEAN.matcher(xml);
        while (matcher.find()) {
            if (flagName.equals(matcher.group(1))) {
                return "true".equalsIgnoreCase(matcher.group(2));
            }
        }
        return defaultVal;
    }

    private static String extractString(String xml, String flagName) {
        Matcher matcher = PATTERN_STRING.matcher(xml);
        while (matcher.find()) {
            if (flagName.equals(matcher.group(1))) {
                return matcher.group(2).trim();
            }
        }
        return null;
    }

    private static String decodeCountryCodes(String base64) {
        try {
            int pad = (4 - base64.length() % 4) % 4;
            String padded = base64 + "===".substring(0, pad);
            byte[] bytes = Base64.decode(padded, Base64.DEFAULT);

            List<String> list = new ArrayList<>();
            int idx = 0;
            while (idx < bytes.length - 1) {
                int len = bytes[idx + 1];
                if (idx + 2 + len <= bytes.length) {
                    list.add(new String(bytes, idx + 2, len));
                }
                idx += 2 + len;
            }
            if (!list.isEmpty()) {
                return String.join(", ", list);
            }
        } catch (Throwable ignored) {}
        return "US, CA, AU, JP";
    }
}
