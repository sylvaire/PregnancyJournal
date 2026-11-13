"""Per-screenshot pixel-diff statistics: before (2026-10-24-ui-theme-fix/after) vs after.

Outputs diffs/diff-stats.txt: changed pixel count/ratio, difference bbox (px & dp), and the
dominant before->after colour pairs. Used to attribute the 18 changed screenshots.
"""
import os
from collections import Counter

import numpy as np
from PIL import Image

BASE = os.path.dirname(os.path.abspath(__file__))
THEME_FIX = os.path.abspath(os.path.join(BASE, "..", "2026-10-24-ui-theme-fix", "after"))
AFTER = os.path.join(BASE, "after")
OUT = os.path.join(BASE, "diffs", "diff-stats.txt")
SCALE = 2.625


def load(p):
    return np.asarray(Image.open(p).convert("RGB")).astype(np.int16)


lines = []
files = sorted(f for f in os.listdir(AFTER) if f.lower().endswith(".png"))
for name in files:
    bp = os.path.join(THEME_FIX, name)
    ap = os.path.join(AFTER, name)
    if not os.path.exists(bp):
        continue
    b, a = load(bp), load(ap)
    if b.shape != a.shape:
        lines.append(f"{name}: size mismatch {b.shape} vs {a.shape}")
        continue
    diff = np.abs(a - b).max(axis=2) > 6
    n = int(diff.sum())
    if n == 0:
        lines.append(f"{name}: IDENTICAL")
        continue
    ys, xs = np.nonzero(diff)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()) + 1, int(ys.min()), int(ys.max()) + 1
    # dominant colour pairs on changed pixels (sampled to keep it fast)
    idx = np.flatnonzero(diff.ravel())[:: max(1, n // 60000)]
    by = b.reshape(-1, 3)[idx]
    ay = a.reshape(-1, 3)[idx]
    pairs = Counter((tuple(int(v) for v in bb), tuple(int(v) for v in aa)) for bb, aa in zip(by, ay))
    top = "; ".join("#%02X%02X%02X->#%02X%02X%02X x%d" % (p[0][0], p[0][1], p[0][2], p[1][0], p[1][1], p[1][2], c)
                    for p, c in pairs.most_common(3))
    lines.append(
        f"{name}: changed={n}px ({n / diff.size * 100:.3f}%) "
        f"bbox=px[{x0},{y0}]-[{x1},{y1}] dp[{x0 / SCALE:.1f},{y0 / SCALE:.1f}]-[{x1 / SCALE:.1f},{y1 / SCALE:.1f}] "
        f"({(x1 - x0) / SCALE:.1f}x{(y1 - y0) / SCALE:.1f}dp) top_pairs={top}")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
print("\nwrote", OUT)
