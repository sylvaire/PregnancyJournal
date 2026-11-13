"""Condense measurements.json into the tables used by README.md (P0-4 header fix)."""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(ROOT, '..', 'measurements.json'), encoding='utf-8') as f:
    data = json.load(f)

cov = data['pairs']
sup = data['supplementary']
design = data['design_reference']


def f(x, key, default='—'):
    if not x or not x.get(key):
        return default
    return ('%.2f' % x[key]).rstrip('0').rstrip('.')


print('## header title/subtitle per page (coverage)')
print('| page | title h before | title h after | ratio | title w before | title w after | subtitle after h | subtitle after x0 |')
print('|---|---|---|---|---|---|---|---|')
for name, pair in cov.items():
    a, b = pair['after'], pair['before']
    th_b = f(b and b.get('title'), 'h_dp')
    th_a = f(a.get('title'), 'h_dp')
    ratio = ''
    try:
        ratio = '%.3f' % (a['title']['h_dp'] / b['title']['h_dp'])
    except Exception:
        pass
    print('| %s | %s | %s | %s | %s | %s | %s | %s |' % (
        name, th_b, th_a, ratio,
        f(b and b.get('title'), 'w_dp'), f(a.get('title'), 'w_dp'),
        f(a.get('subtitle'), 'h_dp'), f(a.get('subtitle'), 'x0_dp')))

print()
print('## supplementary (root pages unchanged, title pages updated)')
print('| page | title h before | title h after | subtitle before | subtitle after |')
print('|---|---|---|---|---|')
for name, pair in sup.items():
    a, b = pair['after'], pair['before']
    print('| %s | %s | %s | %s | %s |' % (
        name, f(b and b.get('title'), 'h_dp'), f(a.get('title'), 'h_dp'),
        'yes' if (b and b.get('subtitle')) else 'no',
        'yes' if a.get('subtitle') else 'no'))

print()
print('## back arrow glyph -> IconButton slot x0')
for name in ('export-screen.png', 'local-backup.png', 'cloud-connection.png', 'restore-wizard.png'):
    pair = cov[name]
    print('%s: before glyph_x0=%s slot_x0=%s | after glyph_x0=%s slot_x0=%s' % (
        name,
        f(pair['before']['arrow'], 'x0_dp'), pair['before']['arrow_slot_x0_dp'],
        f(pair['after']['arrow'], 'x0_dp'), pair['after']['arrow_slot_x0_dp']))

print()
print('## title colour (exact #223C32 ink px) / subtitle colour (exact #5F7166 px)')
for name in ('export-screen.png', 'local-backup.png', 'cloud-connection.png', 'restore-wizard.png'):
    pair = cov[name]
    print('%s: title_colour before=%s after=%s | subtitle_colour before=%s after=%s' % (
        name, pair['before']['title_colour_px'], pair['after']['title_colour_px'],
        pair['before']['subtitle_colour_px'], pair['after']['subtitle_colour_px']))

print()
print('## Export duplicate title (title-sized lines in top 250dp)')
for name in ('export-screen.png', 'export-screen-disabled.png'):
    pair = cov[name]
    print('%s: before %s -> after %s' % (
        name, [(x['h_dp'], x['y0_dp']) for x in pair['before']['title_lines_top250dp']],
        [(x['h_dp'], x['y0_dp']) for x in pair['after']['title_lines_top250dp']]))

print()
print('## content heading 24sp -> 20sp')
for name in ('cloud-connection.png', 'local-backup.png', 'restore-wizard.png', 'restore-wizard-key.png',
             '09-recovery.png'):
    pair = cov.get(name) or sup.get(name)
    b, a = pair['before']['content_heading'], pair['after']['content_heading']
    print('%s: before %s -> after %s' % (
        name,
        (f(b, 'h_dp'), f(b, 'x0_dp'), f(b, 'w_dp'), f(b, 'y0_dp')) if b else 'none',
        (f(a, 'h_dp'), f(a, 'x0_dp'), f(a, 'w_dp'), f(a, 'y0_dp')) if a else 'none'))

print()
print('## design reference (Figma 2x -> dp)')
for k, v in design.items():
    print('%s: title %s | subtitle %s' % (
        k, (f(v['title'], 'h_dp'), f(v['title'], 'x0_dp'), f(v['title'], 'w_dp'), f(v['title'], 'y0_dp')),
        (f(v['subtitle'], 'h_dp'), f(v['subtitle'], 'x0_dp'), f(v['subtitle'], 'w_dp'), f(v['subtitle'], 'y0_dp'))))
