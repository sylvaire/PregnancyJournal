"""Empty-state measurement for task-10 (JournalEmptyState).

Compares the light screenshots before/after the empty-state unification:
  after : light/coverage/trash-empty.png, local-backup.png   (new JournalEmptyState soft card)
  before: docs/verification/2026-10-24-ui-evidence-gaps/light/coverage/<same>

Measures, per screenshot:
  * soft (#E7F0E6) connected components with a card-like shape (>= 80 dp tall, >= 300 dp wide) -> bbox,
    area, share of screen. For the local-backup page the 52 dp secondary JournalButton is also soft, so the
    card detector requires the taller shape and the button is reported separately.
  * inside the card bbox: primary (#356B53) pixels -> semantic icon tint; #223C32 ink -> title; #5F7166 ->
    subtitle. Before the change these states were plain text with no soft card and no icon.
"""
import json
import os

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-ui-p1-polish
REPO = os.path.abspath(os.path.join(ROOT, '..', '..', '..'))
AFTER = os.path.join(ROOT, 'light', 'coverage')
BEFORE = os.path.join(REPO, 'docs', 'verification', '2026-10-24-ui-evidence-gaps', 'light', 'coverage')
OUT_JSON = os.path.join(ROOT, 'measurements-empty.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements-empty.txt')
DP = 1080 / 411.4286
FACTOR = 4

SOFT = '#E7F0E6'
PRIMARY = '#356B53'
INK = '#223C32'
MUTED = '#5F7166'
WHITE = '#FFFFFF'


def rgb(hex_):
    h = hex_.lstrip('#')
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], np.uint8)


def hex_(c):
    return '#%02X%02X%02X' % (int(c[0]), int(c[1]), int(c[2]))


def components(mask, min_px=20):
    h, w = mask.shape
    seen = np.zeros((h, w), bool)
    ys, xs = np.nonzero(mask)
    out = []
    for y0, x0 in zip(ys, xs):
        if seen[y0, x0]:
            continue
        stack = [(y0, x0)]
        seen[y0, x0] = True
        count = 0
        miny = maxy = y0
        minx = maxx = x0
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
            out.append(dict(px=count, x0=minx, y0=miny, x1=maxx, y1=maxy))
    out.sort(key=lambda c: c['px'], reverse=True)
    return out


def analyse(path):
    a = np.array(Image.open(path).convert('RGB'))
    h, w, _ = a.shape
    soft_small = a[::FACTOR, ::FACTOR] == rgb(SOFT)
    soft_mask = np.all(soft_small, axis=2)
    comps = []
    for c in components(soft_mask, min_px=200):
        b = dict(px=c['px'] * FACTOR * FACTOR,
                 x0=c['x0'] * FACTOR, y0=c['y0'] * FACTOR,
                 x1=min(c['x1'] * FACTOR + FACTOR - 1, w - 1), y1=min(c['y1'] * FACTOR + FACTOR - 1, h - 1))
        b['w_dp'] = round((b['x1'] - b['x0'] + 1) / DP, 2)
        b['h_dp'] = round((b['y1'] - b['y0'] + 1) / DP, 2)
        b['x0_dp'] = round(b['x0'] / DP, 2)
        b['y0_dp'] = round(b['y0'] / DP, 2)
        b['share'] = round(100.0 * b['px'] / (w * h), 2)
        comps.append(b)
    cards = [c for c in comps if c['h_dp'] >= 80 and c['w_dp'] >= 300]
    buttons = [c for c in comps if 40 <= c['h_dp'] < 80 and c['w_dp'] >= 300]
    result = dict(file=os.path.basename(path), size=[w, h], soft_components=comps[:8],
                  cards=cards[:3], buttons=buttons[:3], card_evidence=None)
    if cards:
        card = cards[0]
        patch = a[card['y0']:card['y1'] + 1, card['x0']:card['x1'] + 1]
        def count(colour):
            return int(np.all(patch == rgb(colour), axis=2).sum())
        result['card_evidence'] = dict(
            primary_icon_px=count(PRIMARY), title_ink_px=count(INK), subtitle_muted_px=count(MUTED),
            white_px=count(WHITE), soft_px=count(SOFT),
        )
        # icon bbox inside the card (primary pixels in the top-left region, icon is 24 dp)
        prim = np.all(patch == rgb(PRIMARY), axis=2)
        ys, xs = np.nonzero(prim[:int(80 * DP)])
        if len(ys):
            result['card_evidence']['icon_bbox_dp'] = dict(
                w_dp=round((xs.max() - xs.min() + 1) / DP, 2),
                h_dp=round((ys.max() - ys.min() + 1) / DP, 2),
            )
    return result


def main():
    report = {}
    lines = []
    for name in ('trash-empty.png', 'local-backup.png'):
        for phase, base in (('before', BEFORE), ('after', AFTER)):
            path = os.path.join(base, name)
            if not os.path.exists(path):
                continue
            info = analyse(path)
            report['%s/%s' % (phase, name)] = info
            lines.append('== %s / %s' % (phase, name))
            lines.append('  soft card components (>=80dp tall, >=300dp wide): %d' % len(info['cards']))
            for c in info['cards']:
                lines.append('    card %.2f x %.2f dp @ (%.2f, %.2f)  area=%d px (%.1f%%)' % (
                    c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp'], c['px'], c['share']))
            for c in info['buttons']:
                lines.append('    soft button %.2f x %.2f dp @ (%.2f, %.2f)' % (c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp']))
            if info['card_evidence']:
                lines.append('    card evidence: %s' % info['card_evidence'])
    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=lambda o: int(o) if hasattr(o, 'item') else str(o))
    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)


if __name__ == '__main__':
    main()
