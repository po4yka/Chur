#!/usr/bin/env python3
"""Run opt-in Android/iOS album phases against a real loopback Chur server."""

import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-serial", required=True)
    parser.add_argument("--ios-simulator", required=True)
    parser.add_argument("--server-binary", type=Path, required=True)
    parser.add_argument("--port", type=int, default=17880)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    repo = Path(__file__).resolve().parent.parent
    executable = repo / "shared/app/build/bin/iosSimulatorArm64/debugTest/test.kexe"
    apk = repo / "apps/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    tests = repo / "apps/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
    for path in (executable, apk, tests, args.server_binary):
        if not path.is_file():
            parser.error(f"build the acceptance artifacts first: {path}")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    evidence = Path(tempfile.mkdtemp(prefix="chur-album-live-"))
    evidence.chmod(0o700)
    run_id = evidence.name
    ios_root = evidence / "ios"
    ios_root.mkdir()
    android_root = f"cache/album-live-{run_id}"
    adb = ["adb", "-s", args.android_serial]
    endpoint = f"http://127.0.0.1:{args.port}"
    mapping = f"tcp:{args.port}"
    server = None
    server_log = None
    reverse_installed = False
    step = 0
    print(f"Evidence: {evidence}", flush=True)

    def command(argv, *, env=None, data=None, timeout=210):
        return subprocess.run(argv, input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              env=env, check=True, timeout=timeout).stdout

    def start_server():
        nonlocal server, server_log
        environment = os.environ.copy()
        environment.update(CHUR_SYNC_BIND=f"127.0.0.1:{args.port}",
                           CHUR_SYNC_DATA=str(evidence / "relay"),
                           CHUR_SYNC_BOOTSTRAP_TOKEN="42" * 32)
        server_log = (evidence / "relay.log").open("ab")
        server = subprocess.Popen([str(args.server_binary.resolve())], env=environment,
                                  stdout=server_log, stderr=subprocess.STDOUT)
        for _ in range(50):
            if server.poll() is not None:
                raise RuntimeError(f"relay exited: see {evidence / 'relay.log'}")
            try:
                with urllib.request.urlopen(endpoint + "/healthz", timeout=1) as response:
                    if response.status == 204:
                        return
            except OSError:
                time.sleep(0.1)
        raise RuntimeError("relay did not become healthy")

    def stop_server():
        nonlocal server, server_log
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=10)
            server = None
        if server_log is not None:
            server_log.close()
            server_log = None

    def phase(platform, name):
        nonlocal step
        step += 1
        log = evidence / f"{step:02}-{platform}-{name}.log"
        if platform == "android":
            argv = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                          "dev.po4yka.chur.android.AlbumLiveSyncDeviceTest", "-e", "runId", run_id,
                          "-e", "phase", name, "-e", "endpoint", endpoint,
                          "dev.po4yka.chur.test/androidx.test.runner.AndroidJUnitRunner"]
            environment = None
        else:
            argv = ["xcrun", "simctl", "spawn", args.ios_simulator, str(executable),
                    "--ktest_filter=dev.po4yka.chur.app.AlbumLiveSyncIosTest.live_album_sync_phase",
                    "--ktest_logger=GTEST"]
            environment = os.environ.copy()
            environment.update(SIMCTL_CHILD_CHUR_LIVE_PHASE=name,
                               SIMCTL_CHILD_CHUR_LIVE_ROOT=str(ios_root),
                               SIMCTL_CHILD_CHUR_LIVE_ENDPOINT=endpoint)
        result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                env=environment, timeout=210)
        output = result.stdout
        log.write_bytes(output)
        marker = (f"album_live_sync={name} PASS" if platform == "android"
                  else f"ALBUM_LIVE_SYNC {name} PASS").encode()
        if result.returncode != 0 or marker not in output or (platform == "android" and b"OK (1 test)" not in output):
            raise RuntimeError(f"{platform} {name} failed: {log}")
        print(f"{step:02} {platform}: {name} PASS", flush=True)

    def from_android(name):
        return command(adb + ["exec-out", "run-as", "dev.po4yka.chur", "cat", f"{android_root}/{name}"])

    def to_android(name, data):
        # The generated run ID and fixed file names contain no shell metacharacters.
        command(adb + ["shell", "run-as", "dev.po4yka.chur", "sh", "-c",
                       f"'cat > {android_root}/{name}'"], data=data)

    try:
        current = command(adb + ["reverse", "--list"]).decode()
        if mapping in current.split():
            raise RuntimeError(f"an existing adb reverse mapping owns {mapping}")
        command(adb + ["install", "-r", str(apk)])
        command(adb + ["install", "-r", str(tests)])
        command(adb + ["reverse", mapping, mapping])
        reverse_installed = True
        start_server()
        phase("android", "init")
        for name in ("initial.backup", "source-device"):
            (ios_root / name).write_bytes(from_android(name))
        phase("ios", "restore")
        to_android("pairing.request", (ios_root / "pairing.request").read_bytes())
        phase("android", "approve")
        (ios_root / "pairing.approval").write_bytes(from_android("pairing.approval"))
        phase("ios", "pair")
        for platform, name in (
            ("android", "android-seed"), ("ios", "sync"), ("android", "sync"),
            ("ios", "ios-edit"), ("android", "sync"), ("ios", "sync"),
            ("android", "android-edit"), ("ios", "ios-remove"), ("android", "android-empty"),
        ):
            phase(platform, name)
        stop_server()
        for platform, name in (("android", "android-offline"), ("ios", "ios-offline"),
                               ("android", "offline-restart"), ("ios", "offline-restart")):
            phase(platform, name)
        start_server()
        for platform, name in (("android", "sync"), ("ios", "sync"), ("android", "sync"),
                               ("ios", "final"), ("android", "final")):
            phase(platform, name)
        android_snapshot = from_android("final.snapshot")
        (evidence / "android-final.snapshot").write_bytes(android_snapshot)
        ios_snapshot = (ios_root / "final.snapshot").read_bytes()
        if android_snapshot != ios_snapshot:
            raise RuntimeError("final album IDs, names, counts, or member IDs differ across platforms")
        (evidence / "result.txt").write_text(f"PASS: {step} mobile process phases; final snapshots match.\n")
        print(f"PASS: {step} mobile process phases; final snapshots match. Evidence: {evidence}", flush=True)
    finally:
        stop_server()
        if reverse_installed:
            command(adb + ["reverse", "--remove", mapping])


if __name__ == "__main__":
    main()
