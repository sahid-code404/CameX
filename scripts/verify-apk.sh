#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "Usage: $0 path/to/app-debug.apk" >&2
    exit 2
fi

apk_path="$1"
if [[ ! -s "${apk_path}" ]]; then
    echo "APK is missing or empty: ${apk_path}" >&2
    exit 1
fi

for tool in unzip sha256sum; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "Required tool is unavailable: ${tool}" >&2
        exit 1
    fi
done

unzip -tq "${apk_path}" >/dev/null
archive_listing="$(unzip -Z1 "${apk_path}")"

require_entry() {
    local expected="$1"
    if ! grep -Fxq "${expected}" <<<"${archive_listing}"; then
        echo "APK is missing required entry: ${expected}" >&2
        exit 1
    fi
}

require_entry "AndroidManifest.xml"
require_entry "classes.dex"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    require_entry "lib/${abi}/libcamex_native.so"
done

echo "Verified APK archive and JNI libraries: ${apk_path}"
sha256sum "${apk_path}"
