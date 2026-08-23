# Camera discovery

## Contract

Discovery answers what Android exposes and how each observed endpoint can be routed; it does not assume that one Camera2/vendor ID equals one physical lens. All IDs are opaque transport addresses, metadata is nullable/untrusted, every ID failure is isolated, and no manufacturer, model, SoC, or numeric camera-ID table decides topology.

Backends emit evidence independently. `CameraTopologyResolver` first reconciles exact transport observations into `CameraProfile` snapshots, then groups strongly matching optical evidence into `CanonicalLens` identities. `CameraTopologyRepository` publishes immutable canonical-lens topology. A slow or failed backend cannot erase credible evidence supplied by another backend while reconciliation is in progress.

## Phase 1B identity model

The central invariant is:

`Camera route / Camera ID != physical optical lens`

One physical lens may be exposed through several Camera2/vendor routes. CameX therefore models:

`CanonicalLens -> CameraProfile[]`

A `CanonicalLens` owns the stable optical fingerprint, facing, photographic role, representative optical metadata, aggregate trust and preferred profile. Each `CameraProfile` keeps its own discovered/open/physical/logical IDs, route kind, sources, exact metadata/capabilities, session/RAW/metadata trust, last attempt and failure state.

Stable optical identity never includes camera IDs when sufficient optical metadata exists. Profile fingerprints deliberately do include routing identity because their purpose is to identify one transport endpoint, not one piece of glass.

## Cache-first startup

`CameraTopologyStore` and `CameraTrustStore` are the first discovery inputs on a normal launch. A valid Phase-1B cache immediately publishes canonical lenses and exact profile snapshots. Startup resolves the remembered optical fingerprint, selects the remembered/verified preferred profile, and requests the camera without waiting for full discovery.

Warm startup remains:

`cache -> canonical lens list -> remembered optical lens -> preferred profile -> CameraDevice open -> first preview`

There is no startup-wide probe, full discovery, deep scan or profile sweep. Background reconciliation begins independently after startup work has been scheduled. The old route-per-lens cache schema is invalidated rather than reinterpreted as optical identity.

Cache compatibility uses `CameraEnvironmentFingerprint`: cache schema, Android build fingerprint, API level, discovery schema and, after a cheap live scan, an optional normalized advertised-ID signature. Corrupt or incompatible data is a cache miss rather than a crash.

On a cache miss, `scanPrimaryCameraFast()` reads only enough Java metadata to find a credible preview route. It does not fully enumerate every capability before first preview.

## Advertised reconciliation

Fast live reconciliation runs Java and NDK advertised discovery concurrently in background work:

- `JavaCameraDiscoveryBackend` reads `CameraManager.cameraIdList`, minimal characteristics and logical `physicalCameraIds`.
- `PhysicalCameraTopologyBackend` preserves logical-parent/physical-member routes even when a vendor withholds child characteristics.
- `NativeCameraDiscoveryBackend` independently uses `ACameraManager_getCameraIdList` and `ACameraManager_getCameraCharacteristics`.

Java metadata extraction is bounded by three default semaphore lanes while one lane is reserved for sequential native metadata discovery, keeping the intended combined metadata concurrency at four. Results are processed incrementally rather than with unbounded jobs against a fragile HAL.

Useful optical evidence includes facing, focal length, sensor physical size, active/pixel arrays, orientation, actual RAW geometry, approximate FOV, CFA and aperture where available. Preview/RAW stream declarations and capability tables are retained as profile capability evidence.

## Bounded deep AUX discovery

Deep AUX discovery runs only when required by a fresh/incompatible topology or by an explicit **Deep Rescan Cameras** action. It is not on the synchronous startup path.

`DeepAuxCandidatePlanner` builds a deterministic bounded candidate set from previously successful IDs, cached IDs, advertised numeric IDs, a small neighborhood around known numeric IDs and a bounded low numeric namespace. Numeric values have no lens meaning; they are merely a finite set of opaque IDs to ask the service about.

The native deep scan is metadata-only. It does not call camera-open/capture-session APIs, use hidden privileged APIs, retry endlessly or probe preview/RAW frames.

## Logical and physical routing

A public direct profile opens its public ID. A logical/physical profile stores both `openCameraId` and `streamPhysicalCameraId`; the session opens the logical device and targets the physical output on supported Android versions. NDK-discovered direct IDs remain direct candidates unless relationship evidence establishes another route.

Physical IDs are not assumed to be independently openable or present in `cameraIdList`.

## Optical grouping

Exact routing keys merge Java/NDK/cache observations into one profile snapshot first. Cross-profile optical grouping is then performed by `OpticalLensMatcher`.

The matcher is intentionally independent of transport ID, route kind and discovery source. Strongly matching facing, focal length, sensor geometry, RAW geometry, FOV, orientation, CFA/aperture evidence can therefore collapse different vendor IDs into one canonical lens.

Grouping is conservative:

- an authoritative facing/focal/sensor/CFA/orientation/FOV conflict prevents grouping;
- same focal length alone is insufficient;
- same resolution alone is insufficient;
- insufficient metadata does not aggressively merge;
- `PROBABLE_MATCH` remains separate rather than being promoted to a physical identity claim;
- only `STRONG_MATCH` enters the same canonical lens group.

Thus two genuine sensors with superficially similar optics remain separate, while multiple transport routes with a sufficiently strong optical signature become profiles of one lens.

## Profile ranking and failover

`CameraProfileSelector` ranks profiles by persisted session reliability, route accessibility and usable metadata/capabilities. It never derives priority from numeric/vendor camera IDs. `SESSION_VERIFIED` is a sticky winner and structurally rejected profiles rank out of normal attempts.

`FailoverCameraSessionController` wraps the one authoritative `CameraSessionController`; it does not own a second CameraDevice. A user selects a canonical lens, then the wrapper opens the exact stored preferred profile snapshot. If that profile fails structurally, remaining credible profiles for the same optical fingerprint are attempted in ranked order, each at most once for that selection attempt.

Example:

`profile A (structural fail) -> profile B (structural fail) -> profile C (preview verified)`

The canonical lens remains usable. C becomes persisted `SESSION_VERIFIED` and therefore preferred on the next launch. A/B are not retried before C every startup.

Transient failures such as camera-service disruption, timeout, disconnect/background transitions or in-use conditions do not permanently blacklist a profile and do not trigger an uncontrolled profile sweep. There is no A -> B -> A retry loop within one selection attempt.

## Trust

Trust is stored per profile and aggregated separately for the canonical lens. A first real preview frame advances only the selected profile to `SESSION_VERIFIED`. Structural failure applies only to that profile. A canonical lens becomes session-rejected only when all credible profiles are structurally rejected.

`lastAttemptEpochMs` is diagnostic wall-clock metadata only; it is neither latency data nor a ranking input. Environment changes invalidate environment-bound trust.

RAW trust remains separate. Phase 1B does not acquire RAW frames, save DNGs or claim new RAW verification; those are later-phase concerns.

## Camera UI and error behavior

The normal camera UI, zoom/focal-length controls and Lens Settings consume canonical optical lenses, not profile IDs. Multiple profiles never create duplicate normal lens buttons.

Profile-specific structural errors are primarily diagnostics data. While another profile of the same canonical lens can succeed, the failover wrapper suppresses the intermediate large camera error. A user-facing unavailable error is exposed only after every credible profile for that lens has failed structurally, or when a genuine service-level failure must be surfaced.

Discovery completion only updates available topology/profile snapshots; it never tears down a working preview.

## Photographic taxonomy

Roles remain explicit:

- photographic: `PHOTOGRAPHIC_ULTRAWIDE`, `PHOTOGRAPHIC_WIDE`, `PHOTOGRAPHIC_TELEPHOTO`, `PHOTOGRAPHIC_SUPER_TELEPHOTO`, `PHOTOGRAPHIC_MACRO`, `PHOTOGRAPHIC_MONO`, `PHOTOGRAPHIC_UNKNOWN`;
- confidently non-photographic: `NON_PHOTO_DEPTH`, `NON_PHOTO_TOF`, `NON_PHOTO_IR`;
- unavailable: `SYSTEM_ONLY`, `INACCESSIBLE`, `BROKEN`.

Facing is separate. FOV is computed only from valid geometry/focal data; unknown metadata never becomes a fabricated negative claim.

Credible `PHOTOGRAPHIC_UNKNOWN` lenses remain available under Advanced Discovered Cameras. Depth/ToF/IR/system/inaccessible lenses remain diagnostics-only. There is no blanket AUX exclusion.

## RAW evidence

RAW metadata is cross-checked. An actual portable RAW stream is evidence even when a redundant capability flag is missing; an advertised RAW bit without a usable stream is recorded as a discrepancy. `RAW_PRIVATE` remains diagnostic because its layout is implementation-specific.

## Recovery actions

- **Rescan Cameras** reruns advertised Java/NDK reconciliation and keeps user lens preferences.
- **Deep Rescan Cameras** adds the bounded metadata-only deep scan.
- **Reset Camera Discovery Cache** clears topology/profile trust and rejections while retaining lens labels, visibility, order, 1x reference and unrelated settings.

## Diagnostics and compatibility export

Diagnostics separates **Canonical Optical Lens** from expandable **Camera Profiles**. Canonical fields include optical fingerprint, facing, role, focal/FOV/sensor geometry, aggregate trust, preferred profile and profile count. Each profile exposes profile fingerprint, route IDs, route kind, discovery sources, metadata/session/RAW trust, last attempt, failure/durability, preview verification and RAW advertisement.

Compatibility JSON schema v3 makes `canonicalLenses[].profiles[]` authoritative. It includes optical/profile fingerprints, preferred profile, profile ranking/score, profile trust, last attempt, failure durability, sources, canonical trust and grouping confidence. Failed profiles remain visible in diagnostics/export even though they do not become normal lens buttons.

Reports contain no photos, account data, contacts, location, tokens or other personal media.

## Fallbacks

- missing geometry: generic label and no invented FOV;
- malformed metadata: bounded per-ID failure and continued discovery;
- uncertain optical match: keep separate canonical lenses;
- Java/NDK disagreement: retain independent profile evidence;
- `SYSTEM_CAMERA` restriction: record it, never bypass Android access control;
- backend failure: keep cache and evidence from successful backends;
- firmware/ROM/HAL change: invalidate environment-bound topology/trust while matching user preferences by optical fingerprint where valid.
