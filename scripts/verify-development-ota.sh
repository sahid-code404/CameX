#!/usr/bin/env bash

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failures=0

require_literal() {
  local label="$1" needle="$2" file="$3"
  if ! grep -Fq -- "$needle" "$file"; then
    echo "OTA requirement missing: $label" >&2
    failures=$((failures + 1))
  fi
}

reject_literal() {
  local label="$1" needle="$2" file="$3"
  if grep -Fq -- "$needle" "$file"; then
    echo "OTA architecture violation: $label" >&2
    failures=$((failures + 1))
  fi
}

readonly BUILD_FILE=app/build.gradle.kts
readonly MANIFEST=app/src/main/AndroidManifest.xml
readonly CAMERA_VM=app/src/main/java/com/sahidcode404/camex/CameraViewModel.kt
readonly MAIN_ACTIVITY=app/src/main/java/com/sahidcode404/camex/MainActivity.kt
readonly CLIENT=app/src/main/java/com/sahidcode404/camex/core/update/GitHubUpdateClient.kt
readonly AUTO_CHECKER=app/src/main/java/com/sahidcode404/camex/core/update/UpdateAutoChecker.kt
readonly VERIFIER=app/src/main/java/com/sahidcode404/camex/core/update/ApkVerifier.kt
readonly INSTALLER=app/src/main/java/com/sahidcode404/camex/core/update/ApkInstaller.kt
readonly MODELS=app/src/main/java/com/sahidcode404/camex/core/update/UpdateModels.kt
readonly UPDATE_VM=app/src/main/java/com/sahidcode404/camex/feature/update/UpdateViewModel.kt
readonly RELEASE_WORKFLOW=.github/workflows/release.yml
readonly DEV_WORKFLOW=.github/workflows/dev-ota.yml
readonly DEV_KEY=tools/dev-signing/camex-dev.jks.b64

for file in "$CLIENT" "$AUTO_CHECKER" "$VERIFIER" "$INSTALLER" "$MODELS" "$UPDATE_VM" \
  "$RELEASE_WORKFLOW" "$DEV_WORKFLOW" "$DEV_KEY"; do
  [[ -f "$file" ]] || { echo "OTA requirement missing: $file" >&2; failures=$((failures + 1)); }
done

require_literal "same application ID" 'applicationId = "com.sahidcode404.camex"' "$BUILD_FILE"
require_literal "dedicated devOta build type" 'create("devOta")' "$BUILD_FILE"
require_literal "fixed dev OTA signing config" 'signingConfigs.getByName("devOta")' "$BUILD_FILE"
require_literal "repository dev keystore" 'tools/dev-signing/camex-dev.jks.b64' "$BUILD_FILE"
require_literal "development OTA build channel" 'OTA_CHANNEL", "development"' "$BUILD_FILE"
require_literal "stable default OTA channel" 'OTA_CHANNEL", "stable"' "$BUILD_FILE"
require_literal "release versionName property" 'cameraVersionName' "$BUILD_FILE"
require_literal "release versionCode property" 'cameraVersionCode' "$BUILD_FILE"
require_literal "dev OTA versionName property" 'devOtaVersionName' "$BUILD_FILE"
require_literal "dev OTA versionCode property" 'devOtaVersionCode' "$BUILD_FILE"
require_literal "stable release signing properties" 'keystore.properties' "$BUILD_FILE"

require_literal "internet permission" 'android.permission.INTERNET' "$MANIFEST"
require_literal "package install permission" 'android.permission.REQUEST_INSTALL_PACKAGES' "$MANIFEST"
require_literal "FileProvider" 'androidx.core.content.FileProvider' "$MANIFEST"

require_literal "manifest schema" 'data class ReleaseManifest' "$MODELS"
require_literal "development channel model" 'DEVELOPMENT(' "$MODELS"
require_literal "stable channel model" 'STABLE(' "$MODELS"
require_literal "dev-latest endpoint" 'releases/tags/dev-latest' "$MODELS"
require_literal "stable latest endpoint" 'releases/latest' "$MODELS"
require_literal "dev manifest asset" 'dev-manifest.json' "$MODELS"
require_literal "stable manifest asset" 'release-manifest.json' "$MODELS"
require_literal "dev no-delay checks" 'automaticCheckIntervalMs = 0L' "$MODELS"
require_literal "stable 12-hour checks" '12L * 60L * 60L * 1000L' "$MODELS"
require_literal "channel-selected release URL" 'transport.readText(channel.releaseUrl)' "$CLIENT"
require_literal "channel-selected manifest" 'channel.manifestAssetName' "$CLIENT"
require_literal "partial download" '.part' "$CLIENT"
require_literal "package verification" 'UpdateFailureCode.PACKAGE_MISMATCH' "$VERIFIER"
require_literal "hash verification" 'UpdateFailureCode.HASH_MISMATCH' "$VERIFIER"
require_literal "signer verification" 'UpdateFailureCode.SIGNATURE_MISMATCH' "$VERIFIER"
require_literal "FileProvider installer" 'FileProvider.getUriForFile' "$INSTALLER"
require_literal "Android installer intent" 'Intent.ACTION_VIEW' "$INSTALLER"
require_literal "unknown-source check" 'canRequestPackageInstalls()' "$INSTALLER"
require_literal "manual update action" 'fun checkForUpdates()' "$UPDATE_VM"
require_literal "automatic update action" 'fun checkForUpdatesIfDue()' "$UPDATE_VM"
require_literal "build channel binding" 'UpdateChannel.fromBuildConfig(BuildConfig.OTA_CHANNEL)' "$UPDATE_VM"
require_literal "resume/start automatic check" 'updateViewModel.checkForUpdatesIfDue()' "$MAIN_ACTIVITY"

# Stable OTA remains the simple tag-only release path.
require_literal "tag-only stable release trigger" "tags: [ 'v*' ]" "$RELEASE_WORKFLOW"
require_literal "stable release contents permission" 'contents: write' "$RELEASE_WORKFLOW"
require_literal "stable run-number versionCode" 'VERSION_CODE="${GITHUB_RUN_NUMBER}"' "$RELEASE_WORKFLOW"
require_literal "stable tag-derived versionName" 'VERSION_NAME="${GITHUB_REF_NAME#v}"' "$RELEASE_WORKFLOW"
require_literal "stable release build" ':app:assembleRelease' "$RELEASE_WORKFLOW"
require_literal "stable GitHub release" 'gh release create "$GITHUB_REF_NAME"' "$RELEASE_WORKFLOW"
reject_literal "AI-only stable release branch" 'ota-release/v*' "$RELEASE_WORKFLOW"
reject_literal "automatic stable tag target" '--target "$GITHUB_SHA"' "$RELEASE_WORKFLOW"

# Development OTA is a separate rolling channel published on every branch push.
require_literal "development workflow name" 'name: Development OTA' "$DEV_WORKFLOW"
require_literal "development branch trigger" "- 'phase/**'" "$DEV_WORKFLOW"
require_literal "development main trigger" '- main' "$DEV_WORKFLOW"
require_literal "development write permission" 'contents: write' "$DEV_WORKFLOW"
require_literal "development concurrency" 'cancel-in-progress: true' "$DEV_WORKFLOW"
require_literal "commit-deterministic dev versionCode" 'git show -s --format=%ct "$GITHUB_SHA"' "$DEV_WORKFLOW"
require_literal "commit-bearing dev versionName" '0.2.0-dev.${commit_epoch}.${short_sha}' "$DEV_WORKFLOW"
require_literal "dev build" ':app:assembleDevOta' "$DEV_WORKFLOW"
require_literal "dev APK name" 'Camera-dev.apk' "$DEV_WORKFLOW"
require_literal "dev manifest" 'dev-manifest.json' "$DEV_WORKFLOW"
require_literal "rolling dev release" 'dev-latest' "$DEV_WORKFLOW"
require_literal "rolling asset replacement" '--clobber' "$DEV_WORKFLOW"
require_literal "dev package verification" 'com.sahidcode404.camex' "$DEV_WORKFLOW"
require_literal "dev signer pin" '445538e26dd5ad46a026eeca5265fd32d1231dc8863b2383c15a5d55a3285640' "$DEV_WORKFLOW"

reject_literal "development package suffix" 'applicationIdSuffix' "$BUILD_FILE"
reject_literal "silent installer" 'PackageInstaller.Session' "$INSTALLER"
reject_literal "random branch-file updater" 'raw.githubusercontent.com' "$CLIENT"

if grep -Eq '\b(UpdateRepository|UpdateDownloader|HttpURLConnection|checkForUpdates|ApkInstaller)\b' "$CAMERA_VM"; then
  echo 'OTA architecture violation: CameraViewModel depends on OTA code' >&2
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "OTA architecture verification failed with ${failures} violation(s)." >&2
  exit 1
fi

echo "OTA architecture verification passed."
