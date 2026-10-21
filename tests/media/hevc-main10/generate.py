#!/usr/bin/env python3
"""Generate bounded synthetic Main10 fixtures. Requires FFmpeg with libx265."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent
COLOR = {
    "sdr10": ("bt709", "bt709", "bt709"),
    "hdr10": ("bt2020", "smpte2084", "bt2020nc"),
    "hlg": ("bt2020", "arib-std-b67", "bt2020nc"),
}
VIDEO_SOURCE = (
    "testsrc2=size=192x96:rate=24:duration=1.25,format=yuv420p10le,"
    "geq=lum='if(lt(Y,32),clip(64+floor(X/32)*176,64,940),p(X,Y))':"
    "cb='if(lt(Y,16),512,p(X,Y))':cr='if(lt(Y,16),512,p(X,Y))'"
)


def run(arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=90).stdout


def probe(path):
    streams = json.loads(run([
        "ffprobe", "-v", "error", "-show_entries",
        "stream=index,codec_type,codec_name,profile,codec_tag_string,pix_fmt,width,height,"
        "color_range,color_space,color_transfer,color_primaries,r_frame_rate,nb_frames,"
        "sample_rate,channels:format=format_name,duration", "-of", "json", str(path),
    ]))
    frames = json.loads(run([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1",
        "-show_frames", "-show_entries", "frame=side_data_list", "-of", "json", str(path),
    ]))
    streams["first_video_frame_side_data"] = frames.get("frames", [{}])[0].get("side_data_list", [])
    return streams


def main():
    for executable in ("ffmpeg", "ffprobe"):
        if shutil.which(executable) is None:
            raise SystemExit(f"Missing executable: {executable}")
    encoders = run(["ffmpeg", "-hide_banner", "-encoders"])
    if "libx265" not in encoders or " aac " not in encoders:
        raise SystemExit("FFmpeg must provide libx265 and AAC encoders")
    manifest = {
        "schema_version": 1,
        "provenance": "Procedural FFmpeg testsrc2 pixels and 440 Hz sine; no user or third-party media.",
        "ffmpeg_version": run(["ffmpeg", "-version"]).splitlines()[0],
        "generation": "python3 tests/media/hevc-main10/generate.py",
        "pattern": {
            "top_grayscale_patches": {"y_range": [0, 31], "width": 32, "luma_10bit": [64, 240, 416, 592, 768, 940], "chroma_10bit": 512},
            "bottom": "Moving testsrc2 color pattern at y=32..95",
        },
        "files": [],
    }
    with tempfile.TemporaryDirectory(prefix="chur-main10-") as directory:
        for mode, (primaries, transfer, matrix) in COLOR.items():
            output = Path(directory) / f"{mode}-hvc1.mp4"
            parameters = (
                "pools=none:frame-threads=1:wpp=0:pmode=0:pme=0:"
                "keyint=12:min-keyint=12:scenecut=0:repeat-headers=0:log-level=error"
            )
            parameters += f":colorprim={primaries}:transfer={transfer}:colormatrix={matrix}:range=limited"
            if mode == "hdr10":
                parameters += (
                    ":hdr10=1:master-display=G(13250,34500)B(7500,3000)R(34000,16000)"
                    "WP(15635,16450)L(10000000,50):max-cll=1000,400"
                )
            run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", VIDEO_SOURCE,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.25",
                "-map", "0:v:0", "-map", "1:a:0", "-map_metadata", "-1",
                "-c:v", "libx265", "-preset", "fast", "-crf", "26", "-profile:v", "main10",
                "-pix_fmt", "yuv420p10le", "-x265-params", parameters,
                "-color_primaries", primaries, "-color_trc", transfer, "-colorspace", matrix,
                "-color_range", "tv", "-tag:v", "hvc1", "-c:a", "aac", "-b:a", "48k",
                "-threads", "1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
                "-flags:a", "+bitexact", "-movflags", "+faststart", "-t", "1.25", str(output),
            ])
            for tag in ("hvc1", "hev1"):
                path = Path(directory) / f"{mode}-{tag}.mp4"
                if tag == "hev1":
                    run([
                        "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                        "-i", str(output), "-map", "0", "-map_metadata", "-1", "-c", "copy",
                        "-tag:v", tag, "-fflags", "+bitexact", "-movflags", "+faststart", str(path),
                    ])
                metadata = probe(path)
                video = next(stream for stream in metadata["streams"] if stream["codec_type"] == "video")
                assert video["codec_name"] == "hevc" and video["profile"] == "Main 10", metadata
                assert video["pix_fmt"] == "yuv420p10le" and video["codec_tag_string"] == tag, metadata
                assert (video["color_primaries"], video["color_transfer"], video["color_space"]) == COLOR[mode], metadata
                assert (video["width"], video["height"], video["nb_frames"]) == (192, 96, "30"), metadata
                assert any(stream["codec_name"] == "aac" for stream in metadata["streams"]), metadata
                if mode == "hdr10":
                    side_data = {item["side_data_type"] for item in metadata["first_video_frame_side_data"]}
                    assert {"Mastering display metadata", "Content light level metadata"} <= side_data, metadata
                run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", str(path), "-f", "null", "-"])
                data = path.read_bytes()
                manifest["files"].append({
                    "filename": path.name, "length": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                    "ffprobe": metadata,
                })
        for item in manifest["files"]:
            shutil.copyfile(Path(directory) / item["filename"], ROOT / item["filename"])
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Generated and decoded {len(manifest['files'])} fixtures; {sum(item['length'] for item in manifest['files'])} bytes")


if __name__ == "__main__":
    main()
