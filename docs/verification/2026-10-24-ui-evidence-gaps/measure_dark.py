"""Dark-theme snapshot audit for task-9.

Compares the light and dark snapshot runs of the same two suites on emulator-5556:
  light/coverage/*.png   (UiSnapshotCoverageTest, cmd uimode night no)
  light/supplementary/*.png (UiSnapshotTest)
  dark/coverage/*.png    (UiSnapshotCoverageTest, cmd uimode night yes)
  dark/supplementary/*.png  (UiSnapshotTest)

Checks per dark screenshot:
  * dominant (most frequent exact) colour -> must be the dark theme background #141D17
  * dark token hits: surface #1D2921 / onSurface #E3EADF / muted #B9C8BB / primary #A5D0B3 / soft #294C39 /
    outline #8A9D8D / error #F2B8B5
  * light-theme residue: exact light tokens (#F7F8F3/#FFFFFF/#E7F0E6/#DDE4DA/#223C32/#356B53/#5F7166)
  * WCAG contrast ratios for the design token pairs actually used by the theme
  * the largest dark-surface (#1D2921) component, as a proxy for cards/panels
"""
import json
import os
from collections import Counter

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-ui-evidence-gaps
OUT_JSON = os.path.join(ROOT, 'measurements-dark.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements-dark.txt')
DP = 1080 / 411.4286
FACTOR = 4

DARK = {
    'background': '#141D17', 'surface': '#1D2921', 'onSurface': '#E3EADF', 'onSurfaceVariant': '#B9C8BB',
    'primary': '#A5D0B3', 'onPrimary': '#173D2A', 'primaryContainer': '#294C39', 'onPrimaryContainer': '#D5EBD8',
    'outline': '#8A9D8D', 'outlineVariant': '#405647', 'error': '#F2B8B5', 'errorContainer': '#8C1D18',
    'onErrorContainer': '#F9DEDC', 'onError': '#601410',
    'surfaceBright': '#34443A', 'surfaceDim': '#101711', 'surfaceContainerLowest': '#101711',
    'surfaceContainerLow': '#17231B', 'surfaceContainer': '#1B2A20', 'surfaceContainerHigh': '#213228',
    'surfaceContainerHighest': '#294C39', 'inverseSurface': '#E3EADF', 'inverseOnSurface': '#173D2A',
}
LIGHT = {
    'background': '#F7F8F3', 'surface': '#FFFFFF', 'onSurface': '#223C32', 'muted': '#5F7166',
    'primary': '#356B53', 'soft': '#E7F0E6', 'outline': '#DDE4DA',
}
NAMED = ['timeline.png', 'reminder-settings.png', 'security-settings.png', 'local-backup.png',
         '01-today.png', '02-visits.png']


def rgb(hex_):
    h = hex_.lstrip('#')
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], np.uint8)


def hex_(c):
    return '#%02X%02X%02X' % (int(c[0]), int(c[1]), int(c[2]))


def lum(hex_):
    def channel(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    h = hex_.lstrip('#')
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return round((hi + 0.05) / (lo + 0.05), 2)


def components(mask, min_px=30):
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
    flat = a.reshape(-1, 3)
    # most frequent exact colours (quantised to full RGB; drop near-transparent AA noise by counting exact groups)
    counts = Counter(map(tuple, flat))
    top = [dict(colour=hex_(c), px=n) for c, n in counts.most_common(5)]
    dark_hits = {k: int(np.all(a == rgb(v), axis=2).sum()) for k, v in DARK.items()}
    light_hits = {k: int(np.all(a == rgb(v), axis=2).sum()) for k, v in LIGHT.items()}
    small = a[::FACTOR, ::FACTOR]
    surf_mask = np.all(small == rgb(DARK['surface']), axis=2)
    surf_comps = components(surf_mask, min_px=40)
    surface_box = None
    if surf_comps:
        c = surf_comps[0]
        surface_box = dict(px=c['px'] * FACTOR * FACTOR,
                           x0_dp=round(c['x0'] * FACTOR / DP, 2), y0_dp=round(c['y0'] * FACTOR / DP, 2),
                           w_dp=round((c['x1'] - c['x0'] + 1) * FACTOR / DP, 2),
                           h_dp=round((c['y1'] - c['y0'] + 1) * FACTOR / DP, 2))
    meaningful = {k: v for k, v in light_hits.items() if v >= 1000}
    return dict(file=os.path.basename(path), size=[a.shape[1], a.shape[0]], top_colours=top,
                dominant_is_dark_token=top[0]['colour'] in set(DARK.values()),
                dark_token_hits=dark_hits, light_residue_hits=light_hits,
                light_residue_total=sum(light_hits.values()),
                light_residue_meaningful=meaningful,
                largest_surface_component=surface_box)


def main():
    report = {'light': {}, 'dark': {}, 'contrast': {}, 'named': {}}
    lines = []
    for phase in ('light', 'dark'):
        for suite in ('coverage', 'supplementary'):
            d = os.path.join(ROOT, phase, suite)
            if not os.path.isdir(d):
                continue
            for name in sorted(f for f in os.listdir(d) if f.endswith('.png')):
                report[phase]['%s/%s' % (suite, name)] = analyse(os.path.join(d, name))

    pairs = [
        ('onSurface / background', DARK['onSurface'], DARK['background']),
        ('onSurface / surface', DARK['onSurface'], DARK['surface']),
        ('onSurfaceVariant / surface', DARK['onSurfaceVariant'], DARK['surface']),
        ('primary / surface', DARK['primary'], DARK['surface']),
        ('onPrimaryContainer / primaryContainer', DARK['onPrimaryContainer'], DARK['primaryContainer']),
        ('outline / background', DARK['outline'], DARK['background']),
        ('onSurface / primaryContainer', DARK['onSurface'], DARK['primaryContainer']),
    ]
    for label, fg, bg in pairs:
        report['contrast'][label] = dict(fg=fg, bg=bg, ratio=contrast(fg, bg))
    lines.append('== WCAG contrast (theme tokens)')
    for label, v in report['contrast'].items():
        lines.append('  %-42s fg=%s bg=%s ratio=%.2f' % (label, v['fg'], v['bg'], v['ratio']))

    lines.append('')
    lines.append('== dark screenshots: dominant colour / dark token hits / light residue')
    for key, info in report['dark'].items():
        top = info['top_colours'][0]
        lines.append('%-46s dominant=%s (%d px, %.1f%%) dark_token=%s  dark_bg_hits=%d  surface_hits=%d  onSurface_hits=%d  light_residue_total=%d meaningful=%s' % (
            key, top['colour'], top['px'], 100.0 * top['px'] / (1080 * 2400), info['dominant_is_dark_token'],
            info['dark_token_hits']['background'], info['dark_token_hits']['surface'],
            info['dark_token_hits']['onSurface'], info['light_residue_total'], info['light_residue_meaningful']))
        if info['largest_surface_component']:
            c = info['largest_surface_component']
            lines.append('    largest surface component: %.1f x %.1f dp @ (%.1f, %.1f) dp' % (
                c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp']))
        lines.append('    light residue detail: %s' % {k: v for k, v in info['light_residue_hits'].items() if v})

    lines.append('')
    lines.append('== named screen dark/light comparison')
    for name in NAMED:
        for suite in ('coverage', 'supplementary'):
            key = '%s/%s' % (suite, name)
            if key in report['dark']:
                d = report['dark'][key]
                l = report['light'].get(key, {}).get('top_colours', [{}])[0]
                report['named'][key] = dict(dark=d['top_colours'][0], light=l,
                                            dark_surface=d['dark_token_hits']['surface'],
                                            light_residue=d['light_residue_total'],
                                            light_residue_meaningful=d['light_residue_meaningful'],
                                            dark_bg_hits=d['dark_token_hits']['background'],
                                            on_surface_hits=d['dark_token_hits']['onSurface'])
                lines.append('%-46s light_dominant=%s  dark_dominant=%s  dark_bg_px=%d  dark_surface_px=%d  onSurface_px=%d  light_residue=%d (meaningful=%s)' % (
                    key, l.get('colour'), d['top_colours'][0]['colour'], d['dark_token_hits']['background'],
                    d['dark_token_hits']['surface'], d['dark_token_hits']['onSurface'],
                    d['light_residue_total'], d['light_residue_meaningful']))

    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=lambda o: int(o) if hasattr(o, 'item') else str(o))
    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)


if __name__ == '__main__':
    main()



