#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
    echo "Usage: $0 path/to/app-debug.apk output-directory [git-sha]" >&2
    exit 2
fi

apk_path="$1"
output_directory="$2"
git_sha="${3:-${GITHUB_SHA:-unknown}}"
short_sha="${git_sha:0:12}"

if [[ ! "${short_sha}" =~ ^[0-9A-Fa-f]{7,12}$ ]]; then
    echo "A 7-12 character hexadecimal Git SHA is required; got '${short_sha}'." >&2
    exit 2
fi

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"${script_directory}/verify-apk.sh" "${apk_path}"

mkdir -p "${output_directory}"
artifact_name="Camera-dev-${short_sha}.apk"
install -m 0644 "${apk_path}" "${output_directory}/${artifact_name}"

(
    cd "${output_directory}"
    sha256sum "${artifact_name}" > SHA256SUMS.txt
    sha256sum --check SHA256SUMS.txt
)

echo "Packaged ${output_directory}/${artifact_name} and SHA256SUMS.txt"
