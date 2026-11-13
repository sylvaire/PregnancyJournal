"""P0-4 secondary-page header measurements (before/after) for the UI header fix.

Measures, with Pillow + numpy, the ink geometry of the screenshots produced by
UiSnapshotCoverageTest on emulator-5556 (1080x2400, 420dpi => 411.43x914.29 dp,
1 dp = 2.625 px):

  * header title ink box (height / x0 / width in dp)  -> 20 sp TopAppBar before vs 28 sp after
  * header subtitle ink box (present? height / x0)     -> none before, 12 sp after
  * back-arrow glyph bbox (exact primary #356B53 in the header band) -> derives the IconButton
    48 dp slot x0 from the glyph's left bearing (7.6 dp, calibrated on the old TopAppBar whose
    nav container started at 4 dp)
  * "title-sized" text runs in the top 250 dp -> Export used to render the page title twice
    (20 sp AppBar + 24 sp content H1); after the fix only the 28 sp header remains
  * first content heading below the header -> 24 sp headlineSmall before vs 20 sp titleLarge after
    on Cloud / Recovery / Restore

Usage (from the repository root, bundled Python):
  python docs/verification/2026-10-24-ui-header-fix/measure_header.py
Writes docs/verification/2026-10-24-ui-header-fix/measurements.json and _tools/measurements.txt.
"""
import hashlib
import json
import os
import sys

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-ui-header-fix
REPO = os.path.abspath(os.path.join(ROOT, '..', '..', '..'))
BEFORE = os.path.join(REPO, 'docs', 'verification', '2026-10-24-ui-consistency-fix', 'after')
AFTER = os.path.join(ROOT, 'after')
DESIGN = os.path.join(REPO, 'docs', 'verification', '2026-10-24-figma-ui-alignment', 'figma', 'screens')
OUT_JSON = os.path.join(ROOT, 'measurements.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements.txt')

DP = 1080 / 411.4286  # 2.625 px per dp
INK = 3 * 150          # dark glyph ink: per-channel sum below this
ARROW_MAX_X = 140      # px: the back-arrow glyph is left of this; title glyphs are right of it
# Calibration from the old TopAppBar: its 48 dp navigationIcon slot started at x = 4 dp and the
# chevron glyph ink started at x = 23.6 dp -> glyph left bearing = 23.6 - 4 - (48-24)/2 = 7.6 dp.
GLYPH_LEFT_BEARING_DP = 7.6
ICON_CENTRING_DP = 12.0
PRIMARY = np.array([0x35, 0x6B, 0x53])


def dp(px):
    return round(px / DP, 2)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def _row_runs(mask, y0=0, y1=None):
    h = mask.shape[0]
    y1 = h if y1 is None else min(y1, h)
    counts = mask[y0:y1].sum(axis=1)
    runs = []
    start = None
    for i, c in enumerate(counts):
        y = y0 + i
        if c > 0 and start is None:
            start = y
        elif c == 0 and start is not None:
            runs.append((start, y - 1))
            start = None
    if start is not None:
        runs.append((start, y1 - 1))
    return runs


def run_box(mask, y0, y1):
    band = mask[y0:y1 + 1]
    ys, xs = np.nonzero(band)
    if not len(ys):
        return None
    return dict(y0=int(y0 + ys.min()), y1=int(y0 + ys.max()),
                x0=int(xs.min()), x1=int(xs.max()),
                h=int(ys.max() - ys.min() + 1), w=int(xs.max() - xs.min() + 1))


def box_dp(box, px_per_dp=DP):
    if not box:
        return None
    f = 1.0 / px_per_dp
    return dict(y0_dp=round(box['y0'] * f, 2), y1_dp=round(box['y1'] * f, 2),
                x0_dp=round(box['x0'] * f, 2), x1_dp=round(box['x1'] * f, 2),
                h_dp=round(box['h'] * f, 2), w_dp=round(box['w'] * f, 2))


def components(mask, min_px=6):
    h, w = mask.shape
    seen = np.zeros((h, w), bool)
    boxes = []
    ys, xs = np.nonzero(mask)
    for y0, x0 in zip(ys, xs):
        if seen[y0, x0]:
            continue
        stack = [(y0, x0)]
        seen[y0, x0] = True
        miny = maxy = y0
        minx = maxx = x0
        count = 0
        while stack:
            y, x = stack.pop()
            count += 1
            miny = min(miny, y)
            maxy = max(maxy, y)
            minx = min(minx, x)
            maxx = max(maxx, x)
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        if count >= min_px:
            boxes.append(dict(px=count, x0=minx, y0=miny, x1=maxx, y1=maxy,
                              h=maxy - miny + 1, w=maxx - minx + 1))
    return boxes


def analyse(path):
    """Geometry of one 1080x2400 screenshot produced by the Compose snapshot suites."""
    a = np.array(Image.open(path).convert('RGB'))
    h, w, _ = a.shape
    dark = a.sum(axis=2) < INK
    text = dark.copy()
    text[:, :ARROW_MAX_X] = False  # title/subtitle/content text only; drop the back-arrow glyph
    text_runs = _row_runs(text, 0, 700)
    # 1) topmost title: first text run at least 15 dp tall
    title = None
    for y0, y1 in text_runs:
        b = run_box(text, y0, y1)
        if b and dp(b['h']) >= 15:
            title = b
            break
    subtitle = None
    if title:
        for y0, y1 in text_runs:
            if y0 <= title['y1']:
                continue
            if y0 - title['y1'] > 45:
                break
            b = run_box(text, y0, y1)
            if b and 8 <= dp(b['h']) <= 14:
                subtitle = b
            break
    header_bottom = (subtitle or title)['y1'] if (title or subtitle) else -1
    # 2) back-arrow glyph: exact primary colour component in the header band, left of the title
    prim_mask = np.all(a[:360] == PRIMARY, axis=2)
    prim = components(prim_mask, min_px=30)
    arrow = None
    for c in prim:
        if dp(c['x0']) < 60 and dp(c['y1'] - c['y0'] + 1) >= 8:
            arrow = c
            break
    # 3) content heading below the header: first text run 15..24 dp tall and >= 140 dp wide.
    #    The box is measured on the full ink mask so the 24 dp page margin (x0 = 24 dp) is real.
    content = None
    for y0, y1 in text_runs:
        if y0 <= header_bottom + 4:
            continue
        b = run_box(dark, y0, y1)
        if b and 15 <= dp(b['h']) <= 24 and dp(b['w']) >= 100:
            content = b
            break
    # 4) title-sized text lines in the top 250 dp (Export duplicated the page title here)
    title_lines = []
    for y0, y1 in text_runs:
        if dp(y0) >= 250:
            continue
        b = run_box(text, y0, y1)
        if b and 17 <= dp(b['h']) <= 31:
            title_lines.append(b)

    def exact_colour(box_, rgb):
        if not box_:
            return 0
        patch = a[box_['y0']:box_['y1'] + 1, box_['x0']:box_['x1'] + 1]
        return int(np.all(patch == np.array(rgb), axis=2).sum())

    box = lambda b: box_dp(b) if b else None
    return dict(file=os.path.basename(path), size=[w, h], sha256=sha256(path),
                title=box(title), subtitle=box(subtitle), arrow=box(arrow),
                title_colour_px=exact_colour(title, (0x22, 0x3C, 0x32)),
                subtitle_colour_px=exact_colour(subtitle, (0x5F, 0x71, 0x66)),
                arrow_slot_x0_dp=round(dp(arrow['x0']) - GLYPH_LEFT_BEARING_DP - ICON_CENTRING_DP, 2) if arrow else None,
                content_heading=box(content),
                title_lines_top250dp=[box(b) for b in title_lines],
                text_runs_top700px=[dict(box_dp(run_box(text, y0, y1))) for y0, y1 in text_runs])


def analyse_design(path):
    """Figma export (824x1830 = 2x of 412x915 dp): header title + subtitle ink boxes in dp."""
    a = np.array(Image.open(path).convert('RGB'))
    dark = a.sum(axis=2) < INK
    runs = _row_runs(dark, 0, 360)
    boxes = [run_box(dark, y0, y1) for y0, y1 in runs]
    boxes = [b for b in boxes if b]
    title = next((b for b in boxes if 40 <= b['y0'] / 2 <= 120 and b['h'] / 2 >= 18), None)
    subtitle = None
    if title:
        for b in boxes:
            if b['y0'] > title['y1'] and 8 <= b['h'] / 2 <= 13:
                subtitle = b
                break
    box = lambda b: box_dp(b, px_per_dp=2.0) if b else None
    return dict(file=os.path.basename(path), title=box(title), subtitle=box(subtitle))


def fmt(b):
    if not b:
        return 'none'
    return 'h=%5.1fdp x0=%5.1fdp w=%6.1fdp y0=%6.1fdp' % (b['h_dp'], b['x0_dp'], b['w_dp'], b['y0_dp'])


def main():
    groups = [
        ('coverage (UiSnapshotCoverageTest, 22 PNG)', AFTER, BEFORE),
        ('supplementary (UiSnapshotTest, 9 PNG)', os.path.join(ROOT, 'after-supplementary'),
         os.path.join(BEFORE.replace('after', 'after-supplementary'))),
    ]
    report = {'dp_per_px': round(DP, 4), 'pairs': {}, 'supplementary': {}, 'design_reference': {}}
    lines = []
    for group, after_dir, before_dir in groups:
        lines.append('################ %s' % group)
        names = sorted(f for f in os.listdir(after_dir) if f.endswith('.png'))
        for name in names:
            after_path = os.path.join(after_dir, name)
            before_path = os.path.join(before_dir, name)
            a = analyse(after_path)
            b = analyse(before_path) if os.path.exists(before_path) else None
            report['pairs' if 'coverage' in group else 'supplementary'][name] = dict(before=b, after=a)
            lines.append('== %s' % name)
            lines.append('  title     before %s' % fmt(b and b['title']))
            lines.append('  title     after  %s' % fmt(a['title']))
            lines.append('  subtitle  before %s' % fmt(b and b['subtitle']))
            lines.append('  subtitle  after  %s' % fmt(a['subtitle']))
            lines.append('  back arrow glyph before %s slot_x0=%s dp' % (fmt(b and b['arrow']), b and b['arrow_slot_x0_dp']))
            lines.append('  back arrow glyph after  %s slot_x0=%s dp' % (fmt(a['arrow']), a['arrow_slot_x0_dp']))
            lines.append('  title-sized lines (top 250dp) before %d %s' % (
                len(b['title_lines_top250dp']) if b else 0, [(x['h_dp'], x['y0_dp']) for x in (b and b['title_lines_top250dp'] or [])]))
            lines.append('  title-sized lines (top 250dp) after  %d %s' % (
                len(a['title_lines_top250dp']), [(x['h_dp'], x['y0_dp']) for x in a['title_lines_top250dp']]))
            lines.append('  content heading below header before %s' % fmt(b and b['content_heading']))
            lines.append('  content heading below header after  %s' % fmt(a['content_heading']))

    for design in ('05-VisitDetail.png', '06-VisitEditor.png', '19-CloudConnect.png'):
        path = os.path.join(DESIGN, design)
        if not os.path.exists(path):
            continue
        d = analyse_design(path)
        report['design_reference'][design] = d
        lines.append('== DESIGN %s (2x -> dp)' % design)
        lines.append('  design title    %s' % fmt(d['title']))
        lines.append('  design subtitle %s' % fmt(d['subtitle']))

    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)
    print('written:', OUT_TXT)


if __name__ == '__main__':
    sys.exit(main())
