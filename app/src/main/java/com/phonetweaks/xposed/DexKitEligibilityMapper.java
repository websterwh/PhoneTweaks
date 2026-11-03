package com.phonetweaks.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.wrap.DexField;
import org.luckypray.dexkit.wrap.DexMethod;

/**
 * Locates the Dialer's obfuscated methods and fields dynamically using DexKit instead
 * of fragile hardcoded class/method/field names.
 *
 * Resolved items:
 * 1. Hold for Me eligibility dispatcher (checkAndCacheEligibility)
 * 2. Business finder dispatcher (findBusinessMatchingPhoneNumber) + phone info class
 * 3. Call Recording eligibility (CanRecord.canRecordCall)
 * 4. TikTok experiment flag provider (aleu.b)
 *
 * All results are cached in SharedPreferences keyed by Dialer versionCode so the dex
 * scan only runs once per install/update.
 */
final class DexKitEligibilityMapper {

    private static final String TAG = "HoldForMeHook/DexKit";

    private static final String PREFS_NAME = "phonetweaks_dexkit_cache";
    private static final String KEY_VERSION = "dialer_version_code";
    private static final String KEY_METHOD_DESC = "eligibility_method_descriptor";
    private static final String KEY_CASE_FIELD_DESC = "case_selector_field_descriptor";
    private static final String KEY_TOLLFREE_FIELD_DESC = "toll_free_field_descriptor";
    private static final String KEY_BIZ_METHOD_DESC = "business_finder_method_descriptor";
    private static final String KEY_BIZ_TOLLFREE_FIELD_DESC = "business_finder_tollfree_field_descriptor";
    private static final String KEY_CAN_RECORD_DESC = "can_record_method_descriptor";
    private static final String KEY_TIKTOK_FLAG_DESC = "tiktok_flag_method_descriptor";
    private static final String KEY_PLAY_MUTED_DESC = "play_muted_method_descriptor";

    // Flogger anchors for XatuSupportedNumbers.checkAndCacheEligibility().
    private static final String ANCHOR_LOG_CLASS = "com/android/dialer/xatu/impl/service/XatuSupportedNumbers";
    private static final String ANCHOR_LOG_METHOD = "checkAndCacheEligibility";
    private static final String ANCHOR_LOG_SOURCE = "XatuSupportedNumbers.java";
    private static final String ANCHOR_LOG_MESSAGE = "number is TFN in eligible countries";

    // Flogger anchors for AtlasBusinessFinderImpl.findBusinessMatchingPhoneNumber().
    private static final String ANCHOR2_LOG_CLASS = "com/android/incallui/atlas/ui/impl/businessfinder/AtlasBusinessFinderImpl";
    private static final String ANCHOR2_LOG_METHOD = "findBusinessMatchingPhoneNumber";
    private static final String ANCHOR2_LOG_SOURCE = "AtlasBusinessFinderImpl.java";
    private static final String ANCHOR2_LOG_MESSAGE = "toll-free number";

    private static final String MODULE_PKG = "com.phonetweaks.xposed";

    private static volatile boolean nativeLoadAttempted = false;
    private static volatile boolean nativeLoaded = false;

    private DexKitEligibilityMapper() {}

    private static synchronized boolean ensureNativeLibraryLoaded(Context appContext, String dialerPackage) {
        if (nativeLoadAttempted) return nativeLoaded;
        nativeLoadAttempted = true;

        try {
            System.loadLibrary("dexkit");
            nativeLoaded = true;
            Log.i(TAG, "libdexkit.so loaded normally via System.loadLibrary");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "System.loadLibrary(\"dexkit\") failed; trying manual extraction from module APK", t);

            File apk = resolveModuleApkFromError(t);
            if (apk == null) {
                apk = resolveModuleApkViaPackageManager(appContext);
            }
            if (apk == null) {
                Log.e(TAG, "Could not locate module APK to extract libdexkit.so from; auto-mapping disabled");
                return false;
            }

            try {
                File extracted = extractNativeLib(apk, appContext, dialerPackage);
                if (extracted == null) {
                    Log.e(TAG, "libdexkit.so not found inside " + apk + " for ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
                    return false;
                }
                System.load(extracted.getAbsolutePath());
                nativeLoaded = true;
                Log.i(TAG, "libdexkit.so manually extracted and loaded from " + extracted);
                return true;
            } catch (Throwable t2) {
                Log.e(TAG, "Manual libdexkit.so extraction/load failed", t2);
                return false;
            }
        }
    }

    private static File resolveModuleApkFromError(Throwable t) {
        String msg = String.valueOf(t.getMessage());
        Matcher m = Pattern.compile("(/[^\\[\\]\\s,]+?\\.apk)!/lib/[a-zA-Z0-9_-]+").matcher(msg);
        if (m.find()) {
            File apk = new File(m.group(1));
            if (apk.exists()) return apk;
        }
        return null;
    }

    private static File resolveModuleApkViaPackageManager(Context appContext) {
        if (appContext == null) return null;
        try {
            ApplicationInfo info = appContext.getPackageManager().getApplicationInfo(MODULE_PKG, 0);
            if (info.sourceDir != null) {
                File apk = new File(info.sourceDir);
                if (apk.exists()) return apk;
            }
        } catch (Throwable t) {
            Log.w(TAG, "PackageManager lookup for " + MODULE_PKG + " failed", t);
        }
        return null;
    }

    private static File resolveWritableCacheDir(Context appContext, String dialerPackage) {
        if (appContext != null) {
            try {
                File dir = appContext.getCacheDir();
                if (dir != null) return dir;
            } catch (Throwable ignored) {}
        }
        File[] candidates = {
                new File("/data/data/" + dialerPackage + "/cache"),
                new File("/data/user/0/" + dialerPackage + "/cache"),
        };
        for (File dir : candidates) {
            if (dir.isDirectory() && dir.canWrite()) return dir;
        }
        return candidates[0];
    }

    private static File extractNativeLib(File apkFile, Context appContext, String dialerPackage) throws Exception {
        File cacheDir = resolveWritableCacheDir(appContext, dialerPackage);
        File outDir = new File(cacheDir, "phonetweaks_native");
        if (!outDir.exists() && !outDir.mkdirs() && !outDir.exists()) {
            throw new java.io.IOException("Could not create " + outDir);
        }
        File outFile = new File(outDir, "libdexkit.so");

        if (outFile.exists()) {
            if (outFile.length() > 0 && outFile.lastModified() >= apkFile.lastModified()) {
                enforceNonWritablePermissions(outFile);
                return outFile;
            }
            outFile.delete();
        }

        try (ZipFile zip = new ZipFile(apkFile)) {
            ZipEntry entry = null;
            for (String abi : Build.SUPPORTED_ABIS) {
                ZipEntry candidate = zip.getEntry("lib/" + abi + "/libdexkit.so");
                if (candidate != null) {
                    entry = candidate;
                    break;
                }
            }
            if (entry == null) return null;

            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }

        enforceNonWritablePermissions(outFile);
        return outFile;
    }

    /**
     * Fixes Android 15/16/17 (SDK 35+) dynamic native library loading security check:
     * Android Bionic requires loaded native libraries to be read-only and executable,
     * not writable.
     */
    private static void enforceNonWritablePermissions(File file) {
        try {
            file.setReadable(true, false);
            file.setExecutable(true, false);
            file.setWritable(false, false);
            file.setReadOnly();
        } catch (Throwable ignored) {}
    }

    static final class BusinessFinderMapping {
        final Method dispatcherMethod;
        final Field tollFreeField;
        final Class<?> phoneInfoClass;
        final String dispatcherMethodDescriptor;
        final String tollFreeFieldDescriptor;

        BusinessFinderMapping(Method dispatcherMethod, Field tollFreeField, Class<?> phoneInfoClass,
                               String dispatcherMethodDescriptor, String tollFreeFieldDescriptor) {
            this.dispatcherMethod = dispatcherMethod;
            this.tollFreeField = tollFreeField;
            this.phoneInfoClass = phoneInfoClass;
            this.dispatcherMethodDescriptor = dispatcherMethodDescriptor;
            this.tollFreeFieldDescriptor = tollFreeFieldDescriptor;
        }
    }
    static final class CallAssistMapping {
        final Method peerGetModelMethod;
        final Method xatuButtonClickedMethod;

        CallAssistMapping(Method peerGetModelMethod, Method xatuButtonClickedMethod) {
            this.peerGetModelMethod = peerGetModelMethod;
            this.xatuButtonClickedMethod = xatuButtonClickedMethod;
        }
    }

    static BusinessFinderMapping resolveBusinessFinder(String apkPath, ClassLoader classLoader,
                                                         String dialerPackage, Context appContext,
                                                         Mapping mapping) {
        SharedPreferences prefs = openPrefs(appContext);
        long versionCode = getVersionCode(appContext, dialerPackage);

        if (prefs != null && versionCode >= 0 && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            String methodDesc = prefs.getString(KEY_BIZ_METHOD_DESC, null);
            String fieldDesc = prefs.getString(KEY_BIZ_TOLLFREE_FIELD_DESC, null);
            if (methodDesc != null && fieldDesc != null) {
                try {
                    Method method = new DexMethod(methodDesc).getMethodInstance(classLoader);
                    Field field = new DexField(fieldDesc).getFieldInstance(classLoader);
                    method.setAccessible(true);
                    field.setAccessible(true);
                    Class<?> phoneInfoClass = field.getDeclaringClass();
                    Log.i(TAG, "Using cached business-finder mapping for versionCode=" + versionCode);
                    return new BusinessFinderMapping(method, field, phoneInfoClass, methodDesc, fieldDesc);
                } catch (Throwable t) {
                    Log.w(TAG, "Cached business-finder descriptors no longer resolve, will re-scan", t);
                }
            }
        }

        if (apkPath == null || !ensureNativeLibraryLoaded(appContext, dialerPackage)) {
            Log.e(TAG, "Cannot DexKit-scan for business-finder dispatcher (native lib unavailable)");
            return null;
        }

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodData methodData = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings(ANCHOR2_LOG_CLASS, ANCHOR2_LOG_METHOD, ANCHOR2_LOG_SOURCE, ANCHOR2_LOG_MESSAGE)
                    )
            ).singleOrThrow(() -> new IllegalStateException(
                    "expected exactly one method using findBusinessMatchingPhoneNumber log anchors"));

            org.luckypray.dexkit.result.FieldDataList candidateFields = bridge.findField(FindField.create()
                    .matcher(FieldMatcher.create()
                            .type("boolean")
                            .addReadMethod(methodData.getDescriptor())
                    )
            );

            FieldData fieldData = null;
            if (candidateFields.size() == 1) {
                fieldData = candidateFields.first();
            } else if (candidateFields.size() > 1) {
                // When multiple boolean fields are read in the dispatcher, find the exact one
                // that is also read by the eligibility dispatcher (the shared phone info isTollFree field)
                if (mapping != null && mapping.eligibilityMethodDescriptor != null) {
                    for (FieldData fd : candidateFields) {
                        org.luckypray.dexkit.result.FieldDataList shared = bridge.findField(FindField.create()
                                .matcher(FieldMatcher.create()
                                        .declaredClass(fd.getClassName())
                                        .name(fd.getName())
                                        .addReadMethod(mapping.eligibilityMethodDescriptor)
                                )
                        );
                        if (!shared.isEmpty()) {
                            fieldData = fd;
                            break;
                        }
                    }
                }
                if (fieldData == null) {
                    fieldData = candidateFields.get(candidateFields.size() - 1);
                }
            } else {
                throw new IllegalStateException("no boolean field read by business-finder dispatcher");
            }

            Method method = methodData.getMethodInstance(classLoader);
            Field field = fieldData.getFieldInstance(classLoader);
            method.setAccessible(true);
            field.setAccessible(true);
            Class<?> phoneInfoClass = field.getDeclaringClass();

            if (prefs != null && versionCode >= 0) {
                prefs.edit()
                        .putLong(KEY_VERSION, versionCode)
                        .putString(KEY_BIZ_METHOD_DESC, methodData.getDescriptor())
                        .putString(KEY_BIZ_TOLLFREE_FIELD_DESC, fieldData.toDexField().serialize())
                        .apply();
            }

            Log.i(TAG, "DexKit resolved business-finder dispatcher=" + methodData.getDescriptor()
                    + " tollFreeField=" + fieldData.getDescriptor()
                    + " phoneInfoClass=" + phoneInfoClass.getName());

            return new BusinessFinderMapping(method, field, phoneInfoClass, methodData.getDescriptor(), fieldData.toDexField().serialize());
        } catch (Throwable t) {
            Log.e(TAG, "DexKit scan for business-finder dispatcher failed", t);
            return null;
        }
    }
    public static Method resolvePlayAllowingMutedCall(ClassLoader classLoader, String apkPath, Context appContext) {
        SharedPreferences prefs = openPrefs(appContext);
        long versionCode = getVersionCode(appContext, "com.google.android.dialer");

        if (prefs != null && versionCode >= 0 && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            String desc = prefs.getString(KEY_PLAY_MUTED_DESC, null);
            if (desc != null) {
                try {
                    Method m = new DexMethod(desc).getMethodInstance(classLoader);
                    if (m != null) {
                        return m;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (apkPath != null && ensureNativeLibraryLoaded(appContext, "com.google.android.dialer")) {
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                if (bridge != null) {
                    MethodData methodData = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings("playAllowingMutedCall")
                                    .paramTypes("java.lang.String")
                            )
                    ).firstOrNull();
                    if (methodData != null) {
                        Method m = methodData.getMethodInstance(classLoader);
                        if (m != null) {
                            if (prefs != null && versionCode >= 0) {
                                prefs.edit().putString(KEY_PLAY_MUTED_DESC, methodData.getDescriptor()).apply();
                            }
                            Log.i(TAG, "DexKit resolved playAllowingMutedCall: " + m);
                            return m;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "DexKit scan for playAllowingMutedCall failed: " + t.getMessage());
            }
        }
        // Fallback for v244+
        try {
            Class<?> hvwClass = de.robv.android.xposed.XposedHelpers.findClass("defpackage.hvw", classLoader);
            Method m = hvwClass.getDeclaredMethod("f", String.class);
            m.setAccessible(true);
            Log.i(TAG, "Fallback resolved playAllowingMutedCall: " + m);
            return m;
        } catch (Throwable t) {
            Log.w(TAG, "Fallback resolution for playAllowingMutedCall failed", t);
            return null;
        }
    }

    static final class Mapping {
        final Method eligibilityMethod;
        final Field caseSelectorField;
        final String eligibilityMethodDescriptor;
        final String caseSelectorFieldDescriptor;
        final boolean viaDexKit;

        Mapping(Method eligibilityMethod, Field caseSelectorField, String eligibilityMethodDescriptor,
                String caseSelectorFieldDescriptor, boolean viaDexKit) {
            this.eligibilityMethod = eligibilityMethod;
            this.caseSelectorField = caseSelectorField;
            this.eligibilityMethodDescriptor = eligibilityMethodDescriptor;
            this.caseSelectorFieldDescriptor = caseSelectorFieldDescriptor;
            this.viaDexKit = viaDexKit;
        }
    }

    static Mapping resolve(ClassLoader classLoader, String apkPath, String dialerPackage, Context appContext) {
        SharedPreferences prefs = openPrefs(appContext);
        long versionCode = getVersionCode(appContext, dialerPackage);

        if (prefs != null && versionCode >= 0 && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            Mapping cached = tryLoadFromCache(prefs, classLoader);
            if (cached != null) {
                Log.i(TAG, "Using cached DexKit mapping for versionCode=" + versionCode);
                return cached;
            }
        }

        if (apkPath != null && ensureNativeLibraryLoaded(appContext, dialerPackage)) {
            Mapping fresh = tryDexKitScan(apkPath, classLoader);
            if (fresh != null) {
                if (prefs != null && versionCode >= 0) {
                    prefs.edit()
                            .putLong(KEY_VERSION, versionCode)
                            .putString(KEY_METHOD_DESC, fresh.eligibilityMethodDescriptor)
                            .putString(KEY_CASE_FIELD_DESC, fresh.caseSelectorFieldDescriptor)
                            .remove(KEY_TOLLFREE_FIELD_DESC)
                            .apply();
                }
                return fresh;
            }
        }

        Log.e(TAG, "DexKit auto-mapping failed; falling back to legacy hardcoded symbols (wrm/a/b)");
        return tryLegacyFallback(classLoader);
    }

    static Field resolveTollFreeField(String apkPath, ClassLoader classLoader, Object phoneInfoInstance,
                                       Mapping mapping, String dialerPackage, Context appContext) {
        String gxcClassName = phoneInfoInstance.getClass().getName();
        SharedPreferences prefs = openPrefs(appContext);
        long versionCode = getVersionCode(appContext, dialerPackage);

        if (prefs != null && versionCode >= 0 && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            String cachedDescriptor = prefs.getString(KEY_TOLLFREE_FIELD_DESC, null);
            if (cachedDescriptor != null) {
                try {
                    Field cached = new DexField(cachedDescriptor).getFieldInstance(classLoader);
                    if (cached.getDeclaringClass().isInstance(phoneInfoInstance)) {
                        cached.setAccessible(true);
                        return cached;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Cached isTollFree descriptor no longer resolves, will re-scan", t);
                }
            }
        }

        if (apkPath != null && mapping.viaDexKit && ensureNativeLibraryLoaded(appContext, dialerPackage)) {
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                FieldData data = bridge.findField(FindField.create()
                        .matcher(FieldMatcher.create()
                                .declaredClass(gxcClassName)
                                .type("boolean")
                                .addReadMethod(mapping.eligibilityMethodDescriptor)
                        )
                ).singleOrThrow(() -> new IllegalStateException(
                        "expected exactly one boolean field on " + gxcClassName + " read by eligibility dispatcher"));

                Field f = data.getFieldInstance(classLoader);
                f.setAccessible(true);

                if (prefs != null && versionCode >= 0) {
                    prefs.edit()
                            .putLong(KEY_VERSION, versionCode)
                            .putString(KEY_TOLLFREE_FIELD_DESC, data.toDexField().serialize())
                            .apply();
                }
                Log.i(TAG, "DexKit resolved isTollFree field: " + data.getDescriptor());
                return f;
            } catch (Throwable t) {
                Log.e(TAG, "DexKit failed to resolve isTollFree field on " + gxcClassName, t);
            }
        }

        try {
            Field legacy = phoneInfoInstance.getClass().getDeclaredField("g");
            legacy.setAccessible(true);
            return legacy;
        } catch (Throwable t) {
            Log.e(TAG, "Legacy isTollFree field \"g\" not found; giving up for this call", t);
            return null;
        }
    }

    /**
     * Resolves CanRecord.canRecordCall() via DexKit to force-enable unrestricted native
     * call recording across all carriers and countries.
     */
    static Method resolveCanRecordMethod(String apkPath, ClassLoader classLoader,
                                         String dialerPackage, Context appContext) {
        SharedPreferences prefs = openPrefs(appContext);
        long versionCode = getVersionCode(appContext, dialerPackage);

        if (prefs != null && versionCode >= 0 && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            String desc = prefs.getString(KEY_CAN_RECORD_DESC, null);
            if (desc != null) {
                try {
                    Method m = new DexMethod(desc).getMethodInstance(classLoader);
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {}
            }
        }

        if (apkPath == null || !ensureNativeLibraryLoaded(appContext, dialerPackage)) return null;

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodData methodData = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("boolean")
                            .paramTypes()
                            .usingStrings("com/android/dialer/callrecording/impl/canrecord/CanRecord", "canRecordCall")
                    )
            ).singleOrNull();

            if (methodData != null) {
                Method m = methodData.getMethodInstance(classLoader);
                m.setAccessible(true);
                if (prefs != null && versionCode >= 0) {
                    prefs.edit().putString(KEY_CAN_RECORD_DESC, methodData.getDescriptor()).apply();
                }
                Log.i(TAG, "DexKit resolved CanRecord.canRecordCall: " + methodData.getDescriptor());
                return m;
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit scan for CanRecord.canRecordCall failed", t);
        }
        return null;
    }

    /**
     * Resolves TikTok experiment flag provider (aleu.b) via DexKit to override Google Phone
     * feature flags at the experiment layer.
     */
    static Method resolveTikTokFlagProvider(String apkPath, ClassLoader classLoader,
                                            String dialerPackage, Context appContext) {
        SharedPreferences prefs = openPrefs(appContext);
        long versionCode = getVersionCode(appContext, dialerPackage);

        if (prefs != null && versionCode >= 0 && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            String desc = prefs.getString(KEY_TIKTOK_FLAG_DESC, null);
            if (desc != null) {
                try {
                    Method m = new DexMethod(desc).getMethodInstance(classLoader);
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {}
            }
        }

        if (apkPath == null || !ensureNativeLibraryLoaded(appContext, dialerPackage)) return null;

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodData methodData = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("java.lang.String", "java.lang.String")
                            .usingStrings("com.google.android.dialer")
                    )
            ).firstOrNull();

            if (methodData != null) {
                Method m = methodData.getMethodInstance(classLoader);
                m.setAccessible(true);
                if (prefs != null && versionCode >= 0) {
                    prefs.edit().putString(KEY_TIKTOK_FLAG_DESC, methodData.getDescriptor()).apply();
                }
                Log.i(TAG, "DexKit resolved TikTok experiment flag provider: " + methodData.getDescriptor());
                return m;
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit scan for TikTok flag provider failed", t);
        }
        return null;
    }

    private static Mapping tryDexKitScan(String apkPath, ClassLoader classLoader) {
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodData methodData = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings(ANCHOR_LOG_CLASS, ANCHOR_LOG_METHOD, ANCHOR_LOG_SOURCE, ANCHOR_LOG_MESSAGE)
                    )
            ).singleOrThrow(() -> new IllegalStateException(
                    "expected exactly one method using checkAndCacheEligibility log anchors"));

            FieldData caseFieldData = bridge.findField(FindField.create()
                    .matcher(FieldMatcher.create()
                            .declaredClass(methodData.getClassName())
                            .type("int")
                            .addReadMethod(methodData.getDescriptor())
                    )
            ).singleOrThrow(() -> new IllegalStateException(
                    "expected exactly one int field on " + methodData.getClassName() + " read by dispatcher"));

            Method method = methodData.getMethodInstance(classLoader);
            Field caseField = caseFieldData.getFieldInstance(classLoader);
            method.setAccessible(true);
            caseField.setAccessible(true);

            Log.i(TAG, "DexKit resolved dispatcher=" + methodData.getDescriptor()
                    + " caseField=" + caseFieldData.getDescriptor());

            return new Mapping(method, caseField, methodData.getDescriptor(), caseFieldData.getDescriptor(), true);
        } catch (Throwable t) {
            Log.e(TAG, "DexKit scan failed", t);
            return null;
        }
    }

    private static Mapping tryLoadFromCache(SharedPreferences prefs, ClassLoader classLoader) {
        String methodDesc = prefs.getString(KEY_METHOD_DESC, null);
        String fieldDesc = prefs.getString(KEY_CASE_FIELD_DESC, null);
        if (methodDesc == null || fieldDesc == null) return null;

        try {
            Method method = new DexMethod(methodDesc).getMethodInstance(classLoader);
            Field field = new DexField(fieldDesc).getFieldInstance(classLoader);
            method.setAccessible(true);
            field.setAccessible(true);
            return new Mapping(method, field, methodDesc, fieldDesc, true);
        } catch (Throwable t) {
            Log.w(TAG, "Cached DexKit descriptors no longer resolve, will re-scan", t);
            return null;
        }
    }

    private static Mapping tryLegacyFallback(ClassLoader classLoader) {
        try {
            Class<?> dispatcherClass = Class.forName("wrm", false, classLoader);
            Method method = dispatcherClass.getDeclaredMethod("a", Object.class);
            Field caseField = dispatcherClass.getDeclaredField("b");
            method.setAccessible(true);
            caseField.setAccessible(true);
            return new Mapping(method, caseField, null, null, false);
        } catch (Throwable t) {
            Log.e(TAG, "Legacy fallback mapping failed", t);
            return null;
        }
    }
    public static CallAssistMapping resolveCallAssistMapping(
            String apkPath, ClassLoader classLoader, String dialerPackage, Context appContext) {
        if (apkPath == null || !ensureNativeLibraryLoaded(appContext, dialerPackage)) {
            return null;
        }

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            // 1. Find XatuVoiceButtonController.onButtonClicked()
            MethodData xorMethodData = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("com/android/dialer/xatu/impl/service/voice/XatuVoiceButtonController", "onButtonClicked")
                    )
            ).firstOrNull();

            Method xatuButtonClickedMethod = null;
            if (xorMethodData != null) {
                xatuButtonClickedMethod = xorMethodData.getMethodInstance(classLoader);
            }

            // 2. Find CallAssistFragmentPeer via CallAssistFragmentPeer_onCreateView in rnn
            MethodData rnnMethodData = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("CallAssistFragmentPeer_onCreateView")
                    )
            ).firstOrNull();

            Class<?> rnrClass = null;
            Method peerGetModelMethod = null;

            if (rnnMethodData != null) {
                Class<?> rnnClass = classLoader.loadClass(rnnMethodData.getClassName());
                for (Field f : rnnClass.getDeclaredFields()) {
                    Class<?> cand = f.getType();
                    if (!cand.isPrimitive()
                            && !cand.getName().startsWith("java.")
                            && !cand.getName().startsWith("android.")
                            && !cand.getName().startsWith("androidx.")) {
                        for (Method m : cand.getDeclaredMethods()) {
                            if (m.getParameterTypes().length == 0
                                    && !m.getReturnType().equals(void.class)
                                    && !m.getReturnType().isPrimitive()
                                    && !m.getReturnType().getName().startsWith("java.")
                                    && !m.getReturnType().getName().startsWith("android.")) {
                                rnrClass = cand;
                                peerGetModelMethod = m;
                                break;
                            }
                        }
                        if (peerGetModelMethod != null) break;
                    }
                }
            }

            Log.i(TAG, "CallAssistMapping: peerMethod=" + peerGetModelMethod + ", xatuClickMethod=" + xatuButtonClickedMethod);
            if (peerGetModelMethod != null && xatuButtonClickedMethod != null) {
                return new CallAssistMapping(peerGetModelMethod, xatuButtonClickedMethod);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to resolve CallAssistMapping", t);
        }
        return null;
    }

    private static SharedPreferences openPrefs(Context appContext) {
        if (appContext == null) return null;
        try {
            return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long getVersionCode(Context appContext, String dialerPackage) {
        if (appContext == null) return -1;
        try {
            PackageInfo info = appContext.getPackageManager().getPackageInfo(dialerPackage, 0);
            return info.getLongVersionCode();
        } catch (Throwable t) {
            return -1;
        }
    }
}
