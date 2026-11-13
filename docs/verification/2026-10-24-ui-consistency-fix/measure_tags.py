"""Quantify the P0-1 (JournalTag) / P0-3 (JournalButton) / P0-6 (24 dp page margin) fixes.

Inputs (read-only):
  docs/verification/2026-10-24-ui-consistency-fix/after/               (new, 22 screenshots)
  docs/verification/2026-10-24-ui-consistency-fix/after-supplementary/ (new, 9 screenshots)
  docs/verification/2026-10-24-ui-consistency-fix/navigation/          (new, navigation suite shots)
  docs/verification/2026-10-24-ui-theme-fix/after/                     (before: post-P0-2 build)
  docs/verification/2026-10-24-figma-ui-alignment/app-screens/         (before: pre-P0-2 build)

Device pixel ratio: 1080 px / 411.4286 dp = 2.625 px/dp (1080x2400 @ 420 dpi).
"""

import hashlib
import json
import os
import sys
from collections import Counter

import numpy as np
from PIL import Image

BASE = os.path.dirname(os.path.abspath(__file__))  # .../2026-10-24-ui-consistency-fix
ROOT = os.path.dirname(BASE)                       # .../docs/verification
SCALE = 2.625

NEW_DIRS = {
    "after": os.path.join(BASE, "after"),
    "after-supplementary": os.path.join(BASE, "after-supplementary"),
    "navigation": os.path.join(BASE, "navigation"),
}
BEFORE_DIRS = {
    "theme-fix": os.path.join(ROOT, "2026-10-24-ui-theme-fix", "after"),
    "figma-alignment": os.path.join(ROOT, "2026-10-24-figma-ui-alignment", "app-screens"),
}

TOKENS = {
    "primary": (0x35, 0x6B, 0x53),
    "soft": (0xE7, 0xF0, 0xE6),
    "bg": (0xF7, 0xF8, 0xF3),
    "white": (0xFF, 0xFF, 0xFF),
    "outline": (0xDD, 0xE4, 0xDA),
    "muted": (0x5F, 0x71, 0x66),
    "ink": (0x22, 0x3C, 0x32),
}

# screens used for the tag (P0-1) measurement
TAG_SCREENS = [
    ("after", "timeline.png"),
    ("after", "trash-empty.png"),
    ("after", "security-settings.png"),
    ("after-supplementary", "02-visits.png"),
    ("after-supplementary", "06-intakes.png"),
    ("after-supplementary", "07-measurements.png"),
    ("after-supplementary", "08-visit-documents.png"),
    ("navigation", "c04-timeline.png"),
]
# screens used for button (P0-3) measurement
BUTTON_SCREENS = [
    ("after", "local-backup.png"),
    ("after", "restore-wizard.png"),
    ("after", "restore-wizard-key.png"),
    ("after", "export-screen.png"),
    ("after", "export-screen-disabled.png"),
]
# screens used for page-margin (P0-6) measurement: all coverage shots, printed as a table
MARGIN_SKIP = set()


def load(path):
    return np.asarray(Image.open(path).convert("RGB"))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def color_mask(img, rgb):
    return (img[:, :, 0] == rgb[0]) & (img[:, :, 1] == rgb[1]) & (img[:, :, 2] == rgb[2])


def components(mask, min_area=1):
    """Connected components (4-neighbour) of a boolean mask, returned as bboxes."""
    h, w = mask.shape
    parent = []
    boxes = []  # [x0, y0, x1, y1, area]

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra
            boxes[ra][0] = min(boxes[ra][0], boxes[rb][0])
            boxes[ra][1] = min(boxes[ra][1], boxes[rb][1])
            boxes[ra][2] = max(boxes[ra][2], boxes[rb][2])
            boxes[ra][3] = max(boxes[ra][3], boxes[rb][3])
            boxes[ra][4] += boxes[rb][4]

    prev_runs = []  # (x0, x1_exclusive, node)
    for y in range(h):
        row = mask[y]
        idx = np.flatnonzero(row)
        if idx.size == 0:
            prev_runs = []
            continue
        splits = np.flatnonzero(np.diff(idx) > 1)
        starts = np.concatenate(([0], splits + 1))
        ends = np.concatenate((splits + 1, [idx.size]))
        runs = []
        for s, e in zip(starts, ends):
            x0 = int(idx[s])
            x1 = int(idx[e - 1]) + 1
            node = len(parent)
            parent.append(node)
            boxes.append([x0, y, x1, y + 1, x1 - x0])
            runs.append((x0, x1, node))
        # union overlapping runs with previous row
        i = j = 0
        while i < len(prev_runs) and j < len(runs):
            px0, px1, pn = prev_runs[i]
            cx0, cx1, cn = runs[j]
            if px1 <= cx0:
                i += 1
            elif cx1 <= px0:
                j += 1
            else:
                union(pn, cn)
                if px1 <= cx1:
                    i += 1
                else:
                    j += 1
        prev_runs = runs
    result = []
    for node in range(len(parent)):
        if find(node) != node:
            continue
        x0, y0, x1, y1, area = boxes[node]
        if area >= min_area:
            result.append({"x0": x0, "y0": y0, "x1": x1, "y1": y1,
                           "w": x1 - x0, "h": y1 - y0, "area": area})
    return result


def in_bbox(mask, box):
    return int(mask[box["y0"]:box["y1"], box["x0"]:box["x1"]].sum())


def corner_void(mask, box, box_size=32):
    """Pixels inside the top-left box_size square that are NOT part of the shape."""
    x0, y0 = box["x0"], box["y0"]
    region = mask[y0:y0 + box_size, x0:x0 + box_size]
    return int((~region).sum())


def top_row_inset(mask, box):
    row = mask[box["y0"]]
    xs = np.flatnonzero(row[box["x0"]:box["x1"]])
    return int(xs[0]) if xs.size else -1


def long_color_runs(img, rgb, min_len=900, y_min=200, y_max=None):
    """Row-wise runs of an exact colour with length >= min_len."""
    mask = color_mask(img, rgb)
    if y_max is None:
        y_max = img.shape[0]
    out = []
    for y in range(y_min, y_max):
        idx = np.flatnonzero(mask[y])
        if idx.size == 0:
            continue
        splits = np.flatnonzero(np.diff(idx) > 1)
        starts = np.concatenate(([0], splits + 1))
        ends = np.concatenate((splits + 1, [idx.size]))
        for s, e in zip(starts, ends):
            x0 = int(idx[s])
            x1 = int(idx[e - 1]) + 1
            if x1 - x0 >= min_len:
                out.append({"y": y, "x0": x0, "x1": x1, "w": x1 - x0})
                break  # first long run per row is enough
    return out


def run_to_blocks(runs):
    """Group per-row long runs into vertical blocks of the same x0/x1."""
    blocks = []
    for r in runs:
        if blocks and r["y"] == blocks[-1]["y_end"] + 1 and abs(r["x0"] - blocks[-1]["x0"]) <= 1 and abs(r["x1"] - blocks[-1]["x1"]) <= 1:
            blocks[-1]["y_end"] = r["y"]
        else:
            blocks.append({"x0": r["x0"], "x1": r["x1"], "y_start": r["y"], "y_end": r["y"]})
    for b in blocks:
        b["h"] = b["y_end"] - b["y_start"] + 1
        b["w"] = b["x1"] - b["x0"]
    return blocks


def page_margin(img):
    """Most common leftmost non-background x over content rows (below the app bar)."""
    bg = np.array(TOKENS["bg"], dtype=np.int16)
    diff = np.abs(img.astype(np.int16) - bg).max(axis=2)
    nonbg = diff > 8
    h, w = nonbg.shape
    xs = Counter()
    for y in range(300, h - 80):
        idx = np.flatnonzero(nonbg[y])
        if idx.size == 0:
            continue
        x = int(idx[0])
        if x < 200:  # ignore full-bleed rows and right-aligned-only rows
            xs[x] += 1
    if not xs:
        return None
    x0, votes = xs.most_common(1)[0]
    return {"x0_px": x0, "x0_dp": round(x0 / SCALE, 2), "votes": votes, "rows": int(sum(xs.values()))}


def tag_metrics(img, selected_color, sel_min_h, sel_max_h):
    mask = color_mask(img, selected_color)
    comps = components(mask, min_area=50)
    white_mask = color_mask(img, TOKENS["white"])
    primary_mask = color_mask(img, TOKENS["primary"])
    muted_mask = color_mask(img, TOKENS["muted"])
    tags = []
    for b in comps:
        if sel_min_h <= b["h"] <= sel_max_h and b["w"] <= 700:
            white = in_bbox(white_mask, b)
            primary = in_bbox(primary_mask, b)
            muted = in_bbox(muted_mask, b)
            tags.append({
                "x0_dp": round(b["x0"] / SCALE, 1), "y0_dp": round(b["y0"] / SCALE, 1),
                "w_dp": round(b["w"] / SCALE, 1), "h_dp": round(b["h"] / SCALE, 1),
                "fill_px": b["area"], "white_text_px": white, "primary_text_px": primary,
                "muted_text_px": muted,
                "white_ratio_of_bbox": round(white / float(b["w"] * b["h"]), 3),
                "corner_void_px": corner_void(mask, b),
                "top_row_inset_px": top_row_inset(mask, b),
            })
    return tags


def unselected_tag_metrics(img):
    mask = color_mask(img, TOKENS["outline"])
    comps = components(mask, min_area=40)
    tags = []
    white_mask = color_mask(img, TOKENS["white"])
    muted_mask = color_mask(img, TOKENS["muted"])
    for b in comps:
        if 118 <= b["h"] <= 134 and b["w"] <= 700:
            white = in_bbox(white_mask, b)
            muted = in_bbox(muted_mask, b)
            tags.append({
                "x0_dp": round(b["x0"] / SCALE, 1), "y0_dp": round(b["y0"] / SCALE, 1),
                "w_dp": round(b["w"] / SCALE, 1), "h_dp": round(b["h"] / SCALE, 1),
                "border_px": b["area"], "white_fill_px": white,
                "white_ratio_of_bbox": round(white / float(b["w"] * b["h"]), 3),
                "muted_text_px": muted,
                "corner_void_px": corner_void(mask, b),
                "top_row_inset_px": top_row_inset(mask, b),
            })
    return tags


def wide_blocks(img, h_min=90, h_max=170, y_min=160, count_threshold=400, x_lo=40, x_hi=1040):
    """Detect full-width elements (buttons/cards) by per-row dominant non-background colour.

    Works for pills whose rounded ends narrow the run, and for rows interrupted by centred labels.
    """
    bgp = (TOKENS["bg"][0] << 16) | (TOKENS["bg"][1] << 8) | TOKENS["bg"][2]
    whitep = (TOKENS["white"][0] << 16) | (TOKENS["white"][1] << 8) | TOKENS["white"][2]
    h, w = img.shape[:2]
    rows = []
    for y in range(y_min, h):
        seg = img[y, x_lo:min(x_hi, w)]
        packed = (seg[:, 0].astype(np.uint32) << 16) | (seg[:, 1].astype(np.uint32) << 8) | seg[:, 2].astype(np.uint32)
        vals, counts = np.unique(packed, return_counts=True)
        best_c, best_n = None, 0
        for c, n in zip(vals, counts):
            ci = int(c)
            if ci == bgp or ci == whitep:
                continue
            if int(n) > best_n:
                best_c, best_n = ci, int(n)
        if best_c is None or best_n < count_threshold:
            continue
        idx = np.flatnonzero(packed == best_c)
        x0 = int(idx[0]) + x_lo
        x1 = int(idx[-1]) + x_lo + 1
        rows.append((y, best_c, best_n, x0, x1))

    blocks = []
    for y, c, n, x0, x1 in rows:
        if blocks and blocks[-1]["color"] == c and y - blocks[-1]["y_end"] <= 3:
            b = blocks[-1]
            b["y_end"] = y
            b["x0"] = min(b["x0"], x0)
            b["x1"] = max(b["x1"], x1)
            b["max_count"] = max(b["max_count"], n)
        else:
            blocks.append({"color": c, "x0": x0, "x1": x1, "y_start": y, "y_end": y, "max_count": n})
    out = []
    for b in blocks:
        b["h"] = b["y_end"] - b["y_start"] + 1
        b["w"] = b["x1"] - b["x0"]
        if h_min <= b["h"] <= h_max and b["w"] >= 700:
            out.append({"h_px": b["h"], "h_dp": round(b["h"] / SCALE, 2),
                        "w_px": b["w"], "w_dp": round(b["w"] / SCALE, 2),
                        "x0_px": b["x0"], "x0_dp": round(b["x0"] / SCALE, 2),
                        "y0_px": b["y_start"], "color": "#%02X%02X%02X" % ((b["color"] >> 16) & 0xFF, (b["color"] >> 8) & 0xFF, b["color"] & 0xFF),
                        "max_row_count": b["max_count"]})
    return out


def outline_rings(img, y_min=160):
    """Full-width 1 dp outline border rings (native OutlinedButton / text fields)."""
    mask = color_mask(img, TOKENS["outline"])
    out = []
    for b in components(mask, min_area=200):
        if b["w"] >= 850 and b["y0"] >= y_min and 40 <= b["h"] <= 260:
            out.append({"h_px": b["h"], "h_dp": round(b["h"] / SCALE, 2),
                        "w_px": b["w"], "w_dp": round(b["w"] / SCALE, 2),
                        "x0_px": b["x0"], "x0_dp": round(b["x0"] / SCALE, 2),
                        "y0_px": b["y0"]})
    return out


def button_metrics(img):
    blocks = wide_blocks(img)
    out = {"primary_full_width": [], "soft_full_width": [], "outline_full_width": [], "other_full_width": [],
           "outline_rings": outline_rings(img)}
    for b in blocks:
        if b["color"] == "#356B53":
            out["primary_full_width"].append(b)
        elif b["color"] == "#E7F0E6":
            out["soft_full_width"].append(b)
        elif b["color"] == "#DDE4DA":
            out["outline_full_width"].append(b)
        else:
            out["other_full_width"].append(b)
    return out


def white_cards(img):
    """Full-width white JournalCard boxes (x0 = page margin for card-based pages)."""
    mask = color_mask(img, TOKENS["white"])
    out = []
    h = img.shape[0]
    for b in components(mask, min_area=5000):
        if b["w"] >= 850 and b["h"] >= 100 and 200 <= b["y0"] <= h - 330:
            out.append({"x0_px": b["x0"], "x0_dp": round(b["x0"] / SCALE, 2),
                        "w_px": b["w"], "w_dp": round(b["w"] / SCALE, 2),
                        "h_px": b["h"], "h_dp": round(b["h"] / SCALE, 2),
                        "y0_px": b["y0"]})
    return out


def analyze(path):
    img = load(path)
    res = {"path": path, "size": list(img.shape[1::-1]), "sha256": sha256(path)}
    res["page_margin"] = page_margin(img)
    res["white_cards"] = white_cards(img)
    res["tags_selected"] = tag_metrics(img, TOKENS["primary"], 118, 134)
    res["tags_unselected"] = unselected_tag_metrics(img)
    res["tag_candidates_soft_old"] = tag_metrics(img, TOKENS["soft"], 74, 94)  # old M3 FilterChip height 31.6dp
    res["buttons"] = button_metrics(img)
    res["token_counts"] = {k: int(color_mask(img, v).sum()) for k, v in TOKENS.items()}
    return res


def main():
    report = {"scale_px_per_dp": SCALE, "dirs": {}, "files": {}}
    all_files = {}
    for label, d in list(NEW_DIRS.items()) + list(BEFORE_DIRS.items()):
        if not os.path.isdir(d):
            report["dirs"][label] = {"path": d, "exists": False}
            continue
        names = sorted(f for f in os.listdir(d) if f.lower().endswith(".png"))
        report["dirs"][label] = {"path": d, "exists": True, "count": len(names), "files": names}
        for n in names:
            all_files[(label, n)] = os.path.join(d, n)
    for (label, name), p in all_files.items():
        report["files"].setdefault(label, {})[name] = analyze(p)

    # focused summaries
    tag_summary = {}
    for label, name in TAG_SCREENS:
        key = (label, name)
        if key in all_files:
            d = report["files"][label][name]
            tag_summary[f"{label}/{name}"] = {
                "selected_tags": d["tags_selected"],
                "unselected_tags": d["tags_unselected"],
                "old_soft_32dp_chips": d["tag_candidates_soft_old"],
            }
    btn_summary = {}
    for label, name in BUTTON_SCREENS:
        key = (label, name)
        if key in all_files:
            btn_summary[f"{label}/{name}"] = report["files"][label][name]["buttons"]
    margin_summary = {}
    for label in NEW_DIRS:
        if not report["dirs"].get(label, {}).get("exists"):
            continue
        for name in report["dirs"][label]["files"]:
            m = report["files"][label][name]["page_margin"]
            if m:
                margin_summary[f"{label}/{name}"] = m
    before_margin = {}
    for label in BEFORE_DIRS:
        if not report["dirs"].get(label, {}).get("exists"):
            continue
        for name in report["dirs"][label]["files"]:
            m = report["files"][label][name]["page_margin"]
            if m:
                before_margin[f"{label}/{name}"] = m
    report["summary"] = {"tags": tag_summary, "buttons": btn_summary,
                         "page_margin_after": margin_summary, "page_margin_before": before_margin}

    out_json = os.path.join(BASE, "measurements.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print("wrote", out_json)
    return report


if __name__ == "__main__":
    main()
