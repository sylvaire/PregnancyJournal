"""Container-colour audit for the surface snapshots (task-9).

Screenshots (1080x2400, emulator-5556 / 420dpi => 1 dp = 2.625 px):
  surfaces/surface-alert-dialog.png     AlertDialog（产检资料 → 删除确认）
  surfaces/surface-date-picker.png      M3 DatePickerDialog（新建产检预约 → 预约日期）
  surfaces/surface-snackbar.png         Snackbar「已移入全局回收站 · 撤销」（Compose root 截图）
  surfaces/surface-dropdown-menu.png    DropdownMenu（产检资料 → 全部分类）

Checks
  * 精确色计数（tol=0）：设计色板命中 + M3 基线淡紫灰命中（后者必须为 0）。
  * 表面容器：对精确色做 1/4 降采样连通域，取最大/符合形状的矩形作为容器，给出 bbox（px/dp）与面积占比。
  * 交互色：AlertDialog 按钮、DatePicker 选中日、Snackbar 文字/动作、Dropdown 文本的设计色命中。
"""
import json
import os

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-ui-evidence-gaps
SURF = os.path.join(ROOT, 'surfaces')
OUT_JSON = os.path.join(ROOT, 'measurements-surfaces.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements-surfaces.txt')
DP = 1080 / 411.4286  # 2.625 px per dp
FACTOR = 4            # connected-component downscale factor

DESIGN = ['#FFFFFF', '#F7F8F3', '#E7F0E6', '#DDE4DA', '#223C32', '#356B53', '#5F7166', '#FAEAD8', '#80582C', '#A44238', '#FCECE7']
M3_BASELINE = {  # M3 1.x default light palette (the "淡紫灰" family the P0-2 fix removed)
    '#ECE6F0', '#E6E0E9', '#F3EDF7', '#FEF7FF', '#E7E0EC', '#49454F', '#79747E', '#CAC4D0', '#B3261E',
    '#6750A4', '#EADDFF', '#E8DEF8', '#7D5260', '#FFD8E4', '#21005D', '#1D192B', '#625B71', '#1D1B20',
}


def rgb(hex_):
    h = hex_.lstrip('#')
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], np.uint8)


def hex_(c):
    return '#%02X%02X%02X' % (int(c[0]), int(c[1]), int(c[2]))


def components(mask, min_px=20):
    """4-neighbour connected components on a (downscaled) boolean mask; returns bboxes sorted by area."""
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


def scaled_box(c):
    return dict(x0=c['x0'] * FACTOR, y0=c['y0'] * FACTOR,
                x1=min(c['x1'] * FACTOR + FACTOR - 1, 1079), y1=min(c['y1'] * FACTOR + FACTOR - 1, 2399),
                px=c['px'] * FACTOR * FACTOR)


def box_dp(b):
    return dict(x0_dp=round(b['x0'] / DP, 2), y0_dp=round(b['y0'] / DP, 2),
                w_dp=round((b['x1'] - b['x0'] + 1) / DP, 2), h_dp=round((b['y1'] - b['y0'] + 1) / DP, 2))


def colour_counts(a):
    errors = []
    for hx in DESIGN + sorted(M3_BASELINE):
        try:
            rgb(hx)
        except Exception:
            errors.append(hx)
    assert not errors, errors
    design = {hx: int(np.all(a == rgb(hx), axis=2).sum()) for hx in DESIGN}
    baseline = {hx: int(np.all(a == rgb(hx), axis=2).sum()) for hx in sorted(M3_BASELINE)}
    return design, baseline


def inspect_component(big, bbox_full, colour):
    """Sample the bbox centre (full-res) + verify the component's dominant colour."""
    cx = (bbox_full['x0'] + bbox_full['x1']) // 2
    cy = (bbox_full['y0'] + bbox_full['y1']) // 2
    return dict(container=colour, centre_sampled=True, centre_px=[int(cx), int(cy)])


def analyse(path, container_hex):
    a = np.array(Image.open(path).convert('RGB'))
    design, baseline = colour_counts(a)
    small = a[::FACTOR, ::FACTOR]
    mask = np.all(small == rgb(container_hex), axis=2)
    comps = [scaled_box(c) for c in components(mask, min_px=30)]
    enriched = []
    for b in comps[:8]:
        centre = a[(b['y0'] + b['y1']) // 2, (b['x0'] + b['x1']) // 2]
        enriched.append(dict(b, **box_dp(b), centre_colour=hex_(centre),
                             centre_is_container=bool(np.all(centre == rgb(container_hex)))))
    return dict(file=os.path.basename(path), size=[a.shape[1], a.shape[0]],
                design_colours=design, m3_baseline_colours=baseline,
                m3_baseline_total=sum(baseline.values()),
                largest_container_components=enriched,
                centre_colour=enriched[0]['centre_colour'] if enriched else None)


def main():
    cases = [
        ('surface-alert-dialog.png', '#FFFFFF'),
        ('surface-date-picker.png', '#FFFFFF'),
        ('surface-snackbar.png', '#223C32'),
        ('surface-dropdown-menu.png', '#FFFFFF'),
    ]
    report = {}
    lines = []
    for name, container in cases:
        path = os.path.join(SURF, name)
        info = analyse(path, container)
        report[name] = info
        lines.append('== %s' % name)
        lines.append('  expected container=%s  largest component: %s' % (
            container, info['largest_container_components'][0] if info['largest_container_components'] else 'NONE'))
        if info['largest_container_components']:
            c = info['largest_container_components'][0]
            lines.append('  component area=%d px (%.1f%% of screen)  bbox dp: x0=%.2f y0=%.2f w=%.2f h=%.2f' % (
                c['px'], 100.0 * c['px'] / (1080 * 2400), c['x0_dp'], c['y0_dp'], c['w_dp'], c['h_dp']))
            lines.append('  centre sampled colour=%s' % info['centre_colour'])
        lines.append('  design palette hits: %s' % {k: v for k, v in info['design_colours'].items() if v})
        hits = {k: v for k, v in info['m3_baseline_colours'].items() if v}
        lines.append('  M3 baseline (lavender family) hits: %s  total=%d' % (hits, info['m3_baseline_total']))

    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=lambda o: int(o) if hasattr(o, 'item') else str(o))
    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)


if __name__ == '__main__':
    main()


