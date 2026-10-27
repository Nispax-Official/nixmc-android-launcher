# NixMC — Minecraft Java launcher for Android

NixMC is an early-stage, mobile-first launcher project for Minecraft Java Edition.

## Current milestone — 0.5.0 download reliability and engine foundation

The app has a dark Jetpack Compose UI with Home, Profile, Setup, and Settings navigation. A local offline profile is editable, validated, and persisted. The test version is locked to Minecraft Java Edition 1.21.11.

The launcher currently implements:
- User-initiated foreground downloads with persistent Android notifications, stage-aware progress, cancellation, bounded concurrency, HTTPS-only redirects, publisher-hash verification, atomic replacement, a 16 MiB metadata-response cap, and Android 15 foreground-service timeout handling. Notification diagnostics are private on the lock screen.
- Android Java 21 runtime download with pinned SHA-256 verification, bounded safe extraction into a staging directory, atomic activation, and a `java -version` startup probe.
- A Minecraft launch planner with Mojang OS/architecture/feature-rule handling and safe library-path validation.
- An in-process Java launcher (JLI) argument/environment specification with validated memory bounds and classpath fallback.
- A native bridge manifest verifier with bounded manifest size/entry counts, Android ABI/Java compatibility checks, SHA-256 integrity for `.so` and custom LWJGL JAR files, explicit native load order, safe paths, and renderer availability.
- An in-process JLI bridge adapter and JNI compatibility entrypoint, including Android Java runtime native-library resolution/loading and custom LWJGL classpath precedence.
- Unit tests and GitHub Actions build/test automation.

**Minecraft does not launch from inside NixMC yet.** The native Android LWJGL/GLFW bridge bundle (real ABI-specific `.so` libraries, patched LWJGL/custom runtime JARs and renderer components), Android game Activity/surface integration, session lifecycle, and touch/gamepad routing are not yet installed or wired end-to-end. The new JLI adapter is an integration seam, not a claim that Minecraft already launches. The readiness screen is intentionally blocked until these are integrated and verified on a real device.

## Android configuration

All Minecraft game files, downloaded assets, libraries, runtime archives, and installed runtime files live under the single app-private `filesDir/minecraft` directory. NixMC does not write downloaded game files to shared storage. Android removes app-private files when the app is uninstalled, and app backup is disabled so large game files are not unexpectedly restored.

- Namespace / application ID: `in.nispax.nixmc`
- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Minimum Android SDK: 26 (Android 8.0)
- Compile / target SDK: 35
- Java bytecode target: 17
- App version: 0.5.0 (version code 5)
- Android Gradle Plugin: 8.9.2
- Gradle build version used in CI: 8.11.1

## Build and test

Open the repository in Android Studio with JDK 17 and Android SDK Platform 35 installed. Sync the project, then run the `app` configuration on a device or emulator.

For a local command-line build, install Gradle 8.11.1 and Android SDK Platform 35, then run:

```sh
gradle testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Offline profile behavior

NixMC currently stores a local player name only. Names are restricted to 3–16 English letters, numbers, and underscores. An offline profile does not sign in to Microsoft, prove account ownership, or authenticate identity to online-mode servers. The profile is resolved in the launch-plan layer, but no game process is started yet.

## Engine architecture and next work

See [docs/ENGINE_ARCHITECTURE.md](docs/ENGINE_ARCHITECTURE.md) for the Runtime Engine and Graphics/Input Engine design, bridge manifest contract, renderer strategy, ABI targets, safety requirements, and staged integration plan.

The next critical milestone is selecting and integrating a compatible Android-native Minecraft bridge stack. The JLI bridge, custom LWJGL/GLFW compatibility layer, renderer backends, Android surface, and touch-input routing must be implemented or incorporated with a license/notice audit. Desktop Linux native libraries cannot substitute for Android binaries, and calling `ProcessBuilder("java")` alone is not sufficient.

Build 0.4.0 improves launch planning and readiness diagnostics. A complete plan and valid Java runtime are prerequisites, not proof that the game renderer is working. NixMC must not label launch as ready until bridge files are verified, native initialization succeeds, and a real world has been tested with rendering, input, audio, pause/resume, and clean exit.
