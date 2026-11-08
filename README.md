# Privacy Glass

An Android app for **XREAL glasses + Pixel 10**. The phone screen goes black while the glasses keep showing the normal mirrored picture. You still tap and swipe on the phone screen as usual, so it works like a touchpad that shows nothing.

Confirmed working on a Pixel 10 with XREAL glasses: the phone goes black, the glasses show the normal picture, and touch and swipe work as usual.

## How it works

Pixel phones (8 and newer) send video over USB-C (DisplayPort Alt Mode), and by default they mirror the phone screen to the glasses. Privacy Glass covers the phone with a black **accessibility overlay**:

- Its surface is flagged *skip screenshot* with a hidden Android API. Android draws such surfaces only on the built-in panel and leaves them out of external displays, recordings and screenshots, so the glasses never see the black.
- Android trusts accessibility overlays, so touches pass straight through to the apps underneath.

## Install

1. On the phone, open this repo's **Releases** page. Download `PrivacyGlass.apk` from the newest `build-…` pre-release and install it (allow installs from your browser).
2. Open Privacy Glass → **Open Accessibility settings** → enable *Privacy Glass*.
   - If Android says *Restricted setting*: **Open App info** → ⋮ (top right) → **Allow restricted settings**, then enable it again.
3. Plug in the glasses. Use the normal mirrored output: if you turned on *desktop mode* in Developer options, turn it off.
4. Tap **Test for 10 seconds** to check, then leave *Black out automatically* on.

If something doesn't work, use **Copy log** at the bottom of the app and share the log.

## Getting the screen back
- Unplug the glasses: restored automatically.
- Press **volume-down three times** quickly.
- Quick Settings tile **Phone blackout** (add it from the pencil/edit menu). You can operate it while looking at the glasses.

## Uninstall
Open the app → **Uninstall Privacy Glass**. It switches the blackout off first, then shows Android's uninstall prompt. (Settings → Apps → Privacy Glass → Uninstall also works.)

## Options
- **Keep phone awake**: stops the phone from sleeping (which would also blank the glasses) while blacked out.
- **Force landscape**: a portrait phone mirrored to a 16:9 display leaves big black bars. Landscape fills the glasses better. Apps that lock themselves to portrait may ignore this.

## Build
`./gradlew assembleDebug` (JDK 17+, Android SDK 36). CI builds every push and publishes the APK as a pre-release.

`app/debug.keystore` is a throwaway debug key committed on purpose, so every CI build can install over the previous one.
