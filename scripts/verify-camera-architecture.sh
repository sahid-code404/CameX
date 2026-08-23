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

# Run the PCRE searches with ripgrep when available. GitHub-hosted runners do not guarantee rg,
# so fall back to GNU grep using equivalent recursive/include/multiline behavior.
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
        if (($# < 2)); then
          echo "Architecture verifier received --glob without a pattern." >&2
          return 2
        fi
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

  if ((${#paths[@]} == 0)); then
    echo "Architecture verifier received no search path." >&2
    return 2
  fi

  local -a grep_args=(-r -P --binary-files=without-match)
  if [[ "${output_mode}" == "quiet" ]]; then
    grep_args+=(-q)
  else
    grep_args+=(-n -H)
  fi
  if [[ "${multiline}" == true ]]; then
    grep_args+=(-z)
  fi

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

  local matches
  local status
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
    1)
      ;;
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
    0)
      ;;
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

reject_pattern \
  "numeric Camera2 ID used as a dispatch condition" \
  "(?i)\\b(?:camera|public|physical|logical)[a-z0-9_]*id\\b\\s*(?:===|!==|==|!=|\\.equals\\s*\\()\\s*['\"][0-9]+['\"]|['\"][0-9]+['\"]\\s*(?:===|!==|==|!=)\\s*\\b(?:camera|public|physical|logical)[a-z0-9_]*id\\b|\\bopenCamera\\s*\\(\\s*['\"][0-9]+['\"]" \
  --glob '*.kt' --glob '*.java' --glob '*.cpp' --glob '*.cc' --glob '*.cxx' --glob '*.h' --glob '*.hpp' \
  "${PRODUCTION_ROOTS[@]}"

reject_pattern \
  "numeric Camera2 ID used as a when branch" \
  "(?is)\\bwhen\\s*\\(\\s*[a-z0-9_.]*(?:camera|public|physical|logical)[a-z0-9_]*id\\s*\\)\\s*\\{.{0,400}?['\"][0-9]+['\"]\\s*->" \
  --multiline --multiline-dotall --glob '*.kt' app/src/main

reject_pattern \
  "manufacturer/model used as a camera dispatch branch" \
  "(?i)\\b(?:if|when)\\s*\\([^\\n)]*(?:Build\\s*\\.\\s*(?:MANUFACTURER|MODEL)|\\b(?:manufacturer|model)\\b)|(?:Build\\s*\\.\\s*(?:MANUFACTURER|MODEL)|\\b(?:manufacturer|model)\\b)\\s*(?:===|!==|==|!=|\\.equals\\s*\\(|\\.contains\\s*\\(|\\.startsWith\\s*\\()|['\"][^'\"]+['\"]\\s*(?:===|!==|==|!=)\\s*(?:Build\\s*\\.\\s*(?:MANUFACTURER|MODEL)|\\b(?:manufacturer|model)\\b)" \
  --glob '*.kt' --glob '*.java' app/src/main

reject_pattern \
  "blocking Thread.sleep" \
  '\bThread\s*\.\s*sleep\s*\(' \
  --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"

reject_pattern \
  "runBlocking in application source or tests" \
  '\brunBlocking\s*(?:<[^>]+>)?\s*\(' \
  --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"

reject_pattern \
  "global coroutine scope" \
  '\bGlobalScope\b' \
  --glob '*.kt' --glob '*.java' "${KOTLIN_ROOTS[@]}"

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

require_pattern \
  "bounded Java metadata semaphore" \
  '\bmetadataSemaphore\s*=\s*Semaphore\s*\(\s*metadataConcurrencyLimit\s*\)' \
  "${JAVA_DISCOVERY}"
require_pattern \
  "small hard cap on Java metadata concurrency" \
  '(?s)\bmetadataConcurrencyLimit\s*=\s*metadataConcurrency\s*\.\s*coerceIn\s*\(\s*1\s*,\s*DEFAULT_METADATA_CONCURRENCY\s*,?\s*\)' \
  --multiline --multiline-dotall \
  "${JAVA_DISCOVERY}"
require_pattern \
  "bounded metadata permit acquisition" \
  '\bmetadataSemaphore\s*\.\s*withPermit\b' \
  "${JAVA_DISCOVERY}"
require_pattern \
  "combined metadata concurrency target of four" \
  '\bDEFAULT_TOTAL_METADATA_CONCURRENCY\s*=\s*4\b' \
  "${JAVA_DISCOVERY}"
require_pattern \
  "one metadata lane reserved for parallel NDK discovery" \
  '\bRESERVED_NATIVE_METADATA_CONCURRENCY\s*=\s*1\b' \
  "${JAVA_DISCOVERY}"
require_pattern \
  "session operation mutex" \
  '\boperationMutex\s*=\s*Mutex\s*\(' \
  "${SESSION_CONTROLLER}"
require_pattern \
  "camera open serialized by the operation mutex" \
  '(?s)override\s+suspend\s+fun\s+open\s*\([^)]*\)\s*=\s*operationMutex\s*\.\s*withLock' \
  --multiline --multiline-dotall "${SESSION_CONTROLLER}"

if ((failures > 0)); then
  echo "Camera architecture verification failed with ${failures} violation(s)." >&2
  exit 1
fi

echo "Camera architecture verification passed."
