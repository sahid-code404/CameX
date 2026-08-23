# CameX GitHub OTA updates

## Scope

CameX uses the same simple GitHub Release OTA shape as `sahid-code404/Universal_Camera`:

`git tag vX.Y.Z → git push origin vX.Y.Z → GitHub Actions → signed APK + release-manifest.json → GitHub Release → Camera checks /releases/latest → download → verify → Android installer`

Camera startup is not part of this flow. Phase 1C uses manual update checks only.

## Release command

After Phase 1C is accepted and merged, normal releases are:

```bash
git switch main
git pull

git tag v0.1.1
git push origin v0.1.1
```

Next release:

```bash
git tag v0.1.2
git push origin v0.1.2
```

The release workflow derives:

- `versionName`: `${GITHUB_REF_NAME#v}`
- `versionCode`: `${GITHUB_RUN_NUMBER}`

There is no semver versionCode allocator, candidate release flow, manual workflow dispatch, release database, or separate OTA channel.

## Repository secrets

The release workflow uses the same four ordinary repository-level GitHub Actions secrets as `Universal_Camera`:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

No GitHub Environment is required. The keystore is decoded only into runner temporary storage and referenced through `keystore.properties` for the release build.

The workflow derives the public signing-certificate SHA-256 from the final signed APK, so no additional certificate secret or metadata-signing key is required.

## Release assets

Every release uploads exactly two OTA assets:

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

Diagnostics → Updates → Check for updates requests:

`https://api.github.com/repos/sahid-code404/CameX/releases/latest`

The updater finds `release-manifest.json`, parses schema 1, compares `versionCode`, and locates the APK using `manifest.apkAssetName`.

If `manifest.versionCode <= installedVersionCode`, Camera is up to date. Otherwise the release is offered as an available update.

There is no startup update check, WorkManager job, background polling, or automatic pre-preview OTA work.

## Download and verification

The APK downloads into app-private storage:

`cacheDir/updates/`

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

The package/version/signer checks are the one intentional CameX improvement over the reference updater. They remain inside one small `ApkVerifier` rather than a separate OTA security framework.

## Installer

CameX uses:

- `FileProvider`
- `Intent.ACTION_VIEW`
- MIME type `application/vnd.android.package-archive`
- `FLAG_GRANT_READ_URI_PERMISSION`

On Android 8.0+ Camera checks `PackageManager.canRequestPackageInstalls()`. If permission is missing it opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Camera.

There is no PackageInstaller session framework and no silent installation path.

## Phase 1C acceptance boundary

Keep PR #2 DRAFT until the real signed update-in-place path succeeds:

1. install a stable-signed base release
2. publish a later `vX.Y.Z` tag with the same signing key
3. check from Diagnostics → Updates
4. download and verify
5. confirm Android installer handoff
6. confirm app data survives
7. confirm Phase 1B camera behavior and warm cached startup remain intact

Do not start Phase 2 before Phase 1C acceptance.
