#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIRECTORY}/.." && pwd)"
cd "${REPOSITORY_ROOT}"

failures=0

if command -v rg >/dev/null 2>&1; then
  readonly SEARCH_BACKEND="rg"
elif printf 'camex\n' | grep -Pq 'camex' >/dev/null 2>&1; then
  readonly SEARCH_BACKEND="grep"
else
  echo "Camera architecture verification requires either ripgrep (rg) or GNU grep with PCRE support." >&2
  exit 2
fi

search_pattern() {
  local output_mode="$1"
  local pattern="$2"
  shift 2

  if [[ "${SEARCH_BACKEND}" == "rg" ]]; then
    if [[ "${output_mode}" == "quiet" ]]; then
      rg --quiet --pcre2 "${pattern}" "$@"
    else
      rg --line-number --color never --pcre2 "${pattern}" "$@"
    fi
    return $?
  fi

  local multiline=false
  local -a includes=()
  local -a paths=()
  while (($# > 0)); do
    case "$1" in
      --glob)
        (($# >= 2)) || { echo "Architecture verifier received --glob without a pattern." >&2; return 2; }
        includes+=("--include=$2")
        shift 2
        ;;
      --multiline|--multiline-dotall)
        multiline=true
        shift
        ;;
      *)
        paths+=("$1")
        shift
        ;;
    esac
  done
  ((${#paths[@]} > 0)) || { echo "Architecture verifier received no search path." >&2; return 2; }

  local -a grep_args=(-r -P --binary-files=without-match)
  if [[ "${output_mode}" == "quiet" ]]; then grep_args+=(-q); else grep_args+=(-n -H); fi
  [[ "${multiline}" == true ]] && grep_args+=(-z)

  if [[ "${output_mode}" == "quiet" ]]; then
    grep "${grep_args[@]}" "${includes[@]}" -- "${pattern}" "${paths[@]}"
    return $?
  fi
  if [[ "${multiline}" == true ]]; then
    set +e
    grep "${grep_args[@]}" "${includes[@]}" -- "${pattern}" "${paths[@]}" | tr '\0' '\n'
    local grep_status=${PIPESTATUS[0]}
    set -e
    return "${grep_status}"
  fi
  grep "${grep_args[@]}" "${includes[@]}" -- "${pattern}" "${paths[@]}"
}

reject_pattern() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches status
  set +e
  matches="$(search_pattern lines "${pattern}" "$@" 2>&1)"
  status=$?
  set -e
  case "${status}" in
    0)
      echo "Architecture violation: ${label}" >&2
      echo "${matches}" >&2
      failures=$((failures + 1))
      ;;
    1) ;;
    *)
      echo "Architecture check failed while searching for: ${label}" >&2
      echo "${matches}" >&2
      exit "${status}"
      ;;
  esac
}

require_pattern() {
  local label="$1"
  local pattern="$2"
  shift 2
  local status
  set +e
  search_pattern quiet "${pattern}" "$@"
  status=$?
  set -e
  case "${status}" in
    0) ;;
    1)
      echo "Architecture requirement missing: ${label}" >&2
      failures=$((failures + 1))
      ;;
    *)
      echo "Architecture check failed while asserting: ${label}" >&2
      exit "${status}"
      ;;
  esac
}

readonly -a KOTLIN_ROOTS=(app/src/main app/src/test)
readonly -a PRODUCTION_ROOTS=(app/src/main native/core/src/main/cpp)
readonly NATIVE_DISCOVERY=native/core/src/main/cpp/camera_discovery.cpp
readonly JAVA_DISCOVERY=app/src/main/java/com/sahidcode404/camex/core/camera/discovery/JavaCameraDiscoveryBackend.kt
readonly SESSION_CONTROLLER=app/src/main/java/com/sahidcode404/camex/core/camera/CameraSessionController.kt
readonly TOPOLOGY_MODELS=app/src/main/java/com/sahidcode404/camex/core/camera/topology/CameraTopologyModels.kt
readonly TOPOLOGY_RESOLVER=app/src/main/java/com/sahidcode404/camex/core/camera/topology/CameraTopologyResolver.kt
readonly OPTICAL_MATCHER=app/src/main/java/com/sahidcode404/camex/core/camera/topology/OpticalLensMatcher.kt
readonly PROFILE_SELECTOR=app/src/main/java/com/sahidcode404/camex/core/camera/topology/CameraProfileSelector.kt
readonly FAILOVER_CONTROLLER=app/src/main/java/com/sahidcode404/camex/core/camera/runtime/FailoverCameraSessionController.kt
readonly RUNTIME_COORDINATOR=app/src/main/java/com/sahidcode404/camex/core/camera/runtime/CameraRuntimeCoordinator.kt
readonly VIEW_MODEL=app/src/main/java/com/sahidcode404/camex/CameraViewModel.kt
readonly DIAGNOSTICS_SCREEN=app/src/main/java/com/sahidcode404/camex/feature/diagnostics/DiagnosticsScreen.kt
readonly COMPATIBILITY_REPORT=app/src/main/java/com/sahidcode404/camex/core/model/CompatibilityReport.kt
readonly COMPATIBILITY_FACTORY=app/src/main/java/com/sahidcode404/camex/core/diagnostics/CompatibilityReportFactory.kt

reject_pattern \
  "numeric Camera2 ID used as a dispatch condition" \
  "(?i)\\b(?:camera|public|physical|logical)[a-z0-9_]*id\\b\\s*(?:===|!==|==|!=|\\.equals\\s*\\()\\s*['\"][0-9]+['\"]|['\"][0-9]+['\"]\\s*(?:===|!==|==|!=)\\s*\\b(?:camera|public|physical|logical)[a-z0-9_]*id\\b|\\bopenCamera\\s*\\(\\s*['\"][0-9]+['\"]" \
  --glob '*.kt' --glob '*.java' --glob '*.cpp' --glob '*.cc' --glob '*.c' --glob '*.cxx' --glob '*.h' --glob '*.hpp' \
  "${PRODUCTION_ROOTS[@]}"

reject_pattern \
  "numeric Camera2 ID used as a when branch" \
  "(?is)\\bwhen\\s*\\(\\s*[a-z0-9_.]*(?:camera|public|physical|logical)[a-z0-9_]*id\\s*\\)\\s*\\{.{0,400}?['\"][0-9]+['\"]\\s*->" \
  --multiline --multiline-dotall --glob '*.kt' app/src/main

reject_pattern \
  "manufacturer/model used as a camera dispatch branch" \
  "(?i)\\b(?:if|when)\\s*\\([^\\n)]*(?:Build\\s*\\.\\s*(?:MANUFACTURER|MODEL)|\\b(?:manufacturer|model)\\b)|(?:Build\\s*\\.\\s*(?:MANUFACTURER|MODEL)|\\b(?:manufacturer|model)\\b)\\s*(?:===|!==|==|!=|\\.equals\\s*\\(|\\.contains\\s*\\(|\\.startsWith\\s*\\()|['\"][^'\"]+['\"]\\s*(?:===|!==|==|!=)\\s*(?:Build\\s*\\.\\s*(?:MANUFACTURER|MODEL)|\\b(?:manufacturer|model)\\b)" \
  --glob '*.kt' --glob '*.java' app/src/main

reject_pattern "blocking Thread.sleep" '\bThread\s*\.\s*sleep\s*\(' --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"
reject_pattern "runBlocking in application source or tests" '\brunBlocking\s*(?:<[^>]+>)?\s*\(' --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"
reject_pattern "global coroutine scope" '\bGlobalScope\b' --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"

reject_pattern \
  "camera open/session API in native metadata discovery" \
  '\b(?:ACameraManager_openCamera|ACameraDevice_[A-Za-z0-9_]+|ACameraCaptureSession_[A-Za-z0-9_]+|ACaptureSessionOutput(?:Container)?_[A-Za-z0-9_]+|ACameraOutputTarget_[A-Za-z0-9_]+|ACameraDevice|ACameraCaptureSession|ACaptureRequest)\b' \
  "${NATIVE_DISCOVERY}"

reject_pattern \
  "removed startup-wide discovery/probe API" \
  '\b(?:discoverAndProbe|probeSequentially)\b' \
  --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"

reject_pattern \
  "blanket AUX category exclusion" \
  '\bLensCategory\s*\.\s*AUXILIARY\b|\bcategory\s*(?:!=|!==)\s*LensCategory\s*\.\s*[A-Z0-9_]*AUXILIARY\b' \
  --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"

reject_pattern \
  "camera ID based profile priority" \
  '(?i)(?:discoveredCameraId|openCameraId|streamPhysicalCameraId)[^\n]{0,120}(?:toInt|toLong|priority|score)|(?:priority|score)[^\n]{0,120}(?:discoveredCameraId|openCameraId|streamPhysicalCameraId)' \
  "${PROFILE_SELECTOR}"

# A stable optical signature is assembled only from metadata. Directly appending a transport field
# anywhere in that signature builder is forbidden; fallback profile identity is checked separately.
reject_pattern \
  "transport ID appended to optical signature" \
  'append\s*\([^\n]*(?:discoveredCameraId|openCameraId|streamPhysicalCameraId|canonicalRouteId)' \
  "${TOPOLOGY_RESOLVER}"

reject_pattern \
  "global rediscovery during lens switch" \
  '(?is)fun\s+(?:selectLens|switchFacing)\s*\([^)]*\)\s*\{.{0,1800}?\b(?:normalRescan|deepRescan|reconcile\s*\(|seedPrimaryRoute)\b' \
  --multiline --multiline-dotall "${VIEW_MODEL}"

require_pattern "runtime coordinator" '\bclass CameraRuntimeCoordinator\b' app/src/main
require_pattern "discovery coordinator" '\bclass CameraDiscoveryCoordinator\b' app/src/main
require_pattern "topology repository" '\bclass CameraTopologyRepository\b' app/src/main
require_pattern "route repository" '\bclass CameraRouteRepository\b' app/src/main
require_pattern "deterministic topology resolver" '\bobject CameraTopologyResolver\b' app/src/main
require_pattern "topology cache store" '\bclass CameraTopologyStore\b' app/src/main
require_pattern "route trust store" '\bclass CameraTrustStore\b' app/src/main
require_pattern "lazy route validator" '\bobject CameraRouteValidator\b' app/src/main
require_pattern "Java discovery backend" '\bclass JavaCameraDiscoveryBackend\b' app/src/main
require_pattern "physical-topology backend" '\bobject PhysicalCameraTopologyBackend\b' app/src/main
require_pattern "advertised NDK backend" '\bclass NativeCameraDiscoveryBackend\b' app/src/main
require_pattern "deep AUX backend" '\bclass DeepAuxDiscoveryBackend\b' app/src/main

require_pattern "canonical optical lens domain model" '\bdata class CanonicalLens\b' "${TOPOLOGY_MODELS}"
require_pattern "camera profile domain model" '\bdata class CameraProfile\b' "${TOPOLOGY_MODELS}"
require_pattern "optical lens signature" '\bdata class OpticalLensSignature\b' "${TOPOLOGY_MODELS}"
require_pattern "confidence based optical matcher" '\bobject OpticalLensMatcher\b' "${OPTICAL_MATCHER}"
require_pattern "route matcher delegates to optical signatures" 'compare\s*\(\s*signature\s*\(\s*left\s*\)\s*,\s*signature\s*\(\s*right\s*\)\s*\)' "${OPTICAL_MATCHER}"
require_pattern "profile selector" '\bobject CameraProfileSelector\b' "${PROFILE_SELECTOR}"
require_pattern "bounded profile failover controller" '\bclass FailoverCameraSessionController\b' "${FAILOVER_CONTROLLER}"
require_pattern "failover resolves exact profile descriptor" '\bprofileForRoutingKey\s*\(\s*lens\.identity\.routingKey\s*\)' "${FAILOVER_CONTROLLER}"
require_pattern "runtime receives profile descriptors" '\bCameraRoute::profileLensDescriptors\b' "${RUNTIME_COORDINATOR}"
require_pattern "profile-specific trust update" '\bwithProfileTrust\s*\(' app/src/main
require_pattern "profile diagnostics UI model" '\bdata class CameraProfileDiagnosticsUiModel\b' "${DIAGNOSTICS_SCREEN}"
require_pattern "nested canonical lens compatibility report" '\bdata class CanonicalLensCompatibilityReport\b' "${COMPATIBILITY_REPORT}"
require_pattern "nested camera profile compatibility report" '\bdata class CameraProfileCompatibilityReport\b' "${COMPATIBILITY_REPORT}"
require_pattern "compatibility export populates canonical lenses" '\bcanonicalLenses\s*=\s*canonicalLensReports\b' "${COMPATIBILITY_FACTORY}"
require_pattern "cache schema v2" '\bCACHE_SCHEMA_VERSION\s*=\s*2\b' "${TOPOLOGY_MODELS}"
require_pattern "topology schema v2" '\bCURRENT_SCHEMA_VERSION\s*=\s*2\b' "${TOPOLOGY_MODELS}"

require_pattern \
  "bounded Java metadata semaphore" \
  '\bmetadataSemaphore\s*=\s*Semaphore\s*\(\s*metadataConcurrencyLimit\s*\)' \
  "${JAVA_DISCOVERY}"
require_pattern \
  "small hard cap on Java metadata concurrency" \
  '(?s)\bmetadataConcurrencyLimit\s*=\s*metadataConcurrency\s*\.\s*coerceIn\s*\(\s*1\s*,\s*DEFAULT_METADATA_CONCURRENCY\s*,?\s*\)' \
  --multiline --multiline-dotall "${JAVA_DISCOVERY}"
require_pattern "bounded metadata permit acquisition" '\bmetadataSemaphore\s*\.\s*withPermit\b' "${JAVA_DISCOVERY}"
require_pattern "combined metadata concurrency target of four" '\bDEFAULT_TOTAL_METADATA_CONCURRENCY\s*=\s*4\b' "${JAVA_DISCOVERY}"
require_pattern "one metadata lane reserved for parallel NDK discovery" '\bRESERVED_NATIVE_METADATA_CONCURRENCY\s*=\s*1\b' "${JAVA_DISCOVERY}"
require_pattern "session operation mutex" '\boperationMutex\s*=\s*Mutex\s*\(' "${SESSION_CONTROLLER}"
require_pattern \
  "camera open serialized by the operation mutex" \
  '(?s)override\s+suspend\s+fun\s+open\s*\([^)]*\)\s*=\s*operationMutex\s*\.\s*withLock' \
  --multiline --multiline-dotall "${SESSION_CONTROLLER}"

if ((failures > 0)); then
  echo "Camera architecture verification failed with ${failures} violation(s)." >&2
  exit 1
fi

echo "Camera architecture verification passed."
