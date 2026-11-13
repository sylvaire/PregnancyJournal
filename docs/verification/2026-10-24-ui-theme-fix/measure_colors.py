"""P0-2 M3 容器色主题修复：after 截图颜色测量（Pillow/numpy）。

用法（仓库根目录）：
    C:\\Users\\sun\\.dsh\\dsh-runtimes\\dsh-primary-runtime\\dependencies\\python\\python.exe ^
        docs\\verification\\2026-10-24-ui-theme-fix\\measure_colors.py

- after：docs/verification/2026-10-24-ui-theme-fix/after/（2026-10-24 04:19 本次运行，22 张）
- before：docs/verification/2026-10-24-figma-ui-alignment/app-screens/（2026-10-24 02:34 同测试类、同模拟器，22 张）
- 设备：emulator-5556 / API36 / 1080x2400 / 420 dpi => 1 dp = 1080/411.4286 = 2.625 px

测量口径：精确颜色计数（tol=0，PNG 为 Compose 实填色）+ bbox + 连通域（BFS，用于 Switch 轨道）。
"""
from PIL import Image
import numpy as np
import collections
import hashlib
import json
import os
import sys
from collections import deque

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
os.chdir(ROOT)

AFTER = "docs/verification/2026-10-24-ui-theme-fix/after"
BEFORE = "docs/verification/2026-10-24-figma-ui-alignment/app-screens"
D_APP = 1080 / 411.4286  # 2.625 px/dp
TOTAL_PX = 1080 * 2400

# M3 1.2 基线（淡紫灰）容器/控件色：修复前 dialog/menu/switch 等的默认值，均不在设计令牌色板内。
LAVENDER = {
    "surfaceContainerHighest": "E6E0E9",
    "surfaceContainerHigh": "ECE6F0",
    "surfaceContainer": "F3EDF7",
    "surfaceContainerLow": "F7F2FA",
    "surfaceContainerLowest": "FFFFFF",  # 基线为白，不算离板；不纳入离板色统计
    "secondaryContainer": "E8DEF8",
    "primaryContainer": "EADDFF",
    "tertiaryContainer": "FFD8E4",
    "onSurfaceVariant": "49454F",
    "outline": "938F99",
    "outlineVariant": "CAC4D0",
    "surfaceTint": "6750A4",
    "inverseSurface": "322F35",
    "inversePrimary": "D0BCFF",
}
# surfaceBright 基线 #FFFBFF 属近白色（与白仅差 G 通道 4），单独以「连通域大小」判别是残留填充还是文字抗锯齿，
# 不计入“淡紫灰容器色”主扫描。
OFF_PALETTE_KEYS = [k for k in LAVENDER if k not in ("surfaceContainerLowest", "surfaceBright")]
NEAR_WHITE = "FFFBFF"

TOKENS = {
    "Background": "F7F8F3",
    "Surface(White)": "FFFFFF",
    "Soft": "E7F0E6",
    "Outline": "DDE4DA",
    "Primary": "356B53",
    "Ink": "223C32",
    "Muted": "5F7166",
    "Danger": "A44238",
    "Apricot": "FAEAD8",
    "Amber": "80582C",
}


def hex2rgb(h):
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.int16)


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)


def mask_of(arr, hex_color, tol=0):
    c = hex2rgb(hex_color)
    return (np.abs(arr - c).max(axis=2) <= tol)


def bbox_dp(mask):
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return None
    return {
        "px": [int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())],
        "w_px": int(xs.max() - xs.min() + 1),
        "h_px": int(ys.max() - ys.min() + 1),
        "w_dp": round((xs.max() - xs.min() + 1) / D_APP, 1),
        "h_dp": round((ys.max() - ys.min() + 1) / D_APP, 1),
        "n_px": int(mask.sum()),
        "pct": round(100 * int(mask.sum()) / TOTAL_PX, 2),
    }


def bbox_dp_region(mask, x0, y0, x1, y1):
    """限制在指定 px 区域内的 bbox（用于整屏截图中的对话框面，排除状态栏等其它白色元素）。"""
    sub = np.zeros_like(mask, dtype=bool)
    sub[y0:y1 + 1, x0:x1 + 1] = mask[y0:y1 + 1, x0:x1 + 1]
    return bbox_dp(sub)


def top_colors(arr, n=12):
    flat = arr.reshape(-1, 3).astype(np.uint8)
    c = collections.Counter(map(tuple, flat))
    return [("#%02X%02X%02X" % col, round(100 * cnt / TOTAL_PX, 2), cnt) for col, cnt in c.most_common(n)]


def components(mask, min_area=500):
    """BFS 4 邻域连通域，仅用于小面积色（Switch 轨道）。"""
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return []
    h, w = mask.shape
    visited = np.zeros_like(mask, dtype=bool)
    out = []
    for x0, y0 in zip(xs, ys):
        if visited[y0, x0]:
            continue
        q = deque([(x0, y0)])
        visited[y0, x0] = True
        pts = []
        while q:
            x, y = q.popleft()
            pts.append((x, y))
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and mask[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    q.append((nx, ny))
        if len(pts) >= min_area:
            px = [p[0] for p in pts]
            py = [p[1] for p in pts]
            out.append({
                "x0": min(px), "y0": min(py), "x1": max(px), "y1": max(py),
                "w_px": max(px) - min(px) + 1, "h_px": max(py) - min(py) + 1,
                "w_dp": round((max(px) - min(px) + 1) / D_APP, 1),
                "h_dp": round((max(py) - min(py) + 1) / D_APP, 1),
                "n_px": len(pts),
            })
    out.sort(key=lambda c: -c["n_px"])
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    after_files = sorted(f for f in os.listdir(AFTER) if f.endswith(".png"))
    before_files = sorted(f for f in os.listdir(BEFORE) if f.endswith(".png"))
    report = {
        "after_count": len(after_files),
        "before_count": len(before_files),
        "before_files_missing_after": sorted(set(before_files) - set(after_files)),
        "after_files_not_in_before": sorted(set(after_files) - set(before_files)),
        "d_ppd": D_APP,
    }
    print("=" * 100)
    print("0. 文件核对")
    print("=" * 100)
    print(f"after={len(after_files)} before={len(before_files)} delta={report['before_files_missing_after']}/{report['after_files_not_in_before']}")

    # ---------- 1. 逐文件 before/after 哈希 + 差异定位 ----------
    print("\n" + "=" * 100)
    print("1. before -> after 变更文件（sha256）与差异 bbox（定位变更只发生在容器色区域）")
    print("=" * 100)
    changed = []
    for name in after_files:
        bp = os.path.join(BEFORE, name)
        ap = os.path.join(AFTER, name)
        if not os.path.exists(bp):
            print(f"{name}: NO BEFORE")
            continue
        hb, ha = sha256(bp), sha256(ap)
        if hb == ha:
            print(f"{name}: IDENTICAL")
            continue
        b, a = load(bp), load(ap)
        diff = (np.abs(a - b).max(axis=2) > 2)
        ys, xs = np.where(diff)
        entry = {
            "file": name,
            "sha_before": hb,
            "sha_after": ha,
            "diff_px": int(diff.sum()),
            "diff_pct": round(100 * int(diff.sum()) / TOTAL_PX, 3),
            "diff_bbox_px": [int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())],
            "diff_bbox_dp": [
                round(xs.min() / D_APP, 1), round(ys.min() / D_APP, 1),
                round((xs.max() - xs.min() + 1) / D_APP, 1), round((ys.max() - ys.min() + 1) / D_APP, 1),
            ],
        }
        changed.append(entry)
        print(f"{name}: CHANGED diff_px={entry['diff_px']} ({entry['diff_pct']}%) bbox_dp={entry['diff_bbox_dp']}")
    report["changed_files"] = changed
    report["unchanged_files"] = sorted(set(after_files) - {c["file"] for c in changed})

    # ---------- 2. 关键色测量 ----------
    def measure(name, color_hex, tol=0):
        if not os.path.exists(os.path.join(AFTER, name)):
            return None
        a = load(os.path.join(AFTER, name))
        m = mask_of(a, color_hex, tol)
        return bbox_dp(m)

    print("\n" + "=" * 100)
    print("2. 关键界面 before -> after 颜色测量")
    print("=" * 100)

    # 2.1 对话框面（custom-reminder-editor.png，UiAutomation 整屏，含 dim 遮罩）
    print("\n-- 2.1 custom-reminder-editor.png（自定义提醒对话框，整屏含 dim）--")
    before_dialog = load(os.path.join(BEFORE, "custom-reminder-editor.png"))
    after_dialog = load(os.path.join(AFTER, "custom-reminder-editor.png"))
    dialog = {}
    for tag, arr in (("before", before_dialog), ("after", after_dialog)):
        tops = top_colors(arr, 4)
        print(f"{tag} top4: {tops}")
        dialog[tag] = {"top4": tops}
    dialog["before"]["ECE6F0"] = bbox_dp(mask_of(before_dialog, "ECE6F0"))
    # 终版主题：surfaceContainerHigh=White，AlertDialog 容器应为 #FFFFFF；同时保留初版候选 #E7F0E6 的检查。
    dialog["before"]["top_color_bbox"] = bbox_dp(mask_of(before_dialog, dialog["before"]["top4"][0][0][1:]))
    dialog["after"]["top_color_bbox"] = bbox_dp(mask_of(after_dialog, dialog["after"]["top4"][0][0][1:]))
    dialog["after"]["FFFFFF"] = bbox_dp(mask_of(after_dialog, "FFFFFF"))
    dialog["after"]["E7F0E6"] = bbox_dp(mask_of(after_dialog, "E7F0E6"))
    dialog["after"]["ECE6F0"] = bbox_dp(mask_of(after_dialog, "ECE6F0"))
    dialog["after"]["E6E0E9"] = bbox_dp(mask_of(after_dialog, "E6E0E9"))
    # 整屏 UiAutomation 截图含系统状态栏；对话框面颜色 bbox 限定在对话框区域内测量。
    dialog["before"]["surface_bbox_region"] = bbox_dp_region(
        mask_of(before_dialog, dialog["before"]["top4"][0][0][1:]), 100, 540, 980, 1910)
    dialog["after"]["surface_bbox_region"] = bbox_dp_region(
        mask_of(after_dialog, dialog["after"]["top4"][0][0][1:]), 100, 540, 980, 1910)
    print(f"before dialog surface {dialog['before']['top4'][0][0]} bbox(dialog region): {dialog['before']['surface_bbox_region']}")
    print(f"after  dialog surface {dialog['after']['top4'][0][0]} bbox(dialog region): {dialog['after']['surface_bbox_region']}")
    print(f"after  #FFFFFF all={dialog['after']['FFFFFF']}  #E7F0E6={dialog['after']['E7F0E6']}")
    print(f"after  #ECE6F0 remaining: {dialog['after']['ECE6F0']}  #E6E0E9 remaining: {dialog['after']['E6E0E9']}")
    report["dialog"] = dialog

    # 2.2 Switch 未选中轨道（export-screen.png）
    print("\n-- 2.2 export-screen.png（导出资料：M3 Switch 未选中轨道）--")
    before_export = load(os.path.join(BEFORE, "export-screen.png"))
    after_export = load(os.path.join(AFTER, "export-screen.png"))
    switch = {}
    for tag, arr, color in (
        ("before_track", before_export, "E6E0E9"),
        ("after_track", after_export, "DDE4DA"),
        ("after_old_track", after_export, "E6E0E9"),
        ("before_new_track", before_export, "DDE4DA"),
    ):
        m = mask_of(arr, color)
        comp = components(m, min_area=2000)
        switch[tag] = {
            "color": "#" + color,
            "total_px": int(m.sum()),
            "total_pct": round(100 * int(m.sum()) / TOTAL_PX, 3),
            "components_ge2000px": comp,
        }
        print(f"{tag}: #{color} total={int(m.sum())}px ({switch[tag]['total_pct']}%) comps={[(c['w_dp'], c['h_dp'], c['n_px'], [c['x0'], c['y0']]) for c in comp]}")
    report["switch"] = switch

    # 2.3 提醒设置卡片底色
    print("\n-- 2.3 reminder-settings.png（提醒设置：JournalCard 底色）--")
    reminder = {}
    for tag, path in (("before", os.path.join(BEFORE, "reminder-settings.png")), ("after", os.path.join(AFTER, "reminder-settings.png"))):
        arr = load(path)
        reminder[tag] = {
            "top4": top_colors(arr, 4),
            "white": bbox_dp(mask_of(arr, "FFFFFF")),
            "background": bbox_dp(mask_of(arr, "F7F8F3")),
        }
        print(f"{tag} top4: {reminder[tag]['top4']}")
        print(f"{tag} white card coverage: {reminder[tag]['white']['pct']}%  background #F7F8F3: {reminder[tag]['background']['pct']}%")
    report["reminder_settings"] = reminder

    # ---------- 3. 全量离板色扫描（22 张 after） ----------
    print("\n" + "=" * 100)
    print("3. 22 张 after 截图中 M3 基线淡紫灰色（tol=2）扫描")
    print("=" * 100)
    scan = {}
    for name in after_files:
        arr = load(os.path.join(AFTER, name))
        hits = {}
        for key in OFF_PALETTE_KEYS:
            m = mask_of(arr, LAVENDER[key], tol=2)
            n = int(m.sum())
            if n:
                hits[key] = {"hex": "#" + LAVENDER[key], "px": n}
        scan[name] = hits
        if hits:
            print(f"{name}: {hits}")
    files_with_hits = {k: v for k, v in scan.items() if v}
    print(f"files_with_lavender_hits={len(files_with_hits)} / {len(after_files)}")
    report["lavender_scan"] = scan

    # 3.1 近白 #FFFBFF（M3 基线 surfaceBright）before/after 计数 + 最大连通域，判别文字抗锯齿 vs 残留填充
    print("\n-- 3.1 近白 #FFFBFF 单独判别（before/after 计数 + 最大连通域）--")
    near = {}
    for name in after_files:
        row = {}
        for tag, base in (("before", BEFORE), ("after", AFTER)):
            p = os.path.join(base, name)
            if not os.path.exists(p):
                continue
            arr = load(p)
            n = int(mask_of(arr, NEAR_WHITE).sum())
            comps = components(mask_of(arr, NEAR_WHITE), min_area=2) if 0 < n < 60000 else []
            row[tag] = {
                "px": n,
                "max_component_px": max((c["n_px"] for c in comps), default=0),
                "max_component_dp": [(c["w_dp"], c["h_dp"]) for c in comps[:1]],
            }
        near[name] = row
    for name, row in near.items():
        print(f"{name}: before={row.get('before')} after={row.get('after')}")
    report["near_white_fffbff"] = near

    # ---------- 4. 令牌色覆盖 ----------
    print("\n" + "=" * 100)
    print("4. 22 张 after 截图中设计令牌色的总像素（验证容器色回落到令牌色）")
    print("=" * 100)
    totals = collections.Counter()
    per_file = {}
    for name in after_files:
        arr = load(os.path.join(AFTER, name))
        per_file[name] = {}
        for key, hexv in TOKENS.items():
            n = int(mask_of(arr, hexv).sum())
            if n:
                totals[key] += n
                per_file[name][key] = n
    for key in TOKENS:
        print(f"{key} #{TOKENS[key]}: {totals[key]} px")
    report["token_totals"] = dict(totals)
    report["token_per_file"] = per_file

    # ---------- 5. 输出 JSON ----------
    out = "docs/verification/2026-10-24-ui-theme-fix/measure-output.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(
            report, f, ensure_ascii=False, indent=2,
            default=lambda o: float(o) if isinstance(o, np.floating) else
            int(o) if isinstance(o, np.integer) else str(o),
        )
    print(f"\nJSON -> {out}")


if __name__ == "__main__":
    main()
