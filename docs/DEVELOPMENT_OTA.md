# CameX Development OTA

Phase 1C provides a **development-only** OTA channel for `com.sahidcode404.camex`. It is not the permanent production signing policy and it does not change the Phase 1 camera discovery, canonical-lens, profile-failover, or preview architecture.

## Security boundary

A GitHub release is transport, not trust. Before `PackageInstaller` receives an APK, Camera verifies:

1. manifest schema and `channel == development`;
2. package name is exactly `com.sahidcode404.camex`;
3. candidate `versionCode` is strictly greater than the installed `versionCode`;
4. downloaded APK size when supplied by metadata;
5. downloaded APK SHA-256;
6. APK package name and versionCode read from the APK;
7. APK signing-certificate SHA-256 equals both the manifest signer and the signer pinned into the `devOta` build.

The APK contains only the public signing-certificate fingerprint. It never contains the keystore, private key, passwords, or repository secrets.

## Repository secrets

Configure ordinary repository-level GitHub Actions secrets under **Settings → Secrets and variables → Actions → Repository secrets**:

- `CAMERA_DEV_KEYSTORE_BASE64`
- `CAMERA_DEV_KEYSTORE_PASSWORD`
- `CAMERA_DEV_KEY_ALIAS`
- `CAMERA_DEV_KEY_PASSWORD`

No GitHub Environment is required for Phase 1C. The release workflow fails closed if any required secret is absent and reports only the missing secret names.

Never commit the keystore or passwords. Never upload the decoded keystore as an artifact.

## One-time development keystore bootstrap

Create the development identity once on a trusted machine and keep the original keystore backed up securely. One example command shape is:

```bash
keytool -genkeypair \
  -keystore camera-development-ota.jks \
  -alias <development-alias> \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Choose passwords interactively or through a secure local secret mechanism. Do not paste real passwords into documentation, shell history, issues, CI logs, or source files.

Encode the finished keystore for the repository secret without altering the original:

```bash
base64 -w 0 camera-development-ota.jks
```

Store that output as `CAMERA_DEV_KEYSTORE_BASE64`; store the corresponding alias and passwords in the other repository secrets.

## Dedicated `devOta` build

`devOta` keeps the same application ID and all four native ABIs:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

It is development-debuggable, but it does **not** silently use Android's default debug key. A `devOta` Gradle task requires the CI-provided keystore path/passwords, a monotonic versionCode/versionName, and the normalized public signer SHA-256.

Normal debug CI remains separate and does not need signing secrets.

## First base installation

Older Phase 1 CI/debug APKs may be signed by a different certificate. Android will reject an in-place signature change.

Therefore the first stable `devOta` base installation may require exactly one uninstall/reinstall:

1. export any diagnostic data you want to keep from the old debug APK;
2. uninstall the old differently signed debug build if Android rejects the new signer;
3. install the first `Camera-dev-ota.apk` manually;
4. after that, keep the same development signing identity for every Phase 1C OTA build.

Normal later OTA updates must **not** clear app data. Topology cache, profile trust, lens names/visibility/order, rear 1× reference, and last front/rear selections should remain intact across in-place updates.

## Release workflow

Manual workflow: `.github/workflows/dev-ota-release.yml` (`Development OTA Release`).

The workflow:

1. checks out the selected immutable source commit;
2. runs `git diff --check` and the camera architecture verifier;
3. validates required repository-secret names;
4. derives the public signer SHA-256 from the decoded temporary keystore;
5. allocates a monotonic versionCode using UTC time and the maximum historical `dev-ota-v*` release tag;
6. runs focused Phase 1B camera regressions;
7. runs focused update tests and the full unit/lint gate;
8. assembles the signed `devOta` variant with all four ABIs;
9. runs `apksigner verify --print-certs` and compares the signer digest;
10. verifies package/version and native libraries;
11. hashes the final signed APK;
12. builds `update.json` and `SHA256SUMS.txt`;
13. creates a draft prerelease with all assets;
14. verifies the draft asset set;
15. publishes the prerelease.

If validation fails, no published prerelease is created. A draft release, if one was already created, remains unpublished and also reserves its historical version tag so a later workflow advances instead of reusing that versionCode.

Release assets are:

- `Camera-dev-ota.apk`
- `update.json`
- `SHA256SUMS.txt`

Historical release tags/assets are never overwritten.

## Versioning

The release workflow never uses only `GITHUB_RUN_NUMBER`, because run numbers can restart when workflow history changes. Instead it computes a time-based candidate and compares it with every historical `dev-ota-v<versionCode>` release, including drafts. If needed it advances to `maxHistorical + 1`.

Every published candidate therefore has a strictly increasing Android `versionCode`, remains below the signed 32-bit Android limit, and is represented by a unique release tag.

## Update manifest

`update.json` is schema-versioned. Schema 1 contains the development channel, package, versionCode/versionName, Git SHA, APK name/download URL/size/SHA-256/public signer SHA-256, minimum SDK, publication time, mandatory flag, and optional release notes.

The app rejects unknown future schemas instead of guessing their meaning.

## In-app update flow

The Updates screen is under **Camera diagnostics → Updates**. It shows the current version, versionCode, Git SHA, OTA channel, last check, installed signer, and pinned development signer.

No network request is placed on the camera startup critical path. Phase 1C initially checks only when the user taps **Check for updates**.

The state machine is explicit:

`Idle → Checking → UpToDate | UpdateAvailable → Downloading → Verifying → ReadyToInstall → AwaitingInstallPermission | Installing → AwaitingUserAction → Installed`

Any operational stage may fail with a structured error. Manual checks are not rate-limited. The DataStore includes fields for a future optional once-per-24-hour background policy (`lastCheckEpochMs`, `lastKnownVersionCode`, `dismissedVersionCode`).

## Download and verification

Downloads use HTTPS GitHub hosts only, explicit connection/read timeouts, bounded redirects, cancellation, and app-private storage. A candidate stays as a `.part` file until download completion and validation. Invalid or interrupted files are deleted.

Before promotion to `Camera-dev-ota.apk`, the app verifies size, SHA-256, package name, versionCode, and signer.

## Installation

Installation uses supported Android `PackageInstaller` APIs only. There is no root, Shizuku, hidden API, Accessibility, or silent-install bypass.

On Android versions that require it, Camera checks `PackageManager.canRequestPackageInstalls()`. If permission is missing, the Updates screen explains why and provides an explicit button to open the supported per-app unknown-source settings page. Camera does not repeatedly force-open Settings.

Android's normal update confirmation remains required.

## Development key versus future production key

Phase 1C's signer is a **development OTA identity**. It must remain stable throughout development OTA testing, but it is not automatically the future production identity.

A later deliberate transition to a permanent production key may require one final uninstall/reinstall. Once production signing begins, the production identity should remain stable long-term.

## Physical acceptance

Phase 1C remains in a DRAFT PR until a real device passes:

1. install the first stable development-signed base APK;
2. publish a second prerelease with a higher versionCode;
3. open **Camera diagnostics → Updates → Check for updates**;
4. confirm the newer build is found;
5. download and verify it;
6. complete Android's update confirmation;
7. reopen Camera and verify the app updated in place;
8. confirm app data and camera preferences survived;
9. verify rear AUX lenses, each rear preview, front/rear switching, profile failover, warm-cache startup, and diagnostics export.

Do not merge the Phase 1C PR merely because CI is green. Do not start Phase 2 until Phase 1C's physical OTA acceptance is complete.
