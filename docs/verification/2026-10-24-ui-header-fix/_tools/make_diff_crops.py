"""Build before|after comparison crops for the P0-4 header fix.

Left = before (docs/verification/2026-10-24-ui-consistency-fix/after), right = after
(docs/verification/2026-10-24-ui-header-fix/after). Crops the top 760 px (status bar +
header + first content block) and half-scales each side so the pair is 1080 px wide.
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..', '..'))
BEFORE = os.path.join(REPO, 'docs', 'verification', '2026-10-24-ui-consistency-fix', 'after')
AFTER = os.path.join(ROOT, 'after')
OUT = os.path.join(ROOT, 'diffs')
CROP_H = 900
PAGES = ['export-screen', 'local-backup', 'cloud-connection', 'restore-wizard']

os.makedirs(OUT, exist_ok=True)
for page in PAGES:
    before = Image.open(os.path.join(BEFORE, page + '.png')).convert('RGB').crop((0, 0, 1080, CROP_H))
    after = Image.open(os.path.join(AFTER, page + '.png')).convert('RGB').crop((0, 0, 1080, CROP_H))
    scale = 0.5
    before = before.resize((int(1080 * scale), int(CROP_H * scale)), Image.LANCZOS)
    after = after.resize((int(1080 * scale), int(CROP_H * scale)), Image.LANCZOS)
    canvas = Image.new('RGB', (before.width + after.width + 4, before.height + 22), (255, 255, 255))
    canvas.paste(before, (0, 22))
    canvas.paste(after, (before.width + 4, 22))
    draw = ImageDraw.Draw(canvas)
    draw.text((6, 4), 'BEFORE 20sp TopAppBar', fill=(0, 0, 0))
    draw.text((before.width + 10, 4), 'AFTER 28sp JournalPageHeader', fill=(0, 0, 0))
    draw.line([(before.width + 1, 0), (before.width + 1, canvas.height)], fill=(200, 200, 200), width=2)
    path = os.path.join(OUT, 'header-%s-before-after.png' % page)
    canvas.save(path)
    print('wrote', path)
