"""Switch state / contrast measurement for task-10 (JournalSwitch).

Screenshots (1080x2400, emulator-5556 / 420dpi => 1 dp = 2.625 px):
  after : light/coverage/export-screen.png, cloud-connection-backup.png, reminder-settings-custom.png
  before: docs/verification/2026-10-24-ui-evidence-gaps/light/coverage/<same names>

For every switch track found (52x32 dp M3 track = 136.5x84 px, detected as a connected component of the
expected track colour in that shape range) the script reports:
  * track colour / bbox / size in dp
  * thumb pixels inside the track bbox (checked = white onPrimary, unchecked = muted onSurfaceVariant)
  * border ring pixels inside the track bbox (unchecked border = outline)
and computes the WCAG contrast pairs of the design tokens before/after.
"""
import json
import os

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-ui-p1-polish
REPO = os.path.abspath(os.path.join(ROOT, '..', '..', '..'))
AFTER = os.path.join(ROOT, 'light', 'coverage')
BEFORE = os.path.join(REPO, 'docs', 'verification', '2026-10-24-ui-evidence-gaps', 'light', 'coverage')
OUT_JSON = os.path.join(ROOT, 'measurements-switch.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements-switch.txt')
DP = 1080 / 411.4286
FACTOR = 2

PRIMARY = '#356B53'
SOFT = '#E7F0E6'
OUTLINE = '#DDE4DA'
WHITE = '#FFFFFF'
MUTED = '#5F7166'
SURFACE = '#FFFFFF'
BG = '#F7F8F3'
# Material3 defaults before the explicit colours (theme roles used by SwitchDefaults.colors()):
M3_UNCHECKED_TRACK = OUTLINE          # colourScheme.surfaceContainerHighest = outline in this theme
M3_UNCHECKED_THUMB = OUTLINE          # colourScheme.outline
M3_CHECKED_TRACK = PRIMARY
M3_CHECKED_THUMB = WHITE

TRACK_W_DP, TRACK_H_DP = 52.0, 32.0


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
    return 0.2126 * channel(int(h[0:2], 16)) + 0.7152 * channel(int(h[2:4], 16)) + 0.0722 * channel(int(h[4:6], 16))


def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return round((hi + 0.05) / (lo + 0.05), 2)


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


def track_shape(c):
    w = (c['x1'] - c['x0'] + 1) / DP
    h = (c['y1'] - c['y0'] + 1) / DP
    return 40 <= w <= 70 and 24 <= h <= 40


def analyse(path):
    a = np.array(Image.open(path).convert('RGB'))
    found = []
    for colour in (PRIMARY, SOFT, OUTLINE, WHITE):
        mask = np.all(a == rgb(colour), axis=2)
        for c in components(mask, min_px=2000):
            if not track_shape(c):
                continue
            # exclude duplicates (a checked track also contains primary pixels only; unchecked is soft/outline)
            if any(abs(c['x0'] - f['x0']) < 8 and abs(c['y0'] - f['y0']) < 8 for f in found):
                continue
            x0, y0, x1, y1 = c['x0'], c['y0'], c['x1'], c['y1']
            box = a[y0:y1 + 1, x0:x1 + 1]
            thumb_counts = {k: int(np.all(box == rgb(v), axis=2).sum()) for k, v in
                            dict(white=WHITE, muted=MUTED, soft=SOFT, outline=OUTLINE, primary=PRIMARY).items()}
            found.append(dict(
                track_colour=colour, x0=int(x0), y0=int(y0),
                x0_dp=round(x0 / DP, 2), y0_dp=round(y0 / DP, 2),
                w_dp=round((x1 - x0 + 1) / DP, 2), h_dp=round((y1 - y0 + 1) / DP, 2),
                track_px=c['px'], inner_colour_counts=thumb_counts,
            ))
    found.sort(key=lambda f: (f['y0_dp'], f['x0_dp']))
    return found


def main():
    report = {'contrast': {}, 'files': {}}
    pairs_after = {
        'checked thumb(white) / track(primary)': (WHITE, PRIMARY),
        'unchecked thumb(muted) / track(soft)': (MUTED, SOFT),
        'unchecked thumb(muted) / border(outline)': (MUTED, OUTLINE),
        'unchecked track(soft) / page background': (SOFT, BG),
        'checked track(primary) / page background': (PRIMARY, BG),
        'border(outline) / page background': (OUTLINE, BG),
    }
    pairs_before = {
        'checked thumb(onPrimary white) / track(primary)': (WHITE, PRIMARY),
        'unchecked thumb(outline) / track(surfaceContainerHighest=outline)': (OUTLINE, OUTLINE),
        'unchecked track(outline) / page background': (OUTLINE, BG),
    }
    for label, (fg, bg) in pairs_before.items():
        report['contrast']['before: ' + label] = dict(fg=fg, bg=bg, ratio=contrast(fg, bg))
    for label, (fg, bg) in pairs_after.items():
        report['contrast']['after:  ' + label] = dict(fg=fg, bg=bg, ratio=contrast(fg, bg))

    lines = ['== WCAG contrast (Switch states)']
    for k, v in report['contrast'].items():
        lines.append('  %-70s fg=%s bg=%s ratio=%.2f' % (k, v['fg'], v['bg'], v['ratio']))

    for name in ('export-screen.png', 'cloud-connection-backup.png', 'reminder-settings-custom.png'):
        for phase, base in (('before', BEFORE), ('after', AFTER)):
            path = os.path.join(base, name)
            if not os.path.exists(path):
                continue
            switches = analyse(path)
            report['files']['%s/%s' % (phase, name)] = switches
            lines.append('')
            lines.append('== %s / %s : %d switch track(s)' % (phase, name, len(switches)))
            for s in switches:
                lines.append('  track=%s  %.2f x %.2f dp @ (%.2f, %.2f)  track_px=%d  inner=%s' % (
                    s['track_colour'], s['w_dp'], s['h_dp'], s['x0_dp'], s['y0_dp'], s['track_px'], s['inner_colour_counts']))

    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=lambda o: int(o) if hasattr(o, 'item') else str(o))
    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)


if __name__ == '__main__':
    main()

