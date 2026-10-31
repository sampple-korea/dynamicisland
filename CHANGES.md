# Changes made to DynamicIsland 0.1.14-dev24

Everything below was applied to the author's **0.1.14-dev24** tree (`versionCode 111`, `versionName
0.1.14-dev24`), which is kept as the base. Nothing from the newer 0.1.31-dev55 tree was copied
wholesale except the files listed in §2.

Two kinds of change are recorded:

* **§1 Build** – how the APK is produced without Gradle (needed to reproduce this build).
* **§2 Backport** – features brought over from the newer source.
* **§3 Performance** – the frosted-glass lag/jitter fix.

---

## §1 Build (Gradle-free)

The project is pure Java with one `compileOnly` jar, so it is built with
`aapt2 → javac → d8 → zipalign → apksigner` by `build-gradle-free.sh` (included).

Three things Gradle normally does that the script must do itself:

1. **`FrostedShaderSource.java`** — generated from `src/main/resources/.../frosted.agsl`, because
   LSPosed loads the dex without exposing the APK's Java resources.
2. **`tools/hidden-api-stubs/…/CompositionSamplingListener.java`** — compiled separately into
   `hidden-stubs.jar` for javac and **kept out of the dex** (`compileOnly`; shipping it would shadow
   the framework class). The script referenced `$OUT/hidden-stubs.jar` but never created it.
3. **The i18n catalog** — `i18n/<lang>.properties` + `languages.properties` +
   `rules/builtin-translations.json`, mirroring `gradle/localization.gradle`. `IslandTextTable` only
   carries en+ko, so ja/zh/ru/tr come from these files at runtime.

Also: the manifest has no `package` attribute (it comes from `namespace` in `build.gradle`), so
`package="dev.android.dynamicisland"` is injected at link time, and `zipalign` uses `-p 4`
(`-P 16` is not supported by the build-tools release in use and there are no native libs to align).

---

## §2 Backport from the newer source (0.1.31-dev55)

### 2.1 Files taken wholesale (self-contained)

| File | Change |
|---|---|
| `island/source/HotspotSource.java` | **new** (417 lines) – hotspot card: connected devices, traffic, uptime, no expiry |
| `island/IslandTextTable.java` | **new** – labels compiled into the dex |
| `island/source/ChargingSource.java` | 248 → 372 – persistent session card: live current (`BATTERY_PROPERTY_CURRENT_NOW`), voltage, power, temperature, session gain split by screen-on/off |
| `island/source/HeadsetSource.java` | 117 → 160 – card while connected, popup on unplug, seeded from the sticky `ACTION_HEADSET_PLUG` |
| `island/source/PeekSource.java` | group summaries accepted when they carry text; notification's own progress kept on the card; `isShownOnIsland(notificationKey)` |
| `island/source/ProgressSource.java` | the foreground app is no longer skipped; prefers a real bar over a live card; accepts text-only progress |
| `island/source/RecordingClock.java` | ignores sub-second timestamp jitter instead of accumulating resets |
| `island/detect/CallDetector.java` | returns null while a call is ringing so the stock answer UI shows |
| `island/source/TorchSource.java` | Samsung `isTorchStrengthControllable` / `setFlashlightLevel` |

### 2.2 Wiring

* `res/values/strings.xml` – `setting_hotspot_card` + `setting_hotspot_card_desc`.
* `settings/ModuleSettings.java` – `KEY_HOTSPOT_CARD`, `hotspotCard` field, read, and
  `hotspotCard()` getter. **`chargingInfo`, `eventHeadset` and `hotspotCard` now default to `true`**
  (0.1.14 defaulted them off; with the old defaults the new cards would never appear).
* `island/IslandSources.java` – `HotspotSource` field, construction, `start()` and `collect()`.
  `start()` is called once (not from `syncOptionalSources()`, which `IslandController` calls on every
  settings change and would double-register the receivers); `syncOptionalSources()` is now a
  documented no-op because the device cards read their settings at collect time.
  The `charging.isOffering()` de-dupe was dropped – the new `ChargingSource` does that itself.

### 2.3 Merged by hand (NOT copied wholesale – shared with 0.1.14's own work)

* **`island/HeadsUpTakeover.java`**
  * `REPEAT_QUIET_MS` (180 s) + `shownAt`/`shownText`/`recentlyShown()`/`textOf()` – an identical
    re-post inside the window no longer re-pops the card.
  * `pending` queue (`PENDING_MS` 20 s, `MAX_PENDING` 12) + `holdForLater()` + `retryPending()` –
    notifications arriving while the island is hidden are held and replayed instead of dropped.
  * `IslandController.refreshNow()` calls `headsUp.retryPending()` after `view.setItems(...)`.
* **`island/view/Director.java`** – `peekKey`, so a refresh cannot grow a peek into the whole stack.
* **`island/view/IslandView.java`** – the waveform is no longer gated on `ModuleSettings.visualizer()`.

### 2.4 Applied in a second pass (missed the first time)

* **`island/IslandText.java`** – replaced with the version that reads `IslandTextTable` and falls back
  to `prettify()`. Previously the table was dead code and labels could still leak a raw key.
* **`xposed/Diagnostics.java`** – `version()` reads the module's own `PackageInfo`, so a log reports the
  installed build instead of a constant.
* **`settings/ModuleSettings.java`** – `allNotifications` defaults to `true` (heads-up for every
  notification).
* **`island/source/EventSource.java`** – the hotspot toggle is handed to the card (only switch-off is
  still announced), `showSticky()` added, and the charging popup gated on `!chargingInfo()` so it is
  only the fallback when the card is off.

### 2.5 Compatibility shims (needed because 0.1.14 keeps its own callers)

* **`CallDetector`** – the newer file had dropped `isPending`/`isEnded`/`elapsedMillis`, which
  0.1.14's `WhatsAppCallClock` calls. Restored (the patterns were still present).
* **`TorchSource`** – the newer file dropped `onControlsExpanded(boolean)`, which 0.1.14's
  `IslandController` calls. Added as a documented no-op; the new card ends on the torch state itself.

### 2.6 Deliberately NOT taken

* The `IslandView` RemoteBody skip. 0.1.14's font relayout goes through `Director.refreshFontLayouts()`,
  a method the newer tree deleted; transplanting the newer per-card body rebuild would mean rewriting
  0.1.14's layout path for a guard that is not needed here.
* The newer tree's removal of the foreground/summary gating in `HeadsUpTakeover` – a separate
  behaviour change, kept as 0.1.14 had it.

---

## §3 Frosted-glass lag and jitter

### Symptom
Lag and jitter when the **frosted** material is selected. The classic path is smooth.

### Cause 1 – the response field was rebuilt every morph frame
`island/material/FrostedSurface.java`, `Input.update()`. The early-out only fires when the card's
width, height and screen bounds are unchanged – but the card rect is animated (`opening`/`Easing.*` in
`StackPainter`), so during any open/close/expand it ran **every frame, per card**:

* `frame.pixels.response(...)` over a 40×N grid – for 40×30 that is 1,200 pixels × 3 `sample()` and
  **3 `Math.pow`** in `linear()` ≈ **3,600 `pow` calls**;
* `gaussian(luma)` **and** `gaussian(square)` – 2 passes × 19 taps × 1,200 ≈ **91,000 multiply-adds**;
* `Bitmap.createBitmap(...)` + `new BitmapShader(...)` – a **new bitmap and a new GPU texture every
  frame**, and during a capture blend for both `previous` and `current`.

### Cause 2 – a synchronized diagnostics write per frame
`FrostedSurface.draw()` called `Diagnostics.fact("card material", …)` on every draw. It is
`public static synchronized` – a global lock and a `HashMap` put per frame – and the capture thread
calls `Diagnostics.fact` as well, so it also contended across threads.

### Fix applied
* `Input.update()` now separates geometry from content: the offsets and scale still follow the card
  every frame (cheap uniforms), but the response field is recomputed at most every **100 ms**
  (`FIELD_REFRESH_MS`) and the **bitmap is reused** with `setPixels` instead of being reallocated.
  A blurred, low-frequency map does not need rebuilding at 60–120 Hz.
* `Diagnostics.fact("card material", …)` is only called when the reported state actually changes.

Net: that ~91k multiply-adds + 3.6k `pow` + bitmap/texture upload goes from **every frame** to **ten
times a second**, with no per-frame allocation.

### Left alone on purpose
* The ~10 `setFloatUniform` calls per frame – needed for the phase/palette animation, cheap.
* The AGSL shader itself – its GPU cost is inherent to the material, not a bug.
* `StackPainter:252-253` re-requests a frame whenever the frosted draw returns `false`. If the capture
  never lands this is a self-sustaining redraw loop – the next thing to look at if jitter remains.

---


---

## §4 Unlock notice (new feature)

An "Unlocked by …" card on the island when the lock screen is dismissed, in the spirit of the iPhone's
unlock line. New file **`island/source/UnlockSource.java`**.

### How the method is decided
* **`ACTION_USER_PRESENT`** is the trigger. It fires for every unlock whatever the method, so the card
  always appears.
* `SystemUiHooks.hookUnlock()` installs two optional hooks on the keyguard monitor – both
  `com.android.systemui.keyguard.KeyguardUpdateMonitor` and the older
  `com.android.keyguard.KeyguardUpdateMonitor` are tried, because the class moved package between
  releases:
  * `onBiometricAuthenticated(int, BiometricSourceType, boolean)` → records fingerprint or face plus a
    timestamp;
  * `onTrustChanged(...)` → records a trusted device (a paired watch, Smart Lock).
* At user-present: a biometric or trust event within **2.5 s** explains the unlock, so the card reads
  *fingerprint*, *face* or *watch*. Otherwise the only way left is the PIN/pattern/password, and the card
  reads *PIN*.

If neither hook attaches, the notice still appears and reports the PIN – it degrades, it does not break.

### Files touched
| File | Change |
|---|---|
| `island/source/UnlockSource.java` | **new** – receiver, classification, item, expiry |
| `island/graphics/GlyphIcon.java` | `FINGERPRINT`, `FACE`, `WATCH`, `PIN` added to `Kind` + four drawings |
| `xposed/hooks/SystemUiHooks.java` | `hookUnlock(cl)` registered as the `unlock notice` install group |
| `island/IslandSources.java` | field, construction, `start()`, `collect()` |
| `settings/ModuleSettings.java` | `KEY_UNLOCK_NOTICE` + `unlockNotice()` (default **on**) |
| `settings/SettingsActivity.java` | toggle next to the headset one |
| `island/IslandTextTable.java` | five labels compiled in (en + ko) |
| `res/values/strings.xml`, `res/values-ko/strings.xml` | the labels and the setting text |

### Notes
* The card lives 2.6 s (`CARD_MS`) and is removed by a scheduled refresh, the same way the other event
  islands expire.
* Labels go through `IslandTextTable` as well as the string resources, so they resolve even when the
  island cannot read this module's resources.
* Wording is "Unlocked by face" rather than "Face ID", which is Apple's name for it.


### Fixes after the first test (reported: fingerprint showed as PIN, card did not open)

**1. The method was always PIN.** The `KeyguardUpdateMonitor.onBiometricAuthenticated` hook never
attached on the test build, so the fallback always won. The primary signal is now
**`com.android.systemui.biometrics.AuthController.onAuthenticationSucceeded`** – the biometric prompt's
own callback. Its `AuthenticationResult` carries `getAuthenticationType()`, which names the sensor:
**2 = fingerprint, 4 = iris, 8 = face**. The prompt reports there regardless of what the keyguard
monitor does, and the method has kept that shape across releases. `KeyguardUpdateMonitor` is kept as a
fallback (it is also the only place the trust state is reported).

A new diagnostic, **"unlock notice hooks"** (`authController=N keyguardMonitor=N`), records how many
hooks attached, so a build where they miss is visible in the app instead of silently reading PIN.

**2. The card did not open.** Two independent reasons:
* `IslandController.ENABLE_SOURCE_PEEKS` was **`false`**, which switched off the whole source-peek path.
  `HeadsetSource` and `PeekSource` already set `peekMillis` with the comment "open expanded when it
  arrives", so the flag was disabling behaviour they were written for. Now `true`.
* `UnlockSource` never set `peekMillis`. `SourcePeekRequests` only opens a `Kind.EVENT` item when
  `peekMillis > 0`, so the notice now sets it to `CARD_MS`.

(`AutoOpenCards` would not have helped: it only accepts `task:`, `oa:`, `rec:`, `live:` and `rule:`
keys, and this item is `event:unlock`.)


### Fixes after the second test (log from the device)

The diagnostic log from 0.1.14-dev24 showed the real problem:

```
hooks not attached [com.android.keyguard.KeyguardUpdateMonitor#onBiometricAuthenticated,
com.android.systemui.keyguard.KeyguardUpdateMonitor#onBiometricAuthenticated,
com.android.systemui.keyguard.KeyguardUpdateMonitor#onTrustChanged, ...]
```

**Both candidate keyguard classes fail to attach on One UI 9 / SDK 36.** Guessing class names does not
work here, so `hookUnlock()` no longer guesses:

* The System UI class loader is enumerated (`BaseDexClassLoader.pathList → dexElements → DexFile.entries()`)
  and every class whose name contains **biometric** or **keyguardupdatemonitor** is hooked by method name
  across `onAuthenticationSucceeded`, `onBiometricAuthenticated` and `onBiometricAuthSuccess`.
* The result is parsed from whatever shape the callback uses – an `int` (2 fingerprint, 4 iris, 8 face), a
  `BiometricSourceType` whose `toString()` is `FINGERPRINT`/`FACE`, or an `AuthenticationResult` with
  `getAuthenticationType()`. All three are accepted, so the release's choice of signature stops mattering.
* `AuthController.onAuthenticationSucceeded` is still hooked explicitly.
* `onTrustChanged` is attached on whatever keyguard-monitor class the scan finds.

Two new diagnostics make the next round conclusive:
**"unlock notice hooks"** (`authController=N discovered=N`) and **"unlock notice classes"**, which lists the
biometric class names that were found on the device.

### Settings defaults mismatch (introduced earlier, fixed)

`charging_info` and `event_headset` were flipped to default **on** in `ModuleSettings`, but the switches in
`SettingsActivity.eventsPage()` still passed `false` as their default, so they would render off while the
feature was on. Both now pass `true`, matching. The unlock toggle is on the same page, directly after the
headset row.


### Fingerprint icon (supplied Lottie animation)

`Kind.FINGERPRINT` now plays the animation supplied in `files_3.zip` (`fingerprint.json`, 72x72, 60 fps,
1 layer) instead of the hand-drawn arcs.

* `LottieData` gained `d_fingerprint()` and the `case "fingerprint"` lookup. The supplied
  `LottieData.java` and ours differed by exactly that one animation (18 entries vs 17), so nothing else
  in that generated file was touched.
* `GlyphIcon.animationName()` already mapped the kind to `"fingerprint"`, so no other wiring was needed.
* The canvas drawing stays as the fallback when an animation cannot be parsed.

**Note on this build:** the working tree had reverted to a snapshot taken *before* the unlock feature, so
a first build of this change produced an APK with no unlock notice at all. It was caught before delivery
by checking the dex for the feature markers, the tree was restored from the saved source, and the
fingerprint change re-applied on top. The delivered dex is verified to contain both.


---

## §5 Notification duplicates, and a log that could be read

### The duplicate (reported: sometimes the island card and the stock notification both show)

`IslandController.hidesOriginal()` decides whether a notification shown on the island should lose its own
row in the shade. It asked `progress`, `promoted`, `recorder`, `rules`, `torch` and `cast` - but **never
`peeks`**, and `PeekSource` is the source that shows ordinary notification cards.

The newer tree has that clause, with a comment describing exactly the reported symptom: *"a notification
shown as an island card must not keep its own row in the shade: the screenshot shows both at once, the
card floating over its own notification."* It was missing from the backport because 0.1.14's own
`PeekSource` had no `isShownOnIsland()`; the backported one does, and nothing called it.

Added:

```java
|| sources.peeks.isShownOnIsland(notificationKey)
```

That is the whole fix for "the card and the notification both appear". The other half of the report -
"sometimes the notification does not appear" - is the `take()` gating in `HeadsUpTakeover`
(`PeekSource.eligible`, the foreground test, the summary preference), which was deliberately left as
0.1.14 had it; it needs a log with the recent-events section intact to diagnose, which is what the next
item restores.

### The log was unusable

The unlock-notice class scan attaches hooks to hundreds of class/method pairs and most do not exist, so
`hooks not attached` grew into a line of several hundred entries and pushed the **recent events** section
out of the report entirely - which is why the supplied log has no notification history at all.

`Diagnostics.report()` now caps every fact value at **400 characters** (`MAX_FACT_CHARS`) with a trailing
`...`, so no single fact can crowd out the events again.

### A second working-tree revert (caught before delivery)

The tree had again reverted to the snapshot from before the unlock feature, so the first build of these
fixes contained neither the unlock notice nor the fingerprint animation. Caught by checking the built dex
for the feature markers, the tree was restored from the saved source and both fixes re-applied. The
delivered dex is verified to contain: `d_fingerprint`, `island_unlocked_by_*`, the unlock diagnostics,
`recordBiometric`, `FIELD_REFRESH_MS`, `HotspotSource`, `peekMillis` and `MAX_FACT_CHARS`.


### Cause 4 – fifteen pow() per fragment in the card's own SDF

`frosted.agsl`, `materialSdf()`. The card's distance field was a 2.4 superellipse:

```glsl
float d=pow(pow(max(q.x,0.0),2.4)+pow(max(q.y,0.0),2.4),1.0/2.4)-r;
```

Three `pow()` per call, and `main()` called it **five times per pixel** - once for coverage and four more
for the surface normal. After the earlier gate removed the four from the card's interior, the remaining
three were still paid by every fragment, and `pow` compiles to `exp2/log2` (several cycles each). On a
1000x1400 card that is millions of them per frame, which is why the classic solid fill - a single
`drawRoundRect` - stayed smooth while the frosted material could not.

Replaced with the standard rounded-rect SDF, which uses no `pow` at all:

```glsl
float2 q=abs(p)-halfSize+r;
return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
```

A true circular corner instead of a superellipse; at these radii the two are indistinguishable. The
border band still evaluates it four extra times for the normal, but those are now cheap.

**What remains, and how to tell where it is.** `couiLight()` - the approved COUI inner shadow, edge and
G2 lighting - runs one more SDF of its own over every pixel, and that one still uses `pow`. It is left
alone deliberately: it is the approved look. To decide whether what is left is CPU or GPU, a new fact
**`card material cost`** reports the average time spent inside the material draw, in microseconds per
frame, over the last five seconds. On a hardware canvas that call only records work and the shader runs
later on the render thread, so a small number there alongside visible jitter means the remaining cost is
on the GPU and the material itself has to be simplified further; a large number means it is on this
thread and there is still Java to fix.


### Correction to Cause 4 – the glow was reduced, and that was wrong

Replacing the 2.4 superellipse with a plain rounded rect did remove the `pow`, but it also changed the
look: `depth`, `rim` and `roll` are all derived from that distance field, and a rounded rect's
inside-distance grows faster than a superellipse's, so `rim` shrank and the edge glow faded sooner. The
user spotted it immediately. A visual regression is not an acceptable price for frame time.

The shape is now exact wherever it is read, and cheap only where it provably is not:

```glsl
float dRect=length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
if(dRect<-120.0) {
    return dRect;                      // rim and roll are zero past depth 64, so nothing here reads the curve
}
float d=pow(pow(max(q.x,0.0),2.4)+pow(max(q.y,0.0),2.4),1.0/2.4)-r;   // exact superellipse
```

`rim` reaches zero at depth 64, so a 120px band inside the border is generous. The superellipse also
extends further into the corners than the rounded rect, so a point with `dRect < -120` is deeper than
that in the real shape as well - the cheap branch is only taken where the exact curve changes nothing.
The glow is therefore identical to the original material; the saving is confined to the interior.


### Cause 5 – a sin() per fragment for invisible grain, and redundant rebinds

* `frosted.agsl` added film grain of amplitude **1/255** — one step of an 8-bit channel — computed with a
  `sin()` on every fragment. Removed; the output is identical to the eye.
* `FrostedSurface.Input.bind()` set the input shaders and the response buffer on every frame of a morph,
  although those are the same objects unless the field was rebuilt. They are now set only when they change;
  the offsets, scale and size still update every frame. Two of five calls per bind, twice per frame.

These are the last changes available that do not alter the material's appearance. What is left is
`couiLight()` — the COUI inner shadow, edge and G2 lighting, one more per-pixel SDF of its own — and
simplifying that changes how the glass reads, so it is not touched without an explicit decision.

### Cause 6 – an invisible card while the material fades

`StackPainter` only draws the solid fill when `frosted` is false, and `FrostedSurface.draw()` returned true
even when `materialAmount` was 0. The shader ends with `mix(float3(0.0), lit, materialAmount)`, which at 0 is
transparent, so during a collapse neither path drew the card: the shape disappeared and the content kept
drawing. `draw()` now returns false at an amount of 0.001 or less, so the solid fill takes over.

## Verification performed

* Build: `aapt2` link, `javac` (0 errors), `d8`, `zipalign`, `apksigner verify` → **signature OK**.
* Dex: all nine backported sources present; `prettify`, `IslandTextTable`, `showSticky`,
  `getLongVersionCode`, `island_setting_changed`, `REPEAT_QUIET_MS`, `retryPending`, `peekKey`,
  `setting_hotspot_card`, `FIELD_REFRESH_MS`, `lastMaterialState`, `fieldBitmap` all present.
* 0.1.14's own features still present: `WhatsAppCallClock`, `glowSession`, `refreshFontLayouts`.
* Unlock notice: `UnlockSource` in the dex, the four labels compiled into `IslandTextTable`,
  `android.intent.action.USER_PRESENT` registered, `AuthController.onAuthenticationSucceeded` /
  `getAuthenticationType` / `onBiometricAuthenticated` / `onTrustChanged` hook names present,
  `peekMillis` on the notice so it opens.
* `CompositionSamplingListener` and `XposedBridge` are **not** defined in the dex (both compileOnly).

**Not verified:** nothing has been run on a device. All of the above is structural inspection of the
built APK, not runtime behaviour.
