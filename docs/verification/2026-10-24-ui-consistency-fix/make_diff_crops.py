"""Build side-by-side before|after crops for the three P0 fixes (evidence images)."""
import os

from PIL import Image

BASE = os.path.dirname(os.path.abspath(__file__))
THEME_FIX = os.path.abspath(os.path.join(BASE, "..", "2026-10-24-ui-theme-fix", "after"))
AFTER = os.path.join(BASE, "after")
OUT = os.path.join(BASE, "diffs")
os.makedirs(OUT, exist_ok=True)

# (name, before_file, after_file, crop box in device px, output scale)
JOBS = [
    ("tags-trash-empty", os.path.join(THEME_FIX, "trash-empty.png"), os.path.join(AFTER, "trash-empty.png"),
     (40, 250, 1040, 480), 1.0),
    ("tags-timeline", os.path.join(THEME_FIX, "timeline.png"), os.path.join(AFTER, "timeline.png"),
     (40, 280, 1040, 700), 1.0),
    ("buttons-local-backup", os.path.join(THEME_FIX, "local-backup.png"), os.path.join(AFTER, "local-backup.png"),
     (40, 830, 1040, 1240), 1.0),
    ("buttons-export-disabled", os.path.join(THEME_FIX, "export-screen-disabled.png"), os.path.join(AFTER, "export-screen-disabled.png"),
     (40, 2050, 1040, 2350), 1.0),
    ("tags-security-settings", os.path.join(THEME_FIX, "security-settings.png"), os.path.join(AFTER, "security-settings.png"),
     (40, 1150, 700, 1420), 1.0),
]


def label(img, text):
    from PIL import ImageDraw
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, img.width, 46], fill=(34, 60, 50))
    draw.text((12, 12), text, fill=(255, 255, 255))
    return img


for name, bpath, apath, box, scale in JOBS:
    if not (os.path.exists(bpath) and os.path.exists(apath)):
        print("skip", name)
        continue
    b = Image.open(bpath).convert("RGB").crop(box)
    a = Image.open(apath).convert("RGB").crop(box)
    if scale != 1.0:
        size = (int(b.width * scale), int(b.height * scale))
        b = b.resize(size, Image.LANCZOS)
        a = a.resize(size, Image.LANCZOS)
    gap = 16
    canvas = Image.new("RGB", (b.width + a.width + gap, b.height + 46), (247, 248, 243))
    canvas.paste(label(b, "BEFORE (2026-10-24 theme-fix)"), (0, 46))
    canvas.paste(label(a, "AFTER (2026-10-24 consistency-fix)"), (b.width + gap, 46))
    out = os.path.join(OUT, name + "-before-after.png")
    canvas.save(out)
    print("wrote", out, canvas.size)
