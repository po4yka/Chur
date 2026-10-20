#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$ROOT/prepare-filament.sh"
case "${PLATFORM_NAME:?Xcode PLATFORM_NAME is required}" in
  iphoneos) slice=ios-arm64 ;;
  iphonesimulator) slice=ios-arm64_x86_64-simulator ;;
  *) echo 'Unsupported Filament platform' >&2; exit 1 ;;
esac
DEST="${DERIVED_FILE_DIR:?Xcode DERIVED_FILE_DIR is required}/chur-filament"
mkdir -p "$DEST"
for name in gltfio_core uberarchive uberzlib filament backend filabridge filaflat utils geometry stb ktxreader basis_transcoder dracodec meshoptimizer zstd abseil image ibl mikktspace shaders smol-v; do
  source="$ROOT/.vendor/filament/lib/lib$name.xcframework/$slice/lib$name.a"
  test -f "$source"
  cp "$source" "$DEST/lib$name.a"
done
