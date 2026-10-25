# Building the release APK

```
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`, R8-shrunk and signed with the key from
`keystore.properties`. It stops with a message, rather than building a debug-signed release, if that file is missing.

## What the machine needs

- **JDK 25.** `gradle/gradle-daemon-jvm.properties` asks for it and Gradle fetches it by itself when it has
  network access (foojay); install it first if the machine is offline.
- **Android SDK** with platform 37 (`compileSdk = 37`), and Gradle's own downloads: Gradle 9.8.2 and the
  Android Gradle plugin, from `services.gradle.org`, Google's Maven and Maven Central.
- **NDK 30.0.16248370** and **CMake**, from the SDK manager. `app/build.gradle.kts` builds `libwlbridge.so`
  from `app/src/main/cpp` with that NDK.

`app/src/main/jniLibs/arm64-v8a` already has proot and its libraries (`libproot.so`, `libproot-loader.so`,
`libtalloc.so`, `libandroid-shmem.so`), so `scripts/fetch-proot.sh` is not needed unless you want to rebuild them.

## Signing

`omalauncher-release.jks` and `keystore.properties` at this folder's root were made by `scripts/make-keystore.sh`.
**Keep both.** An update only installs over an app signed with the same key. Anyone who has the two files can sign
an app as you, so do not hand them to a builder you do not trust; delete them and run the script on your own machine.

Installing this over an Omalauncher signed with a different key (your earlier APK) fails with a signature
mismatch: uninstall the old one first, which clears its data.

## What differs from upstream

This is `SimonSchubert/omalauncher` at the commit recorded in your APK (`ccd9d7f`), with:

- your APK's changes: `assets/widgets/drawer.owl`, the transparent `Theme.Omalauncher.Window`, and the permissions the
  APK declared that upstream does not (storage, media, vibrate, audio settings);
- liquid glass: the launcher's surfaces are translucent over the wallpaper (`Palette.glass`, `pane`, `card` and
  `control` in `ui/Theme.kt`, set by `glass.opacity`, and by `screens.*` and `drawer.*` for the full-screen panels and the
  app drawer); the theme's own colors stay in charge of every other color;
- `CrashLog`: an uncaught exception is written to Download/Omalauncher, so a force close comes with its stack trace;
- `android.audio` and `audio(...)` (owl level 13): the audio other apps play, as levels per band
  (`widgets/runtime/Audio.kt`, `Spectrum.kt`, `AudioCaptureService.kt`, `AudioPermissionActivity.kt`).
