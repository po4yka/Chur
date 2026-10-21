#!/usr/bin/env python3
"""Run the built HDR instrumentation test and inspect only its SurfaceView layer."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time


def owned_output(dump):
    """Keep one app video output layer; omit other apps, addresses and buffers."""
    rows = dump.splitlines()
    for index, row in enumerate(rows):
        if "- Output Layer " not in row or "SurfaceView[dev.po4yka.chur/" not in row:
            continue
        for detail in rows[index + 1:]:
            if "- Output Layer " in detail:
                break
            match = re.search(r"\bdataspace=([A-Z0-9_]+) \((\d+)\)", detail)
            if match:
                return {"app_surface": "dev.po4yka.chur SurfaceView",
                        "dataspace": match[1], "dataspace_value": int(match[2])}
    raise RuntimeError("The app's active SurfaceView output dataspace was not found")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial of the HDR-capable physical test device")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--results", type=Path, default=Path("build/reports/video-acceptance/android-hdr"))
    args = parser.parse_args()
    if shutil.which(args.adb) is None:
        parser.error("ADB is required")
    args.results.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]
    device = {}
    for key, prop in (("model", "ro.product.model"), ("sdk", "ro.build.version.sdk")):
        device[key] = subprocess.check_output(adb + ["shell", "getprop", prop], text=True, timeout=10).strip()
    expected = {f"{prefix}-{tag}": space for prefix, space in
                (("hdr10", "BT2020_ITU_PQ"), ("hlg", "BT2020_ITU_HLG")) for tag in ("hvc1", "hev1")}
    process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r",
        "-e", "chur.video.hdrHoldMs", "5000", "-e", "class",
        "dev.po4yka.chur.android.VideoHdrOutputAndroidTest",
        "dev.po4yka.chur.test/androidx.test.runner.AndroidJUnitRunner"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    deadline = threading.Timer(360, process.kill)
    deadline.start()
    outputs, native_frames, lines = {}, {}, []
    try:
        with (args.results / "instrumentation.txt").open("w") as log:
            for line in process.stdout:
                line = line.replace("\r", "")
                log.write(line)
                log.flush()
                lines.append(line)
                match = re.search(r"HDR_OUTPUT_READY name=(\S+) .*?decoderOutputTransfer=(\d+) "
                                  r"decoderOutputStandard=(\d+) decoderOutputRange=(\d+)", line)
                if not match:
                    continue
                name = match[1]
                native_frames[name] = {"transfer": int(match[2]), "standard": int(match[3]), "range": int(match[4])}
                if name in expected:
                    # A decoder callback can precede SurfaceFlinger's first
                    # composition. Wait inside the test's five-second hold.
                    until = time.monotonic() + 4
                    last_error = "No app video output observed"
                    while True:
                        dump = subprocess.check_output(adb + ["shell", "dumpsys", "SurfaceFlinger"], text=True, timeout=10)
                        try:
                            output = owned_output(dump)
                            if output["dataspace"] == expected[name]:
                                break
                            last_error = f"Expected {expected[name]}, found {output['dataspace']}"
                        except RuntimeError as error:
                            last_error = str(error)
                        if time.monotonic() >= until:
                            raise RuntimeError(f"{name}: {last_error}")
                        time.sleep(0.1)
                    outputs[name] = output
                    print(f"{name}: decoder transfer {match[2]}, SurfaceFlinger {output['dataspace']}", flush=True)
        status = process.wait(timeout=15)
    finally:
        deadline.cancel()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
    text = "".join(lines)
    if status != 0 or not re.search(r"^OK \(1 test\)$", text, re.M) or not re.search(r"^INSTRUMENTATION_CODE: -1$", text, re.M):
        raise RuntimeError("HDR instrumentation did not complete its one required test")
    if re.search(r"INSTRUMENTATION_(FAILED|ABORTED)|INSTRUMENTATION_STATUS_CODE: -\d+|FAILURES!!!|shortMsg=", text):
        raise RuntimeError("HDR instrumentation failed, aborted or skipped a test")
    if set(outputs) != set(expected) or len(native_frames) != 6:
        raise RuntimeError("All six native formats and four HDR SurfaceFlinger outputs are required")
    report = {"schema_version": 1, "device": device, "result": "passed", "test_count": 1,
              "native_decoder_output": native_frames, "surfaceflinger_output": outputs,
              "physical_luminance_measured": False,
              "limitations": "Layer dataspace and decoder output do not establish calibrated luminance, gamut or Dolby Vision RPU rendering"}
    (args.results / "hdr-output.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"HDR native output and app layer dataspaces passed; reports: {args.results}")


if __name__ == "__main__":
    main()
