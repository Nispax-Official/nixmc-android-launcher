# NixMC — Minecraft Java launcher for Android

NixMC is an early-stage Android launcher project exploring a mobile-first experience for Minecraft Java Edition.

## Project status

The initial Android app scaffold and a dark Jetpack Compose home screen are in place. Minecraft installation, Java runtime integration, rendering/input compatibility, account authentication, and profile persistence are not implemented yet.

## Package

- Namespace: `in.nispax.nixmc`
- Application ID: `in.nispax.nixmc`
- Language: Kotlin
- UI: Jetpack Compose / Material 3
- Minimum Android SDK: 26
- Java toolchain target: 17

## Build

Open the repository in a recent Android Studio version with JDK 17 installed. Allow Gradle to sync, then run the `app` configuration on an Android device or emulator.

## Offline profiles

The project intends to support custom offline profiles for single-player/local use and servers that explicitly allow offline-mode connections. Offline profiles do not authenticate a Microsoft account and cannot establish authenticated identity on online-mode servers.

## Roadmap

1. Finish and verify the Android build scaffold.
2. Add editable player profiles and local persistence.
3. Investigate a compatible Java runtime and Android rendering/input stack.
4. Implement version metadata, downloads, libraries, assets, and launch arguments.
5. Add authenticated Microsoft account support for services that require it.
