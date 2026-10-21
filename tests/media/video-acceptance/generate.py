#!/usr/bin/env python3
"""Generate synthetic stress clips and acquire one pinned AndroidX camera clip."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request


ROOT = Path(__file__).resolve().parent
REVISION = "dac651634e15a8a97076037fd81285de18d86d62"
UPSTREAM = f"https://raw.githubusercontent.com/androidx/media/{REVISION}/"
CAMERA_PATH = "libraries/test_data/src/test/assets/media/mp4/dolbyVision-hdr.MOV"
SPECS = [
    {"filename": "main10-4k60.mp4", "role": "4k60", "width": 3840, "height": 2160,
     "rate": 60, "duration": 2.5, "seek_seconds": [0.5, 1.5]},
    {"filename": "main10-long-gop-vfr.mp4", "role": "long-gop-vfr", "width": 320, "height": 180,
     "rate": 50, "duration": 64, "seek_seconds": [0.5, 31, 55, 62]},
    {"filename": "main10-portrait-rotation.mp4", "role": "portrait-rotation", "width": 1920, "height": 1080,
     "rate": 24, "duration": 2.5, "seek_seconds": [0.5, 1.5]},
]


def run(arguments):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed: {arguments!r}\n{result.stderr[-4000:]}")
    return result.stdout


def acquire(name, path, limit):
    existing = ROOT / name
    if existing.exists():
        return existing.read_bytes()
    with urllib.request.urlopen(UPSTREAM + path, timeout=30) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError(f"Upstream file exceeds the {limit}-byte bound: {path}")
    return data


def inspect(path):
    probe = json.loads(run([
        "ffprobe", "-v", "error", "-show_entries",
        "stream=index,codec_type,codec_name,profile,codec_tag_string,pix_fmt,width,height,"
        "color_range,color_space,color_transfer,color_primaries,r_frame_rate,avg_frame_rate,"
        "time_base,nb_frames,duration,sample_rate,channels:stream_side_data:format=format_name,duration:"
        "format_tags=com.apple.quicktime.make,com.apple.quicktime.model,com.apple.quicktime.software",
        "-of", "json", str(path),
    ]))
    frames = json.loads(run([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries",
        "frame=best_effort_timestamp_time,key_frame:frame_side_data=side_data_type", "-of", "json", str(path),
    ]))["frames"]
    timestamps = [float(frame["best_effort_timestamp_time"]) for frame in frames]
    deltas = sorted({round(b - a, 6) for a, b in zip(timestamps, timestamps[1:])})
    keys = [float(frame["best_effort_timestamp_time"]) for frame in frames if frame.get("key_frame") == 1]
    return probe, {
        "decoded_video_frames": len(frames), "presentation_interval_seconds": deltas,
        "keyframe_seconds": keys,
        "frame_side_data_types": sorted({item["side_data_type"] for frame in frames for item in frame.get("side_data_list", [])}),
    }


def encode(directory, spec):
    print(f"Encoding {spec['filename']}", flush=True)
    output = directory / spec["filename"]
    source = f"testsrc2=size={spec['width']}x{spec['height']}:rate={spec['rate']}:duration={spec['duration']},format=yuv420p10le"
    if spec["role"] == "long-gop-vfr":
        source += ",select='eq(mod(n,7),0)+eq(mod(n,7),1)+eq(mod(n,7),3)'"
    arguments = [
        "ffmpeg", "-v", "error", "-nostdin", "-y", "-filter_threads", "1",
        "-f", "lavfi", "-i", source, "-f", "lavfi", "-i",
        f"sine=frequency=440:sample_rate=48000:duration={spec['duration']}",
        "-map", "0:v:0", "-map", "1:a:0", "-map_metadata", "-1",
        "-c:v", "libx265", "-preset", "ultrafast", "-crf", "30", "-profile:v", "main10",
        "-pix_fmt", "yuv420p10le", "-x265-params",
        "pools=none:frame-threads=1:wpp=0:bframes=0:keyint=3200:min-keyint=1:scenecut=0:"
        "repeat-headers=0:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited:log-level=error",
        "-force_key_frames", "expr:gte(t,n_forced*16)", "-tag:v", "hvc1",
        "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv",
        "-fps_mode", "vfr", "-enc_time_base", "filter", "-video_track_timescale", "50000",
        "-c:a", "aac", "-b:a", "48k", "-threads", "1", "-fflags", "+bitexact",
        "-flags:v", "+bitexact", "-flags:a", "+bitexact", "-movflags", "+faststart",
        "-t", str(spec["duration"]), str(output),
    ]
    run(arguments)
    if spec["role"] == "portrait-rotation":
        rotated = directory / "rotated.mp4"
        run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-display_rotation:v:0", "90", "-i", str(output),
             "-map", "0", "-c", "copy", "-fflags", "+bitexact", "-movflags", "+faststart", str(rotated)])
        rotated.replace(output)
    return output


def main():
    missing = [name for name in ("ffmpeg", "ffprobe") if shutil.which(name) is None]
    if missing:
        raise SystemExit(f"Missing tools: {missing}")
    encoders = run(["ffmpeg", "-hide_banner", "-encoders"])
    if "libx265" not in encoders or " aac " not in encoders:
        raise SystemExit("FFmpeg must provide libx265 and AAC encoders")
    manifest = {"schema_version": 1, "ffmpeg_version": run(["ffmpeg", "-version"]).splitlines()[0],
                "generation": "python3 tests/media/video-acceptance/generate.py", "files": []}
    with tempfile.TemporaryDirectory(prefix="chur-video-acceptance-") as temporary:
        directory = Path(temporary)
        for spec in SPECS:
            path = encode(directory, spec)
            probe, timing = inspect(path)
            video = next(stream for stream in probe["streams"] if stream["codec_type"] == "video")
            assert video["codec_name"] == "hevc" and video["profile"] == "Main 10"
            assert video["pix_fmt"] == "yuv420p10le" and video["codec_tag_string"] == "hvc1"
            assert (video["width"], video["height"]) == (spec["width"], spec["height"])
            if spec["role"] == "4k60":
                assert timing["decoded_video_frames"] == 150 and video["r_frame_rate"] == "60/1", timing
            elif spec["role"] == "long-gop-vfr":
                assert {0.02, 0.04, 0.08} <= set(timing["presentation_interval_seconds"]), timing
                assert float(probe["format"]["duration"]) >= 64, probe
                assert max(b - a for a, b in zip(timing["keyframe_seconds"], timing["keyframe_seconds"][1:])) >= 15, timing
            else:
                assert any(abs(item.get("rotation", 0)) == 90 for item in video.get("side_data_list", [])), probe
            run(["ffmpeg", "-v", "error", "-nostdin", "-xerror", "-i", str(path), "-f", "null", "-"])
            data = path.read_bytes()
            manifest["files"].append({
                "filename": path.name, "length": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "role": spec["role"], "genuine_camera": False, "provenance": "Procedural testsrc2 moving texture and 440 Hz sine.",
                "seek_seconds": spec["seek_seconds"], "ffprobe": probe, "timing": timing,
            })
        camera = acquire("camera-dolby-vision.mov", CAMERA_PATH, 100000)
        camera_hash = hashlib.sha256(camera).hexdigest()
        assert len(camera) == 64565, "Pinned upstream byte length changed"
        assert camera_hash == "c96e24bdfa7f3e0ef9b73b0b891805100119b9fa129e4cf10375af90846de534", "Pinned upstream bytes changed"
        path = directory / "camera-dolby-vision.mov"
        path.write_bytes(camera)
        probe, timing = inspect(path)
        video = next(stream for stream in probe["streams"] if stream["codec_type"] == "video")
        configuration = next(item for item in video["side_data_list"] if item["side_data_type"] == "DOVI configuration record")
        assert configuration["dv_profile"] == 8 and configuration["rpu_present_flag"] == 1
        assert "Dolby Vision RPU Data" in timing["frame_side_data_types"], timing
        assert video["color_transfer"] == "arib-std-b67" and video["pix_fmt"] == "yuv420p10le"
        run(["ffmpeg", "-v", "error", "-nostdin", "-xerror", "-i", str(path), "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"])
        manifest["files"].append({
            "filename": path.name, "length": len(camera), "sha256": camera_hash, "role": "camera-dolby-vision",
            "genuine_camera": None, "camera_origin": "Inferred from Apple iPhone 14 Pro capture metadata; not independently witnessed.",
            "provenance": {"repository": "https://github.com/androidx/media", "revision": REVISION,
                           "path": CAMERA_PATH, "url": UPSTREAM + CAMERA_PATH, "license": "Apache-2.0", "modified": False},
            "seek_seconds": [], "ffprobe": probe, "timing": timing,
        })
        total = sum(item["length"] for item in manifest["files"])
        assert total <= 20000000, total
        license_data = acquire("LICENSE.androidx-media", "LICENSE", 20000)
        for item in manifest["files"]:
            shutil.copyfile(directory / item["filename"], ROOT / item["filename"])
    (ROOT / "LICENSE.androidx-media").write_bytes(license_data)
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Validated {len(manifest['files'])} video fixtures; {total} bytes")


if __name__ == "__main__":
    main()
