# CameX GitHub OTA updates

## Design

CameX uses one simple GitHub Release OTA path:

`vX.Y.Z tag → GitHub Actions → signed APK + release-manifest.json → GitHub Release → Camera checks /releases/latest → verify → Android installer`

There is one updater, one release workflow, one manifest schema, one application ID, and one stable development signing identity.

Camera startup is not part of this flow. No update network request is made by `CameraViewModel`, camera discovery, canonical lens grouping, profile failover, or preview startup.

## Release command

Normal releases are created from `main`:

```bash
git switch main
git pull
git tag v0.1.1
git push origin v0.1.1
```

The next update is the same:

```bash
git tag v0.1.2
git push origin v0.1.2
```

The tag must be exactly `vMAJOR.MINOR.PATCH`. The workflow derives:

- `versionName`: tag without `v`
- `versionCode`: `major * 1,000,000 + minor * 1,000 + patch`

The workflow refuses a tag whose derived versionCode is not newer than the existing `vX.Y.Z` tags.

## Repository secrets

The release workflow uses exactly these ordinary repository-level GitHub Actions secrets:

- `CAMERA_DEV_KEYSTORE_BASE64`
- `CAMERA_DEV_KEYSTORE_PASSWORD`
- `CAMERA_DEV_KEY_ALIAS`
- `CAMERA_DEV_KEY_PASSWORD`

No GitHub Environment is required. Never commit a keystore, passwords, or private keys, and never rotate the signing identity for routine updates.

## Release assets

For `v0.1.1`, GitHub publishes exactly:

- `Camera-0.1.1.apk`
- `release-manifest.json`

Manifest schema:

```json
{
  "schema": 1,
  "versionCode": 1001,
  "versionName": "0.1.1",
  "minSdk": 23,
  "apkAssetName": "Camera-0.1.1.apk",
  "sha256": "...",
  "signingCertSha256": "...",
  "changelog": "See GitHub release notes for v0.1.1.",
  "mandatory": false
}
```

The workflow derives the public signing-certificate SHA-256 from the final APK after signing. No private signing material is placed in the manifest or app.

## App update check

Diagnostics → Updates → Check for updates requests:

`https://api.github.com/repos/sahid-code404/CameX/releases/latest`

The updater finds `release-manifest.json`, parses schema 1, compares `versionCode`, and locates the APK by `manifest.apkAssetName`. Same or older releases are treated as up to date.

The APK is downloaded into `cacheDir/updates/` using a temporary `.part` file. It is never written to Photos or Downloads.

Before installer handoff CameX verifies:

- manifest schema is 1
- candidate versionCode is newer than installed
- device SDK meets `minSdk`
- APK SHA-256 matches the manifest
- APK package is `com.sahidcode404.camex`
- APK versionCode matches the manifest
- manifest signer matches the installed Camera signer
- downloaded APK signer matches the installed Camera signer

GitHub is transport. The Android application signing certificate is the trust anchor.

## Installer

CameX uses `FileProvider` + `Intent.ACTION_VIEW` with MIME type `application/vnd.android.package-archive` and read-URI permission. Android shows the normal package installer confirmation.

On Android 8.0+ the app checks `PackageManager.canRequestPackageInstalls()`. If permission is missing, Updates opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Camera.

There is no silent installation, root path, Shizuku path, Accessibility installer, hidden API, or PackageInstaller session state machine.

## One-time stable base migration

A currently installed debug APK may use a different signing certificate. For the first stable OTA base this is acceptable:

1. Uninstall the old debug Camera once if Android reports a signing conflict.
2. Install the first stable signed `Camera-<version>.apk` manually.
3. Do not rotate the signing key afterward.

All later `v*` releases signed by the same key can update that installation in place.

## Physical Phase 1C acceptance

Example validation:

1. Publish `v0.1.1` and install `Camera-0.1.1.apk` as the stable base.
2. Use Camera and set lens names/visibility/order, rear 1× reference, and rear/front selections.
3. Publish `v0.1.2` from the same source line and signing key.
4. In 0.1.1 open Diagnostics → Updates → Check for updates.
5. Confirm Camera 0.1.2 is offered.
6. Download it and wait for verification.
7. Tap Install update and confirm the Android installer.
8. Reopen Camera and confirm app data remains intact.
9. Confirm rear AUX cameras, front/rear switching, and warm cached startup still work.

Keep Phase 1C PR #2 DRAFT until this physical update-in-place test succeeds. Do not start Phase 2 before Phase 1C acceptance.
