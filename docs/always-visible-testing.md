# Always Visible Island: device verification

The setting is under Behavior and is disabled by default. It uses the existing SystemUI preference refresh; allow the refresh to run after changing it. The idle capsule follows the configured island and camera-hole geometry. No new size control is provided.

Before testing, keep a way to disable the module in LSPosed and make sure the device can recover from a SystemUI restart. Install a debug APK built by CI, enable the module for `com.android.systemui`, and restart SystemUI if needed to load the new APK.

On the Galaxy A52, check the capsule alignment with the camera in portrait. Exercise both iMotion and the classic animation mode: enable and disable the setting with no activities; post and remove consecutive notifications; play music while a notification appears and disappears; open and close a card; change the display density and position settings; turn the screen off and wake it; check lock screen, notification shade, full-screen apps, and landscape. In iMotion, hide an active island by swiping up and restore it near the camera. Check that touch input around an empty capsule passes through.

Observe for jumps, flicker, incorrect touch interception, missing activities, persistent animation, or SystemUI restarts. Camera cutout reporting, window geometry and SystemUI hooks vary across ROMs; behavior on Un1ca and other ROMs requires device verification.

If SystemUI becomes unstable, disable this module or remove its SystemUI scope in LSPosed and restart SystemUI or reboot. If the UI cannot be used, use the recovery or safe-mode procedure supported by the installed LSPosed/Magisk environment. Reinstall the previous known-good APK if necessary. Do not assume that changing the setting alone can recover a crashing SystemUI process.
