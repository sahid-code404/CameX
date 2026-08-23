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
if [[ -z "${android_cli}" || ! -x "${android_cli}" ]]; then
    echo "Android CLI was not found. Install current Android SDK command-line tools first." >&2
    exit 1
fi

set +e
set +o pipefail
yes | "${android_cli}" --sdk="${sdk_root}" sdk install \
    "platforms/android-${compile_sdk}.0@${platform_revision}" \
    "build-tools/${build_tools}" \
    "ndk/${ndk_version}" \
    "cmake/${cmake_version}"
install_status="${PIPESTATUS[1]}"
set -o pipefail
set -e

if [[ "${install_status}" -ne 0 ]]; then
    echo "Android SDK package installation failed with status ${install_status}." >&2
    exit "${install_status}"
fi
