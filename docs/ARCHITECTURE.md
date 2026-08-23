# Architecture

## Design objective

Phase 1 is a capability-driven Camera2 application. Its architecture treats Camera HAL metadata as untrusted input, represents uncertainty explicitly, and isolates each camera's failure. Manufacturer, model, SoC, sensor vendor, and raw Camera2 ID are never the primary dispatch mechanism.

The project intentionally uses one Gradle application module. Package boundaries provide separation without the configuration and build cost of many small modules:

```text
UI / features
    camera preview | lens settings | diagnostics
                         |
application orchestration and explicit camera state
                         |
discovery | probe | capability policy | settings | reports
                         |
Camera2 adapter | DataStore adapter | JNI bridge
                         |
Android Camera HAL                 native/core (C++20)
```

Pure camera models and policy functions do not depend directly on `CameraCharacteristics`. The Android adapter converts nullable, occasionally malformed framework metadata into internal values. This keeps fingerprinting, field-of-view calculations, classification, duplicate detection, ordering, state transitions, and report serialization testable with synthetic inputs.

## Camera ownership

Camera operations have a single lifecycle owner. Events such as permission changes, discovery, probing, opening, switching, backgrounding, and retrying are serialized through an explicit state model. Long-running work uses structured coroutines and is cancelled when its owner is closed.

Only one candidate is probed at a time. Opening a new preview waits for the previous capture session and device to close. Every path, including timeout and exception paths, owns and releases its `ImageReader`, `Surface`, `CameraCaptureSession`, `CameraDevice`, executor/thread, and coroutine job.

Expected high-level states are:

```text
Idle -> PermissionRequired -> Discovering -> Probing -> Ready
Ready -> Opening -> Previewing -> Switching -> Closing -> Ready
any active state -> ErrorRecoverable -> Ready or retry
```

The exact source enum may contain more detail, but uncontrolled overlapping `open`, `close`, `switch`, and `probe` operations are not permitted.

## Logical and physical cameras

A logical camera is the openable Camera2 device. A physical lens may be represented by that same public ID, or it may only be selectable as a physical stream through a logical parent. The internal model preserves both IDs and never assumes a physical-only ID can be opened independently.

The normal lens selector is a projection of discovered nodes: photographic, probed as usable, not hidden, ordered by persisted fingerprint preferences, and de-duplicated. Diagnostics retain every node and its failure reason.

## Preview boundary

The preview layer consumes a selected lens route rather than a bare camera ID. A route can identify either a public camera or a logical parent plus physical camera. Preview size selection balances display size, aspect ratio, minimum frame duration, and a practical resolution ceiling; capture resolution is a separate future concern.

The UI owns only the display surface and screen lifecycle. Camera configuration, callbacks, timeouts, and resource cleanup remain outside Compose.

## Persistence and reports

DataStore preferences are keyed primarily by deterministic `LensFingerprint`, not by Camera2 ID. Unknown or orphaned keys are ignored safely after firmware changes. Persisted Phase 1 settings are visibility, custom label, ordering, the 1x reference, and the last selection for each facing.

Compatibility reports serialize a sanitized snapshot of device/build capability, graphics capability, camera relationships, probe results, and applied quirks. They include no image, account, contact, location, media-library, or authentication data.

## Native boundary

`native/core` builds `libcamex_native.so` for ARM 32-bit, ARM 64-bit, x86, and x86_64. `NativeBridge` loads it and invokes `nativeVersion()` and `nativeSelfTest()`. A compile-time C++20 assertion and a runtime JNI result prove the boundary without pretending to implement an image pipeline.

## Build information

`BuildConfig` exposes the standard version/build-type values plus `GIT_SHA` and `BUILD_TIMESTAMP_UTC`. CI uses the checked-out commit SHA and that commit's timestamp, so metadata is traceable and does not depend on wall-clock build time. Local fallback values are deterministic `unknown` strings.

See [Camera discovery](CAMERA_DISCOVERY.md) for detailed policy and [Development](DEVELOPMENT.md) for the pinned build environment.
