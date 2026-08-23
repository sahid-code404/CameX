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
readonly MODELS=app/src/main/java/com/sahidcode404/camex/core/update/UpdateModels.kt
readonly CLIENT=app/src/main/java/com/sahidcode404/camex/core/update/GitHubUpdateClient.kt
readonly VERIFIER=app/src/main/java/com/sahidcode404/camex/core/update/ApkVerifier.kt
readonly INSTALLER=app/src/main/java/com/sahidcode404/camex/core/update/ApkInstaller.kt
readonly UPDATE_VM=app/src/main/java/com/sahidcode404/camex/feature/update/UpdateViewModel.kt
readonly RELEASE_WORKFLOW=.github/workflows/release.yml

for file in "$MODELS" "$CLIENT" "$VERIFIER" "$INSTALLER" "$UPDATE_VM" "$RELEASE_WORKFLOW"; do
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
require_literal "tag versionName property" 'cameraVersionName' "$BUILD_FILE"
require_literal "tag versionCode property" 'cameraVersionCode' "$BUILD_FILE"
require_literal "stable release signing properties" 'keystore.properties' "$BUILD_FILE"

require_literal "internet permission" 'android.permission.INTERNET' "$MANIFEST"
require_literal "package install permission" 'android.permission.REQUEST_INSTALL_PACKAGES' "$MANIFEST"
require_literal "FileProvider" 'androidx.core.content.FileProvider' "$MANIFEST"
require_literal "private update cache path" '@xml/update_file_paths' "$MANIFEST"

require_literal "small manifest schema" 'data class ReleaseManifest' "$MODELS"
require_literal "latest GitHub release endpoint" 'releases/latest' "$CLIENT"
require_literal "manifest asset lookup" 'release-manifest.json' "$CLIENT"
require_literal "manifest-selected APK asset" 'manifest.apkAssetName' "$CLIENT"
require_literal "partial private-cache download" '.part' "$CLIENT"
require_literal "package verification" 'UpdateFailureCode.PACKAGE_MISMATCH' "$VERIFIER"
require_literal "hash verification" 'UpdateFailureCode.HASH_MISMATCH' "$VERIFIER"
require_literal "signer verification" 'UpdateFailureCode.SIGNATURE_MISMATCH' "$VERIFIER"
require_literal "FileProvider installer handoff" 'FileProvider.getUriForFile' "$INSTALLER"
require_literal "normal Android installer intent" 'Intent.ACTION_VIEW' "$INSTALLER"
require_literal "unknown-source check" 'canRequestPackageInstalls()' "$INSTALLER"
require_literal "manual update check" 'fun checkForUpdates()' "$UPDATE_VM"

require_literal "tag push trigger" "- 'v*'" "$RELEASE_WORKFLOW"
reject_literal "manual workflow dispatch" 'workflow_dispatch:' "$RELEASE_WORKFLOW"
reject_literal "draft release staging" '--draft' "$RELEASE_WORKFLOW"
reject_literal "old candidate tag scheme" 'dev-ota-v' "$RELEASE_WORKFLOW"
reject_literal "extra checksum release asset" 'SHA256SUMS.txt' "$RELEASE_WORKFLOW"
require_literal "repository keystore secret" 'CAMERA_DEV_KEYSTORE_BASE64' "$RELEASE_WORKFLOW"
require_literal "repository keystore password secret" 'CAMERA_DEV_KEYSTORE_PASSWORD' "$RELEASE_WORKFLOW"
require_literal "repository key alias secret" 'CAMERA_DEV_KEY_ALIAS' "$RELEASE_WORKFLOW"
require_literal "repository key password secret" 'CAMERA_DEV_KEY_PASSWORD' "$RELEASE_WORKFLOW"
require_literal "apksigner verification" 'apksigner" verify --verbose --print-certs' "$RELEASE_WORKFLOW"
require_literal "release APK naming" 'Camera-${CAMERA_VERSION_NAME}.apk' "$RELEASE_WORKFLOW"
require_literal "release manifest naming" 'release-manifest.json' "$RELEASE_WORKFLOW"
require_literal "GitHub release creation" 'gh release create' "$RELEASE_WORKFLOW"

if grep -Eq '\b(UpdateRepository|UpdateDownloader|HttpURLConnection|checkForUpdates|ApkInstaller)\b' "$CAMERA_VM"; then
  echo 'OTA simplification violation: CameraViewModel depends on updater/network/installer code' >&2
  failures=$((failures + 1))
fi

startup_block="$(perl -0777 -ne 'while(/override\s+fun\s+(?:onCreate|onStart)\b.*?\{(.*?)\n\s*\}/sg){print $1,"\n"}' "$MAIN_ACTIVITY")"
if grep -Eq '\bcheckForUpdates\b|releases/latest|HttpURLConnection' <<<"$startup_block"; then
  echo 'OTA simplification violation: update network request entered camera startup callbacks' >&2
  failures=$((failures + 1))
fi

if grep -R -n -E --include='*.kt' --include='*.kts' --include='*.yml' --include='*.yaml' \
  '-----BEGIN (PRIVATE KEY|RSA PRIVATE KEY)-----|MII[A-Za-z0-9+/]{200,}' app/src .github scripts 2>/dev/null; then
  echo 'OTA security violation: committed private signing material detected' >&2
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "Simple OTA architecture verification failed with ${failures} violation(s)." >&2
  exit 1
fi

echo "Simple OTA architecture verification passed."
