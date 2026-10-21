#!/usr/bin/env python3
"""Decode twelve actual VFR slot centers into a synthetic JPEG reference atlas."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent / "video-acceptance" / "main10-long-gop-vfr.mp4"


def run(arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=120).stdout


def main():
    for executable in ("ffmpeg", "ffprobe"):
        if shutil.which(executable) is None:
            raise SystemExit(f"Missing executable: {executable}")
    duration = float(json.loads(run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(SOURCE)]))["format"]["duration"])
    centers = [duration * (slot + 0.5) / 12 for slot in range(12)]
    with tempfile.TemporaryDirectory(prefix="chur-video-timeline-") as directory:
        directory = Path(directory)
        frames = []
        for slot, center in enumerate(centers):
            frame = directory / f"frame-{slot:02}.png"
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                 "-ss", f"{center:.6f}", "-i", str(SOURCE), "-frames:v", "1",
                 "-vf", "scale=160:90:force_original_aspect_ratio=decrease,pad=160:90:(ow-iw)/2:(oh-ih)/2:black,format=rgb24",
                 "-threads", "1", str(frame)])
            frames.append(frame)
        output = directory / "reference-storyboard.jpg"
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
             "-framerate", "1", "-i", str(directory / "frame-%02d.png"),
             "-vf", "tile=4x3", "-frames:v", "1", "-q:v", "3", "-pix_fmt", "yuvj420p", "-threads", "1", str(output)])
        metadata = json.loads(run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height,pix_fmt", "-of", "json", str(output)]))
        image = metadata["streams"][0]
        assert (image["codec_name"], image["width"], image["height"]) == ("mjpeg", 640, 270), metadata
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", str(output), "-f", "null", "-"])
        data = output.read_bytes()
        assert 0 < len(data) <= 2 * 1024 * 1024
        frame_hashes = [hashlib.sha256(frame.read_bytes()).hexdigest() for frame in frames]
        assert len(set(frame_hashes)) > 1, "VFR slot centers must not all reuse one frame"
        manifest = {
            "schema_version": 1,
            "provenance": "Twelve decoded center frames from the repository's procedural 64-second Main10 SDR VFR fixture; no user or third-party media.",
            "generation": "python3 tests/media/video-timeline/generate.py",
            "ffmpeg_version": run(["ffmpeg", "-version"]).splitlines()[0],
            "source": {"filename": "../video-acceptance/main10-long-gop-vfr.mp4", "sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(), "duration_seconds": duration},
            "slot_centers_seconds": centers,
            "decoded_frame_png_sha256": frame_hashes,
            "files": [{"filename": output.name, "length": len(data), "sha256": hashlib.sha256(data).hexdigest(), "ffprobe": metadata}],
        }
        shutil.copyfile(output, ROOT / output.name)
        (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"Generated and decoded {output.name}: {len(data)} bytes, sha256={hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
