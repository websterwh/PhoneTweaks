package com.phonetweaks.xposed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * Secure IPC bridge that allows the Xposed hook inside com.google.android.dialer
 * to report native flag states directly to the companion app without requiring
 * cross-UID filesystem writes or root access.
 *
 * Implements strict caller verification to ensure only Google Dialer or this app itself
 * can invoke methods on this ContentProvider.
 */
public class PhoneTweaksProvider extends ContentProvider {

    private static final String TAG = "PhoneTweaks/Provider";
    public static final String AUTHORITY = "com.phonetweaks.xposed.provider";
    public static final String METHOD_REPORT_NATIVE_STATE = "report_native_state";
    public static final String PREFS_NAME = "phonetweaks_native_state";
    private static final String DIALER_PKG = "com.google.android.dialer";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceCallingPermission();

        if (METHOD_REPORT_NATIVE_STATE.equals(method) && extras != null) {
            Context ctx = getContext();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit()
                        .putBoolean("hfm_native", extras.getBoolean("hfm_native", false))
                        .putBoolean("cs_native", extras.getBoolean("cs_native", false))
                        .putBoolean("rec_native", extras.getBoolean("rec_native", false))
                        .putBoolean("sonic_native", extras.getBoolean("sonic_native", false))
                        .putBoolean("scam_native", extras.getBoolean("scam_native", false))
                        .putBoolean("direct_native", extras.getBoolean("direct_native", false))
                        .putString("countries_native", extras.getString("countries_native", "US, CA, AU, JP"))
                        .putBoolean("reported", true)
                        .apply();
            }
            Bundle resp = new Bundle();
            resp.putBoolean("success", true);
            return resp;
        }
        return null;
    }

    /**
     * Enforces that only com.google.android.dialer or this app's own process UID
     * can communicate with this ContentProvider.
     */
    private void enforceCallingPermission() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return; // Allowed: same application process
        }

        Context ctx = getContext();
        if (ctx == null) {
            throw new SecurityException("Context unavailable for caller verification");
        }

        PackageManager pm = ctx.getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        if (packages != null) {
            for (String pkg : packages) {
                if (DIALER_PKG.equals(pkg) || ctx.getPackageName().equals(pkg)) {
                    return; // Allowed: caller is Google Phone
                }
            }
        }

        Log.w(TAG, "Rejected unauthorized ContentProvider call from UID " + callingUid);
        throw new SecurityException("Unauthorized caller UID: " + callingUid);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
