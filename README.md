# NixMC — Minecraft Java launcher for Android

NixMC is an early-stage, mobile-first launcher project for Minecraft Java Edition.

## Current milestone — 0.4.0 engine foundation

The app has a dark Jetpack Compose UI with Home, Profile, Setup, and Settings navigation. A local offline profile is editable, validated, and persisted. The test version is locked to Minecraft Java Edition 1.21.11.

The launcher currently implements:
- Official client JAR, libraries, asset index, and asset-object download with publisher SHA-1 verification.
- Android Java 21 runtime download, archive SHA-256 verification, extraction, and `java -version` startup probe.
- A Minecraft launch planner with Mojang OS/architecture/feature-rule handling and safe library-path validation.
- An in-process Java launcher (JLI) argument/environment specification with validated memory bounds and classpath fallback.
- A native bridge manifest verifier for Android ABI, Java compatibility, native-file SHA-256 integrity, safe paths, and renderer availability.
- Unit tests and GitHub Actions build/test automation.

**Minecraft does not launch from inside NixMC yet.** The native Android LWJGL/GLFW bridge, its ABI-specific renderer libraries, Android surface integration, and touch/gamepad routing are not installed or wired to the new launch spec. The readiness screen is intentionally blocked until these are integrated and verified on a real device.

## Android configuration

- Namespace / application ID: `in.nispax.nixmc`
- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Minimum Android SDK: 26 (Android 8.0)
- Compile / target SDK: 35
- Java bytecode target: 17
- App version: 0.4.0 (version code 4)
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

The next critical milestone is selecting and legally integrating a compatible upstream Android Minecraft bridge stack. PojavLauncher/Amethyst contain the hard native pieces (JLI bridge, custom LWJGL/GLFW, renderer backends, Android surface and input routing); they cannot be replaced by desktop Linux native libraries or by calling `ProcessBuilder("java")`. Their code and bundled components require a license/notice audit before redistribution.

Build 0.4.0 improves launch planning and readiness diagnostics. A complete plan and valid Java runtime are prerequisites, not proof that the game renderer is working. NixMC must not label launch as ready until bridge files are verified, native initialization succeeds, and a real world has been tested with rendering, input, audio, pause/resume, and clean exit.
