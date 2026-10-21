#!/usr/bin/env python3
"""Build genuine DTS audio and malformed-container inputs from an existing MP4."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent / "hevc-main10" / "sdr10-hvc1.mp4"


def run(arguments, expected=0):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=60)
    if (expected == 0 and result.returncode != 0) or (expected != 0 and result.returncode == 0):
        raise RuntimeError(f"Unexpected exit {result.returncode}: {arguments!r}\n{result.stderr[-2000:]}")
    return result


def main():
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            raise SystemExit(f"Missing executable: {tool}")
    if " dca " not in run(["ffmpeg", "-hide_banner", "-encoders"]).stdout:
        raise SystemExit("FFmpeg must provide the dca DTS core encoder")
    source = SOURCE.read_bytes()
    manifest = {
        "schema_version": 1,
        "ffmpeg_version": run(["ffmpeg", "-version"]).stdout.splitlines()[0],
        "source": {"path": "tests/media/hevc-main10/sdr10-hvc1.mp4", "sha256": hashlib.sha256(source).hexdigest()},
        "files": [],
    }
    with tempfile.TemporaryDirectory(prefix="chur-video-diagnostics-") as temporary:
        directory = Path(temporary)
        dts = directory / "hevc-dts.mp4"
        run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(SOURCE),
             "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.25",
             "-map", "0:v:0", "-map", "1:a:0", "-map_metadata", "-1", "-c:v", "copy", "-tag:v", "hvc1",
             "-c:a", "dca", "-strict", "-2", "-b:a", "384k", "-threads", "1", "-fflags", "+bitexact",
             "-flags:a", "+bitexact", "-shortest", "-movflags", "+faststart", str(dts)])
        probe = json.loads(run(["ffprobe", "-v", "error", "-show_entries",
                              "stream=codec_type,codec_name,profile,codec_tag_string,pix_fmt,width,height,sample_rate,channels:format=duration",
                              "-of", "json", str(dts)]).stdout)
        audio = next(stream for stream in probe["streams"] if stream["codec_type"] == "audio")
        assert audio["codec_name"] == "dts" and audio["codec_tag_string"] in ("mp4a", "dtsc"), probe
        video = next(stream for stream in probe["streams"] if stream["codec_type"] == "video")
        assert video["codec_name"] == "hevc" and video["pix_fmt"] == "yuv420p10le", probe
        run(["ffmpeg", "-v", "error", "-nostdin", "-xerror", "-i", str(dts), "-f", "null", "-"])
        data = dts.read_bytes()
        manifest["files"].append({"filename": dts.name, "length": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "role": "device-dependent-unsupported-audio", "ffprobe": probe,
            "provenance": "Unchanged synthetic Main10 video plus real FFmpeg dca-encoded DTS core sine audio. Not guaranteed unsupported on every device."})
        malformed = directory / "truncated-container.mp4"
        # The source is a real fast-start MP4. Keep ftyp but omit its required moov box.
        moov = source.find(b"moov")
        assert 4 <= moov <= 128 and moov + 100 < len(source)
        malformed.write_bytes(source[:moov - 4])
        result = run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(malformed)], expected=1)
        data = malformed.read_bytes()
        manifest["files"].append({"filename": malformed.name, "length": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "role": "invalid-container", "ffprobe_exit": result.returncode,
            "provenance": "Exact ftyp prefix of the source MP4, truncated before moov; no fabricated codec wrapper or encrypted integrity failure."})
        for item in manifest["files"]:
            shutil.copyfile(directory / item["filename"], ROOT / item["filename"])
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Validated real DTS audio and rejected truncated MP4; {sum(item['length'] for item in manifest['files'])} bytes")


if __name__ == "__main__":
    main()
