# System UI compatibility

The module hooks Samsung's System UI by name, so each One UI version has to be checked. This page says what is checked and how to add a version.

## Status

| One UI | Android | Status |
|---|---|---|
| 9 | 17 (SDK 37) | the code has branches for it; not confirmed on a device |
| 8 | 16 (SDK 36) | the module was written against it; tested on one device only |
| 7 | 15 (SDK 35) | added in 0.1.7 from a System UI build (Android 15, version code 35): the hook targets were checked against it by name and attach; not tested on a device. The blurred shade cards and the shade media player takeover are off here (those rows do not exist in One UI 7), and the screen-recording / screen-sharing card is not available |

"Tested" means used on a real phone. Nothing here is tested on more than the author's own device.

## How a build is checked

1. Get the System UI of that One UI version (`/system_ext/priv-app/SystemUI/SystemUI.apk`, or its dex files).
2. Run `python3 scripts/check_systemui.py <SystemUI.apk>`. It lists the classes, methods and fields the module touches that the build does not have, with the source file that uses each one.
3. A missing class means a hook group will not attach on that build. Each group is installed on its own (`SystemUiHooks.install`), so one missing group does not take the others down; the diagnostics report lists them under **hooks not attached**.
4. For each miss, either find the name the build uses instead and hook that as well (the hooks are written so that a missing target is skipped, not fatal), or leave the feature off on that version.
5. The report of **Tools → Diagnostics** shows the One UI version (`one ui`), the System UI build (`system ui`) and the hooks that did not attach. Ask testers on a new version to send it.

Names are matched by name only (any class), so a hit from the script is a hint, not a proof; view ids are not in the dex and are checked with `aapt2 dump resources`, as the script prints.

## One UI 7 specifics

What differs from One UI 8 and how the module handles it (found by comparing the symbols with `scripts/check_systemui.py`):

- **Hiding the stock capsule.** One UI 7 has no status bar binder; the module makes `OngoingActivityController.shouldVisible` answer "no" and stops the chip animation controller from showing the capsule (`animateChipShow`, `showView`, `nudgeAnimation`). Chosen at start-up by whether the binder class exists (diagnostics: `chip style`).
- **Calls.** There is no Samsung call controller; the AOSP `OngoingCallController` is used, as on Android 16. Its call info has no package, app name or icon, so the package comes from the notification key and the label from the package manager.
- **Heads-up.** `HeadsUpManagerImpl` is called `HeadsUpManagerPhone` (written as `new|old` alternatives, see below).
- **Shade.** The blurred notification cards (`isBlurBackground`) do not exist, so the card theme and the media player takeover are not installed and the stock rows stay (diagnostics: `shade rows`).
- **Not available.** The launcher exit animation (`IOnGoingChipListener`) and the screen-record / cast chip model are missing; the module skips them.

## Name alternatives

Any class, method or field name in a `Refl` call may be written `"new|old"`: the first that exists on the running build is used, and a name that was found only under an older alternative appears in the report as `older names used`. Put the newest System UI's name first.
