"""Dark-theme surface measurement for task-10 (§C of the task).

Input: dark-surfaces/*.png (UiSurfaceSnapshotTest run after `cmd uimode night yes`):
  surface-alert-dialog.png, surface-date-picker.png, surface-snackbar.png, surface-dropdown-menu.png

For each image it reports:
  * the largest connected component of each candidate dark surface colour (dialog = surfaceContainerHigh
    #213228, menu = surfaceContainer #1B2A20 / high #213228, snackbar = inverseSurface #E3EADF)
  * light residue: exact #FFFFFF / #F7F8F3 counts and any component larger than 5,000 px (a real pale panel)
"""
import json
import os

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-ui-p1-polish
DARK = os.path.join(ROOT, 'dark-surfaces')
OUT_JSON = os.path.join(ROOT, 'measurements-dark-surfaces.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements-dark-surfaces.txt')
DP = 1080 / 411.4286
FACTOR = 4

TOKENS = {
    'background': '#141D17',
    'surface': '#1D2921',
    'surfaceContainer': '#1B2A20',
    'surfaceContainerHigh': '#213228',
    'surfaceContainerHighest': '#294C39',
    'inverseSurface': '#E3EADF',
    'onSurface': '#E3EADF',
    'primary': '#A5D0B3',
    'onSurfaceVariant': '#B9C8BB',
}
LIGHT = {'#FFFFFF', '#F7F8F3', '#E7F0E6', '#DDE4DA'}


def rgb(hex_):
    h = hex_.lstrip('#')
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], np.uint8)


def hex_(c):
    return '#%02X%02X%02X' % (int(c[0]), int(c[1]), int(c[2]))


def components(mask, min_px=40):
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


def box_dp(b):
    return dict(x0_dp=round(b['x0'] * FACTOR / DP, 2), y0_dp=round(b['y0'] * FACTOR / DP, 2),
                w_dp=round((b['x1'] - b['x0'] + 1) * FACTOR / DP, 2),
                h_dp=round((b['y1'] - b['y0'] + 1) * FACTOR / DP, 2),
                px=b['px'] * FACTOR * FACTOR)


def analyse(path):
    a = np.array(Image.open(path).convert('RGB'))
    h, w, _ = a.shape
    flat = a.reshape(-1, 3)
    vals, counts = np.unique(flat, axis=0, return_counts=True)
    top = sorted(zip(counts.tolist(), [hex_(v) for v in vals]), reverse=True)[:4]
    small = a[::FACTOR, ::FACTOR]
    surfaces = {}
    for name, colour in TOKENS.items():
        mask = np.all(small == rgb(colour), axis=2)
        comps = components(mask, min_px=40)
        if comps:
            surfaces[name] = dict(colour=colour, largest=box_dp(comps[0]),
                                  components=[box_dp(c) for c in comps[:3]])
    light_counts = {c: int(np.all(a == rgb(c), axis=2).sum()) for c in LIGHT}
    big_light = []
    for c in LIGHT:
        mask = np.all(small == rgb(c), axis=2)
        for comp in components(mask, min_px=300):
            big_light.append(dict(colour=c, **box_dp(comp)))
    big_light.sort(key=lambda b: b['px'], reverse=True)
    return dict(file=os.path.basename(path), size=[w, h], dominant=[dict(px=p, colour=c) for p, c in top],
                surfaces=surfaces, light_counts=light_counts, large_light_components=big_light[:5])


def main():
    report = {}
    lines = []
    for name in sorted(f for f in os.listdir(DARK) if f.endswith('.png')):
        info = analyse(os.path.join(DARK, name))
        report[name] = info
        lines.append('== %s' % name)
        lines.append('  dominant colours: %s' % [(d['colour'], d['px']) for d in info['dominant']])
        for key in ('surfaceContainerHigh', 'surfaceContainer', 'surfaceContainerHighest', 'inverseSurface', 'surface'):
            s = info['surfaces'].get(key)
            if s and s['largest']['px'] >= 20000:
                b = s['largest']
                lines.append('  %-24s %s  %6.2f x %7.2f dp @ (%.2f, %.2f)  area=%d px' % (
                    key + ':', s['colour'], b['w_dp'], b['h_dp'], b['x0_dp'], b['y0_dp'], b['px']))
        lines.append('  light exact px: %s' % info['light_counts'])
        lines.append('  large (>5,000px) light components: %s' % info['large_light_components'])
    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=lambda o: int(o) if hasattr(o, 'item') else str(o))
    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)


if __name__ == '__main__':
    main()
