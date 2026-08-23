# Development

## Authoritative environment

GitHub Actions is the authoritative build and delivery environment. The repository owner does not need a local Android development setup and should not be asked to run Gradle or Android Studio to produce the official APK.

Phase 1 uses only `main` and `phase/01-foundation-discovery`. Development is validated on the phase branch and through pull requests. A validated merge to `main` triggers the development APK workflow.

## Pinned toolchain

Versions are fixed in the wrapper, version catalog, Android module, and SDK installation script:

| Component | Pinned version | Rationale / primary source |
|---|---:|---|
| Android compile/target SDK | 37 (Android 17, platform revision 2) | Latest stable platform at implementation time; [Android 17 release](https://developer.android.com/blog/posts/android-17-is-here) |
| Android SDK Build Tools | 37.0.0 | Latest stable Android 17 tool set; [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk) |
| Minimum SDK | 23 | Broad Android 6.0+ compatibility |
| Android Gradle Plugin | 9.3.1 | Latest stable bug-fix in the 9.3 line; [AGP 9.3 notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) |
| Gradle | 9.5.0 | AGP 9.3 default/minimum and Kotlin 2.4 fully-supported maximum; [AGP matrix](https://developer.android.com/build/releases/about-agp), [Kotlin matrix](https://kotlinlang.org/docs/gradle-configure-project.html) |
| JDK / JVM target | 17 | Required/default for AGP 9.3 |
| Kotlin + Compose compiler | 2.4.10 | Latest stable Kotlin bug-fix; [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| Compose BOM | 2026.08.00 | Stable BOM; [Compose BOM guidance](https://developer.android.com/develop/ui/compose/bom) |
| Android NDK | r29 (`29.0.14206865`) | Latest stable NDK; [NDK releases](https://developer.android.com/ndk/downloads/revision_history) |
| CMake | 4.1.2 | Pinned SDK package used by CI |

Gradle 9.7.1 exists, but 9.5.0 is intentionally selected because it is the newest version in Kotlin 2.4.10's fully supported range and AGP 9.3's documented default. This avoids combining individually current tools outside their published compatibility matrix.

Direct libraries are also pinned in `gradle/libs.versions.toml`; no `+`, `latest`, prerelease, or snapshot selector is used.

AGP 9's built-in Kotlin support compiles Android sources; the deprecated `org.jetbrains.kotlin.android` plugin is intentionally absent. The Kotlin-matched Compose compiler and serialization plugins remain explicitly pinned.

## Workflows

`Phase 1 CI` runs for pull requests, phase-branch pushes, and manual dispatch. It validates the wrapper, installs exact SDK/NDK/CMake packages, runs unit tests and lint, compiles every configured JNI ABI, assembles a debug APK, checks its ZIP contents, and uploads a checksum-bearing artifact.

`Main Development APK` runs for pushes to `main` and manual dispatch. It repeats validation before uploading:

```text
Camera-dev-<12-character-commit>.apk
SHA256SUMS.txt
```

Both workflows have read-only repository permission. Actions are pinned to immutable commit SHAs. No signing or release secret is configured.

## Reproducing a workflow locally (optional)

Local builds are not required or authoritative. A contributor who already has JDK 17 and Android command-line tools may run:

```bash
./scripts/install-android-sdk.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk
```

The SDK installation script installs platform 37 revision 2, Build Tools 37.0.0, NDK r29, and CMake 4.1.2. It deliberately does not install Android Studio.

## Build metadata

CI sets `CAMEX_GIT_SHA` to the checked-out commit and `CAMEX_BUILD_TIMESTAMP_UTC` to that commit's timestamp. It uses `GITHUB_RUN_NUMBER` to make successive development version codes install-orderable within a workflow. A build without those variables uses deterministic fallback metadata.

## Development signing

Phase 1 uses Android's debug signing only. There is no permanent application key, production release, OTA updater, signing secret, or update trust chain. Do not upload these APKs as production releases. Permanent signing, signed metadata, signature/checksum verification, rollback protection, and installer integration belong to a later dedicated phase.

## Dependency updates

Update the matrix only after checking official release and compatibility documentation. Change pins in one pull request, allow the wrapper validation and complete CI suite to run, and record any minimum-SDK or target-SDK behavior impact. Never replace a fixed version with a dynamic selector.
