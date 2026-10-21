"""Generate public synthetic JPEG XL fixtures with Pillow and reference libjxl."""
from pathlib import Path
import hashlib
import json
import struct
import subprocess
import tempfile
from PIL import Image, ImageCms

out = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="chur-jxl-fixtures-") as directory:
    temp = Path(directory)
    rgb = Image.new("RGB", (96, 48))
    rgb.putdata([(255, 0, 0) if x < 48 else (0, 128, 255) for y in range(48) for x in range(96)])
    rgb.save(temp / "rgb.png")
    alpha = Image.new("RGBA", (32, 16))
    alpha.putdata([(255, 0, 0, 0) if x < 8 else (0, 255, 0, 128) if x < 24 else (0, 0, 255, 255)
                   for y in range(16) for x in range(32)])
    alpha.save(temp / "alpha.png")
    Image.new("RGB", (2050, 1025), (32, 128, 224)).save(temp / "large.png")
    Image.new("RGB", (16385, 1)).save(temp / "oversized.png")
    frames = [Image.new("RGB", (48, 24), c) for c in [(255, 0, 0), (0, 255, 0), (0, 0, 255)]]
    frames[0].save(temp / "animated.gif", save_all=True, append_images=frames[1:], duration=[80, 120, 160], loop=2, disposal=2)
    exif = Image.Exif(); exif[274] = 6
    rgb.transpose(Image.Transpose.ROTATE_90).save(temp / "oriented.png", exif=exif)
    for source, name, container in [("rgb", "still-codestream", 0), ("rgb", "still-container", 1),
                                    ("alpha", "alpha", 1), ("large", "large", 1), ("oversized", "oversized", 1),
                                    ("oriented", "oriented", 1), ("animated", "animated", 1)]:
        suffix = ".gif" if source == "animated" else ".png"
        subprocess.run(["cjxl", str(temp / (source + suffix)), str(out / (name + ".jxl")),
                        "-d", "0", "--container=" + str(container), "--quiet"], check=True)
    flags = subprocess.check_output(["pkg-config", "--cflags", "--libs", "libjxl"], text=True).split()
    for name in ["encode-animation", "encode-profile-alpha"]:
        subprocess.run(["cc", str(out / (name + ".c")), *flags, "-o", str(temp / name)], check=True)
    subprocess.run([str(temp / "encode-animation"), str(out / "finite-animation.jxl")], check=True)
    subprocess.run([str(temp / "encode-profile-alpha"), str(out / "associated-alpha.jxl")], check=True)
    icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    (temp / "profile.icc").write_bytes(icc)
    subprocess.run([str(temp / "encode-profile-alpha"), str(out / "profiled.jxl"), str(temp / "profile.icc")], check=True)
    count = struct.unpack_from(">I", icc, 128)[0]
    end = 132 + 12 * count
    large = bytearray(icc[:end] + bytes(12) + icc[end:])
    for index in range(count):
        field = 136 + 12 * index
        struct.pack_into(">I", large, field, struct.unpack_from(">I", large, field)[0] + 12)
    large.extend(bytes((-len(large)) % 4))
    offset = len(large)
    payload = b"data" + bytes(8) + bytes((1024 * 1024 + 64) - offset - 12)
    large.extend(payload)
    large[end:end + 12] = b"chur" + struct.pack(">II", offset, len(payload))
    struct.pack_into(">I", large, 128, count + 1)
    struct.pack_into(">I", large, 0, len(large))
    large[84:100] = bytes(16)
    (temp / "limit.icc").write_bytes(large)
    subprocess.run([str(temp / "encode-profile-alpha"), str(out / "icc-limit.jxl"), str(temp / "limit.icc")], check=True)
    (out / "truncated.jxl").write_bytes((out / "still-container.jxl").read_bytes()[:20])
    (out / "malformed.jxl").write_bytes(b"\xff\x0a" + bytes(8))
manifest = {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.glob("*.jxl"))}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
