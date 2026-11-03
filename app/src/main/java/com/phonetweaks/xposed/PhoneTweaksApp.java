package com.phonetweaks.xposed;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class PhoneTweaksApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Enables Material You dynamic wallpaper colors across the app on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
