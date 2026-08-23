# Architecture

## Phase 1A objective

Phase 1A is a universal, cache-first Camera2 foundation. It discovers every photographic route a normal Android application can access, represents incomplete vendor metadata without turning it into a failure, and gets the viewfinder running before background reconciliation finishes. RAW image output, DNG, HDR, fusion, night mode, AI processing, and video are outside this phase.

The startup critical path is deliberately short:

```text
APP START
    -> read cached topology and trust
    -> publish cached lens row
    -> resolve remembered or primary route
    -> request camera open
    -> configure one preview session
    -> first preview frame
```

Java/NDK advertised discovery, full metadata enrichment, topology reconciliation, and any required deep AUX scan run outside that path. On a first install, where no cache exists, a minimal Java `scanPrimaryCameraFast()` seed finds one credible preview route and stops; preview can start while the complete topology appears incrementally.

## Ownership and boundaries

```text
Compose UI
    |
CameraViewModel
    |
CameraRuntimeCoordinator --------------------------+
    |                                               |
CameraDiscoveryCoordinator                         | user/session actions
    |                                               v
    +-- CameraTopologyStore / CameraTrustStore  CameraSessionController
    +-- JavaCameraDiscoveryBackend                  |
    +-- PhysicalCameraTopologyBackend               +-- one CameraDevice/session owner
    +-- NativeCameraDiscoveryBackend                +-- serialized open/switch/close
    +-- DeepAuxDiscoveryBackend
    |
CameraTopologyResolver
    |
CameraTopologyRepository -> CameraRouteRepository -> lens projections
    |
CameraRouteValidator (interprets only selected-route session outcomes)
```

The boundaries are strict:

- `CameraDiscoveryCoordinator` owns discovery policy and invokes independent evidence backends. It never opens a camera.
- `CameraTopologyResolver` is pure and deterministic. It merges exact routes freely, merges aliases only with strong evidence, and preserves uncertain candidates separately.
- `CameraTopologyRepository` is the sole mutable owner of canonical topology in a process. Backends submit evidence; UI and session consumers receive projections.
- `CameraRuntimeCoordinator` sequences cache bootstrap, primary open, background reconciliation, explicit rescans, and trust observations.
- `CameraSessionController` is the sole owner of `CameraDevice`, capture-session, preview-surface, and callback lifetimes. It does not enumerate, discover, or globally probe routes.
- `CameraRouteValidator` converts only naturally observed selected-route session events into durable or transient trust evidence; it never initiates a session.
- UI code receives state and submits actions. It does not depend directly on `CameraManager` and does not know how AUX discovery works.

Topology updates are observational to the session owner. Adding or enriching a route must not close, reopen, or relabel the active preview. A lens switch uses the already-resolved route and never launches global discovery.

## Route model

A `CameraRoute` separates discovery identity from the way a stream is opened:

- `discoveredCameraId`: the ID observed by a backend;
- `openCameraId`: the public/logical device passed to Camera2 open;
- `streamPhysicalCameraId`: optional physical output target;
- `logicalParentCameraId`: preserved logical relationship;
- `routeKind` and `sources`: routing and evidence provenance;
- minimal metadata, optional full capabilities, lens fingerprint, role/confidence, aliases, and trust.

A physical member is never assumed to be independently openable. Its session opens the logical parent and targets the physical output when the platform supports that route. Camera IDs are opaque routing values, never lens roles or preferred ordering.

## Metadata, classification, and uncertainty

Discovery uses two metadata stages. The minimal stage reads only fields needed to establish a credible route and render a lens: facing, focal lengths, sensor geometry, preview/RAW stream evidence, hardware level, and logical/physical relationships. Full capability mapping runs asynchronously after minimal candidates have been published.

Role and facing are orthogonal. `PhotographicRole` distinguishes ultrawide, wide, telephoto, super-telephoto, macro, monochrome, and photographic-unknown from depth, ToF, IR, system-only, inaccessible, and broken routes. Front/back/external/unknown remain facing attributes. Missing metadata means unknown; it does not mean broken. A route with credible preview/optical or real RAW stream evidence may be offered for lazy validation even when its precise role is unknown.

Duplicate handling is conservative. Exact route identity and strong optical/routing evidence may establish an alias. Similar focal length alone is not enough to remove a route. Original evidence and aliases remain available to diagnostics.

## Lazy trust progression

Topology existence and runtime usability are separate. Trust progresses through three dimensions:

```text
metadata: DISCOVERED -> METADATA_VALID (or a specific rejection)
session:  UNKNOWN    -> SESSION_VERIFIED (or SESSION_REJECTED)
RAW:      UNKNOWN    -> RAW_VERIFIED     (or RAW_REJECTED)
```

Discovery never requires `SESSION_VERIFIED`. The selected route is validated naturally: a delivered first preview frame records session verification. Temporary camera-in-use, maximum-cameras-in-use, disconnection, backgrounding, timeout, or service conditions do not permanently blacklist a route. Only strong structural evidence is persisted as a rejection for the current camera environment. RAW verification is reserved for the later capture phase; advertised RAW and an actual declared RAW stream remain metadata evidence, not proof of delivery.

## Cache and invalidation

The compact topology cache contains enough canonical routing, metadata, role, source, and trust information to render the complete known lens selector without first constructing `CameraManager`. User labels, visibility, ordering, 1x reference, and last selection live in separate preferences keyed by deterministic `LensFingerprint`.

`CameraEnvironmentFingerprint` binds discovery state to cache schema, build fingerprint, Android API level, and discovery schema. A cheap advertised-ID signature is added after startup when available. Schema, firmware/ROM/HAL, or advertised-topology changes invalidate or trigger reconciliation; a missing post-startup signature does not prevent cache bootstrap.

The Diagnostics actions have distinct scope:

- **Rescan Cameras** discards process-local live evidence and reruns advertised Java/NDK reconciliation while preserving topology baseline, trust, and user settings.
- **Deep Rescan Cameras** also runs the bounded metadata-only deep candidate scan.
- **Reset Camera Discovery Cache** clears topology and route-trust/rejection caches, preserves unrelated lens preferences, and restarts progressive discovery.

## Concurrency and lifecycle safety

Advertised metadata work has a combined default target of four concurrent HAL reads: Java's bounded semaphore uses three lanes while the parallel NDK backend performs one characteristics read at a time. Native scans create one `ACameraManager` per scan and bound every input, candidate list, metadata vector, string, and diagnostic failure. No discovery backend owns a `CameraDevice`.

All actual open, switch, close, surface, pause, and resume transitions share the session controller's operation mutex. Callback work runs on one owned camera thread and all resources are released on success, error, timeout, cancellation, and shutdown. Structured scopes are lifecycle-owned; `GlobalScope`, `runBlocking`, and blocking sleeps are prohibited.

`CameraStartupTrace` uses a monotonic clock for cache, UI, open, session, first-frame, Java, NDK, deep-scan, and persistence milestones. Wall-clock time is cache/report metadata only and is never used for latency calculations.

## Native boundary and source provenance

`native/core` builds `libcamex_native.so` for the configured Android ABIs. `camera_discovery.cpp` uses public NDK metadata APIs (`ACameraManager_create`, advertised ID enumeration, and characteristics lookup) as an independent evidence source. It contains no camera-open, capture-session, preview, image-reader, or privileged API path.

The owner's prior repositories were studied as behavioral and architectural references at these exact snapshots:

- [`sahid-code404/Camera`](https://github.com/sahid-code404/Camera), branch `phase/01-foundation-discovery`, commit `0c7a19e1b4809c7ba03fce7a2810f43726ef5d7d`;
- [`sahid-code404/Camera-Computaional`](https://github.com/sahid-code404/Camera-Computaional), branch `main`, commit `03d04e13c676e9c3eab6258ea3c5bf24ccbbf86b`.

The useful ideas were metadata-only NDK enumeration, deterministic bounded candidate planning, per-ID failure isolation, and fast primary seeding. Legacy code that opened speculative hidden IDs was explicitly rejected. MotionCam may inform understanding of Android camera behavior, but its GPL source is not copied into CameX; any independently implemented CameX code must retain a clear provenance and license boundary.

## Build boundary

GitHub Actions is authoritative. The Phase 1 CI workflow runs the static architecture guard before Gradle, then unit tests, lint, native compilation, APK assembly, structural APK verification, checksum generation, and artifact upload. `BuildConfig` exposes commit-derived reproducible build metadata. See [Camera discovery](CAMERA_DISCOVERY.md), [Device compatibility](DEVICE_COMPATIBILITY.md), and [Development](DEVELOPMENT.md).
