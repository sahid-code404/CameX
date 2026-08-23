# Camera discovery

## Contract

Discovery answers what Android exposes and how each observed node can be routed; it does not prove every route by opening it. All IDs are opaque, all metadata is nullable/untrusted, every ID failure is isolated, and no manufacturer, model, SoC, or numeric camera-ID table decides topology.

The backends emit evidence independently. `CameraTopologyResolver` canonicalizes it, and `CameraTopologyRepository` publishes incremental immutable topologies. A slow or failed backend cannot erase credible evidence supplied by another backend while reconciliation is in progress.

## Level 0: cache bootstrap

`CameraTopologyStore` and `CameraTrustStore` are the first discovery inputs on a normal launch. A valid cache is read before live enumeration, publishes every known selector route, resolves the remembered/primary route, and permits the open request immediately. Background reconciliation is launched separately and is not awaited by preview.

Cache compatibility is evaluated using `CameraEnvironmentFingerprint`: cache schema version, Android build fingerprint, API level, discovery schema version, and—after a cheap live scan—an optional normalized advertised-ID signature. Corrupt or incompatible data is a cache miss, not a crash.

On a cache miss, `scanPrimaryCameraFast()` reads minimal Java metadata in advertised order, stops at the first credible rear route with PRIVATE preview support, and falls back to another credible preview route. It does not enumerate every camera capability. The lens row can then grow incrementally while preview is already starting.

## Level 1: advertised reconciliation

Fast live reconciliation runs Java and NDK advertised discovery concurrently in background work:

- `JavaCameraDiscoveryBackend` reads `CameraManager.cameraIdList`, safe minimal characteristics, and logical `physicalCameraIds`. Minimal candidates and relationships are emitted before asynchronous full capability enrichment.
- `PhysicalCameraTopologyBackend` preserves logical-parent/physical-member routes even when a vendor withholds child characteristics.
- `NativeCameraDiscoveryBackend` independently uses `ACameraManager_getCameraIdList` and `ACameraManager_getCameraCharacteristics`.

Java metadata extraction is bounded by a semaphore with three default lanes while the parallel NDK advertised scan uses one sequential lane, keeping combined HAL metadata concurrency at the target maximum of four. Results are processed in bounded batches. This allows independent metadata calls to overlap without creating an unbounded job storm against a fragile HAL.

The useful minimal fields are facing, focal lengths, sensor physical size, active/pixel arrays, orientation, hardware level, advertised request capabilities, actual PRIVATE/YUV preview declarations, actual RAW stream declarations, and depth/ToF/IR/monochrome/system evidence. Full stream tables and feature capabilities are enrichment data, never prerequisites for the first preview.

## Level 2: bounded deep AUX discovery

Deep AUX discovery runs only on first install, incompatible/changed topology, evidence that advertised results are incomplete, or an explicit **Deep Rescan Cameras** action. It is not on the synchronous startup path.

`DeepAuxCandidatePlanner` produces a deterministic priority list:

1. previously successful deep IDs;
2. cached successful IDs;
3. advertised numeric IDs;
4. a small bounded neighborhood around known numeric IDs;
5. a bounded low numeric namespace.

Defaults cover low IDs `0..31`, a neighbor radius of four, and at most 96 candidates. Defensive hard caps bound candidate count, numeric values, input IDs, and ID length. Numeric values carry no lens meaning; they are only a generic finite namespace to ask the camera service for metadata.

The native scan creates one `ACameraManager`, attempts characteristics lookup once per planned candidate, records bounded sanitized failures, and destroys all metadata/manager resources. It never calls a camera-open or capture-session API, never uses a privileged API, never retries endlessly, and never probes a preview or RAW frame.

## Logical and physical routing

A public direct route opens its public ID. A physical-member route stores both the logical `openCameraId` and `streamPhysicalCameraId`; session construction opens the logical device and targets the physical output on supported Android versions. An NDK-discovered ID remains a candidate direct route unless stronger relationship evidence proves otherwise.

Physical IDs are not assumed to appear in `cameraIdList`, and an observed physical ID is not blindly passed to `openCamera`. On older APIs, CameX retains direct public discovery and records unavailable relationship detail as unknown.

## Reconciliation and duplicate policy

Exact routing keys merge across cache, Java, physical, advertised-NDK, and deep-NDK sources. Strong aliases require compatible route identity and optical/sensor evidence. Similar focal length, matching role, or a nearby numeric ID is insufficient. When evidence is uncertain, both routes survive; diagnostics retain sources and aliases.

During cache bootstrap and incremental reconciliation, absence from a partial live result does not delete a cached route. Stale cached routes may be pruned only after all required backends—including a required deep scan—completed successfully enough to make absence meaningful. Resolver ordering and canonical IDs are deterministic.

## Photographic taxonomy

Roles are explicit:

- photographic: `PHOTOGRAPHIC_ULTRAWIDE`, `PHOTOGRAPHIC_WIDE`, `PHOTOGRAPHIC_TELEPHOTO`, `PHOTOGRAPHIC_SUPER_TELEPHOTO`, `PHOTOGRAPHIC_MACRO`, `PHOTOGRAPHIC_MONO`, `PHOTOGRAPHIC_UNKNOWN`;
- confidently non-photographic: `NON_PHOTO_DEPTH`, `NON_PHOTO_TOF`, `NON_PHOTO_IR`;
- unavailable: `SYSTEM_ONLY`, `INACCESSIBLE`, `BROKEN`.

Facing (`BACK`, `FRONT`, `EXTERNAL`, `UNKNOWN`) is separate. Field of view is computed only from valid sensor dimensions and focal length, using `2 * atan(d / (2 * f))`; unavailable geometry produces no invented zoom/FOV claim.

The normal selector includes known photographic roles that are not structurally rejected. Credible `PHOTOGRAPHIC_UNKNOWN` routes remain available under Advanced Discovered Cameras so the user can show/hide, rename, reorder, select a 1x reference, and try them. Depth/ToF/IR/system/inaccessible routes remain diagnostics-only. There is no blanket “AUX” exclusion, and unknown is never synonymous with broken.

## RAW evidence

RAW metadata is cross-checked. An actual portable RAW stream (`RAW_SENSOR`, RAW10, RAW12, or RAW14 where supported) is evidence even when a redundant capability bit is missing; an advertised bit without a usable stream is recorded as a discrepancy. `RAW_PRIVATE` is diagnostic only because its layout is implementation-specific.

Phase 1A does not open a RAW session, acquire a RAW frame, save DNG, or claim `RAW_VERIFIED`. Those operations belong to a later explicitly scoped phase.

## Lazy route validation and trust

New credible routes can appear before any open. When the user selects a route, the single `CameraSessionController` opens it. Camera-open, session-configured, and first-frame events are reported; a first frame advances session trust to `SESSION_VERIFIED` and persists it against the environment fingerprint.

Failures are classified as transient or structural. In-use, maximum-cameras-in-use, disconnection, app-background, timeout, and service disruption retain prior good trust and do not create a permanent blacklist. Invalid metadata or a deterministic unsupported session route may become `METADATA_REJECTED` or `SESSION_REJECTED`. A changed environment invalidates that knowledge. No known-good route is re-probed merely because the process restarted.

## User recovery actions

- **Rescan Cameras** reruns advertised Java/NDK reconciliation and preserves user settings; it does not invoke deep discovery.
- **Deep Rescan Cameras** runs advertised reconciliation plus the bounded metadata-only candidate scan.
- **Reset Camera Discovery Cache** clears topology and trust/rejection state but retains lens labels, visibility, order, 1x reference, and other unrelated preferences; discovery then rebuilds progressively.

Discovery completion must not restart an active preview. Recovery may reopen the selected camera only when the session was already in a recoverable error state.

## Diagnostics and fallbacks

Diagnostics/reporting retain canonical/route IDs, logical and physical IDs, fingerprints, sources, aliases, role/confidence, facing, FOV/sensor/preview/RAW evidence, cache status, trust dimensions, rejection reason, backend failures/counts/timings, relationships, and monotonic startup milestones. Reports contain no images or personal data.

Fallbacks are conservative:

- missing geometry: generic label and no FOV claim;
- malformed metadata: bounded per-ID failure and continued discovery;
- Java/NDK disagreement: retain independent evidence and reconcile without assuming either is universally authoritative;
- `SYSTEM_CAMERA` restriction: record when observable, never bypass Android access control;
- backend failure: keep cache and other-backend evidence rather than erasing routes;
- firmware/ROM/HAL change: invalidate environment-bound discovery/trust while migrating matching user preferences by lens fingerprint.
