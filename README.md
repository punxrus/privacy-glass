# Privacy Glass

An Android app for **XREAL glasses + Pixel 10**. The phone screen goes black while the glasses keep showing the normal mirrored picture. You still tap and swipe on the phone screen as usual, so it works like a touchpad that shows nothing.

## How it works

Pixel phones (8 and newer) send video over USB-C (DisplayPort Alt Mode), and by default they mirror the phone screen to the glasses. Privacy Glass hides the picture on the phone panel only, using one of two methods:

| Method | What it does | Needs | Touch |
|---|---|---|---|
| **Overlay A / B** (default) | Covers the phone with black flagged *skip screenshot* (A: the overlay window itself; B: a separate layer inside it). Android draws that kind of layer only on the built-in panel, never on external displays, recordings or screenshots, so the glasses don't see it. The overlay is an accessibility overlay, which Android lets touches pass through. | Nothing extra | Always works |
| **Panel off** (fallback) | Turns the phone panel off with DisplayManager's hidden `requestDisplayPower()`, the same call scrcpy uses. Android stays awake and keeps mirroring. | [Shizuku](https://shizuku.rikka.app/) | May stop on some phones: the touch chip can sleep when the panel turns off |

Both methods rely on undocumented Android behaviour, so **test before trusting**: the app has a "Test for 10 seconds" button that always restores the screen.

## Install

1. On the phone, open this repo's **Releases** page. Download `PrivacyGlass.apk` from the newest `build-…` pre-release and install it (allow installs from your browser).
2. Open Privacy Glass → **Open Accessibility settings** → enable *Privacy Glass*.
   - If Android says *Restricted setting*: **Open App info** → ⋮ (top right) → **Allow restricted settings**, then enable it again.
3. Plug in the glasses. Use the normal mirrored output: if you turned on *desktop mode* in Developer options, turn it off.
4. Tap **Test for 10 seconds**:
   - Phone black, glasses normal, touch works → done. Leave *Black out automatically* on.
   - Glasses also go black, or nothing happens → try **Overlay B**, then **Panel off** (below).
   - Still stuck → **Copy log** at the bottom of the app and share it.

### Panel off mode (only if needed)
1. Install Shizuku from the Play Store and start it via **Wireless debugging** (see Shizuku's in-app guide; you'll need to restart it after each reboot).
2. In Privacy Glass choose **Panel off** → **Grant Shizuku permission** → **Test for 10 seconds**.

## Getting the screen back
- Unplug the glasses: restored automatically.
- Press **volume-down three times** quickly.
- Quick Settings tile **Phone blackout** (add it from the pencil/edit menu). You can operate it while looking at the glasses.
- Panel-off mode: the power button wakes the panel too. Blackout comes back after you unlock.

## Options
- **Keep phone awake**: stops the phone from sleeping (which would also blank the glasses) while blacked out.
- **Force landscape**: a portrait phone mirrored to a 16:9 display leaves big black bars. Landscape fills the glasses better. Apps that lock themselves to portrait may ignore this.

## Build
`./gradlew assembleDebug` (JDK 17+, Android SDK 36). CI builds every push and publishes the APK as a pre-release.

`app/debug.keystore` is a throwaway debug key committed on purpose, so every CI build can install over the previous one.
