#!/usr/bin/env bash
#
# Cross-compiles the native library for the mobile targets DEVELOPMENT.md
# "Native targets" requires, and checks that each artifact exports the ABI
# handshake of docs/interop/FFI_CONTRACT.md section 2.
#
# A build that produces an archive with no `chur_` symbols is a build a host
# would load and then fail to call, so the symbol check is the point of this
# script rather than an extra.
#
# Usage:
#
#   scripts/build-native-targets.sh android
#   scripts/build-native-targets.sh apple
#   scripts/build-native-targets.sh all
#
# Android needs an NDK. The script reads ANDROID_NDK_HOME, then
# ANDROID_NDK_ROOT, then the newest NDK under ANDROID_HOME/ndk. Apple targets
# need Xcode and run on macOS only.

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root/rust"

# The API level every Android artifact targets, frozen by ADR-0017.
readonly ANDROID_API=29

# The iOS deployment target, frozen by ADR-0017.
#
# It is exported rather than left to Rust's default. The vendored C of ADR-0038
# is compiled against the installed SDK, whose objects reference symbols Rust's
# default iOS 10 link target does not provide, and the link then fails on
# `___chkstk_darwin`.
export IPHONEOS_DEPLOYMENT_TARGET=18.0

readonly ANDROID_TARGETS=(aarch64-linux-android x86_64-linux-android)
readonly APPLE_TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim)

# The exports FFI_CONTRACT.md section 2 requires, plus the status predicate.
readonly REQUIRED_SYMBOLS=(
  chur_abi_version_major
  chur_abi_version_minor
  chur_capabilities
  chur_object_format_min
  chur_object_format_max
  chur_key_slot_format_min
  chur_key_slot_format_max
  chur_build_flavor
  chur_status_is_known
)

log() { printf '== %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

find_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    printf '%s' "$ANDROID_NDK_HOME"; return
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
    printf '%s' "$ANDROID_NDK_ROOT"; return
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$sdk" && -d "$sdk/ndk" ]] || die "no NDK: set ANDROID_NDK_HOME"
  local newest
  newest="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
  [[ -n "$newest" ]] || die "no NDK under $sdk/ndk"
  printf '%s' "$newest"
}

ndk_prebuilt() {
  local ndk="$1" host
  case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) die "unsupported host for the NDK: $(uname -s)" ;;
  esac
  local prebuilt="$ndk/toolchains/llvm/prebuilt/$host"
  [[ -d "$prebuilt" ]] || die "no NDK toolchain at $prebuilt"
  printf '%s' "$prebuilt"
}

# Cargo takes the linker from an environment variable whose name embeds the
# target, which is how a machine-specific NDK path stays out of a checked-in
# .cargo/config.toml.
linker_variable() {
  local target="$1"
  printf 'CARGO_TARGET_%s_LINKER' "$(printf '%s' "$target" | tr 'a-z-' 'A-Z_')"
}

# The symbol reader. An Android artifact is ELF and a macOS `nm` cannot read
# one, so the NDK's llvm-nm is used for those targets.
NM_TOOL="${NM_TOOL:-}"

check_symbols() {
  local artifact="$1" reader missing=0
  [[ -f "$artifact" ]] || die "no artifact at $artifact"
  if [[ -n "$NM_TOOL" ]]; then reader=("$NM_TOOL" --defined-only)
  elif command -v llvm-nm >/dev/null 2>&1; then reader=(llvm-nm --defined-only)
  elif command -v nm >/dev/null 2>&1; then reader=(nm)
  else die "neither llvm-nm nor nm is available"; fi
  # Take the last field of every line, drop the Mach-O leading underscore, and
  # compare whole names. A herestring rather than a pipe into `grep -q`: `-q`
  # exits at the first match, the writer takes SIGPIPE, and `pipefail` would
  # then report a match as a failure.
  local symbols
  symbols="$({ "${reader[@]}" "$artifact" 2>/dev/null || true; } | awk '{ print $NF }' | sed 's/^_//' | sort -u)"
  local wanted
  for wanted in "${REQUIRED_SYMBOLS[@]}"; do
    if ! grep -qxF "$wanted" <<<"$symbols"; then
      printf '   missing %s\n' "$wanted" >&2
      missing=$((missing + 1))
    fi
  done
  [[ $missing -eq 0 ]] || die "$artifact exports $missing fewer symbols than the handshake needs"
  printf '   %s exports all %d handshake symbols\n' \
    "$(basename "$artifact")" "${#REQUIRED_SYMBOLS[@]}"
}

target_directory() {
  cargo metadata --format-version 1 --no-deps \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])'
}

build_target() {
  local target="$1"
  local installed directory
  installed="$(rustup target list --installed)"
  grep -qxF "$target" <<<"$installed" \
    || die "target $target is not installed: rustup target add $target"
  log "building $target"
  cargo build -p chur-ffi --release --target "$target"
  directory="$(target_directory)"
  check_symbols "$directory/$target/release/libchur_ffi.a"
}

# The Android JNI adapter of ADR-0040.
#
# It is a second artifact because FFI_CONTRACT.md section 6.2 forbids a `Java_*`
# symbol in the Chur library. Its own symbol set is checked against the export
# list it wraps by rust/crates/chur-jni/tests/surface.rs; what this checks is
# that the shared library builds and exports the adapter at all, because an
# artifact with no `Java_` symbol is one the JVM would load and then fail to
# call.
build_jni() {
  local target="$1" directory artifact count
  log "building the JNI adapter for $target"
  cargo build -p chur-jni --release --target "$target"
  directory="$(target_directory)"
  artifact="$directory/$target/release/libchur_jni.so"
  [[ -f "$artifact" ]] || die "no JNI adapter at $artifact"
  count="$({ "$NM_TOOL" --defined-only "$artifact" 2>/dev/null || true; } \
    | awk '{ print $NF }' | grep -c '^Java_dev_po4yka_chur_ffi_ChurJni_' || true)"
  [[ "$count" -ge 40 ]] || die "$artifact exports $count JNI symbols, which is too few"
  printf '   %s exports %s JNI symbols\n' "$(basename "$artifact")" "$count"
}

build_android() {
  local ndk prebuilt target variable
  ndk="$(find_ndk)"
  prebuilt="$(ndk_prebuilt "$ndk")"
  log "NDK $ndk"
  export AR="$prebuilt/bin/llvm-ar"
  # The vendored OpenSSL of ADR-0038 configures itself for Android and then
  # invokes `${CROSS_COMPILE}ranlib` through make, which resolves on PATH rather
  # than through an environment variable. Without the NDK's bin directory on
  # PATH the build fails at `install_dev` with "ranlib: command not found",
  # which names neither OpenSSL nor the NDK.
  export PATH="$prebuilt/bin:$PATH"
  export RANLIB="$prebuilt/bin/llvm-ranlib"
  NM_TOOL="$prebuilt/bin/llvm-nm"
  [[ -x "$NM_TOOL" ]] || die "no llvm-nm at $NM_TOOL"
  for target in "${ANDROID_TARGETS[@]}"; do
    local clang="$prebuilt/bin/${target}${ANDROID_API}-clang"
    [[ -x "$clang" ]] || die "no linker at $clang"
    variable="$(linker_variable "$target")"
    export "$variable=$clang"
    # The `cc` crate reads these when a dependency has a build script.
    export CC="$clang" CXX="${clang}++"
    build_target "$target"
    build_jni "$target"
  done
}

build_apple() {
  [[ "$(uname -s)" == "Darwin" ]] || die "the Apple targets build on macOS only"
  NM_TOOL=""
  local target
  for target in "${APPLE_TARGETS[@]}"; do
    build_target "$target"
  done
}

case "${1:-all}" in
  android) build_android ;;
  apple) build_apple ;;
  all) build_android; build_apple ;;
  *) die "usage: $0 [android|apple|all]" ;;
esac

log "every requested target built and exports the handshake"
