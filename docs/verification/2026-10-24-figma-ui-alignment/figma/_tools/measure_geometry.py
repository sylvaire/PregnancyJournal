"""Measure component geometry from exported PNGs (scale 2 -> divide by 2 for dp).

Coordinates below are absolute frame coords (metadata y + container offset), verified
against the pixel content. Read-only evidence for
docs/verification/2026-10-24-figma-ui-alignment/.
"""
import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SCREENS = os.path.normpath(os.path.join(HERE, "..", "screens"))


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def corner_radius(img, color, x, y, w, h):
    """Return for a filled rounded rect: top inset, radius (px), full width (px)."""
    px = img.load()
    rows = []
    for dy in range(0, h):
        yy = y + dy
        xs = [xx for xx in range(x, x + w) if px[xx, yy] == color]
        if xs:
            rows.append((dy, len(xs), min(xs), max(xs)))
    if not rows:
        return None
    full = max(r[1] for r in rows)
    for dy, n, lo, hi in rows:
        if n >= full - 2:
            return {
                "topInsetDp": round(dy / 2, 1),
                "fullWidthPx": full,
                "fullWidthDp": round(full / 2, 1),
                "row0WidthPx": rows[0][1],
                "radiusDpIfCircleApprox": round(dy / 2, 1),
                "rows": len(rows),
                "heightDp": round(len(rows) / 2, 1),
            }
    return None


def vertical_runs(img, color, x, y0, y1, min_px=20):
    px = img.load()
    runs, cur, start = [], 0, None
    for y in range(y0, y1):
        if px[x, y] == color:
            if start is None:
                start = y
            cur += 1
        else:
            if cur >= min_px:
                runs.append({"yDp": round(start / 2, 1), "hDp": round(cur / 2, 1)})
            cur, start = 0, None
    if cur >= min_px:
        runs.append({"yDp": round(start / 2, 1), "hDp": round(cur / 2, 1)})
    return runs


def main():
    out = {}
    primary, soft, surface = rgb("#356B53"), rgb("#E7F0E6"), rgb("#FFFFFF")

    welcome = Image.open(os.path.join(SCREENS, "01-Welcome.png")).convert("RGB")
    # 创建我的孕期档案: content y=500 + 130 = 630 design -> 1260 px @2x
    out["welcomePrimaryButton"] = corner_radius(welcome, primary, 48, 1260, 728, 104)
    # 恢复已有资料: content y=564 + 130 = 694
    out["welcomeSecondaryButton"] = corner_radius(welcome, soft, 48, 1388, 728, 104)
    # record row: content y=332/416 + 130 -> 462/546
    out["welcomeRecordRow"] = {
        "whiteRunsAtX400": vertical_runs(welcome, surface, 400, 800, 1400, 60)
    }

    today = Image.open(os.path.join(SCREENS, "03-Today.png")).convert("RGB")
    out["todaySurfaceRunsAtX20Dp_plus40px"] = vertical_runs(today, surface, 40, 260, 1600, 100)
    # pregnancy summary card bg soft (surface #fff inside); measure summary height
    out["todaySoftRunsAtX400"] = vertical_runs(today, soft, 400, 260, 1600, 60)

    # health selected tab: y=0+130=130 design -> 260 px, height 48 -> 96
    health = Image.open(os.path.join(SCREENS, "11-Health.png")).convert("RGB")
    out["healthSelectedTab"] = corner_radius(health, primary, 48, 260, 128, 96)

    print(json.dumps(out, ensure_ascii=False, indent=2))
    with open(os.path.join(HERE, "geometry-evidence.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
