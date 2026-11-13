"""Timeline 方案 A 视觉量化（before/after/设计基准）。

输入（均为 1080x2400，emulator-5556 / 420dpi => 1 dp = 2.625 px）：
  after  : docs/verification/2026-10-24-timeline-ia-a/after/coverage/timeline.png（UiSnapshotCoverageTest，真实 JournalPage 骨架）
           docs/verification/2026-10-24-timeline-ia-a/after/navigation/c04-timeline.png（NavigationExperienceTest，真实 App 导航）
  before : docs/verification/2026-10-24-ui-p1-polish/light/coverage/timeline.png（方案 A 前的信息流版）
设计基准：docs/verification/2026-10-24-figma-ui-alignment/figma/screens/12-Timeline.png（824x1830 = 2x => 1 dp = 2 px）

量化项：
  * 记录行：白色卡（#FFFFFF）连通域，宽约 364 dp、高 >= 60 dp -> 行高（设计 72 dp min + 图标 44 dp）
  * 图标容器：#E7F0E6 连通域 38~50 dp 方块 -> 数量/边长
  * 杏色卡：#FAEAD8 连通域 bbox/面积，内部 #80582C 文本像素
  * 筛选：顶部条带内 #356B53 选中 chip（48 dp 高）+ #DDE4DA 描边未选 chip 计数（设计 3 个）
  * 底部 CTA：#356B53 大块（宽 >= 300 dp、高 45~60 dp）的 bbox 与“距屏幕底部”距离
  * 日期组头/孕周：左侧 #223C32 文本行 + 右侧 #5F7166 文本行（同一 y 带）是否存在
"""
import json
import os

from PIL import Image
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))  # docs/verification/2026-10-24-timeline-ia-a
REPO = os.path.abspath(os.path.join(ROOT, '..', '..', '..'))
AFTER_COVERAGE = os.path.join(ROOT, 'after', 'coverage', 'timeline.png')
AFTER_NAV = os.path.join(ROOT, 'after', 'navigation', 'c04-timeline.png')
BEFORE = os.path.join(REPO, 'docs', 'verification', '2026-10-24-ui-p1-polish', 'light', 'coverage', 'timeline.png')
DESIGN = os.path.join(REPO, 'docs', 'verification', '2026-10-24-figma-ui-alignment', 'figma', 'screens', '12-Timeline.png')
OUT_JSON = os.path.join(ROOT, 'measurements-timeline.json')
OUT_TXT = os.path.join(ROOT, '_tools', 'measurements-timeline.txt')

WHITE = '#FFFFFF'
SOFT = '#E7F0E6'
PRIMARY = '#356B53'
INK = '#223C32'
MUTED = '#5F7166'
OUTLINE = '#DDE4DA'
APRICOT = '#FAEAD8'
AMBER = '#80582C'
FACTOR = 2


def rgb(hex_):
    h = hex_.lstrip('#')
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], np.uint8)


def hex_(c):
    return '#%02X%02X%02X' % (int(c[0]), int(c[1]), int(c[2]))


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
            out.append(dict(px=count, x0=minx, y0=miny, x1=maxx, y1=maxy,
                            w=maxx - minx + 1, h=maxy - miny + 1))
    out.sort(key=lambda c: c['px'], reverse=True)
    return out


def colour_components(a, colour, min_px=100):
    mask = np.all(a == rgb(colour), axis=2)
    small = mask[::FACTOR, ::FACTOR]
    comps = components(small, min_px=max(10, min_px // (FACTOR * FACTOR)))
    out = []
    for c in comps:
        b = dict(px=c['px'] * FACTOR * FACTOR,
                 x0=c['x0'] * FACTOR, y0=c['y0'] * FACTOR,
                 x1=min(c['x1'] * FACTOR + FACTOR - 1, a.shape[1] - 1),
                 y1=min(c['y1'] * FACTOR + FACTOR - 1, a.shape[0] - 1))
        b['w']=b['x1']-b['x0']+1; b['h']=b['y1']-b['y0']+1
        out.append(b)
    return out


def analyse(path, px_per_dp, offset_px=0, label=''):
    a = np.array(Image.open(path).convert('RGB'))
    h, w, _ = a.shape
    dp = lambda px: round(px / px_per_dp, 2)

    def box(b, unit=None):
        f = 1.0 / px_per_dp if unit is None else 1.0 / px_per_dp
        return dict(x0_dp=round(b['x0'] / px_per_dp, 2), y0_dp=round(b['y0'] / px_per_dp, 2),
                    w_dp=round(b['w'] / px_per_dp, 2), h_dp=round(b['h'] / px_per_dp, 2),
                    x1_dp=round((b['x1'] + 1) / px_per_dp, 2), y1_dp=round((b['y1'] + 1) / px_per_dp, 2))

    white = colour_components(a, WHITE, 2000)
    rows = [dict(box(b), px=b['px']) for b in white if 300 <= dp(b['w']) <= 420 and 55 <= dp(b['h']) <= 120]
    # 未选中的筛选 chip 是白底 + outline 圆角描边：在筛选条带内按白色小方块计数
    chips_unselected = [dict(box(b), px=b['px']) for b in white
                        if 50 <= dp(b['w']) <= 140 and 40 <= dp(b['h']) <= 60 and 125 <= dp(b['y0']) <= 200]
    icon_containers = [dict(box(b), px=b['px']) for b in colour_components(a, SOFT, 800)
                       if 40 <= dp(b['w']) <= 48 and 40 <= dp(b['h']) <= 48]
    apricot = [dict(box(b), px=b['px']) for b in colour_components(a, APRICOT, 1000) if dp(b['w']) >= 200]
    primary = colour_components(a, PRIMARY, 1000)
    chips_selected = [dict(box(b), px=b['px']) for b in primary
                      if 40 <= dp(b['h']) <= 56 and 50 <= dp(b['w']) <= 140 and 125 <= dp(b['y0']) <= 200]
    cta = [dict(box(b), px=b['px']) for b in primary
           if 45 <= dp(b['h']) <= 62 and dp(b['w']) >= 300]

    # date header band = content band between the filter row (完成于 ~191 dp) and the first card (~250 dp)
    left_ink, right_label = [], []
    band_start = int(193 * px_per_dp)
    band = a[band_start:int(250 * px_per_dp)]
    ink_mask = np.all(band == rgb(INK), axis=2)
    ys, xs = np.nonzero(ink_mask)
    if len(ys):
        left_ink.append(dict(y0_dp=round((ys.min() + band_start) / px_per_dp, 2),
                             y1_dp=round((ys.max() + band_start) / px_per_dp, 2),
                             x0_dp=round(xs.min() / px_per_dp, 2), x1_dp=round(xs.max() / px_per_dp, 2)))
    # 右侧孕周标签：日期带右半区（x >= 250 dp）非背景/非白像素（设计像素取色为 primary #356B53）
    right_band = a[band_start:int(250 * px_per_dp), int(250 * px_per_dp):int(405 * px_per_dp)]
    bgmask = ~(np.all(right_band == np.array([247, 248, 243], np.uint8), axis=2) |
               np.all(right_band == np.array([255, 255, 255], np.uint8), axis=2))
    ys, xs = np.nonzero(bgmask)
    if len(ys):
        rp = right_band[bgmask]
        right_label.append(dict(
            y0_dp=round((ys.min() + band_start) / px_per_dp, 2),
            y1_dp=round((ys.max() + band_start) / px_per_dp, 2),
            x0_dp=round((xs.min() + int(250 * px_per_dp)) / px_per_dp, 2),
            x1_dp=round((xs.max() + int(250 * px_per_dp)) / px_per_dp, 2),
            primary_px=int(np.all(rp == rgb(PRIMARY), axis=1).sum()),
            muted_px=int(np.all(rp == rgb(MUTED), axis=1).sum()),
        ))

    counts = {c: int(np.all(a == rgb(c), axis=2).sum()) for c in (WHITE, SOFT, PRIMARY, INK, MUTED, OUTLINE, APRICOT, AMBER)}
    return dict(file=label or os.path.basename(path), size=[w, h], px_per_dp=px_per_dp,
                colour_px=counts,
                record_rows=rows[:8], record_row_count=len(rows),
                icon_containers=icon_containers[:6], icon_container_count=len(icon_containers),
                unselected_chip_count=len(chips_unselected), unselected_chips=chips_unselected[:4],
                apricot_cards=apricot[:3],
                selected_chips=chips_selected,
                bottom_cta=[c for c in cta if c['y0_dp'] >= 600][:3],
                top_cta=[c for c in cta if c['y0_dp'] < 300][:3],
                date_header_left_ink=left_ink, date_header_right_label=right_label)


def main():
    report = {}
    lines = []

    def dump(key, info):
        report[key] = info
        lines.append('== %s' % key)
        lines.append('  colours: %s' % info['colour_px'])
        lines.append('  record rows: %d %s' % (info['record_row_count'], [(r['h_dp'], r['w_dp'], r['y0_dp']) for r in info['record_rows'][:6]]))
        lines.append('  icon containers (44dp): %d %s' % (info['icon_container_count'], [(c['w_dp'], c['h_dp']) for c in info['icon_containers'][:4]]))
        lines.append('  apricot cards: %s' % [(c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp']) for c in info['apricot_cards']])
        lines.append('  selected filter chips: %d %s' % (len(info['selected_chips']), [(c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp']) for c in info['selected_chips']]))
        lines.append('  unselected filter chips: %d %s' % (info['unselected_chip_count'], [(c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp']) for c in info['unselected_chips']]))
        lines.append('  bottom CTA: %s' % [(c['w_dp'], c['h_dp'], c['x0_dp'], c['y0_dp'], c['y1_dp']) for c in info['bottom_cta']])
        lines.append('  top CTA (old design): %s' % [(c['w_dp'], c['h_dp'], c['y0_dp']) for c in info['top_cta']])
        lines.append('  date header: left ink=%s right label=%s' % (info['date_header_left_ink'], info['date_header_right_label']))

    if os.path.exists(AFTER_COVERAGE):
        dump('after/coverage/timeline.png', analyse(AFTER_COVERAGE, 1080 / 411.4286, label='after/coverage/timeline.png'))
    if os.path.exists(AFTER_NAV):
        dump('after/navigation/c04-timeline.png', analyse(AFTER_NAV, 1080 / 411.4286, label='after/navigation/c04-timeline.png'))
    if os.path.exists(BEFORE):
        dump('before/2026-10-24-ui-p1-polish/timeline.png', analyse(BEFORE, 1080 / 411.4286, label='before/timeline.png'))
    if os.path.exists(DESIGN):
        dump('design/12-Timeline.png (2x)', analyse(DESIGN, 2.0, label='design/12-Timeline.png'))

    with open(OUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1, default=lambda o: int(o) if hasattr(o, 'item') else str(o))
    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    with open(OUT_TXT, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    print('\nwritten:', OUT_JSON)


if __name__ == '__main__':
    main()





