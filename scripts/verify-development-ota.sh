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
    echo "OTA simplification violation: $label" >&2
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

for file in "$CLIENT" "$AUTO_CHECKER" "$VERIFIER" "$INSTALLER" "$MODELS" "$UPDATE_VM" "$RELEASE_WORKFLOW"; do
  [[ -f "$file" ]] || { echo "OTA requirement missing: $file" >&2; failures=$((failures + 1)); }
done

for obsolete in \
  app/src/main/java/com/sahidcode404/camex/core/update/PackageInstallerController.kt \
  app/src/main/java/com/sahidcode404/camex/core/update/UpdateDownloader.kt \
  app/src/main/java/com/sahidcode404/camex/core/update/UpdateManifestParser.kt \
  app/src/main/java/com/sahidcode404/camex/core/update/UpdateNetworkClient.kt \
  app/src/main/java/com/sahidcode404/camex/core/update/UpdatePolicy.kt \
  app/src/main/java/com/sahidcode404/camex/core/update/UpdatePreferences.kt \
  app/src/main/java/com/sahidcode404/camex/core/update/UpdateRepository.kt \
  .github/workflows/dev-ota-release.yml; do
  if [[ -e "$obsolete" ]]; then
    echo "OTA simplification violation: obsolete file remains: $obsolete" >&2
    failures=$((failures + 1))
  fi
done

require_literal "same application ID" 'applicationId = "com.sahidcode404.camex"' "$BUILD_FILE"
reject_literal "dedicated devOta build type" 'create("devOta")' "$BUILD_FILE"
require_literal "release versionName property" 'cameraVersionName' "$BUILD_FILE"
require_literal "release versionCode property" 'cameraVersionCode' "$BUILD_FILE"
require_literal "stable release signing properties" 'keystore.properties' "$BUILD_FILE"

require_literal "internet permission" 'android.permission.INTERNET' "$MANIFEST"
require_literal "package install permission" 'android.permission.REQUEST_INSTALL_PACKAGES' "$MANIFEST"
require_literal "FileProvider" 'androidx.core.content.FileProvider' "$MANIFEST"

require_literal "small manifest schema" 'data class ReleaseManifest' "$MODELS"
require_literal "latest GitHub release endpoint" 'releases/latest' "$CLIENT"
require_literal "manifest lookup" 'release-manifest.json' "$CLIENT"
require_literal "manifest-selected APK" 'manifest.apkAssetName' "$CLIENT"
require_literal "partial download" '.part' "$CLIENT"
require_literal "package verification" 'UpdateFailureCode.PACKAGE_MISMATCH' "$VERIFIER"
require_literal "hash verification" 'UpdateFailureCode.HASH_MISMATCH' "$VERIFIER"
require_literal "signer verification" 'UpdateFailureCode.SIGNATURE_MISMATCH' "$VERIFIER"
require_literal "FileProvider installer" 'FileProvider.getUriForFile' "$INSTALLER"
require_literal "Android installer intent" 'Intent.ACTION_VIEW' "$INSTALLER"
require_literal "unknown-source check" 'canRequestPackageInstalls()' "$INSTALLER"
require_literal "manual update action" 'fun checkForUpdates()' "$UPDATE_VM"
require_literal "automatic due update action" 'fun checkForUpdatesIfDue()' "$UPDATE_VM"
require_literal "12-hour automatic checker" '12L * 60L * 60L * 1000L' "$AUTO_CHECKER"
require_literal "automatic check persistence" 'last_check_ms' "$AUTO_CHECKER"
require_literal "automatic check on app open" 'updateViewModel.checkForUpdatesIfDue()' "$MAIN_ACTIVITY"

require_literal "tag push trigger" "tags: [ 'v*' ]" "$RELEASE_WORKFLOW"
require_literal "release contents permission" 'contents: write' "$RELEASE_WORKFLOW"
require_literal "run-number versionCode" 'VERSION_CODE="${GITHUB_RUN_NUMBER}"' "$RELEASE_WORKFLOW"
require_literal "generic keystore secret" 'secrets.ANDROID_KEYSTORE_BASE64' "$RELEASE_WORKFLOW"
require_literal "generic keystore password" 'secrets.ANDROID_KEYSTORE_PASSWORD' "$RELEASE_WORKFLOW"
require_literal "generic key alias" 'secrets.ANDROID_KEY_ALIAS' "$RELEASE_WORKFLOW"
require_literal "generic key password" 'secrets.ANDROID_KEY_PASSWORD' "$RELEASE_WORKFLOW"
require_literal "release build" ':app:assembleRelease' "$RELEASE_WORKFLOW"
require_literal "signed APK verification" 'apksigner' "$RELEASE_WORKFLOW"
require_literal "package verification" 'com.sahidcode404.camex' "$RELEASE_WORKFLOW"
require_literal "release APK naming" 'Camera-${VERSION_NAME}.apk' "$RELEASE_WORKFLOW"
require_literal "release manifest" 'release-manifest.json' "$RELEASE_WORKFLOW"
require_literal "GitHub release creation" 'gh release create' "$RELEASE_WORKFLOW"

reject_literal "manual workflow dispatch" 'workflow_dispatch:' "$RELEASE_WORKFLOW"
reject_literal "semver versionCode allocator" '1000000' "$RELEASE_WORKFLOW"
reject_literal "historical version scan" 'max_existing' "$RELEASE_WORKFLOW"
reject_literal "old CAMERA_DEV signing secrets" 'CAMERA_DEV_' "$RELEASE_WORKFLOW"
reject_literal "duplicate unit-test CI" 'testDebugUnitTest' "$RELEASE_WORKFLOW"
reject_literal "duplicate camera architecture CI" 'verify-camera-architecture' "$RELEASE_WORKFLOW"
reject_literal "duplicate lint CI" 'lintDebug' "$RELEASE_WORKFLOW"
reject_literal "draft release staging" '--draft' "$RELEASE_WORKFLOW"
reject_literal "extra checksum release asset" 'SHA256SUMS.txt' "$RELEASE_WORKFLOW"

if grep -Eq '\b(UpdateRepository|UpdateDownloader|HttpURLConnection|checkForUpdates|ApkInstaller)\b' "$CAMERA_VM"; then
  echo 'OTA simplification violation: CameraViewModel depends on OTA code' >&2
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "Simple OTA architecture verification failed with ${failures} violation(s)." >&2
  exit 1
fi

echo "Simple OTA architecture verification passed."
