# CUSTOM CHANGES — DynamicIsland 0.1.14-dev24 (custom build)

Everything in this document was added or changed on top of the **original author's source** for
`0.1.14-dev24` (`versionCode 111`, package `dev.android.dynamicisland`). The version identity was kept
unchanged on purpose, so the build is recognisable as "0.1.14-dev24 with custom changes" rather than a new
release.

It is organised by *what the change does for the user*, then by *what was touched*. `CHANGES.md` is the
engineering changelog (chronological, with the reasoning and the mistakes); this file is the complete
inventory.

---

## A. Features brought over from the newer source (0.1.31-dev55)

Nine files were self-contained enough to take whole; the rest were merged by hand.

### A1. Charging — a persistent session card
`island/source/ChargingSource.java` (248 → 372 lines).

0.1.14 showed a short popup when the charger was plugged in. The card now stays for the whole charging
session and reports:
* live current (`BATTERY_PROPERTY_CURRENT_NOW`), voltage, power (V × I) and battery temperature;
* what the session has gained — time, percent and mAh;
* the gain split by screen-on vs screen-off time.

Gated by **Charging info** (`charging_info`). When it is on, the short popup is suppressed so the two do
not both appear; when it is off, the popup is the fallback.

### A2. Hotspot card — new
`island/source/HotspotSource.java` (new, 417 lines).

A card that stays while the hotspot is on: connected devices, the traffic of this session and how long it
has been up. It is the only feature that needed new strings (`setting_hotspot_card`, `..._desc`).
The old "hotspot on" toggle announcement is suppressed in favour of the card; only switching it **off** is
still announced, briefly. Gated by **Hotspot card** (`hotspot_card`).

### A3. Headset — a card instead of an announcement
`island/source/HeadsetSource.java` (117 → 160 lines).

Analogue and USB headsets now keep a card while connected, show a short popup on unplug, and seed from the
sticky `ACTION_HEADSET_PLUG` broadcast so opening the module does not announce headphones that were
plugged in hours earlier. Gated by **Headset events** (`event_headset`).

### A4. Notification handling (`PeekSource`, `ProgressSource`)

* **Group summaries** (Telegram and friends) were rejected outright, so a group's messages only ever showed
  the stock banner. They are now accepted when they carry text.
* **A notification's own progress bar** is kept on the card, and `isShownOnIsland(notificationKey)` lets
  the caller drop the matching shade row.
* **Foreground-app progress**: the app you are currently in was skipped, so a download started there never
  appeared. Now only notifications One UI already shows as a live card are excluded, a real progress bar is
  preferred over a live card, and text-only progress ("Downloading 73 %") plus non-ongoing notifications
  that carry a bar are accepted.

### A5. Smaller corrections
* `RecordingClock` — ignores sub-second timestamp jitter instead of accumulating resets.
* `CallDetector` — returns null while a call is ringing, so the stock answer UI shows.
* `TorchSource` — Samsung `isTorchStrengthControllable` / `setFlashlightLevel`.

### A6. Heads-up repeat suppression and held notifications
`island/HeadsUpTakeover.java`, merged by hand.

* **Repeat suppression**: an identical re-post inside **180 s** no longer re-pops the card. Telegram re-posts
  on read, sync and edit — the original comment cites one notification taken fifteen times in eight minutes.
* **Held notifications**: notifications arriving while the island is hidden (fullscreen app, screen off) were
  dropped entirely. Up to **12** are now queued for **20 s** and replayed via `retryPending()`, which is
  called from `IslandController.refreshNow()`.

### A7. Peek pinning and waveform
* `island/view/Director.java` — `peekKey` pins a peek to its own card, so a refresh cannot grow it into the
  whole stack.
* `island/view/IslandView.java` — the waveform is no longer gated on the visualiser setting; it follows the
  item's own playing state.

### A8. Labels can never leak a resource key
`island/IslandText.java` + `island/IslandTextTable.java`.

The island draws inside System UI, which cannot always read this module's resources. The labels are
compiled into the dex, and `prettify()` is the last resort, turning `island_do_not_disturb` into
"Do not disturb".

### A9. Compatibility shims (0.1.14 keeps its own callers)
* `CallDetector` — the newer file had dropped `isPending` / `isEnded` / `elapsedMillis`, which 0.1.14's
  `WhatsAppCallClock` calls. Restored.
* `TorchSource` — the newer file dropped `onControlsExpanded(boolean)`, which 0.1.14's `IslandController`
  calls. Added as a documented no-op; the newer card ends on the torch state itself.

---

## B. New features written for this build

### B1. "Unlocked by …" notice
`island/source/UnlockSource.java` (new).

When the lock screen is dismissed, a card appears on the island saying how it was unlocked, with an icon:
**fingerprint**, **face**, **PIN** or **watch**. It opens like a notification (not a pill) and stays
**2.6 s**.

How the method is decided:
* `ACTION_USER_PRESENT` is the trigger — it fires for every unlock whatever the method, so the card always
  appears.
* Biometric hooks record **fingerprint** or **face** with a timestamp; a trust hook records a **trusted
  device** (a paired watch, Smart Lock).
* If one of those happened within **2.5 s**, that is the reason. Otherwise the only way left is the
  PIN/pattern/password, and the card reads **PIN**.

The hooks are **discovered, not guessed**: `hookUnlock()` enumerates the System UI class loader
(`BaseDexClassLoader.pathList → dexElements → DexFile.entries()`), finds every class whose name contains
*biometric* or *keyguardupdatemonitor*, and hooks `onAuthenticationSucceeded`,
`onBiometricAuthenticated` and `onBiometricAuthSuccess` on each. The result is parsed from whatever shape
the callback uses — an `int` (2 fingerprint, 4 iris, 8 face), a `BiometricSourceType` whose `toString()` is
`FINGERPRINT`/`FACE`, or an `AuthenticationResult` with `getAuthenticationType()`.
`AuthController.onAuthenticationSucceeded` is hooked explicitly as well.

It fails soft: if no hook attaches, the card still appears and reports the PIN.

Settings: **Unlock notice** (`unlock_notice`), on by default, on the Events page directly after the headset
row. Labels: `island_unlocked`, `island_unlocked_by_fingerprint`, `..._face`, `..._pin`, `..._watch`
(English and Korean, compiled into `IslandTextTable` as well as the string resources).

### B2. Fingerprint icon
`island/graphics/LottieData.java` — `Kind.FINGERPRINT` plays the supplied Lottie animation
(`fingerprint.json`, 72×72, 60 fps, one layer) instead of the hand-drawn arcs. The drawing remains as the
fallback if the animation cannot be parsed.

Four glyphs were added for this feature: `FINGERPRINT`, `FACE`, `WATCH`, `PIN` in `GlyphIcon`.

---

## C. Bug fixes

### C1. Duplicate notification (island card *and* stock notification)
`island/IslandController.hidesOriginal()` asked `progress`, `promoted`, `recorder`, `rules`, `torch` and
`cast` — but **never `peeks`**, which is the source that shows ordinary notification cards. So when the
island showed a card, nothing told the shade to drop its row, and both appeared.

Added:

```java
|| sources.peeks.isShownOnIsland(notificationKey)
```

### C2. Settings defaults mismatch
`charging_info` and `event_headset` were flipped to default **on** in `ModuleSettings`, but their switches in
`SettingsActivity.eventsPage()` still passed `false` as their default, so they rendered off while the
feature was on. Both now pass `true`.

### C3. Source-peek path was disabled
`IslandController.ENABLE_SOURCE_PEEKS` was `false`, which switched off the mechanism that opens an event
card expanded. `HeadsetSource` and `PeekSource` already set `peekMillis` with the comment *"open expanded
when it arrives"*, so the flag was disabling behaviour they were written for. Now `true`, and
`UnlockSource` sets `peekMillis` so its card opens too.

---

## D. Frosted-glass performance (no visual change)

The classic material is a single `drawRoundRect` with a solid paint. Frosted is a per-pixel AGSL shader, and
four separate costs were making it stutter. **No blur radius, opacity or colour was changed** in any of
these fixes.

### D1. The response field was rebuilt every morph frame
`island/material/FrostedSurface.java`, `Input.update()`. The early-out only fired when the card's width,
height and screen bounds were unchanged — but the rect is animated, so during any open/close it ran every
frame, per card: `MaterialPixels.response()` (1,200 pixels × 3 `pow` + **two** Gaussian blurs ≈ 91,000
multiply-adds), then a fresh `Bitmap` and `BitmapShader` — a new GPU texture per frame, and twice that during
a capture blend.

Now the offsets and scale still follow the card every frame (cheap uniforms), but the field is recomputed at
most every **100 ms** (`FIELD_REFRESH_MS`) and the **bitmap is reused** with `setPixels`.

### D2. A synchronized diagnostics write per frame
`Diagnostics.fact("card material", …)` was called on every draw. It is `public static synchronized` — a
global lock and a `HashMap` put per frame, contending with the capture thread. It is now called only when the
reported state actually changes.

### D3. The surface normal was computed across the whole card
`material/frosted.agsl`. `materialSdf` was called **five times per pixel** — once for coverage, four more to
derive the normal by finite difference — at three `pow()` each: up to **15 `pow` per fragment**. The normal is
only read through `rim` and `roll`, both exactly 0 past depth 64, so the four extra calls are now inside
`if(depth<64.0)`. The interior drops from 15 `pow` to 3 with identical output.

### D4. The superellipse — exact where it is read, cheap where it is not
The card's distance field is a 2.4 superellipse. Replacing it with a plain rounded rect removed the `pow` but
**visibly shrank the edge glow**, because `depth`, `rim` and `roll` all derive from it. That was a
regression and was reverted.

The exact curve is now used everywhere it can be read, and the cheap rounded rect only where it provably
changes nothing:

```glsl
float dRect=length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
if(dRect<-120.0) {
    return dRect;          // rim and roll are zero past depth 64
}
float d=pow(pow(max(q.x,0.0),2.4)+pow(max(q.y,0.0),2.4),1.0/2.4)-r;   // exact superellipse
```

`rim` reaches zero at depth 64, so a 120 px band inside the border is generous; and because the superellipse
extends further into the corners than the rounded rect, a point with `dRect < -120` is deeper than that in
the real shape too. The glow is therefore **identical to the original**.

### D5. Allocation churn during animation
`configureLight()` runs on every frame of a morph and built **five fresh `float[]`** each time (two from
`fade()` plus three literals) — GC jitter during exactly the animation that has to feel fluid. It now writes
the same numbers in place (`fadeInto()`), allocating nothing. The three tone-colour uniforms, which are
constant zero when nothing is playing, are now set once instead of every frame.


### D6. Per-pixel film grain removed
`frosted.agsl`. The shader added `(fract(sin(p.x*12.9898+p.y*78.233)*43758.5453)-.5)/255.0` — a `sin()` on
every fragment for a grain of **1/255**, one step of an 8-bit channel, which the display cannot show.
Removed; the output is unchanged to the eye.

### D7. Redundant shader-input rebinds skipped
`FrostedSurface.Input.bind()` runs on every frame of a morph. It set the input shaders and the response
buffer every time, although those are the same objects unless the field was rebuilt. They are now set only
when they change (`bound` / `boundField`); the offsets, scale and size still update every frame because
those follow the card. Two of five calls per bind are gone, twice per frame (previous and current).

### D8. The card went invisible as the material faded

`StackPainter` draws the solid fill only when `frosted` is false. `FrostedSurface.draw` returned true even at
`materialAmount` 0 — and the shader's last line is `mix(float3(0.0), lit, materialAmount)`, which at 0 is
fully transparent. So while a card collapsed back into the pill, neither path drew anything: the card's shape
vanished and its content kept drawing, which reads as text and album art floating free.

`draw()` now returns false when the amount is at or below 0.001, handing the frame back so the solid fill is
drawn instead.

---

## E. Build system (Gradle-free)

The project is pure Java with one `compileOnly` jar, so it builds with
`aapt2 → javac → d8 → zipalign → apksigner` via `build-gradle-free.sh` (included). Three things Gradle
normally does that the script must do itself:

1. **`FrostedShaderSource.java`** — generated from the AGSL asset, because LSPosed loads the dex without
   exposing the APK's Java resources.
2. **`tools/framework-stubs/.../CompositionSamplingListener.java`** — compiled into `hidden-stubs.jar` for
   javac and **kept out of the dex** (`compileOnly`; shipping it would shadow the framework class).
3. **The i18n catalog** — `i18n/<lang>.properties`, `languages.properties` and
   `rules/builtin-translations.json`, mirroring `gradle/localization.gradle`. `IslandTextTable` carries only
   en + ko, so ja/zh/ru/tr come from these at runtime.

The manifest has no `package` attribute (Gradle supplies it from `namespace`), so
`package="dev.android.dynamicisland"` is injected at link time. `zipalign` uses `-p 4`; the build-tools
release in use has no `-P`, and the app ships no native libraries.

---

## F. Diagnostics added

| Fact | What it tells you |
|---|---|
| `unlock notice hooks` | `authController=N discovered=N` — how many biometric hooks attached |
| `unlock notice classes` | the biometric class names actually found on the device |
| `card material cost` | average µs/frame inside the material draw — CPU vs GPU |

`Diagnostics.report()` also caps every fact value at **400 characters** (`MAX_FACT_CHARS`). The hook
attachment list can run to hundreds of entries and used to push the recent events out of the report.

---

## G. Settings added or changed

| Setting | Key | Default | Where |
|---|---|---|---|
| Unlock notice | `unlock_notice` | on | Events page, after the headset row |
| Hotspot card | `hotspot_card` | on | Events page |
| Charging info | `charging_info` | **on** (was off) | Events page |
| Headset events | `event_headset` | **on** (was off) | Events page |
| Heads-up for every notification | `all_notifications` | **on** (was off) | Notifications page |

---

## H. Every file touched

**New files**
```
island/source/HotspotSource.java
island/source/UnlockSource.java
island/IslandTextTable.java
build-gradle-free.sh
CUSTOM-CHANGES.md  (this file)
CHANGES.md
```

**Backported wholesale**
```
island/source/ChargingSource.java
island/source/HeadsetSource.java
island/source/PeekSource.java
island/source/ProgressSource.java
island/source/RecordingClock.java
island/source/TorchSource.java
island/detect/CallDetector.java
```

**Modified**
```
island/IslandSources.java          hotspot + unlock wiring
island/IslandController.java       peeks clause, retryPending(), ENABLE_SOURCE_PEEKS
island/HeadsUpTakeover.java        repeat window, held queue
island/IslandText.java             label table + prettify
island/graphics/GlyphIcon.java     FINGERPRINT, FACE, WATCH, PIN
island/graphics/LottieData.java    fingerprint animation
island/material/FrostedSurface.java  response field, cost fact, allocation-free lighting
island/view/Director.java          peekKey
island/view/IslandView.java        waveform gating
island/source/EventSource.java     hotspot handoff, showSticky(), charging popup fallback
settings/ModuleSettings.java       new keys, defaults
settings/SettingsActivity.java     unlock toggle, default fixes
xposed/Diagnostics.java            installed version, fact cap
xposed/hooks/SystemUiHooks.java    unlock hooks (discovery), log noise
res/values/strings.xml             new labels
res/values-ko/strings.xml          Korean labels
res/main/resources/.../frosted.agsl  SDF and normal work
```

---

## I. Deliberately not changed

* **The blur itself** — radius, opacity, tint and the COUI inner shadow / edge / G2 lighting are untouched.
  `couiLight()` still runs its own per-pixel SDF; simplifying it would change how the glass reads.
* **0.1.14's own features** — the recording waveform, `refreshFontLayouts`, the ambient glow session and the
  WhatsApp call clock are all still there.
* **The `IslandView` RemoteBody skip** — 0.1.14's font relayout goes through `Director.refreshFontLayouts()`,
  a method the newer tree deleted; the newer per-card rebuild does not apply.
* **The newer tree's removal of the foreground/summary gating** in `HeadsUpTakeover` — a separate behaviour
  change, left as 0.1.14 had it.
* **The version identity** — `versionCode 111`, `versionName 0.1.14-dev24`.

---

## J. Build and install

```
./build-gradle-free.sh          # needs JDK 17, build-tools, platform android-36, R8
```

Install: uninstall any previous Dynamic Island build first (the signing key differs), install, enable the
module in LSPosed, reboot.


---

## K. Open issue — empty expanded card (not yet fixed)

Reported with screen recordings: the island sometimes expands into a large panel with nothing inside it, and
separately the stock heads-up banner appears alongside the island pill.

`StackPainter` explains the first one structurally:

```java
if (alpha <= IslandView.MIN_VISIBLE_ALPHA || c.body instanceof RemoteBody) {
    return;                       // no content drawn at all
}
```

A notification carrying its own `RemoteViews` becomes a `RemoteBody`, and the card deliberately draws no
content for it — the app's own view is expected to be attached as a child. When that child does not attach,
the result is a correctly shaped, completely empty card.

**Not fixed here**, because the cause needs one fact the recordings cannot supply: *which* card it is.
Shipping another reasoned guess is what produced the glow regression earlier, so a diagnostic was added
instead — **`card without content`** records the card key and body class once per card, so the next report
names the offending card directly.

**Verification status:** every claim above was checked structurally on the built APK — manifest, dex
contents, resources, signature. Nothing in this project has been run on a device by the person who built it,
so runtime behaviour (hook attachment, animation smoothness, notification de-duplication) is unverified here.
