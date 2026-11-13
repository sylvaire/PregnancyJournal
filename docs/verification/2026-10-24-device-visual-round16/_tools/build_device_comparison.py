import os, glob
from PIL import Image, ImageDraw, ImageFont

BASE = r"docs\verification\2026-10-24-device-visual-round16"
DESIGN = r"docs\verification\2026-10-24-figma-ui-alignment\figma\screens"
SHOTS = os.path.join(BASE, "shots")
OUT = os.path.join(BASE, "comparisons")
os.makedirs(OUT, exist_ok=True)

pairs = [
    ("03-Today", "01-today"),
    ("04-Visits", "02-visits"),
    ("05-VisitDetail", "03-visit-detail"),
    ("12-Timeline", "05-timeline"),
    ("09-Medication", "07-medications"),
    ("11-Health", "10-health"),
    ("13-Settings", "11-profile"),
    ("19-CloudConnect", "12-cloud"),
    ("14-Backup", "13-recovery"),
    ("07-Documents", "09-all-documents"),
]

def fit(img, h):
    w = int(img.width * h / img.height)
    return img.resize((w, h), Image.LANCZOS)

font = ImageFont.load_default()
H = 1600
for d, a in pairs:
    dp = os.path.join(DESIGN, d + ".png")
    ap = os.path.join(SHOTS, a + ".png")
    if not (os.path.exists(dp) and os.path.exists(ap)):
        print("skip", d, a)
        continue
    dimg = fit(Image.open(dp).convert("RGB"), H)
    aimg = fit(Image.open(ap).convert("RGB"), H)
    pad = 24
    canvas = Image.new("RGB", (dimg.width + aimg.width + pad * 3, H + 70), (247, 248, 243))
    canvas.paste(dimg, (pad, 60))
    canvas.paste(aimg, (pad * 2 + dimg.width, 60))
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 20), "Figma: " + d, fill=(34, 60, 50), font=font)
    draw.text((pad * 2 + dimg.width, 20), "11 Ultra: " + a, fill=(34, 60, 50), font=font)
    canvas.save(os.path.join(OUT, f"{d}__vs__{a}.png"))
    print("saved", d, a, canvas.size)

# summary sheet: all device screenshots
paths = sorted(glob.glob(os.path.join(SHOTS, "*.png")))
thumbs = []
TH = 520
for p in paths:
    im = Image.open(p).convert("RGB")
    thumbs.append((os.path.basename(p), fit(im, TH)))
cols = 4
rows = (len(thumbs) + cols - 1) // cols
cw = max(t[1].width for t in thumbs) + 16
sheet = Image.new("RGB", (cols * cw, rows * (TH + 36) + 16), (247, 248, 243))
draw = ImageDraw.Draw(sheet)
for i, (name, im) in enumerate(thumbs):
    x = (i % cols) * cw + 8
    y = (i // cols) * (TH + 36) + 28
    sheet.paste(im, (x, y))
    draw.text((x, y - 18), name, fill=(34, 60, 50), font=font)
sheet.save(os.path.join(OUT, "00-device-screens-overview.png"))
print("overview", sheet.size, len(thumbs))
