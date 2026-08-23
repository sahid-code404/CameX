#!/usr/bin/env bash
set -euo pipefail

readonly compile_sdk="37"
readonly platform_revision="2"
readonly build_tools="37.0.0"
readonly ndk_version="29.0.14206865"
readonly cmake_version="4.1.2"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${sdk_root}" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to an Android SDK." >&2
    exit 1
fi

android_cli="${sdk_root}/cmdline-tools/latest/bin/android"
if [[ ! -x "${android_cli}" ]]; then
    android_cli="$(command -v android || true)"
fi

set +e
set +o pipefail
if [[ -n "${android_cli}" && -x "${android_cli}" ]]; then
    yes | "${android_cli}" --sdk="${sdk_root}" sdk install \
        "platforms/android-${compile_sdk}.0@${platform_revision}" \
        "build-tools/${build_tools}" \
        "ndk/${ndk_version}" \
        "cmake/${cmake_version}"
else
    sdkmanager="${sdk_root}/cmdline-tools/latest/bin/sdkmanager"
    if [[ ! -x "${sdkmanager}" ]]; then
        sdkmanager="$(command -v sdkmanager || true)"
    fi
    if [[ -z "${sdkmanager}" || ! -x "${sdkmanager}" ]]; then
        echo "Neither android nor sdkmanager was found in the Android SDK." >&2
        exit 1
    fi
    # GitHub-hosted runners can lag the new `android` CLI while still shipping the official
    # sdkmanager. Package IDs plus the revision checks below preserve the same exact toolchain.
    yes | "${sdkmanager}" --sdk_root="${sdk_root}" \
        "platforms;android-${compile_sdk}.0" \
        "build-tools;${build_tools}" \
        "ndk;${ndk_version}" \
        "cmake;${cmake_version}"
fi
install_status="${PIPESTATUS[1]}"
set -o pipefail
set -e

if [[ "${install_status}" -ne 0 ]]; then
    echo "Android SDK package installation failed with status ${install_status}." >&2
    exit "${install_status}"
fi

verify_revision() {
    local package_path="$1"
    local expected="$2"
    local properties="${sdk_root}/${package_path}/source.properties"
    local actual=""
    if [[ -f "${properties}" ]]; then
        actual="$(awk -F= '
            $1 ~ /^Pkg.Revision[[:space:]]*$/ {
                gsub(/[[:space:]]/, "", $2)
                print $2
                exit
            }
        ' "${properties}")"
    fi
    if [[ "${actual}" != "${expected}" ]]; then
        echo "Unexpected ${package_path} revision: expected ${expected}, got ${actual:-missing}." >&2
        exit 1
    fi
}

verify_revision "platforms/android-${compile_sdk}.0" "${platform_revision}"
verify_revision "build-tools/${build_tools}" "${build_tools}"
verify_revision "ndk/${ndk_version}" "${ndk_version}"
verify_revision "cmake/${cmake_version}" "${cmake_version}"
