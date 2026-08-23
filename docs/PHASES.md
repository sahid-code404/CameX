# Product phases

## Phase 1 — foundation, discovery, preview, and diagnostics

Current scope:

- reproducible GitHub Actions Android build and development APK;
- Camera2 capability discovery with logical/physical relationships;
- deterministic lens fingerprints, classification, FOV/zoom policy, and duplicate filtering;
- conservative sequential probes and failure isolation;
- real preview and safe lens switching;
- DataStore-backed lens label, visibility, order, 1x reference, and last-selection preferences;
- diagnostics and sanitized compatibility-report export;
- minimal Kotlin → JNI → C++20 → Kotlin self-test.

Phase 1 is complete only after CI is green and at least one real device passes the documented acceptance sequence. Additional devices may reveal foundational defects that remain Phase 1 work.

## Later phases — intentionally unimplemented

Future work may include final RAW/DNG capture, a computational image pipeline, multi-frame alignment/fusion, HDR, night and portrait processing, denoise, super-resolution, RAW video, enhanced video stabilization, gallery features, and a production update system.

These are boundaries, not a committed schedule or implemented placeholders. Phase 1 must not fake their behavior. In particular, the visible shutter can be disabled or labeled as future work, but it must not pretend to capture a final image.

Production signing and OTA delivery require a dedicated security design: a permanent protected key, signed update metadata, checksum and signature verification, rollback protection, and safe PackageInstaller behavior. Debug-signed Phase 1 artifacts do not establish that trust chain.

The next phase must not begin until Phase 1's physical-device results and exported compatibility report have been reviewed.
