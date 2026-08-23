# Camera (CameX)

[![Phase 1 CI](https://github.com/sahid-code404/CameX/actions/workflows/ci.yml/badge.svg?branch=phase%2F01-foundation-discovery)](https://github.com/sahid-code404/CameX/actions/workflows/ci.yml)

Camera is an Android Camera2 foundation for a future universal computational RAW camera. Phase 1 discovers cameras from runtime capabilities, relates logical and physical lenses, probes them conservatively, presents usable photographic lenses, and provides diagnostics. It does not contain computational photography or final capture processing.

The application ID is `com.sahidcode404.camex`; the user-visible name is **Camera**. Android 6.0/API 23 is the minimum supported version.

## Get a development APK

GitHub Actions is the authoritative build environment. No Android Studio, Java, Gradle, Android SDK, or NDK installation is required on the project owner's computer.

1. Open the repository's **Actions** tab.
2. Open a successful **Main Development APK** run for `main`, or a successful **Phase 1 CI** run for the phase branch.
3. Download the `Camera-dev-…` or `Camera-ci-…` artifact.
4. Extract it and verify the APK against `SHA256SUMS.txt` before installing it.

The APK is debug-signed for development. It is **not** permanently release-signed and is **not** suitable for production distribution or OTA updates. Because hosted runners can create different debug keys, Android may require uninstalling a prior development build before another one can be installed.

## Current validation boundary

CI validates compilation, pure unit tests, Android lint, C++/JNI compilation, APK structure, all configured native ABIs, and the APK checksum. GitHub-hosted runners cannot validate real Camera HAL behavior, auxiliary lenses, preview output, or RAW streams. Those require the physical-device checklist in [Device compatibility](docs/DEVICE_COMPATIBILITY.md).

Do not interpret a green build as a claim that every camera or device is supported.

## Repository map

- `app/` — the single Android application module, organized internally by core and feature packages.
- `native/core/` — the minimal C++20/JNI foundation; image processing is intentionally absent.
- `.github/workflows/` — authoritative CI and development APK delivery.
- `scripts/` — pinned SDK installation and APK integrity checks used by CI.
- `docs/` — architecture, discovery, compatibility, development, and phase boundaries.

Start with [Architecture](docs/ARCHITECTURE.md), [Camera discovery](docs/CAMERA_DISCOVERY.md), and [Development](docs/DEVELOPMENT.md).

## Phase 1 guardrails

- Capabilities before device or camera-ID assumptions.
- Probe before showing a lens in the normal camera UI.
- Preserve logical-to-physical relationships.
- Isolate failures so one broken HAL node cannot take down discovery.
- Keep manufacturer/model rules in an exception-only quirk layer.
- Do not implement HDR, denoise, fusion, DNG capture, RAW video, or production updates in Phase 1.

License information has not yet been added to the repository; no license should be inferred.
