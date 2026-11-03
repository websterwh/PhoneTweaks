package com.phonetweaks.xposed;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Settings & Control Center for Phone Tweaks.
 *
 * Implements lifecycle-safe background execution via ExecutorService to prevent
 * Activity reference leaks across configuration changes.
 */
public class MainActivity extends AppCompatActivity {

    private PhoneTweaksPrefs prefs;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private MaterialSwitch swHfmAnyNumber;
    private MaterialSwitch swHfmCountry;
    private MaterialSwitch swCallScreen;
    private MaterialSwitch swCallRecording;
    private MaterialSwitch swSilentRecording;
    private MaterialSwitch swAudioEmoji;
    private MaterialSwitch swScamDetection;
    private MaterialSwitch swDirectMyCall;
    private MaterialSwitch swDmcTollFreeOnly;
    private MaterialSwitch swDmcExcludeContacts;
    private View layoutDmcTollFree;
    private View layoutDmcContacts;
    private View dividerDmcSub;

    private TextView tvNativeHfmAnyNumber;
    private TextView tvNativeHfmCountry;
    private TextView tvNativeCallScreen;
    private TextView tvNativeCallRecording;
    private TextView tvNativeAudioEmoji;
    private TextView tvNativeScamDetection;
    private TextView tvNativeDirectMyCall;

    private MaterialButton btnRestart;
    private boolean hasRoot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Material You dynamic wallpaper colors before inflating layout
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Fix status bar & navigation bar overlapping content on Android 15/16/17 edge-to-edge
        applyWindowInsets();

        prefs = new PhoneTweaksPrefs(this);

        initViews();
        setupActionButtons();

        // Check root access and detect native phone state
        checkRootAccess();
        loadNativeStateAndSetupSwitches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.root_layout);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }
    }

    private void initViews() {
        swHfmAnyNumber = findViewById(R.id.switch_hfm_any_number);
        swHfmCountry = findViewById(R.id.switch_hfm_country);
        swCallScreen = findViewById(R.id.switch_call_screen);
        swCallRecording = findViewById(R.id.switch_call_recording);
        swSilentRecording = findViewById(R.id.switch_silent_recording);
        swAudioEmoji = findViewById(R.id.switch_audio_emoji);
        swScamDetection = findViewById(R.id.switch_scam_detection);
        swDirectMyCall = findViewById(R.id.switch_direct_my_call);
        swDmcTollFreeOnly = findViewById(R.id.switch_dmc_toll_free_only);
        swDmcExcludeContacts = findViewById(R.id.switch_dmc_exclude_contacts);
        layoutDmcTollFree = findViewById(R.id.layout_dmc_toll_free);
        layoutDmcContacts = findViewById(R.id.layout_dmc_contacts);
        dividerDmcSub = findViewById(R.id.divider_dmc_sub);

        tvNativeHfmAnyNumber = findViewById(R.id.tv_native_hfm_any_number);
        tvNativeHfmCountry = findViewById(R.id.tv_native_hfm_country);
        tvNativeCallScreen = findViewById(R.id.tv_native_call_screen);
        tvNativeCallRecording = findViewById(R.id.tv_native_call_recording);
        tvNativeAudioEmoji = findViewById(R.id.tv_native_audio_emoji);
        tvNativeScamDetection = findViewById(R.id.tv_native_scam_detection);
        tvNativeDirectMyCall = findViewById(R.id.tv_native_direct_my_call);

        btnRestart = findViewById(R.id.btn_restart_phone);
    }

    private void checkRootAccess() {
        backgroundExecutor.execute(() -> {
            boolean root = testRootCommand();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    updateRootUI(root);
                }
            });
        });
    }

    private boolean testRootCommand() {
        String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su", "su"};
        for (String su : suPaths) {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{su, "-c", "id"});
                process.waitFor();
                if (process.exitValue() == 0) return true;
            } catch (Throwable ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return false;
    }

    private void updateRootUI(boolean root) {
        this.hasRoot = root;
        if (btnRestart != null) {
            btnRestart.setAlpha(hasRoot ? 1.0f : 0.45f);
        }
    }

    private void loadNativeStateAndSetupSwitches() {
        backgroundExecutor.execute(() -> {
            NativeDialerDetector.State nativeState = NativeDialerDetector.detect(this);
            prefs.initializeDefaultsIfFirstLaunch();

            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    bindSwitchStates();
                    updateNativeStatusBadges(nativeState);
                }
            });
        });
    }

    private void bindSwitchStates() {
        swHfmAnyNumber.setChecked(prefs.isHoldForMeAnyNumberEnabled());
        swHfmCountry.setChecked(prefs.isHoldForMeCountryUnlockEnabled());
        swCallScreen.setChecked(prefs.isCallScreenEnabled());
        swCallRecording.setChecked(prefs.isCallRecordingEnabled());
        swSilentRecording.setChecked(prefs.isSilentCallRecordingEnabled());
        swAudioEmoji.setChecked(prefs.isAudioEmojiEnabled());
        swScamDetection.setChecked(prefs.isScamDetectionEnabled());
        swDirectMyCall.setChecked(prefs.isDirectMyCallEnabled());
        swDmcTollFreeOnly.setChecked(prefs.isDirectMyCallTollFreeOnly());
        swDmcExcludeContacts.setChecked(prefs.isDirectMyCallExcludeContacts());
        updateDmcSubOptionsVisibility(prefs.isDirectMyCallEnabled());

        swHfmAnyNumber.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_HFM_ANY_NUMBER, isChecked));
        swHfmCountry.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_HFM_COUNTRY_UNLOCK, isChecked));
        swCallScreen.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_CALL_SCREEN, isChecked));
        swCallRecording.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_CALL_RECORDING, isChecked));
        swSilentRecording.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_SILENT_CALL_RECORDING, isChecked));
        swAudioEmoji.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_AUDIO_EMOJI, isChecked));
        swScamDetection.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_SCAM_DETECTION, isChecked));
        swDirectMyCall.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.setBoolean(PhoneTweaksPrefs.KEY_DIRECT_MY_CALL, isChecked);
            updateDmcSubOptionsVisibility(isChecked);
        });
        swDmcTollFreeOnly.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_DMC_TOLL_FREE_ONLY, isChecked));
        swDmcExcludeContacts.setOnCheckedChangeListener((btn, isChecked) -> prefs.setBoolean(PhoneTweaksPrefs.KEY_DMC_EXCLUDE_CONTACTS, isChecked));
    }

    private void updateDmcSubOptionsVisibility(boolean dmcEnabled) {
        int visibility = dmcEnabled ? View.VISIBLE : View.GONE;
        if (layoutDmcTollFree != null) layoutDmcTollFree.setVisibility(visibility);
        if (layoutDmcContacts != null) layoutDmcContacts.setVisibility(visibility);
        if (dividerDmcSub != null) dividerDmcSub.setVisibility(visibility);
    }

    private void updateNativeStatusBadges(NativeDialerDetector.State state) {
        setBadge(tvNativeHfmAnyNumber, false, "Toll-free only");
        setBadge(tvNativeHfmCountry, false, state.holdForMeNative ? "Restricted region" : "Disabled");
        setBadge(tvNativeCallScreen, state.callScreenNative, state.callScreenNative ? "Enabled" : "Disabled by carrier");
        setBadge(tvNativeCallRecording, state.callRecordingNative, state.callRecordingNative ? "Enabled" : "Geofenced");
        setBadge(tvNativeAudioEmoji, state.audioEmojiNative, state.audioEmojiNative ? "Enabled" : "Disabled");
        setBadge(tvNativeScamDetection, state.scamDetectionNative, state.scamDetectionNative ? "Enabled" : "Disabled");
        setBadge(tvNativeDirectMyCall, state.directMyCallNative, state.directMyCallNative ? "Enabled" : "Disabled");
    }

    private void setBadge(TextView tv, boolean enabled, String text) {
        if (tv == null) return;
        tv.setText("● Native: " + text);
        tv.setTextColor(getColor(enabled ? R.color.status_enabled : R.color.status_disabled));
    }

    private void setupActionButtons() {
        MaterialButton btnSettings = findViewById(R.id.btn_open_settings);

        btnRestart.setOnClickListener(v -> {
            if (!hasRoot) {
                Toast.makeText(this, "Root access needed to restart Google Phone", Toast.LENGTH_SHORT).show();
                checkRootAccess();
                return;
            }
            restartDialer();
        });

        btnSettings.setOnClickListener(v -> openDialerSettings());
    }

    private void restartDialer() {
        backgroundExecutor.execute(() -> {
            boolean success = false;
            String[] suPaths = {"/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su", "su"};
            for (String su : suPaths) {
                Process process = null;
                try {
                    process = Runtime.getRuntime().exec(new String[]{
                            su, "-c", "am force-stop com.google.android.dialer"
                    });
                    process.waitFor();
                    if (process.exitValue() == 0) {
                        success = true;
                        break;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (process != null) {
                        process.destroy();
                    }
                }
            }

            final boolean killed = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (killed) {
                    Toast.makeText(this, R.string.toast_phone_restarted, Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:com.google.android.dialer"));
                        startActivity(intent);
                        Toast.makeText(this, "Force-stop Google Phone in App info to apply changes", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, "Please force-stop Google Phone to apply changes", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    private void openDialerSettings() {
        try {
            Intent intent = new Intent("com.google.android.dialer.action.CALL_SETTINGS");
            intent.setPackage("com.google.android.dialer");
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Intent.ACTION_DIAL);
                startActivity(fallback);
            } catch (Throwable ignored) {
                Toast.makeText(this, "Could not launch Google Phone", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
