#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION=1.77.3
SHA256=40bc8e5a51040cc4b0bf8aaff7bf339ce16e13485644f60dbe1cb023b284fde7
URL="https://github.com/google/filament/releases/download/v${VERSION}/filament-v${VERSION}-ios.tgz"
DEST="$ROOT/.vendor/filament"
if [[ -f "$DEST/.chur-version" && "$(cat "$DEST/.chur-version")" == "$VERSION $SHA256" && -f "$DEST/include/gltfio/materials/uberarchive.h" && -d "$DEST/lib/libfilament.xcframework" ]]; then
    printf 'Filament %s is already prepared.\n' "$VERSION"
    exit 0
fi
if [[ -e "$DEST" ]]; then
    echo "Refusing to overwrite $DEST. Move the existing SDK aside, then retry." >&2
    exit 1
fi
TEMP="$(mktemp -d "${TMPDIR:-/tmp}/chur-filament.XXXXXXXX")"
trap 'rm -rf "$TEMP"' EXIT
ARCHIVE="$TEMP/filament.tgz"
if [[ -n "${FILAMENT_ARCHIVE:-}" ]]; then
    cp "$FILAMENT_ARCHIVE" "$ARCHIVE"
else
    curl --fail --location --retry 2 --proto '=https' --tlsv1.2 "$URL" -o "$ARCHIVE"
fi
if command -v shasum >/dev/null; then
    ACTUAL="$(shasum -a 256 "$ARCHIVE" | cut -d ' ' -f 1)"
else
    ACTUAL="$(sha256sum "$ARCHIVE" | cut -d ' ' -f 1)"
fi
[[ "$ACTUAL" == "$SHA256" ]] || { echo 'Filament checksum mismatch; nothing installed.' >&2; exit 1; }
mkdir "$TEMP/sdk"
tar -xzf "$ARCHIVE" -C "$TEMP/sdk"
[[ -f "$TEMP/sdk/filament/include/gltfio/materials/uberarchive.h" ]]
[[ -d "$TEMP/sdk/filament/lib/libfilament.xcframework/ios-arm64" ]]
[[ -d "$TEMP/sdk/filament/lib/libfilament.xcframework/ios-arm64_x86_64-simulator" ]]
printf '%s %s\n' "$VERSION" "$SHA256" > "$TEMP/sdk/filament/.chur-version"
mkdir -p "$ROOT/.vendor"
mv "$TEMP/sdk/filament" "$DEST"
printf 'Prepared checksum-verified Filament %s for iOS device and Simulator.\n' "$VERSION"
