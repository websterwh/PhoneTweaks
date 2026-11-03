# Phone Tweaks

An Xposed/LSPosed module for Google Phone (Pixel Dialer) that unlocks region-locked and hardware-restricted features.

Tested and maintained for Google Phone v244+ on Pixel devices (tested on Pixel 9 Pro XL, Android 15/16/17).

## Features

- **Worldwide Hold for Me**: Enables Hold for Me in all countries and on non-toll-free numbers.
- **Direct My Call**: Automates interactive phone menus (IVR). Includes optional filters to exclude personal contacts and restrict auto-popup to toll-free/business numbers, with an in-call override button in Call Assist.
- **Silent Call Recording**: Enables native two-way call recording and silences the loud "This call is now being recorded" announcement for both sides.
- **Call Screen & Audio Saving**: Enables Google Call Screen and voice recording options.
- **AI Scam Detection**: Enables real-time on-device scam detection.
- **Audio Emoji**: Unlocks in-call sound reactions and animations.
- **In-App Quick Settings**: Long-press the hamburger menu button in Google Phone to open module settings directly without needing a separate launcher icon.

## Requirements

- Android 11+
- Google Phone app (Pixel Dialer)
- LSPosed (or modern forks like Vector / LSPosed-mod)
- Root is **optional** (only used for one-tap force-restarting Google Phone from the companion app). The Xposed hooks themselves operate without root.

## Installation

1. Download the latest `PhoneTweaks.apk` from [Releases](https://github.com/websterwh/PhoneTweaks/releases).
2. Install the APK on your device.
3. Open LSPosed / Vector Manager, enable the module, and check `Phone` (`com.google.android.dialer`) in the module scope.
4. Force-stop the Google Phone app to apply the hooks.
5. Access settings by long-pressing the search bar menu button in Google Phone, or through your Xposed manager.

## Building from Source

```bash
git clone https://github.com/websterwh/PhoneTweaks.git
cd PhoneTweaks
./gradlew assembleRelease
```

The compiled APK will be in `app/build/outputs/apk/release/app-release.apk`.

## License

GPL-3.0
