# Device compatibility

## What CI can prove

GitHub Actions proves that the Kotlin/Compose application, Camera2 adapters, unit-tested policy, C++20 library, and JNI symbols compile into a structurally valid APK. It cannot provide a representative physical Camera HAL. Emulator success is not evidence that auxiliary cameras, logical/physical routing, RAW streams, or stabilization work on a phone.

No device family is currently declared universally supported. Compatibility is established from runtime diagnostics and repeatable physical tests, never a brand assumption.

## Physical-device validation

For each test phone, record the app commit SHA, Android build, and exported compatibility report, then verify:

- clean installation and launch;
- camera permission denial, grant, and subsequent launch;
- rear/front discovery and logical/physical relationships;
- filtering of depth, IR, support, inaccessible, and duplicate nodes;
- live preview orientation, aspect ratio, rotation, background/resume, and rapid lens switching;
- RAW capability claims against safe probe results;
- rename, visibility, ordering, 1x reference, and last-lens persistence;
- diagnostics readability and sanitized JSON export;
- continued operation when one camera route fails.

Suggested short sequence:

1. Install the development APK, launch it, and grant camera permission.
2. Switch through every visible rear and front lens; rotate the phone once on each facing.
3. Background and resume the app, then switch lenses several times.
4. Rename, hide, and reorder one lens; force-stop and relaunch to confirm persistence.
5. Open diagnostics, retry probes once, and export the compatibility report.

Do not repeatedly probe a route that disconnects the camera service; reboot the phone before collecting a clean follow-up report.

## Recording results

Use one row per app commit and OS build. “Pass” means the checklist was actually run, not that metadata merely advertised the feature.

| Device / OS build | App commit | Discovery | Preview / switch | RAW probe | Settings | Report | Notes |
|---|---|---|---|---|---|---|---|
| Awaiting physical validation | — | — | — | — | — | — | — |

Compatibility fixes should improve the generic capability path first. A quirk is appropriate only for a narrow, reproducible HAL defect and must document its match criteria, evidence, correction, and safe fallback.

## Report privacy

The exported report may contain device model/build information, graphics capabilities, Camera2 IDs and characteristics, app build information, probe outcomes, and applied quirks. It must not contain photographs, account data, contacts, location, personal media paths, or credentials. Review a report before publishing it if a device/build identifier is sensitive in your context.
