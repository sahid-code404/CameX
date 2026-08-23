#!/usr/bin/env bash

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failures=0

require_literal() {
  local label="$1" needle="$2" file="$3"
  if ! grep -Fq -- "$needle" "$file"; then
    echo "Development OTA requirement missing: $label" >&2
    failures=$((failures + 1))
  fi
}

reject_regex() {
  local label="$1" pattern="$2"
  shift 2
  local matches
  set +e
  matches="$(grep -R -n -P --include='*.kt' --include='*.kts' --include='*.yml' --include='*.yaml' -- "$pattern" "$@" 2>/dev/null)"
  local status=$?
  set -e
  if [[ $status -eq 0 ]]; then
    echo "Development OTA architecture violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  elif [[ $status -ne 1 ]]; then
    echo "Development OTA guard search failed: $label" >&2
    exit "$status"
  fi
}

readonly BUILD_FILE=app/build.gradle.kts
readonly MANIFEST=app/src/main/AndroidManifest.xml
readonly CAMERA_VM=app/src/main/java/com/sahidcode404/camex/CameraViewModel.kt
readonly MAIN_ACTIVITY=app/src/main/java/com/sahidcode404/camex/MainActivity.kt
readonly UPDATE_MODELS=app/src/main/java/com/sahidcode404/camex/core/update/UpdateModels.kt
readonly UPDATE_PARSER=app/src/main/java/com/sahidcode404/camex/core/update/UpdateManifestParser.kt
readonly UPDATE_POLICY=app/src/main/java/com/sahidcode404/camex/core/update/UpdatePolicy.kt
readonly UPDATE_NETWORK=app/src/main/java/com/sahidcode404/camex/core/update/UpdateNetworkClient.kt
readonly UPDATE_REPOSITORY=app/src/main/java/com/sahidcode404/camex/core/update/UpdateRepository.kt
readonly UPDATE_DOWNLOADER=app/src/main/java/com/sahidcode404/camex/core/update/UpdateDownloader.kt
readonly APK_VERIFIER=app/src/main/java/com/sahidcode404/camex/core/update/ApkVerifier.kt
readonly INSTALLER=app/src/main/java/com/sahidcode404/camex/core/update/PackageInstallerController.kt
readonly UPDATE_PREFERENCES=app/src/main/java/com/sahidcode404/camex/core/update/UpdatePreferences.kt
readonly UPDATE_VM=app/src/main/java/com/sahidcode404/camex/feature/update/UpdateViewModel.kt
readonly RELEASE_WORKFLOW=.github/workflows/dev-ota-release.yml

for file in \
  "$UPDATE_MODELS" "$UPDATE_PARSER" "$UPDATE_POLICY" "$UPDATE_NETWORK" \
  "$UPDATE_REPOSITORY" "$UPDATE_DOWNLOADER" "$APK_VERIFIER" "$INSTALLER" \
  "$UPDATE_PREFERENCES" "$UPDATE_VM" "$RELEASE_WORKFLOW"; do
  [[ -f "$file" ]] || { echo "Development OTA requirement missing: $file" >&2; failures=$((failures + 1)); }
done

require_literal "dedicated devOta build type" 'create("devOta")' "$BUILD_FILE"
require_literal "devOta signing config" 'signingConfig = signingConfigs.getByName("devOta")' "$BUILD_FILE"
require_literal "fail-closed devOta keystore path" 'CAMEX_DEV_KEYSTORE_PATH' "$BUILD_FILE"
require_literal "public signer pin BuildConfig" 'OTA_SIGNING_CERT_SHA256' "$BUILD_FILE"
require_literal "same application ID" 'applicationId = "com.sahidcode404.camex"' "$BUILD_FILE"
require_literal "internet permission" 'android.permission.INTERNET' "$MANIFEST"
require_literal "package install permission" 'android.permission.REQUEST_INSTALL_PACKAGES' "$MANIFEST"
require_literal "explicit update state machine" 'sealed interface UpdateState' "$UPDATE_MODELS"
require_literal "future schema rejection" 'Unsupported update manifest schemaVersion' "$UPDATE_PARSER"
require_literal "strict package validation" 'UpdateFailureCode.PACKAGE_MISMATCH' "$UPDATE_POLICY"
require_literal "strict hash validation" 'UpdateFailureCode.HASH_MISMATCH' "$UPDATE_POLICY"
require_literal "strict signer validation" 'UpdateFailureCode.SIGNATURE_MISMATCH' "$UPDATE_POLICY"
require_literal "24 hour policy" 'AUTOMATIC_INTERVAL_MS' "$UPDATE_POLICY"
require_literal "network connect timeout" 'CONNECT_TIMEOUT_MS' "$UPDATE_NETWORK"
require_literal "network read timeout" 'READ_TIMEOUT_MS' "$UPDATE_NETWORK"
require_literal "bounded redirects" 'MAX_REDIRECTS' "$UPDATE_NETWORK"
require_literal "part-file download" '.part' "$UPDATE_DOWNLOADER"
require_literal "PackageInstaller use" 'PackageInstaller.SessionParams' "$INSTALLER"
require_literal "unknown-source preflight" 'canRequestPackageInstalls()' "$INSTALLER"
require_literal "DataStore last check" 'last_check_epoch_ms' "$UPDATE_PREFERENCES"
require_literal "manual update view model" 'fun checkForUpdates()' "$UPDATE_VM"

require_literal "workflow dispatch trigger" 'workflow_dispatch:' "$RELEASE_WORKFLOW"
require_literal "release concurrency" 'group: development-ota-release' "$RELEASE_WORKFLOW"
require_literal "release-only contents write" 'contents: write' "$RELEASE_WORKFLOW"
require_literal "keystore secret" 'CAMERA_DEV_KEYSTORE_BASE64' "$RELEASE_WORKFLOW"
require_literal "keystore password secret" 'CAMERA_DEV_KEYSTORE_PASSWORD' "$RELEASE_WORKFLOW"
require_literal "key alias secret" 'CAMERA_DEV_KEY_ALIAS' "$RELEASE_WORKFLOW"
require_literal "key password secret" 'CAMERA_DEV_KEY_PASSWORD' "$RELEASE_WORKFLOW"
require_literal "apksigner verification" 'apksigner" verify --verbose --print-certs' "$RELEASE_WORKFLOW"
require_literal "package verification" 'com.sahidcode404.camex' "$RELEASE_WORKFLOW"
require_literal "historical monotonic version scan" 'max_historical' "$RELEASE_WORKFLOW"
require_literal "draft prerelease staging" '--draft' "$RELEASE_WORKFLOW"
require_literal "stable OTA APK asset" 'Camera-dev-ota.apk' "$RELEASE_WORKFLOW"
require_literal "update manifest asset" 'update.json' "$RELEASE_WORKFLOW"
require_literal "checksum asset" 'SHA256SUMS.txt' "$RELEASE_WORKFLOW"

# Camera runtime/view-model must remain unaware of networking and update orchestration.
reject_regex \
  "network or OTA orchestration entered CameraViewModel" \
  '\b(?:UpdateRepository|UpdateDownloader|UpdateNetworkClient|checkForUpdates|HttpURLConnection|PackageInstaller)\b' \
  "$CAMERA_VM"

# No automatic check is allowed in Activity startup callbacks. A manual Updates screen action is okay.
set +e
startup_block="$(perl -0777 -ne 'while(/override\s+fun\s+(?:onCreate|onStart)\b.*?\{(.*?)\n\s*\}/sg){print $1,"\n"}' "$MAIN_ACTIVITY")"
set -e
if grep -Pq '\b(?:checkForUpdates|UpdateRepository|DefaultUpdateNetworkClient)\b' <<<"$startup_block"; then
  echo "Development OTA architecture violation: update network check on startup path" >&2
  failures=$((failures + 1))
fi

# Never commit obvious private signing material or literal passwords into production/workflow source.
reject_regex \
  "committed Android keystore/private key material" \
  '-----BEGIN (?:PRIVATE KEY|RSA PRIVATE KEY)-----|MII[A-Za-z0-9+/]{200,}' \
  app/src .github scripts

if ((failures > 0)); then
  echo "Development OTA architecture verification failed with ${failures} violation(s)." >&2
  exit 1
fi

echo "Development OTA architecture verification passed."
