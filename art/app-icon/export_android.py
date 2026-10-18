"""Export Android adaptive resources from the native icon's aligned PNG sources.

Run with Python 3 and Pillow from any directory. Icon Composer owns the .icon;
this exporter only reads its source images and never changes its document.
"""

from collections import deque
from pathlib import Path

from PIL import Image, ImageChops, ImageCms


ROOT = Path(__file__).resolve().parents[2]
ART = Path(__file__).resolve().parent
SOURCES = ROOT / "apps/iosApp/ChurMirror.icon/Assets"
SRGB = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()


def monochrome(source):
    """Map the authored grayscale regions to a single-color alpha mask."""
    gray = source.convert("L")
    alpha = source.getchannel("A")
    # The deep reflection becomes negative space; mirror and outside spirit
    # remain filled. Its white reflected eyes remain positive shapes.
    fill = gray.point(lambda value: 255 if value >= 90 else 0)
    mask = ImageChops.multiply(alpha, fill)
    # The two white eyes enclosed in the outside spirit become negative space.
    # Connected components distinguish them from the large white mirror face.
    white = ImageChops.multiply(gray.point(lambda v: 255 if v > 225 else 0), alpha)
    points = {(x, y) for y in range(309, 718) for x in range(681, 912)
              if white.getpixel((x, y)) > 128}
    eyes = []
    while points:
        start = points.pop()
        component, queue = {start}, deque([start])
        while queue:
            x, y = queue.popleft()
            for neighbor in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if neighbor in points:
                    points.remove(neighbor)
                    component.add(neighbor)
                    queue.append(neighbor)
        enclosed = all(681 < x < 911 and 309 < y < 717 for x, y in component)
        if enclosed and 100 < len(component) < 3000:
            eyes.append(component)
    if len(eyes) != 2:
        raise ValueError(f"Expected two external eyes, found {len(eyes)}")
    for eye in eyes:
        for x, y in eye:
            mask.putpixel((x, y), 0)
    result = Image.new("RGBA", source.size, "white")
    result.putalpha(mask)
    return result


def adaptive_canvas(source):
    # Keep all meaningful artwork within the adaptive icon's central safe area.
    scaled = source.resize((737, 737), Image.Resampling.LANCZOS)
    result = Image.new("RGBA", (1024, 1024))
    result.alpha_composite(scaled, (143, 143))
    return result


if __name__ == "__main__":
    foreground = Image.new("RGBA", (1024, 1024))
    for name in ("01-mirror", "02-reflection", "03-spirit"):
        foreground.alpha_composite(Image.open(SOURCES / f"{name}.png").convert("RGBA"))
    mono = monochrome(Image.open(SOURCES / "04-mono.png").convert("RGBA"))
    for name, source in (("foreground", foreground), ("monochrome", mono)):
        source.save(ART / f"android-{name}.png", icc_profile=SRGB)
        adaptive = adaptive_canvas(source)
        for density, size in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                              ("xxhdpi", 324), ("xxxhdpi", 432)):
            path = ROOT / f"apps/androidApp/src/main/res/mipmap-{density}/ic_launcher_{name}.png"
            adaptive.resize((size, size), Image.Resampling.LANCZOS).save(path, icc_profile=SRGB)
    print("Exported 10 adaptive PNG resources and 2 full-size source composites.")
