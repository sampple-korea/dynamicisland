# Heads-up takeover: implementation and validation

## Behaviour

`NATIVE` preserves the stock banner and excludes that event version from subsequent island scans. `RESERVED` requires an available window, an eligible notification and a verified return path to the heads-up manager. At most four alerts can wait, with a maximum wait of 30 seconds. `PRESENTED` requires an active card accepted by the renderer.

Event identity compares the Android user, notification key, post time and local Notification instance, distinguishing updates with the same timestamp. A local version follows a reservation through presentation.

Returning an alert requires the same entry still registered in the manager, the same notification version and a favourable current `shouldHeadsUpBecomePinned` decision. Removed or replaced entries are not resurrected. Without this return path, an alert that would need to wait stays native and is excluded from later island presentation. Reservations do not extend the lifetime of manager entries.

Calls, alarms, full-screen intents, other profiles, locked users, the lock screen, filtered apps and unavailable UI retain native handling. Experimental recovery also requires confirmed ownership. Verification-code cards keep their independent setting. No takeover or recovery API cancels the actual notification.

## Files and methods

| File | Changes |
|---|---|
| `HeadsUpTakeover.java` | `decide`, `reconcile`, `onUpdate`, `setSnapshot`, `owns`, `show` and scan methods: decisions, reservations, event versions and native-fallback exclusion |
| `HeadsUpHooks.java` | `declarations`, `install`, `notificationOf`, `beforeSetPinned`, `afterPinDecision`, `returnPath` and `dismissStock`: hierarchy discovery, signatures, return paths and bounded recovery |
| `IslandController.java` | `decideHeadsUp`, `updateHeadsUp`, `headsUpEvent`, `refreshNow` and `onIslandFrame`: main-thread integration |
| `IslandPlacement.java` | `canPresent`: attached window, interaction availability and lock state |
| `IslandView.java` | `peek` and `isShowingCard`: presentation confirmation, excluding departing cards |
| `IMode.java` | `isPresenting`: selected activity and active node |
| Tests | `HeadsUpDecisionTest`, `HeadsUpHookDiscoveryTest` and `HeadsUpRecoveryTest` |

The heads-up correction preserves the accompanying local material, colour, Always Visible and notification-morph work. Its presentation check does not change iMotion's drawing or spring curves. Hooks remain in the existing CrashGuard-protected group and use SafeHook, XLog and Diagnostics.

The branch also includes optional notification morphing, application-specific ambient glow, adaptive compositor capture and their tests. These changes were validated together. Always Visible, smart multitasking and partial Brazilian Portuguese localization are inherited from the branch's earlier commits.

## Samsung device checklist

Run from the repository root after building the Debug APK:

```powershell
adb devices
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.android.dynamicisland/.settings.SettingsActivity
```

Enable the module in LSPosed for `com.android.systemui` and restart the device if needed. Select Banners and allow the application used for the test. Initially leave experimental recovery and All notifications disabled.

```powershell
adb shell settings get global dynamicisland_report
adb logcat -d -s DynamicIsland:I
```

Diagnostic publication is throttled to 15-second intervals. Distinguish `heads-up capability` (installed hooks), `heads-up executed ...` (an observed method invocation), `heads-up decision` and `heads-up presentation` (confirmed card acceptance). No single field proves ROM compatibility.

1. Receive a message from another device with the messaging app in the background. Verify one presentation, open the message and exercise its original actions.
2. With music expanded, receive a message, wait for it to close and confirm music restoration. Repeat with interrupted transitions and quick reply.
3. Receive six distinct messages in succession. Check reservations, queue overflow and fallback. A ROM without a verified return path should keep alerts native when they would otherwise need to wait.
4. Update one notification and remove another while it waits. Updated content should replace the same key; removed events must not reappear or trigger stale recovery attempts.
5. Repeat with Always Visible in IDLE, the screen locked, the shade open, the source app in the foreground and the source app filtered out. If the island is unavailable, banner behaviour should follow native policy.
6. Switch between Off, Banners and All notifications, independently enabling and disabling verification-code cards. Repeat with experimental recovery enabled: stock-banner removal must still require ownership.
7. Place a normal call and trigger an alarm. Both should retain their original UI.

## Device observations: November 9, 2026

The authorized ADB connection identified a Samsung SM-S901E running Android 16. The initial diagnostic setting was `null`, and the package lookup found no installation. The Debug APK was then installed successfully using `adb install --no-streaming -r`. Starting SettingsActivity returned `Status: ok`, and its process was running.

Subsequent diagnostics confirmed:

- The module loaded into System UI through LSPosed.
- CrashGuard and the heads-up hook group reported `ok`; the island window was created.
- Three hooks were installed in HeadsUpManagerImpl: `addListener`, `setEntryPinned(HeadsUpEntry, PinnedStatus, String)` and `shouldHeadsUpBecomePinned(NotificationEntry)`.
- Execution of `addListener` was observed.
- `updateNotificationInternal` was not among the installed declarations on this ROM.

There was no observed execution of the two preventive hooks and no confirmed alert presentation. The device entered keyguard during inspection; no unlock or reboot was performed. Visual approval with messages, quick replies and bursts remains pending. These observations do not establish general One UI or Un1ca compatibility.

## Automated validation

With an Android SDK available:

```powershell
# Use the SDK prepared locally, or set ANDROID_HOME to your own SDK.
$env:ANDROID_HOME="$PWD\build\android-sdk"
.\gradlew.bat --no-daemon :app:testReleaseUnitTest :app:assembleRelease :app:assembleDebug
python scripts/translations.py check
git diff --check
```

| Check | Recorded result |
|---|---|
| `testReleaseUnitTest` | Passed: 200 tests across 75 suites, including 18 heads-up tests |
| `assembleRelease` | Passed; produces an unsigned Release APK |
| `assembleDebug` | Passed |
| `git diff --check` | Passed |
| `python scripts/translations.py check` | Passed after completing the 20 visual-feature translation entries; all registered locales have 617/617 strings |

The suite includes existing iMotion tests for music restoration, original actions and interrupted transitions. These automated checks do not replace the physical System UI checklist above.

The initial translation failure was caused by missing `glow_by_app`, `glow_by_app_summary`, `notification_morph` and `notification_morph_summary` entries in ko, ja, zh, ru and tr. These 20 entries are now translated. The checker still requires complete registered locales and validates draft keys and formats. The incomplete `values-pt` and `values-pt-rBR` counts are informational because these drafts are not registered in locales_config.xml.

## Build artifacts

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, installed on the test Samsung.
- Recorded Debug SHA-256: `94EC590D9F691717285522D474A704A397DC4202A9748ED5899279E84FBE1A09`.
- Unsigned Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.

Build outputs and the locally prepared SDK are ignored by Git and are not included in the implementation commit. Rebuilding may produce a different APK hash.

For the legacy signatures, see [HeadsUpManagerImpl in AOSP](https://android.googlesource.com/platform/frameworks/base/+/5e4e4a9abd183ec61c0e24961213932e339d0a56/packages/SystemUI/src/com/android/systemui/statusbar/notification/headsup/HeadsUpManagerImpl.java). Source availability alone does not demonstrate execution on a device.
