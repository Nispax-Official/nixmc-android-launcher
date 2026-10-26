# NixMC Engine Architecture

Status: implementation plan plus launch-core foundation. The Android-native graphics/input bridge is **not integrated yet**. A resolved JVM command is not proof that Minecraft can launch.

## Product goal

Run the official Minecraft Java Edition client from inside NixMC on Android, with NixMC owning installation, runtime selection, launch settings, rendering selection, input controls, diagnostics, and the game-session lifecycle. The launcher must report a missing capability as a blocker rather than display a false "ready" state.

## Engine boundary

NixMC has two cooperating engines with a small platform-bridge boundary between them.

### 1. Game Runtime Engine

Owns everything required to turn an installed version and profile into a safe launch request.

Components:
- **Version resolver** — reads the official version manifest and version JSON; validates the requested version and required metadata.
- **Artifact installer** — downloads HTTPS-only client JAR, libraries, asset index, and assets; verifies publisher-provided hashes and file sizes; writes to temporary files and atomically finalizes them.
- **Native resolver/extractor** — resolves *Android-compatible* native bundles for the active ABI, checks manifest/hash/ABI, safely extracts files, and never treats desktop Linux natives as Android-compatible.
- **JVM provider** — provisions a supported Android Java runtime, validates the runtime's architecture and Java version, creates runtime directories, and records a probe result.
- **Launch planner** — interprets ordered Mojang OS/architecture/feature rules, creates the classpath, substitutes known variables, and lists every missing artifact.
- **Command builder** — creates a reproducible MinecraftProcessSpec with ordered JVM arguments, game arguments, memory bounds, working directory, temporary directory, and native-library search path.
- **Session supervisor** — starts the selected bridge/runtime, streams logs to a session-specific file, emits progress/exit/failure events, supports graceful stop before forced termination, and cleans transient files.

The Runtime Engine must not initialize a graphics context itself. It asks the platform bridge to create a session and passes the resolved launch request to it.

### 2. Graphics and Input Engine

Owns the Android-native path needed by LWJGL-based Minecraft versions.

Components:
- **Android game surface** — owns the Surface lifecycle and synchronizes pause/resume, resize, focus, and teardown with the native renderer.
- **LWJGL/GLFW compatibility bridge** — supplies the Android-specific native and Java compatibility layer expected by Minecraft, routes window/input callbacks, and makes the EGL/OpenGL context current on the correct thread.
- **Renderer adapter** — selects a compatible backend through one interface instead of hard-wiring the app to a single renderer.
- **Touch input mapper** — maps virtual joystick, look drag, tap, inventory, chat, and action buttons to keyboard/mouse events; supports scaling, safe areas, opacity, and editable layouts.
- **Gamepad mapper** — routes Android controller axes/buttons to Minecraft input and gives physical controls precedence over conflicting touch actions.
- **Audio adapter** — supplies Android-compatible OpenAL/native audio where the chosen bridge requires it.
- **Diagnostics** — reports the actual ABI, renderer, GL version/extensions, surface dimensions, bridge version, and native load failures.

NixMC should integrate an established Android Minecraft compatibility stack rather than attempt to reimplement OpenGL, LWJGL, and GLFW from scratch. PojavLauncher/Amethyst document the required custom LWJGL/GLFW work and multiple renderers. Their code and native artifacts must be integrated with an explicit license and attribution audit before any files are copied or bundled:
- https://github.com/AngelAuraMC/Amethyst-Android
- https://github.com/TeamPojavLauncher/PojavLauncher
- https://pojav.ru/wiki/faq/android/RENDERERS.html

## Bridge contract

The bridge package is a versioned, ABI-specific installation, not a loose collection of .so files.

Its installation manifest must declare:
- schema and bridge versions;
- supported Android ABI(s);
- Java runtime compatibility range;
- JNI / LWJGL / GLFW compatibility versions;
- bundled native file paths and SHA-256 hashes;
- available renderer backend IDs and their requirements;
- input/audio capabilities;
- source repository, source revision, licenses, and notices.

Installation is transactional: download to a temporary directory, verify every declared file and ABI, then switch the active bridge manifest only after all checks pass. A missing, malformed, mismatched, or partially installed manifest means bridgeReady = false.

The Runtime Engine hands the bridge a LaunchRequest containing the prepared JVM/game arguments, environment overrides, game directory, profile identity, memory configuration, selected renderer, and session ID. The bridge returns lifecycle events: Starting, SurfaceReady, GameOutput, Exited, and Failed. Logs must redact access tokens and other credentials.

## Renderer policy

Expose a renderer ID and capability report instead of implying every device supports the same features.

- **Compatibility / default backend:** prioritize vanilla correctness and broad device support.
- **Extended OpenGL backend:** expose only when the bundled bridge and device provide the needed OpenGL version/extensions.
- **Vulkan-backed backend:** expose only when the bridge build, device driver, and renderer compatibility check all pass.

The first release should choose a conservative compatible default automatically, while an advanced setting can allow users to select another installed and supported backend. Renderer names and availability must come from the bridge manifest, not hardcoded assumptions.

## Runtime pipeline

1. Validate player/profile and selected Minecraft version.
2. Verify/install version JSON, client JAR, libraries, asset index, and assets.
3. Verify Java runtime and native bridge ABI compatibility.
4. Resolve Mojang rules and arguments; fail visibly on missing artifacts or unresolved variables.
5. Resolve/extract bridge-native files and any version-specific native requirements.
6. Show a final readiness report. A complete Java command alone does not pass this stage.
7. Create an Android surface and initialize the selected renderer/input/audio backend.
8. Start the game through the bridge and attach bounded stdout/stderr logging.
9. Track session state; release graphics/audio resources on exit or Android lifecycle changes.

## ABI strategy

- **Primary target:** arm64-v8a phones/tablets.
- **Secondary target:** x86_64 emulator/testing where a compatible runtime and bridge are available.
- **Legacy targets:** armeabi-v7a and 32-bit x86 only when every runtime, native bridge, and renderer dependency is independently built and tested for that ABI. Do not advertise support based on the Java archive alone.

Use the Android device ABI (Build.SUPPORTED_ABIS) as the source of truth. Do not infer the Android ABI solely from a JVM os.arch string. Translate architecture aliases only for evaluating Mojang rules; the bridge manifest needs an exact Android ABI.

## Safety and reliability rules

- Only fetch from HTTPS URLs expected by official metadata or the bridge manifest.
- Validate all archive paths after canonicalization, including symbolic-link targets.
- Verify hashes before marking artifacts installed.
- Use temporary files/directories and atomic rename/swap on successful verification.
- Do not log Microsoft credentials, access tokens, or full sensitive environment data.
- Keep launcher UI responsive; downloads, hash checks, extraction, and probes run off the main thread.
- Bound parallel downloads, output-log size, and renderer diagnostics.
- Surface specific failures (unsupported ABI, Java probe failure, missing library, incompatible renderer, native linker error) instead of the same generic "launch failed" state.

## Milestone plan

### Phase 1 — launch-core foundation
- Hardened ordered Mojang OS/architecture/feature rule evaluation.
- Library path traversal protection.
- Testable JVM command builder with validated memory settings, classpath fallback, temporary directory, and runtime native-library environment.
- Unit tests for planner and command construction.

### Phase 2 — native artifact and bridge integration
- Choose a specific upstream bridge/revision and renderer bundle.
- Audit licenses and notices before bundling.
- Add ABI-specific bridge manifest, download/verification, install/rollback, and diagnostics.
- Add integration tests for bridge manifest verification and safe extraction.

### Phase 3 — session + graphics/input
- Surface lifecycle, renderer selection, game process/session control, logs, graceful stop/restart.
- Touch overlay and physical controller mapping.
- Test the same release build on a real arm64-v8a Android device and an x86_64 emulator where supported.

### Phase 4 — release hardening
- Build APK per release, automated tests, artifact retention, crash diagnostics, low-memory tests, runtime repair, and install migration.
- Do not mark Play functional until a real world loads and input, rendering, audio, pause/resume, and exit have been exercised on target hardware.

## Current truth

The repository currently has official game-file installation, an Android Java 21 runtime installer, a launch planner, and now a testable JVM command builder. It does **not** yet have the versioned Android LWJGL/GLFW bridge or a device-verified graphics/input engine. The next critical engineering decision is selecting and integrating that native stack; a new renderer implementation from scratch is not the right shortcut.
