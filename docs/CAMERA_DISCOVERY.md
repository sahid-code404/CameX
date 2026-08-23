# Camera discovery

## Principle

Discovery answers two different questions:

1. What camera nodes and lens relationships does Android expose to this application?
2. Which of those routes can this application safely use for ordinary photography?

Existence is never treated as proof of usability. All metadata access is nullable and isolated per node.

## Public cameras

Discovery starts with `CameraManager.cameraIdList`. Each ID is handled independently: obtain `CameraCharacteristics`, map known fields into an internal descriptor, record unknown fields as unknown, and continue if another ID throws. No ID such as `"0"` or `"1"` has special meaning.

Facing, sensor geometry, focal lengths, stream maps, hardware level, control modes, stabilization, high-speed support, and request capabilities come from runtime characteristics. Manufacturer and model are diagnostic context only.

## Logical and physical relationships

On API levels that support logical multi-camera metadata, discovery reads `physicalCameraIds` for each logical camera. Characteristics for an exposed physical ID are read through the logical relationship where the platform permits it.

A physical-only lens is represented as:

```text
logical parent ID + physical ID + lens capabilities
```

It is not blindly passed to `openCamera`. Preview/session construction must open the logical parent and target the physical output surface using supported platform APIs. Older Android versions simply omit this relationship rather than failing discovery.

## Usability and non-photographic filtering

The normal UI only considers a route when its metadata and conservative probe support a photographic preview. Depth-only output, ToF, IR, face-authentication, tracking, monochrome support, and other auxiliary nodes remain visible in diagnostics but are not camera buttons unless their advertised streams and successful probe establish ordinary photographic use.

Classification distinguishes native/physical/max-resolution RAW, processed-only, preview-only, depth/auxiliary, privileged/system, inaccessible, broken, unknown, and disabled-by-user outcomes. A richer internal result may retain several flags rather than force one lossy enum.

## `SYSTEM_CAMERA` limitation

Android may omit privileged system cameras from a normal application's public camera list or deny access when queried/opened. CameX does not bypass that security boundary. A system-only node that can be identified is recorded as privileged and excluded; an ID Android does not reveal cannot be discovered by a normal application. Failed privileged access is not retried in a loop.

## RAW detection

RAW support requires more than a single capability bit. Discovery cross-checks request capabilities and stream configuration formats/sizes, including portable `RAW_SENSOR` and supported packed RAW formats such as RAW10, RAW12, or RAW14. It records maximum sizes and whether routing is direct or requires a logical parent.

`RAW_PRIVATE` may be reported for diagnostics but is not a universal processing foundation because its layout can be implementation-specific. Phase 1 does not save DNG files. A one-frame RAW test, if enabled later in diagnostics, must be isolated, strictly timed, immediately discarded, and reported separately from metadata/session validation.

## Lens fingerprint

Preferences use a SHA-256 hash of a normalized, versioned representation of stable observable fields when available:

- lens facing and logical/physical relationship;
- sensor physical dimensions, pixel array, active array, and orientation;
- focal lengths and apertures;
- RAW dimensions and color-filter arrangement when exposed.

Normalization uses locale-independent ordering and numeric formatting. Missing fields have explicit markers, so the same input produces the same hash. When too little metadata exists, a documented fallback adds the Camera2 ID and device/build fingerprint. This fallback is less stable across ROM updates; old settings are therefore tolerated and ignored rather than treated as corruption.

## Field of view and labels

For valid sensor dimension `d` and focal length `f`, angular field of view is approximated as:

```text
FOV = 2 * atan(d / (2 * f))
```

Rear lenses are categorized from comparable horizontal/diagonal FOV, not camera IDs. Relative zoom is the selected 1x reference's FOV ratio to another lens. Labels are rounded only when metadata is sufficiently reliable; otherwise the UI uses a neutral lens name. Users can override the 1x reference.

## Duplicate filtering

Duplicate detection compares route identity, logical parent, sensor geometry, focal lengths/FOV, orientation, and stream formats. It groups only high-confidence matches and chooses the best successfully probed representation for the normal UI. Every original descriptor remains in diagnostics, avoiding irreversible data loss from an uncertain heuristic.

## Sequential probe

Candidates are probed one at a time with explicit timeouts. Stages distinguish metadata validation, session-configuration support, device open, preview delivery, RAW configuration, optional RAW delivery, and overall usability. Each stage records success, unsupported, failure, timeout, or exception with a sanitized reason.

Cleanup runs after every outcome. A session-scoped failure memory prevents immediate retry loops; a user-started diagnostics retry can clear temporary failure state. No permanent blacklist is created from a transient failure.

## Fallbacks

- API too old for physical IDs: retain public-camera discovery and direct routes.
- Missing or invalid sensor geometry: use a generic label and omit FOV/zoom claims.
- Inconsistent RAW metadata: downgrade the RAW claim and retain the discrepancy in diagnostics.
- Session/open failure: classify only that route, release resources, and continue.
- Firmware changes: recompute fingerprints and ignore orphaned preferences safely.
- Quirk match: apply the smallest documented correction after the generic path; never replace discovery with a brand-wide pipeline.
