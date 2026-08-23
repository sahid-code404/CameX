# Device compatibility

## What automation proves

GitHub Actions proves that the static architecture constraints, Kotlin/Compose code, pure topology/cache/trust policy, Camera2 adapters, C++20 native metadata backend, JNI symbols, lint, and APK structure build together. It cannot provide a representative physical camera HAL or measure real startup latency. Emulator success is not evidence that AUX exposure, logical/physical routing, first-frame delivery, RAW metadata, or repeated lifecycle behavior works on a phone.

No manufacturer or device family is declared universally supported. Compatibility comes from runtime evidence and repeatable physical validation on each app commit plus OS build.

## Required real-device evidence

Use the same physical phone previously tested with the owner's Camera applications. CameX must expose every equivalent application-accessible photographic route—ultrawide, main, telephoto, macro/periscope, monochrome, or other AUX—that the older implementation can safely access. A missing route is acceptable only when the compatibility report demonstrates that current Android/firmware no longer exposes usable metadata or access.

Record the CameX commit SHA, Android build fingerprint, exported compatibility report, and screenshots of the first and cached launches. Validate:

- fresh permission denial, grant, and later relaunch behavior;
- first-install rear preview starts without waiting for full/deep discovery;
- new photographic lens buttons appear incrementally without preview restart;
- logical/physical routes open through the correct parent and deliver a frame;
- unknown photographic candidates remain testable while depth, ToF, IR, system-only, and inaccessible routes stay out of the normal selector;
- preview orientation/aspect, front/back switching, rapid lens switching, rotation, pause/resume, and surface recreation;
- user labels, visibility, order, 1x reference, and last selection survive force-stop/relaunch and topology refresh;
- normal rescan, deep rescan, and discovery-cache reset have their documented scopes;
- diagnostics are readable and exported JSON is sanitized;
- failure of one route does not remove unrelated routes or deadlock the camera service.

## Startup and stability acceptance

Performance figures are targets to measure, not universal guarantees:

- cached topology read: approximately under 20 ms where storage permits;
- cached lens row: first useful UI frame;
- camera request: immediately after cached route resolution;
- normal first preview: approximately 150–400 ms depending on HAL;
- Java/NDK reconciliation and deep scan: never block preview;
- normal cached launch: no mandatory deep scan and no startup-wide session probing.

Run at least ten fully closed launches. The cached lens row and remembered/main camera should be available immediately each time, with no duplicate routes, random disappearance, order instability, cache corruption, repeated deep scan, known-good re-probe, or camera-service deadlock.

Exercise repeated switching in this order: ultrawide → main → telephoto → main → ultrawide → front → back. It must cause no global rediscovery, route-list reset, UI freeze, stale preview, or overlapping camera opens. Background and resume must preserve topology and reopen only the selected route when needed.

## Twelve-step validation sequence

1. Install the CI development APK fresh and launch it.
2. Deny camera permission once, then grant it and continue.
3. Confirm the first rear preview begins before full/deep discovery completes.
4. Confirm additional photographic lens buttons appear without restarting that preview.
5. Run **Deep Rescan Cameras** once and compare the route set with the older Camera app on the same phone.
6. Open every normal and Advanced Discovered Camera route; record which routes deliver a first frame.
7. Run the repeated lens-switch sequence, rotate once, then background and resume.
8. Rename, hide, reorder, and choose a 1x reference; force-stop the app.
9. Relaunch and confirm all cached lens buttons appear immediately and the remembered/main preview opens without deep scan.
10. Repeat fully closed launch until ten launches have completed without instability.
11. Open Diagnostics, capture startup/cache/backend/trust screens, and export the compatibility report.
12. Send the report and screenshots with phone model, Android build, CameX commit SHA, observed timing, and any route mismatch.

Use **Rescan Cameras** to refresh advertised evidence and **Deep Rescan Cameras** only when bounded AUX exploration is intended. Use **Reset Camera Discovery Cache** to validate progressive rebuilding; it must preserve user lens settings. If the camera service repeatedly disconnects, reboot before collecting a clean follow-up report rather than looping scans.

## Recording results

Use one row per app commit and OS build. “Pass” means the complete relevant checklist ran; advertised metadata alone is not a pass.

| Device / OS build | App commit | Cold seed | Cached launch | AUX parity | Preview / switch | Rescan / cache | Report | Notes |
|---|---|---|---|---|---|---|---|---|
| Awaiting physical validation | — | — | — | — | — | — | — | — |

Compatibility fixes should improve the generic capability path first. A quirk is acceptable only for a narrow, reproducible HAL defect with documented match criteria, evidence, correction, and safe fallback; quirks must not become manufacturer-wide discovery pipelines.

## Report privacy

The compatibility report may contain device/build information, graphics capabilities, opaque Camera2 IDs, canonical topology, metadata, trust/rejection state, backend outcomes, timings, app build information, and applied quirks. It must not contain photographs, account data, contacts, location, personal media paths, credentials, raw stack traces, or unbounded vendor payloads. Review device/build identifiers before publishing if they are sensitive in your context.
