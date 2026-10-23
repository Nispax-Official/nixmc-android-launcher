# NixMC — Minecraft Java launcher for Android

NixMC is an early-stage, mobile-first launcher project for Minecraft Java Edition.

## Current milestone

The app has a dark Jetpack Compose UI with Home, Profile, Setup, and Settings navigation. The active offline profile name is editable, validated, and persisted locally. The Setup screen fetches the official Mojang Java Edition version manifest, lists recent stable releases, and persists the selected version. Unit tests cover player-name validation, and GitHub Actions runs tests and builds a debug APK on pushes and pull requests.

## Android configuration

- Namespace / application ID: `in.nispax.nixmc`
- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Minimum Android SDK: 26 (Android 8.0)
- Compile / target SDK: 35
- Java bytecode target: 17
- Android Gradle Plugin: 8.9.2
- Gradle build version used in CI: 8.11.1

## Build and test

Open the repository in a recent Android Studio installation with JDK 17 and Android SDK Platform 35 installed. Sync the project, then run the `app` configuration on a device or emulator.

For a local command-line build, install Gradle 8.11.1 and Android SDK Platform 35, then run:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Offline profile behavior

NixMC currently stores a local player name only. Names are restricted to 3–16 English letters, numbers, and underscores. An offline profile does not sign in to Microsoft, prove account ownership, or authenticate identity to online-mode servers. The profile is not passed to a game process yet because the Java runtime and game launch pipeline have not been integrated.

## Not implemented yet

- Downloading selected Minecraft version metadata, assets, libraries, and game files
- Compatible Java runtime provisioning/execution on Android
- Rendering and input integration
- Connecting saved profiles to launch arguments
- Microsoft account authentication for services that require it
- Installation integrity checks, progress, and error recovery

NixMC does not present a working Launch action until those pieces exist.
