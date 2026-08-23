# Development

## Authoritative environment and phase policy

GitHub Actions is the authoritative build and delivery environment. The repository owner does not need a local Android toolchain and should not be asked to run Gradle or Android Studio for the official result.

Phase 1A continues on `phase/01-foundation-discovery`; PR #1 remains draft until physical AUX parity, startup, repeated-launch, preview, and cache behavior pass. Do not create Phase 2 or merge to `main` during this corrective pass. RAW output, DNG, HDR, burst/fusion, night mode, AI processing, and video are out of scope.

## Pinned toolchain

Versions are fixed in the wrapper, version catalog, Android module, and SDK installation script:

| Component | Pinned version | Rationale / primary source |
|---|---:|---|
| Android compile/target SDK | 37 (Android 17, platform revision 2) | Stable platform selected for this implementation; [Android 17 release](https://developer.android.com/blog/posts/android-17-is-here) |
| Android SDK Build Tools | 37.0.0 | Pinned Android 17 tools; [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk) |
| Minimum SDK | 23 | Android 6.0+ compatibility |
| Android Gradle Plugin | 9.3.1 | Stable 9.3 bug-fix; [AGP 9.3 notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) |
| Gradle | 9.5.0 | Within published AGP/Kotlin compatibility; [AGP matrix](https://developer.android.com/build/releases/about-agp), [Kotlin matrix](https://kotlinlang.org/docs/gradle-configure-project.html) |
| JDK / JVM target | 17 | Required/default for AGP 9.3 |
| Kotlin + Compose compiler | 2.4.10 | Pinned stable Kotlin; [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| Compose BOM | 2026.08.00 | Stable BOM; [Compose BOM guidance](https://developer.android.com/develop/ui/compose/bom) |
| Android NDK | r29 (`29.0.14206865`) | Pinned stable NDK; [NDK releases](https://developer.android.com/ndk/downloads/revision_history) |
| CMake | 4.1.2 | Pinned SDK package used by CI |

Direct dependencies in `gradle/libs.versions.toml` are fixed; do not use `+`, `latest`, prerelease, or snapshot selectors. AGP 9's built-in Kotlin support compiles Android sources, while the Kotlin-matched Compose compiler and serialization plugins remain explicitly pinned.

## Architecture guard

Run `./scripts/verify-camera-architecture.sh` after camera architecture changes. CI runs it immediately after checkout and before any Gradle task. It rejects:

- hardcoded numeric camera-ID and manufacturer/model dispatch branches;
- `Thread.sleep`, `runBlocking`, or `GlobalScope` in app source/tests;
- camera-open or capture-session APIs in native `camera_discovery.cpp`;
- removed startup-wide `discoverAndProbe` / `probeSequentially` paths;
- blanket `LensCategory.AUXILIARY` filtering.

It also requires the coordinator, topology repository/resolver, cache/trust stores, independent Java/physical/NDK/deep backends, bounded Java metadata semaphore, and serialized session-open mutex. This is a narrow static guard, not a substitute for unit tests, lint, or physical-device tests.

## Workflows

`Phase 1 CI` runs for pull requests, phase-branch pushes, and manual dispatch. It validates architecture and the Gradle wrapper, installs exact SDK/NDK/CMake packages, runs unit tests and lint, compiles every configured JNI ABI, assembles a debug APK, checks its ZIP contents, and uploads a checksum-bearing artifact.

`Main Development APK` runs for pushes to `main` and manual dispatch. It remains the downloadable development-APK delivery path and uploads:

```text
Camera-dev-<12-character-commit>.apk
SHA256SUMS.txt
```

Both workflows have read-only repository permission and pin actions to immutable commit SHAs. No production signing or release secret is configured.

## Optional local checks

Local builds are useful to contributors who already have JDK 17 and Android command-line tools, but are not authoritative:

```bash
./scripts/verify-camera-architecture.sh
./scripts/install-android-sdk.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

The SDK script installs platform 37 revision 2, Build Tools 37.0.0, NDK r29, and CMake 4.1.2. It deliberately does not install Android Studio.

## Camera implementation rules

- Keep cache → lens UI → selected camera open → first frame independent of live reconciliation.
- Keep discovery metadata-only; only `CameraSessionController` may own Camera2 open/session lifecycle.
- Use structured, lifecycle-owned coroutines. Metadata concurrency must stay bounded and actual camera opens serialized.
- Preserve unknown candidates and per-ID failures; do not infer broken from absent vendor metadata.
- Keep user preferences separate from environment-bound topology/trust cache.
- Add pure deterministic tests for topology, aliases, malformed/missing metadata, cache migration/invalidation, trust progression, ordering, and startup sequencing.
- Do not report CI-measured HAL latency. Only physical-device diagnostics can provide open/session/first-frame timing.

## Source-reference provenance

Architectural research used the owner's repositories at fixed commits: `sahid-code404/Camera` `0c7a19e1b4809c7ba03fce7a2810f43726ef5d7d` and `sahid-code404/Camera-Computaional` `03d04e13c676e9c3eab6258ea3c5bf24ccbbf86b`. Reuse is limited to owner-controlled ideas/code whose provenance is understood. The speculative legacy path that opened hidden candidates is prohibited.

MotionCam is GPL-licensed reference material. It may be studied for concepts and Android behavior, but its implementation must not be copied into CameX without an explicit, documented licensing decision. Current Phase 1A native discovery is an independent public-NDK metadata implementation.

## Build metadata, signing, and updates

CI sets `CAMEX_GIT_SHA` from the checkout and `CAMEX_BUILD_TIMESTAMP_UTC` from that commit timestamp. `GITHUB_RUN_NUMBER` makes development version codes install-orderable. Local fallback metadata is deterministic.

Phase 1 uses debug signing only. Do not publish these APKs as production releases. Permanent keys, signed metadata, verification, rollback protection, and installer integration require a later dedicated phase.

Update pinned dependencies only after checking official compatibility documentation. Change pins together, run the complete CI suite, and record any API/minimum-SDK impact.
