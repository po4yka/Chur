#!/usr/bin/env python3
"""Build a synthetic AVC reference for viewing-copy tests. Requires FFmpeg/libx264."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent / "hevc-main10" / "sdr10-hvc1.mp4"


def run(arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=90).stdout


def main():
    for executable in ("ffmpeg", "ffprobe"):
        if shutil.which(executable) is None:
            raise SystemExit(f"Missing executable: {executable}")
    encoders = run(["ffmpeg", "-hide_banner", "-encoders"])
    if "libx264" not in encoders or " aac " not in encoders:
        raise SystemExit("FFmpeg must provide libx264 and AAC encoders")
    if not SOURCE.is_file():
        raise SystemExit("Generate the repository HEVC Main10 fixtures first")
    with tempfile.TemporaryDirectory(prefix="chur-avc-reference-") as directory:
        output = Path(directory) / "reference-sdr.mp4"
        run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-i", str(SOURCE), "-map", "0:v", "-map", "0:a", "-map_metadata", "-1",
            "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p",
            "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
            "-color_range", "tv", "-crf", "24", "-threads", "1",
            "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart", str(output),
        ])
        metadata = json.loads(run([
            "ffprobe", "-v", "error", "-show_entries",
            "stream=index,codec_type,codec_name,profile,codec_tag_string,width,height,pix_fmt,"
            "color_range,color_space,color_transfer,color_primaries,avg_frame_rate,sample_rate,channels:"
            "format=format_name,duration", "-of", "json", str(output),
        ]))
        video = next(stream for stream in metadata["streams"] if stream["codec_type"] == "video")
        assert (video["codec_name"], video["profile"], video["pix_fmt"], video["codec_tag_string"]) == ("h264", "Main", "yuv420p", "avc1"), metadata
        assert (video["width"], video["height"], video["avg_frame_rate"]) == (192, 96, "24/1"), metadata
        assert (video["color_primaries"], video["color_transfer"], video["color_space"], video["color_range"]) == ("bt709", "bt709", "bt709", "tv"), metadata
        assert any(stream["codec_name"] == "aac" for stream in metadata["streams"]), metadata
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", str(output), "-f", "null", "-"])
        data = output.read_bytes()
        manifest = {
            "schema_version": 1,
            "provenance": "Software conversion of the repository's procedural Main10 SDR pattern and 440 Hz tone; no user or third-party media.",
            "generation": "python3 tests/media/video-compatibility/generate.py",
            "ffmpeg_version": run(["ffmpeg", "-version"]).splitlines()[0],
            "source": {"filename": "../hevc-main10/sdr10-hvc1.mp4", "sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest()},
            "files": [{"filename": output.name, "length": len(data), "sha256": hashlib.sha256(data).hexdigest(), "ffprobe": metadata}],
        }
        shutil.copyfile(output, ROOT / output.name)
        (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"Generated and fully decoded {output.name}: {len(data)} bytes, sha256={hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
