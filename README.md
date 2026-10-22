[![English](https://img.shields.io/badge/English-2F80ED?style=flat-square)](README.md) [![한국어](https://img.shields.io/badge/%ED%95%9C%EA%B5%AD%EC%96%B4-555?style=flat-square)](README.ko.md)

# Dynamic Island

A Dynamic Island for Samsung One UI: replaces the status bar Now Bar capsule with a pill and cards, and turns ordinary notifications into live cards.

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) ![LSPosed](https://img.shields.io/badge/LSPosed-Xposed%20API%2093-6f42c1) ![License: MIT](https://img.shields.io/badge/License-MIT-blue)

## Features

- **Live notifications as cards:** Every System UI ongoing activity gets a compact pill and an expanded card: header, chronometer, description, plain / segmented / indeterminate progress, icon or text buttons, custom chip views, work profile badge. Calls, cast, screen recording, timers and a music player (seek bar, art-tinted glow, waveform, output route) are included.
- **Ordinary notifications as cards:**
  - Messages and banners open as an island card instead of the stock heads-up banner (calls and alarms keep the stock banner).
  - Verification codes get a card with a Copy button (optional auto-copy, marked sensitive).
  - Downloads, copies and updates become progress cards, including progress written only as text ("73%", "12 MB / 40 MB"). The original shade row can be hidden while the card is shown.
  - Voice-chat and VoIP calls get a call card with the running time and an end button.
- **Rules as data:** Delivery, pickup, parcel, ride, route, wearable and battery cards come from a rule file in LiveBridge's dictionary format. The module ships its own Korean rule file, built from a year and a half of recorded notifications; nothing is downloaded and the module has no network permission. Add your own rules from the clipboard.
- **System events:** Charging, low battery, sound mode, Bluetooth audio with battery, camera / microphone / location in use, and quick-setting feedback (Do Not Disturb, airplane, power saving, torch, hotspot, auto-rotate).
- **App control:** A blacklist or whitelist of apps for the ordinary-notification features. Live notifications, media, calls and system events are never filtered.
- **Motion:** Spring-based animations with squash and stretch, a liquid bridge between pill and card, and cards that grow out of the pill.
- **One UI settings app:** A hub of grouped cards with sub pages, in Korean and English.
- **Safety:** A crash guard switches the module off after repeated System UI crashes, hook groups are isolated, and a diagnostics page shows what the module did inside System UI.

## Requirements

- A Samsung device on One UI 8 / 9 (Android 16 / 17) with the stock System UI.
- LSPosed (module minimum SDK 35).
- Root is only needed for the optional **Restart System UI** button.

## Install

1. Install a signed Dynamic Island APK from the project's **Releases**, if available. Otherwise, build it from source (below).
2. In LSPosed, enable **Dynamic Island** and set its scope to **System UI** (`com.android.systemui`).
3. Restart System UI (the app's **Restart System UI** button, or reboot).
4. Open Dynamic Island from the launcher. Changes apply within a few seconds, no restart needed.

## Settings

| Page | What it holds |
|---|---|
| Display and motion | Card header, audio waveform, haptics, auto-collapse and paused-music timers |
| Notification cards | Notifications in the island, progress cards, verification codes, voice-chat calls |
| System events | Charging, sound mode, Bluetooth, setting changes, privacy indicators |
| App control | Blacklist / whitelist and the app list |
| Rules | A switch per card rule, stages, apps and wording of each rule (add your own), a tester that shows what a notification text becomes, import / export |
| Tools | Restart System UI, diagnostics |

## If it does not work

Open **Tools → Diagnostics** in the app after restarting System UI. It lists the System UI version, the start-up stages, hooks that could not attach, errors and the previous crash. Use **Copy report** and send it with the issue.

## Build from source

Requires JDK 17+ and the Android SDK (compile SDK 36).

```bash
./gradlew assembleRelease     # unsigned APK in app/build/outputs/apk/release
KEYSTORE_PASSWORD=... scripts/sign.sh   # builds, aligns and signs -> release/DynamicIsland-<version>.apk
./gradlew testReleaseUnitTest # unit tests and headless render tests
```

`scripts/sign.sh` takes the keystore password from `KEYSTORE_PASSWORD` and creates a self-signed key in `release-keys/` on first run (git-ignored). Keep it: updates must be signed with the same key. See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for the source layout, hook notes, rules format and test setup.

## Releases

Signed APKs are published on the [Releases](../../releases) page; the build is automated (see [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)). Every release is signed with the same key, so a new version installs as an update over the old one.

## Privacy

The module runs inside System UI and reads the notifications System UI already holds. It has no network permission and sends nothing anywhere. Settings and the diagnostics report stay on the device; the report holds no notification text.

## Disclaimer

Dynamic Island is an independent, unofficial project and is not affiliated with or endorsed by Samsung. It hooks private System UI classes that may change with any update, and it has been verified only in a headless test and render environment, not on a device. Use it at your own risk.

## Credits

- [LiveBridge](https://github.com/appsfolder/livebridge) — the rule file format this project reads (no LiveBridge files are bundled)
- @cryyear's DynamicIsland ([github.com/Spottq](https://github.com/Spottq)), which inspired this project; the code here is a new implementation
- [Xposed API](https://api.xposed.info/) (Apache-2.0, `app/libs/xposed-api-82.jar`) and [LSPosed](https://github.com/LSPosed/LSPosed)

## License

[MIT](LICENSE) © 2026 sampple-korea.

## Made by [sampple-korea](https://github.com/sampple-korea)
