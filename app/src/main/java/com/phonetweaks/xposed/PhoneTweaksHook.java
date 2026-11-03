package com.phonetweaks.xposed;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Universal Google Phone feature unlocker and Hold for Me module.
 *
 * Capabilities:
 * 1. Hold for Me on Any Number (DexKit-mapped, immune to switch-case drift)
 * 2. Worldwide Country Unlock for Hold for Me (Base64 protobuf injection)
 * 3. Google Call Screen (Revelio / SpeakEasy / on-device SODA / saving audio / demo)
 * 4. Unrestricted Native Call Recording (CanRecord.canRecordCall DexKit hook + geofence bypass)
 * 5. Audio Emoji / In-call Sound Reactions (Sonic)
 * 6. Real-Time AI Scam Detection (Sharpie / Gemini Nano / AICore)
 * 7. Direct My Call (Automated phone menu IVR button navigator)
 *
 * Preferences are synchronized with the companion app via PhoneTweaksPrefs.
 */
public class PhoneTweaksHook implements IXposedHookLoadPackage {

    private static final String TAG = "PhoneTweaksHook";
    private static final String DIALER_PKG = "com.google.android.dialer";
    private static final String MODULE_PKG = "com.phonetweaks.xposed";

    // Worldwide country codes for Hold for Me (Base64 wire protobuf format)
    private static final String WORLDWIDE_COUNTRY_CODES_B64 =
            "CgJVUwoCQ0EKAkFVCgJKUAoCR0IKAkRFCgJGUgoCRVMKAklUCgJJTgoCQlIKAk1YCgJOTAoCU0UKAlNHCgJOWgoCSUUKAkNICgJBVAoCQkUKAlBMCgJQVAoCREsKAkZJCgJOTwoCS1IKAlRXCgJISwoCU0cKAk1ZCgJQSAoCSUQKAlRICgJWTgoCWkEKAkFFCgJTQQoCSUwKAlRSCgJHUgoCQ1oKAlJPCgJIVQoCQ0wKAkNPCgJQRQoCQVI=";

    private PhoneTweaksPrefs prefs;
    private static volatile boolean isExportingNativeState = false;
    private static volatile boolean currentCallAllowsDirectMyCall = true;
    private static volatile Context globalAppContext = null;
    private static volatile Field cachedPhoneInfoNumberField = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(DIALER_PKG)) return;

        try {
            prefs = new PhoneTweaksPrefs();
            final ClassLoader classLoader = lpparam.classLoader;
            final String apkPath = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
            final Context appContext = getAppContext(classLoader);
            globalAppContext = appContext;

            if (apkPath == null) {
                Log.e(TAG, "No APK path available for " + DIALER_PKG + "; cannot run DexKit auto-mapping");
                return;
            }
            // Hook Application.onCreate to save an accurate snapshot of the un-hooked native dialer flags
            hookApplicationForNativeExport(classLoader);

            // 1. Hook Hold for Me (Eligibility & Business Finder dispatchers)
            installHoldForMeHooks(classLoader, apkPath, appContext);

            // 2. Hook Native Call Recording (CanRecord.canRecordCall)
            installCallRecordingHook(classLoader, apkPath, appContext);

            // 3. Hook Phenotype Flags via SharedPreferencesImpl
            installPhenotypeFlagsHook(classLoader, appContext);

            // 4. Hook TikTok experiment flag provider (aleu.b) via DexKit
            installTikTokFlagHook(classLoader, apkPath, appContext);
            // 5. Hook Call Assist popup menu to inject Direct My Call button & handle button clicks
            installCallAssistButtonHook(classLoader, apkPath, appContext);
            // 6. Hook Dialer UI for quick settings & shortcut access
            hookDialerUiForHamburgerShortcut(classLoader);
            // 7. Hook Call Recording announcement audio to make it completely silent
            installSilentCallRecordingHook(classLoader, apkPath, appContext);

            Log.i(TAG, "All PixelHold hooks installed successfully on " + DIALER_PKG);

        } catch (Throwable t) {
            Log.e(TAG, "Hook installation failed: " + t.getMessage(), t);
        }
    }

    private void installHoldForMeHooks(final ClassLoader classLoader, final String apkPath, final Context appContext) {
        try {
            final DexKitEligibilityMapper.Mapping mapping =
                    DexKitEligibilityMapper.resolve(classLoader, apkPath, DIALER_PKG, appContext);

            final DexKitEligibilityMapper.BusinessFinderMapping bizMapping =
                    DexKitEligibilityMapper.resolveBusinessFinder(apkPath, classLoader, DIALER_PKG, appContext, mapping);

            final Class<?> phoneInfoClass = (bizMapping != null) ? bizMapping.phoneInfoClass : null;

            if (mapping != null) {
                XposedBridge.hookMethod(mapping.eligibilityMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        if (!prefs.isHoldForMeAnyNumberEnabled()) return;

                        Object phoneInfo = param.args.length > 0 ? param.args[0] : null;
                        if (phoneInfo == null) return;

                        // Immune to switch-case drift: check whether the argument matches the phone info class
                        boolean isEligibilityCase = false;
                        if (phoneInfoClass != null) {
                            isEligibilityCase = phoneInfoClass.isInstance(phoneInfo);
                        } else {
                            // Fallback using case selector: 10 in v244+, 9 in v230, 17 in older builds
                            int caseNum = mapping.caseSelectorField.getInt(param.thisObject);
                            if (caseNum == 10 || caseNum == 9 || caseNum == 17) {
                                isEligibilityCase = true;
                            }
                        }

                        if (!isEligibilityCase) return;

                        Field tollFreeField = (bizMapping != null && bizMapping.tollFreeField != null)
                                ? bizMapping.tollFreeField
                                : DexKitEligibilityMapper.resolveTollFreeField(apkPath, classLoader, phoneInfo, mapping, DIALER_PKG, appContext);

                        if (tollFreeField != null && tollFreeField.getDeclaringClass().isInstance(phoneInfo)) {
                            evaluateDirectMyCallEligibility(prefs, appContext, phoneInfo, tollFreeField);
                            if (prefs.isHoldForMeAnyNumberEnabled()) {
                                tollFreeField.setBoolean(phoneInfo, true);
                            }
                        }
                    }
                });

                Log.i(TAG, "Hook installed on eligibility dispatcher: " + mapping.eligibilityMethod
                        + " (case-drift immune: " + (phoneInfoClass != null) + ")");
            }

            if (bizMapping != null) {
                XposedBridge.hookMethod(bizMapping.dispatcherMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        prefs.reload();
                        Object arg = param.args.length > 0 ? param.args[0] : null;
                        if (arg != null && bizMapping.tollFreeField.getDeclaringClass().isInstance(arg)) {
                            evaluateDirectMyCallEligibility(prefs, appContext, arg, bizMapping.tollFreeField);
                            if (prefs.isHoldForMeAnyNumberEnabled()) {
                                bizMapping.tollFreeField.setBoolean(arg, true);
                            }
                        }
                    }
                });
                Log.i(TAG, "Hook installed on business-finder dispatcher: " + bizMapping.dispatcherMethod);
            }

        } catch (Throwable t) {
            Log.e(TAG, "Failed to install Hold for Me hooks", t);
        }
    }

    private void installCallRecordingHook(ClassLoader classLoader, String apkPath, Context appContext) {
        try {
            Method canRecordMethod = DexKitEligibilityMapper.resolveCanRecordMethod(apkPath, classLoader, DIALER_PKG, appContext);
            if (canRecordMethod != null) {
                XposedBridge.hookMethod(canRecordMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        if (prefs.isCallRecordingEnabled()) {
                            param.setResult(true);
                        }
                    }
                });
                Log.i(TAG, "Call recording hook installed on " + canRecordMethod);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install Call Recording hook", t);
        }
    }

    private void installPhenotypeFlagsHook(ClassLoader classLoader, final Context appContext) {
        try {
            Class<?> spImplClass = XposedHelpers.findClass("android.app.SharedPreferencesImpl", classLoader);

            XposedBridge.hookMethod(XposedHelpers.findMethodExact(spImplClass, "getBoolean", String.class, boolean.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    prefs.reload();
                    String key = (String) param.args[0];
                    if (key == null) return;
                    if (isExportingNativeState) return;

                    // Call Screen
                    if (prefs.isCallScreenEnabled() && isCallScreenFlag(key)) {
                        param.setResult(true);
                        return;
                    }

                    // Call Recording
                    if (prefs.isCallRecordingEnabled()) {
                        if ("enable_call_recording_system_feature".equals(key)) {
                            param.setResult(true);
                            return;
                        } else if ("G__use_call_recording_geofence_overrides".equals(key)) {
                            param.setResult(false);
                            return;
                        }
                    }

                    // Hold for Me
                    if (prefs.isHoldForMeAnyNumberEnabled() && isHoldForMeFlag(key)) {
                        param.setResult(true);
                        return;
                    }

                    // Audio Emoji (Sonic)
                    if (prefs.isAudioEmojiEnabled() && isAudioEmojiFlag(key)) {
                        param.setResult(true);
                        return;
                    }

                    // AI Scam Detection (Sharpie)
                    if (prefs.isScamDetectionEnabled() && isScamDetectionFlag(key)) {
                        param.setResult(true);
                        return;
                    }

                    // Direct My Call
                    if (isDirectMyCallFlag(key)) {
                        boolean dmcMasterEnabled = prefs.isDirectMyCallEnabled();
                        if (!dmcMasterEnabled) {
                            param.setResult(false);
                            return;
                        }
                        boolean inCall = isInActiveCall(appContext);
                        boolean allow = (!inCall) || currentCallAllowsDirectMyCall;
                        param.setResult(allow);
                        return;
                    }
                }
            });

            XposedBridge.hookMethod(XposedHelpers.findMethodExact(spImplClass, "getString", String.class, String.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    prefs.reload();
                    String key = (String) param.args[0];
                    if (key == null) return;
                    if (isExportingNativeState) return;

                    if ("atlas_enabled_business_number_country_codes".equals(key) && prefs.isHoldForMeCountryUnlockEnabled()) {
                        param.setResult(WORLDWIDE_COUNTRY_CODES_B64);
                    }
                }
            });

            Log.i(TAG, "Phenotype SharedPreferences flag hook installed");

        } catch (Throwable t) {
            Log.e(TAG, "Failed to install Phenotype SharedPreferences flag hook", t);
        }
    }

    private void installTikTokFlagHook(ClassLoader classLoader, String apkPath, Context appContext) {
        try {
            Method tikTokFlagMethod = DexKitEligibilityMapper.resolveTikTokFlagProvider(apkPath, classLoader, DIALER_PKG, appContext);
            if (tikTokFlagMethod != null) {
                final Class<?> flagHolderClass = tikTokFlagMethod.getReturnType();
                final Field trueHolderField = flagHolderClass.getDeclaredField("a");
                trueHolderField.setAccessible(true);
                final Object trueFlagHolder = trueHolderField.get(null);

                XposedBridge.hookMethod(tikTokFlagMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        prefs.reload();
                        String flagName = param.args.length > 1 ? (String) param.args[1] : null;
                        if (flagName == null) return;

                        boolean shouldEnable = false;
                        if (prefs.isCallScreenEnabled() && isCallScreenFlag(flagName)) shouldEnable = true;
                        else if (prefs.isCallRecordingEnabled() && "enable_call_recording_system_feature".equals(flagName)) shouldEnable = true;
                        else if (prefs.isHoldForMeAnyNumberEnabled() && isHoldForMeFlag(flagName)) shouldEnable = true;
                        else if (prefs.isAudioEmojiEnabled() && isAudioEmojiFlag(flagName)) shouldEnable = true;
                        else if (prefs.isScamDetectionEnabled() && isScamDetectionFlag(flagName)) shouldEnable = true;
                        else if (prefs.isDirectMyCallEnabled() && isDirectMyCallFlag(flagName)) shouldEnable = true;

                        if (shouldEnable && trueFlagHolder != null) {
                            param.setResult(trueFlagHolder);
                        }
                    }
                });

                Log.i(TAG, "TikTok experiment flag provider hook installed on " + tikTokFlagMethod);
            }
        } catch (Throwable t) {
            Log.w(TAG, "TikTok experiment flag hook installation skipped", t);
        }
    }
    private void installCallAssistButtonHook(ClassLoader classLoader, String apkPath, final Context appContext) {
        try {
            DexKitEligibilityMapper.CallAssistMapping mapping =
                    DexKitEligibilityMapper.resolveCallAssistMapping(apkPath, classLoader, DIALER_PKG, appContext);
            if (mapping == null) {
                Log.w(TAG, "CallAssistMapping not resolved, skipping Call Assist button injection");
                return;
            }

            // 1. Hook XatuVoiceButtonController.onButtonClicked() to override filter on demand
            XposedBridge.hookMethod(mapping.xatuButtonClickedMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    prefs.reload();
                    Log.i(TAG, "User tapped Direct My Call in-call button! Overriding filter for this call.");
                    currentCallAllowsDirectMyCall = true;
                }
            });

            // 2. Hook CallAssistFragmentPeer.getModel() to inject the Direct My Call button
            XposedBridge.hookMethod(mapping.peerGetModelMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    prefs.reload();
                    if (!prefs.isDirectMyCallEnabled()) return;

                    Object rneObj = param.getResult();
                    if (rneObj == null) return;

                    Field fieldD = null;
                    for (Field f : rneObj.getClass().getDeclaredFields()) {
                        if (java.util.List.class.isAssignableFrom(f.getType())) {
                            fieldD = f;
                            break;
                        }
                    }
                    if (fieldD == null) return;
                    fieldD.setAccessible(true);

                    java.util.List<?> buttons = (java.util.List<?>) fieldD.get(rneObj);
                    if (buttons == null || buttons.isEmpty()) return;

                    // Check if BUTTON_XATU (e == 22) is already in the list
                    for (Object btn : buttons) {
                        try {
                            if (XposedHelpers.getIntField(btn, "e") == 22) {
                                return; // Already present
                            }
                        } catch (Throwable ignored) {}
                    }

                    // Clone an existing button to create BUTTON_XATU
                    Object sampleButton = buttons.get(0);
                    Object builder = XposedHelpers.callMethod(sampleButton, "E");
                    if (builder == null) return;

                    Object buttonProto = XposedHelpers.getObjectField(builder, "b");
                    if (buttonProto == null) return;

                    Context ctx = globalAppContext != null ? globalAppContext : appContext;
                    int dialpadIcon = 0;
                    if (ctx != null) {
                        try {
                            dialpadIcon = ctx.getResources().getIdentifier(
                                    "gs_dialpad_vd_theme_24", "drawable", DIALER_PKG);
                            if (dialpadIcon == 0) {
                                dialpadIcon = ctx.getResources().getIdentifier(
                                        "quantum_gm_ic_dialpad_vd_theme_24", "drawable", DIALER_PKG);
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (dialpadIcon == 0) {
                        try {
                            dialpadIcon = ((Integer) XposedHelpers.getObjectField(sampleButton, "d")).intValue();
                        } catch (Throwable ignored) {}
                    }

                    XposedHelpers.setIntField(buttonProto, "e", 22); // BUTTON_XATU
                    int bitfield = XposedHelpers.getIntField(buttonProto, "b");
                    XposedHelpers.setIntField(buttonProto, "b", bitfield | 1 | 4 | 8 | 16 | 512);
                    XposedHelpers.setIntField(buttonProto, "c", 2); // Resource ID type
                    XposedHelpers.setObjectField(buttonProto, "d", Integer.valueOf(dialpadIcon));
                    XposedHelpers.setObjectField(buttonProto, "g", "Direct My Call");
                    XposedHelpers.setObjectField(buttonProto, "h", "Navigate automated phone menus");
                    XposedHelpers.setBooleanField(buttonProto, "i", true);
                    XposedHelpers.setIntField(buttonProto, "m", 2); // rjv.CALL_ASSIST

                    Object xatuButton = XposedHelpers.callMethod(builder, "s");
                    if (xatuButton == null) return;

                    java.util.List mutableButtons = buttons;
                    try {
                        boolean isMutable = (boolean) XposedHelpers.callMethod(buttons, "c");
                        if (!isMutable) {
                            Class<?> amfoClass = rneObj.getClass().getSuperclass();
                            mutableButtons = (java.util.List) XposedHelpers.callStaticMethod(amfoClass, "aa", buttons);
                            fieldD.set(rneObj, mutableButtons);
                        }
                    } catch (Throwable t) {
                        mutableButtons = new java.util.ArrayList<>(buttons);
                        fieldD.set(rneObj, mutableButtons);
                    }

                    mutableButtons.add(xatuButton);
                }
            });

            Log.i(TAG, "Call Assist button injection hooks installed successfully!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install Call Assist button hook", t);
        }
    }

    private static boolean isCallScreenFlag(String key) {
        switch (key) {
            case "G__enable_revelio":
            case "G__speak_easy_enabled":
            case "G__speak_easy_use_soda_asr":
            case "G__enable_call_screen_saving_audio":
            case "enable_call_screen_demo":
            case "enable_call_screen_i18n_tidepods":
            case "G__enable_call_screen_audio_stitching":
            case "G__enable_call_screen_data_in_call_log":
            case "G__enable_revelio_on_bluetooth":
            case "G__enable_revelio_on_wired_headset":
            case "G__enable_revelio_r_api":
                return true;
            default:
                return false;
        }
    }

    private static boolean isHoldForMeFlag(String key) {
        switch (key) {
            case "G__enable_atlas":
            case "show_atlas_hold_for_me_confirmation_dialog":
            case "atlas_allow_hardcoded_business":
            case "enable_atlas_call_audio_state_verification":
            case "atlas_supports_english_only_string_for_settings":
            case "atlas_show_preview_label":
                return true;
            default:
                return false;
        }
    }

    private static boolean isAudioEmojiFlag(String key) {
        switch (key) {
            case "is_sonic_enabled_switch_key":
            case "enable_sonic_preference_view_id":
                return true;
            default:
                return false;
        }
    }

    private static boolean isScamDetectionFlag(String key) {
        switch (key) {
            case "enableAiCoreInferencePixel9":
            case "enableSharpieIdP9Plus":
            case "enableSharpiePlP9Plus":
            case "enableSharpiePtP9Plus":
                return true;
            default:
                return false;
        }
    }

    private static boolean isDirectMyCallFlag(String key) {
        switch (key) {
            case "G__enable_duplex":
            case "enable_sigil":
            case "xatu_show_ahead_enrollment_status":
            case "xatu_automatic_display_enrollment_status":
            case "xatu_enable_eligibility_expansion":
                return true;
            default:
                return false;
        }
    }

    private static boolean isInActiveCall(Context context) {
        Context ctx = context != null ? context : globalAppContext;
        if (ctx == null) return false;
        try {
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            return tm != null && tm.isInCall();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String getPhoneNumberFromInfo(Object phoneInfo) {
        if (phoneInfo == null) return null;
        try {
            Field field = cachedPhoneInfoNumberField;
            if (field == null) {
                field = phoneInfo.getClass().getDeclaredField("c");
                field.setAccessible(true);
                cachedPhoneInfoNumberField = field;
            }
            Object val = field.get(phoneInfo);
            return val instanceof String ? (String) val : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isContact(Context context, String number) {
        Context ctx = context != null ? context : globalAppContext;
        if (ctx == null || number == null || number.trim().isEmpty()) return false;
        try {
            android.net.Uri uri = android.net.Uri.withAppendedPath(
                    android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number.trim())
            );
            try (android.database.Cursor cursor = ctx.getContentResolver().query(
                    uri,
                    new String[]{android.provider.ContactsContract.PhoneLookup._ID},
                    null, null, null
            )) {
                return cursor != null && cursor.moveToFirst();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void evaluateDirectMyCallEligibility(PhoneTweaksPrefs prefs, Context context, Object phoneInfo, Field tollFreeField) {
        try {
            if (tollFreeField == null || phoneInfo == null || !tollFreeField.getDeclaringClass().isInstance(phoneInfo)) {
                return;
            }
            boolean naturallyTollFree = tollFreeField.getBoolean(phoneInfo);
            String number = getPhoneNumberFromInfo(phoneInfo);
            boolean isContactNum = isContact(context, number);

            boolean allowDmc = false;
            if (prefs.isDirectMyCallEnabled()) {
                if (prefs.isDirectMyCallExcludeContacts() && isContactNum) {
                    allowDmc = false;
                } else if (prefs.isDirectMyCallTollFreeOnly()) {
                    allowDmc = naturallyTollFree;
                } else {
                    allowDmc = true;
                }
            }
            currentCallAllowsDirectMyCall = allowDmc;
            Log.i(TAG, "Direct My Call evaluated: number=" + number
                    + ", naturallyTollFree=" + naturallyTollFree
                    + ", isContact=" + isContactNum
                    + ", allowDirectMyCall=" + allowDmc);
        } catch (Throwable t) {
            Log.e(TAG, "Error evaluating Direct My Call eligibility", t);
        }
    }

    private static Context getAppContext(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Object app = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication");
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }
    private void hookApplicationForNativeExport(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) param.thisObject;
                    globalAppContext = context;
                    exportNativeDialerState(context);
                    cleanupOldShortcuts(context);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook Application.onCreate for native export", t);
        }
    }

    private void exportNativeDialerState(Context appContext) {
        try {
            if (appContext == null) return;
            android.content.SharedPreferences dialerPrefs =
                    appContext.getSharedPreferences("dialer_phenotype_flags", Context.MODE_PRIVATE);
            if (dialerPrefs == null) return;

            isExportingNativeState = true;
            boolean hfm;
            boolean cs;
            boolean rec;
            boolean sonic;
            boolean scam;
            boolean direct;
            String countries;

            try {
                hfm = dialerPrefs.getBoolean("G__enable_atlas", false);
                cs = dialerPrefs.getBoolean("G__enable_revelio", false);
                rec = dialerPrefs.getBoolean("enable_call_recording_system_feature", false)
                        && !dialerPrefs.getBoolean("G__use_call_recording_geofence_overrides", false);
                sonic = dialerPrefs.getBoolean("is_sonic_enabled_switch_key", false);
                scam = dialerPrefs.getBoolean("enableAiCoreInferencePixel9", false)
                        || dialerPrefs.getBoolean("enableSharpieIdP9Plus", false);
                direct = dialerPrefs.getBoolean("G__enable_duplex", false)
                        || dialerPrefs.getBoolean("enable_sigil", false);
                countries = dialerPrefs.getString("atlas_enabled_business_number_country_codes", "CgJVUwoCQ0EKAkFVCgJKUA");
            } finally {
                isExportingNativeState = false;
            }
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putBoolean("hfm_native", hfm);
            bundle.putBoolean("cs_native", cs);
            bundle.putBoolean("rec_native", rec);
            bundle.putBoolean("sonic_native", sonic);
            bundle.putBoolean("scam_native", scam);
            bundle.putBoolean("direct_native", direct);
            bundle.putString("countries_native", countries);

            try {
                appContext.getContentResolver().call(
                        android.net.Uri.parse("content://com.phonetweaks.xposed.provider"),
                        "report_native_state", null, bundle
                );
                Log.i(TAG, "Native dialer state sent to companion app via ContentProvider");
            } catch (Throwable t) {
                Log.w(TAG, "Could not call companion ContentProvider: " + t.getMessage());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not export native dialer state", t);
        }
    }
    private void cleanupOldShortcuts(Context context) {
        if (context == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N_MR1) return;
        try {
            android.content.pm.ShortcutManager sm = context.getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null) {
                sm.removeDynamicShortcuts(java.util.Arrays.asList("pixelhold_settings", "phonetweaks_settings"));
            }
        } catch (Throwable ignored) {}
    }

    private void hookDialerUiForHamburgerShortcut(ClassLoader classLoader) {
        try {
            Class<?> mainActivityClass = XposedHelpers.findClass("com.android.dialer.main.impl.MainActivity", classLoader);
            XC_MethodHook uiHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final android.app.Activity activity = (android.app.Activity) param.thisObject;
                    android.view.View decorView = activity.getWindow().getDecorView();
                    attachHamburgerLongClick(decorView);
                }
            };
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate", android.os.Bundle.class, uiHook);
            XposedHelpers.findAndHookMethod(mainActivityClass, "onResume", uiHook);
            Log.i(TAG, "Hooked MainActivity lifecycle for hamburger menu button");
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook MainActivity for hamburger menu button: " + t.getMessage());
        }
        try {
            // 3. Hook DialerSettingsActivity toolbar long-press as additional entry point
            Class<?> settingsActivityClass = XposedHelpers.findClass("com.android.dialer.multibindingsettings.impl.DialerSettingsActivity", classLoader);
            XposedHelpers.findAndHookMethod(settingsActivityClass, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final android.app.Activity activity = (android.app.Activity) param.thisObject;
                    activity.getWindow().getDecorView().post(() -> {
                        try {
                            int toolbarId = activity.getResources().getIdentifier("action_bar", "id", DIALER_PKG);
                            if (toolbarId == 0) {
                                toolbarId = activity.getResources().getIdentifier("toolbar", "id", DIALER_PKG);
                            }
                            android.view.View target = toolbarId != 0 ? activity.findViewById(toolbarId) : activity.getWindow().getDecorView();
                            if (target != null) {
                                target.setOnLongClickListener(v -> {
                                    launchPhoneTweaksSettings(v.getContext());
                                    return true;
                                });
                            }
                        } catch (Throwable ignored) {}
                    });
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook DialerSettingsActivity for long-press settings", t);
        }
    }

    private static void attachHamburgerLongClick(android.view.View root) {
        if (root == null) return;
        root.post(() -> {
            try {
                findAndHookHamburgerView(root);
            } catch (Throwable t) {
                Log.w(TAG, "Error attaching hamburger long-click", t);
            }
        });
    }

    private static boolean findAndHookHamburgerView(android.view.View view) {
        if (view == null) return false;

        Object tag = view.getTag();
        CharSequence desc = view.getContentDescription();
        boolean isHamburger = false;

        if ("hamburger_menu".equals(tag)) {
            isHamburger = true;
        } else if (desc != null) {
            String descStr = desc.toString().toLowerCase();
            if (descStr.contains("open navigation drawer") || descStr.contains("navigation drawer")) {
                isHamburger = true;
            }
        }

        if (isHamburger) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                view.setTooltipText(null);
            }
            view.setOnLongClickListener(v -> {
                Log.i(TAG, "Hamburger menu button long-pressed! Launching Phone Tweaks settings...");
                launchPhoneTweaksSettings(v.getContext());
                return true; // Consumes event to suppress the 'Open navigation drawer' tooltip toast
            });
            Log.i(TAG, "Successfully attached long-click listener to hamburger menu button!");
            return true;
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (findAndHookHamburgerView(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void launchPhoneTweaksSettings(Context context) {
        if (context == null) return;
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setComponent(new android.content.ComponentName(MODULE_PKG, "com.phonetweaks.xposed.MainActivity"));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch Phone Tweaks settings", t);
        }
    }
    private void installSilentCallRecordingHook(final ClassLoader classLoader, final String apkPath, final Context context) {
        // 1. Hook TextToSpeech.synthesizeToFile to replace announcement speech with silence
        try {
            Class<?> ttsClass = android.speech.tts.TextToSpeech.class;
            XposedHelpers.findAndHookMethod(ttsClass, "synthesizeToFile",
                    CharSequence.class, android.os.Bundle.class, java.io.File.class, String.class,
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (prefs == null || !prefs.isSilentCallRecordingEnabled()) return;
                    CharSequence text = (CharSequence) param.args[0];
                    if (text != null) {
                        String textStr = text.toString().toLowerCase();
                        if (textStr.contains("record") || textStr.contains("call")) {
                            Log.i(TAG, "Silencing TextToSpeech synthesis for announcement: \"" + text + "\"");
                            param.args[0] = " "; // single space produces a silent, valid WAV in ~50ms
                        }
                    }
                }
            });
            Log.i(TAG, "Hooked TextToSpeech.synthesizeToFile for silent call recording");
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook TextToSpeech.synthesizeToFile", t);
        }

        // 2. Hook MediaPlayer.setDataSource to redirect announcement playback to silent WAV and mute volume
        try {
            Class<?> mpClass = android.media.MediaPlayer.class;
            XposedHelpers.findAndHookMethod(mpClass, "setDataSource", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (prefs == null || !prefs.isSilentCallRecordingEnabled()) return;
                    String path = (String) param.args[0];
                    if (isAnnouncementPath(path)) {
                        String silentPath = getOrCreateSilentWavPath(context != null ? context : globalAppContext);
                        if (silentPath != null) {
                            param.args[0] = silentPath;
                            Log.i(TAG, "Redirected local MediaPlayer announcement playback to silent WAV: " + path);
                        }
                        try {
                            android.media.MediaPlayer mp = (android.media.MediaPlayer) param.thisObject;
                            mp.setVolume(0.0f, 0.0f);
                        } catch (Throwable ignored) {}
                    }
                }
            });
            Log.i(TAG, "Hooked MediaPlayer.setDataSource for silent call recording");
            XposedHelpers.findAndHookMethod(mpClass, "setDataSource", Context.class, android.net.Uri.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (prefs == null || !prefs.isSilentCallRecordingEnabled()) return;
                    android.net.Uri uri = (android.net.Uri) param.args[1];
                    if (uri != null && isAnnouncementPath(uri.toString())) {
                        String silentPath = getOrCreateSilentWavPath(context != null ? context : globalAppContext);
                        if (silentPath != null) {
                            param.args[1] = android.net.Uri.fromFile(new java.io.File(silentPath));
                            Log.i(TAG, "Redirected local MediaPlayer (Uri) announcement playback to silent WAV: " + uri);
                        }
                        try {
                            android.media.MediaPlayer mp = (android.media.MediaPlayer) param.thisObject;
                            mp.setVolume(0.0f, 0.0f);
                        } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook MediaPlayer.setDataSource", t);
        }

        // 3. Hook TidepodsCallAudioImpl.playAllowingMutedCall to prevent audio injection into the call uplink
        try {
            java.lang.reflect.Method playMethod = DexKitEligibilityMapper.resolvePlayAllowingMutedCall(classLoader, apkPath, context);
            if (playMethod != null) {
                XposedBridge.hookMethod(playMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (prefs == null || !prefs.isSilentCallRecordingEnabled()) return;
                        String path = (String) param.args[0];
                        if (isAnnouncementPath(path)) {
                            String silentPath = getOrCreateSilentWavPath(context != null ? context : globalAppContext);
                            if (silentPath != null) {
                                param.args[0] = silentPath;
                                Log.i(TAG, "Redirected remote call-uplink announcement to silent WAV: " + path);
                            }
                        }
                    }
                });
                Log.i(TAG, "Hooked TidepodsCallAudioImpl.playAllowingMutedCall for silent call recording");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook TidepodsCallAudioImpl.playAllowingMutedCall", t);
        }
    }

    private static boolean isAnnouncementPath(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.contains("ttsfileprovider")
                || p.contains("starting_voice")
                || p.contains("ending_voice")
                || p.contains("beep_sound")
                || p.contains("callrecordingprompt");
    }

    private static synchronized String getOrCreateSilentWavPath(Context context) {
        if (context == null) return null;
        try {
            java.io.File cacheDir = context.getCacheDir();
            if (cacheDir == null) return null;
            java.io.File silentFile = new java.io.File(cacheDir, "silent_call_recording_disclosure.wav");
            if (!silentFile.exists() || silentFile.length() < 44) {
                byte[] wav = createSilentWavBytes();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(silentFile)) {
                    fos.write(wav);
                    fos.flush();
                }
                silentFile.setReadable(true, false);
            }
            return silentFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Could not create silent wav file", t);
            return null;
        }
    }

    private static byte[] createSilentWavBytes() {
        int sampleRate = 8000;
        int numChannels = 1;
        int bitsPerSample = 16;
        int numSamples = (int) (sampleRate * 0.05); // 50ms
        int dataSize = numSamples * numChannels * (bitsPerSample / 8);
        int totalSize = 36 + dataSize;

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(44 + dataSize);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(totalSize);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16); // subchunk1size (16 for PCM)
        buffer.putShort((short) 1); // audio format (1 = PCM)
        buffer.putShort((short) numChannels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * numChannels * (bitsPerSample / 8)); // byte rate
        buffer.putShort((short) (numChannels * (bitsPerSample / 8))); // block align
        buffer.putShort((short) bitsPerSample);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(dataSize);
        return buffer.array();
    }
}
