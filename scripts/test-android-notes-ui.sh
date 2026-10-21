#!/usr/bin/env bash
# Run the notes/lock and HEVC Main10 tests on one disposable Linux/KVM emulator.
# The APKs are built by the Android application job; this script never compiles.
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" ]] || { echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2; exit 1; }
readonly adb="$sdk_root/platform-tools/adb"
readonly emulator="$sdk_root/emulator/emulator"
readonly avdmanager="$sdk_root/cmdline-tools/latest/bin/avdmanager"
readonly image='system-images;android-35;google_apis;x86_64'
readonly application='dev.po4yka.chur'
readonly apk="$repository_root/apps/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
readonly test_apk="$repository_root/apps/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
readonly results="${CHUR_NOTES_UI_RESULTS:-$repository_root/build/notes-ui-ci}"

for executable in "$adb" "$emulator" "$avdmanager"; do
    [[ -x "$executable" ]] || { echo "Missing SDK executable: $executable" >&2; exit 1; }
done
for executable in timeout setsid python3; do
    command -v "$executable" >/dev/null || { echo "Missing executable: $executable" >&2; exit 1; }
done
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo "This UI gate requires accessible Linux KVM" >&2; exit 1; }
[[ -f "$apk" && -f "$test_apk" ]] || { echo "Build the application and instrumented test APKs first" >&2; exit 1; }
mkdir -p "$results"
[[ -z "$(find "$results" -mindepth 1 -maxdepth 1 -print -quit)" ]] || {
    echo "Use an empty CHUR_NOTES_UI_RESULTS directory; existing results are preserved" >&2; exit 1;
}

owned_directory="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/chur-notes-ui.XXXXXX")"
export ANDROID_AVD_HOME="$owned_directory/avd"
mkdir -p "$ANDROID_AVD_HOME"
avd_name="ChurNotesCI-$$"
emulator_pid=''
serial=''
files_collected=false
collect_artifacts() {
    [[ -n "$serial" ]] || return 0
    timeout 15 "$adb" -s "$serial" logcat -d -v threadtime > "$results/logcat.txt" 2>&1 || true
    if [[ "$files_collected" == false ]]; then
        if timeout 60 "$adb" -s "$serial" pull "/sdcard/Android/data/$application/files" "$results/screenshots" > "$results/pull.txt" 2>&1; then
            files_collected=true
        fi
    fi
}
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    collect_artifacts || true
    if [[ -n "$emulator_pid" ]]; then
        # setsid gives this emulator its own group. Never stop the ADB server,
        # another serial, or a pre-existing emulator on the machine.
        kill -TERM -- "-$emulator_pid" 2>/dev/null || kill -TERM "$emulator_pid" 2>/dev/null || true
        for ((attempt = 0; attempt < 10; attempt++)); do
            if ! kill -0 "$emulator_pid" 2>/dev/null && ! kill -0 -- "-$emulator_pid" 2>/dev/null; then break; fi
            sleep 1
        done
        kill -KILL -- "-$emulator_pid" 2>/dev/null || kill -KILL "$emulator_pid" 2>/dev/null || true
        wait "$emulator_pid" 2>/dev/null || true
    fi
    rm -rf -- "$owned_directory"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Reserve no ambient/default AVD and avoid ports already used by another device.
port="$(python3 - <<'PY'
import socket
for port in range(5560, 5682, 2):
    sockets = [socket.socket(), socket.socket()]
    try:
        for listener, number in zip(sockets, (port, port + 1)):
            listener.bind(("127.0.0.1", number))
        print(port)
        break
    except OSError:
        pass
    finally:
        for listener in sockets:
            listener.close()
else:
    raise SystemExit("No free emulator console/ADB port pair")
PY
)"
serial="emulator-$port"
timeout 30 "$adb" start-server
timeout 120 "$avdmanager" create avd --name "$avd_name" --package "$image" \
    --device pixel_7 --path "$owned_directory/device.avd" <<< 'no' > "$results/avd.txt" 2>&1
# SwiftShader renders in software; KVM accelerates the two guest CPU cores.
setsid "$emulator" -avd "$avd_name" -port "$port" -no-window -no-audio -no-boot-anim \
    -no-snapshot -wipe-data -gpu swiftshader -accel on -cores 2 -memory 2048 \
    -camera-back none -camera-front none > "$results/emulator.txt" 2>&1 &
emulator_pid=$!
boot_deadline=$((SECONDS + 300))
while [[ "$(timeout 5 "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" != 1 ]]; do
    # The background launcher may still be starting setsid; its process group
    # does not exist until that child has actually entered its new session.
    if ! kill -0 "$emulator_pid" 2>/dev/null && ! kill -0 -- "-$emulator_pid" 2>/dev/null; then
        echo "The owned emulator exited during boot" >&2
        exit 1
    fi
    ((SECONDS < boot_deadline)) || { echo "The owned emulator did not boot within five minutes" >&2; exit 1; }
    sleep 2
done

timeout 15 "$adb" -s "$serial" shell input keyevent KEYCODE_WAKEUP
timeout 15 "$adb" -s "$serial" shell wm dismiss-keyguard
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    timeout 15 "$adb" -s "$serial" shell settings put global "$setting" 0
done
timeout 90 "$adb" -s "$serial" install -r -t "$apk"
timeout 90 "$adb" -s "$serial" install -r -t "$test_apk"
timeout 15 "$adb" -s "$serial" logcat -c
classes='dev.po4yka.chur.android.PrivateNotesPresentationTest,dev.po4yka.chur.android.PrivateNotesAndroidTest,dev.po4yka.chur.android.DiscreetExitPresentationTest'
timeout 600 "$adb" -s "$serial" shell am instrument -w -r -e class "$classes" \
    "$application.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' | tee "$results/instrumentation.txt"
# `adb shell am instrument` can exit zero even when JUnit fails or runs nothing.
# Require all 22 selected cases, and refuse failures, ignored cases and assumptions.
grep -Eq '^OK \(22 tests\)$' "$results/instrumentation.txt"
grep -Eq '^INSTRUMENTATION_CODE: -1$' "$results/instrumentation.txt"
if grep -Eq 'INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[0-9]+|FAILURES!!!|shortMsg=' "$results/instrumentation.txt"; then
    echo "Instrumentation failed, skipped a selected test, or aborted" >&2
    exit 1
fi
timeout 600 "$adb" -s "$serial" shell am instrument -w -r \
    -e class dev.po4yka.chur.android.HevcMain10AndroidTest \
    "$application.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' | tee "$results/instrumentation-hevc-main10.txt"
grep -Eq '^OK \(3 tests\)$' "$results/instrumentation-hevc-main10.txt"
grep -Eq '^INSTRUMENTATION_CODE: -1$' "$results/instrumentation-hevc-main10.txt"
if grep -Eq 'INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[0-9]+|FAILURES!!!|shortMsg=' "$results/instrumentation-hevc-main10.txt"; then
    echo "HEVC Main10 instrumentation failed, skipped a selected test, or aborted" >&2
    exit 1
fi
timeout 600 "$adb" -s "$serial" shell am instrument -w -r \
    -e class dev.po4yka.chur.android.VideoAcceptanceAndroidTest \
    "$application.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' | tee "$results/instrumentation-video-acceptance.txt"
grep -Eq '^OK \(2 tests\)$' "$results/instrumentation-video-acceptance.txt"
grep -Eq '^INSTRUMENTATION_CODE: -1$' "$results/instrumentation-video-acceptance.txt"
if grep -Eq 'INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[0-9]+|FAILURES!!!|shortMsg=' "$results/instrumentation-video-acceptance.txt"; then
    echo "Extended video instrumentation failed, skipped a selected test, or aborted" >&2
    exit 1
fi
timeout 600 "$adb" -s "$serial" shell am instrument -w -r \
    -e class dev.po4yka.chur.android.VideoPerformanceAndroidTest \
    "$application.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r' | tee "$results/instrumentation-video-performance.txt"
grep -Eq '^OK \(1 test\)$' "$results/instrumentation-video-performance.txt"
grep -Eq '^INSTRUMENTATION_CODE: -1$' "$results/instrumentation-video-performance.txt"
if grep -Eq 'INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -[0-9]+|FAILURES!!!|shortMsg=' "$results/instrumentation-video-performance.txt"; then
    echo "Video performance instrumentation failed, skipped its test, or aborted" >&2
    exit 1
fi
collect_artifacts
[[ "$files_collected" == true ]] || { echo "Could not retrieve the UI screenshots" >&2; exit 1; }
[[ -n "$(find "$results/screenshots" -name private-note-managed-image-public-fixture.png -print -quit)" ]] || {
    echo "The actual native attachment image screenshot is missing" >&2; exit 1;
}
echo "All 22 notes/lock, 3 HEVC Main10, 2 extended video and 1 performance tests passed; screenshots and device logs: $results"
