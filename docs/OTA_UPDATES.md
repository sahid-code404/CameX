# CameX GitHub OTA updates

## Scope

CameX follows the same simple GitHub Release OTA shape as `sahid-code404/Universal_Camera`:

`git tag vX.Y.Z → git push origin vX.Y.Z → GitHub Actions → signed APK + release-manifest.json → GitHub Release → Camera checks /releases/latest → download → verify → Android installer`

The public release workflow is tag-only. There is no AI release branch, manual workflow dispatch, candidate/staging release path, GitHub Environment, release database, or separate OTA channel.

Like `Universal_Camera`, CameX performs a lightweight update check when Camera opens if the previous automatic check was at least 12 hours ago. The check runs asynchronously and does not block camera discovery or preview. Manual checking remains available from Diagnostics → Updates.

## Release flow

Normal releases are created from a pushed `v*` tag. The workflow derives:

- `versionName`: `${GITHUB_REF_NAME#v}`
- `versionCode`: `${GITHUB_RUN_NUMBER}`

It builds `assembleRelease`, prepares `Camera-<version>.apk` and `release-manifest.json`, then creates the GitHub Release.

The workflow retains the same ordinary Android release-signing placeholders/style as the reference repository. Release signing/publication validation is deferred until releases are actually being produced; it is not an implementation blocker for Phase 2.

No keystore is committed to the repository and there is no second OTA metadata-signing system.

## Release assets

Every release contains exactly two OTA assets:

1. `Camera-<version>.apk`
2. `release-manifest.json`

Manifest schema:

```json
{
  "schema": 1,
  "versionCode": 123,
  "versionName": "0.1.1",
  "minSdk": 23,
  "apkAssetName": "Camera-0.1.1.apk",
  "sha256": "...",
  "signingCertSha256": "...",
  "changelog": "See GitHub release notes for v0.1.1.",
  "mandatory": false
}
```

`versionCode` is the GitHub Actions release workflow run number for that release.

## Update check

CameX requests:

`https://api.github.com/repos/sahid-code404/CameX/releases/latest`

The updater finds `release-manifest.json`, parses schema 1, compares `versionCode`, and locates the APK using `manifest.apkAssetName`.

If no release exists, or `manifest.versionCode <= installedVersionCode`, Camera reports up to date. Otherwise the release is offered as an available update.

Automatic behavior:

- when Camera opens, `UpdateAutoChecker` checks only if 12 hours have elapsed since `last_check_ms`
- an available update is surfaced with the existing update popup/indicator flow
- up-to-date and failed background checks stay quiet
- Diagnostics → Updates → Check for updates remains available for an explicit manual check
- there is no WorkManager job or continuous background polling

## Download and verification

The APK downloads into app-private storage at `cacheDir/updates/`.

A `.part` file is used while the download is incomplete. After verification it is promoted to the final APK filename.

Before Android installer handoff CameX verifies:

- manifest schema is 1
- candidate versionCode is newer than installed
- device SDK meets `minSdk`
- APK SHA-256 matches the manifest
- APK package is `com.sahidcode404.camex`
- APK versionCode matches the manifest
- downloaded APK signer matches the installed Camera signing certificate
- manifest signing certificate matches the installed Camera signing certificate

The package/version/signer checks are the intentional CameX improvement over the reference updater. They remain inside one small `ApkVerifier` rather than a separate OTA security framework.

## Installer

CameX uses:

- `FileProvider`
- `Intent.ACTION_VIEW`
- MIME type `application/vnd.android.package-archive`
- `FLAG_GRANT_READ_URI_PERMISSION`

On Android 8.0+ Camera checks `PackageManager.canRequestPackageInstalls()`. If permission is missing it opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Camera.

There is no PackageInstaller session framework and no silent installation path.

## Validation status

The OTA implementation is present and structurally follows `Universal_Camera`. Actual signed release publication and physical update-in-place validation have not been claimed here; those checks are deferred until releases are being produced.

Phase 2 may proceed once the normal Phase 1C code/architecture CI is green. OTA behavior and updater code stay frozen during Phase 2.
