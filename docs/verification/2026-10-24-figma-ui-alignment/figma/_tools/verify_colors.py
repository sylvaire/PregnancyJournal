"""Sample actual rendered colors from the exported Figma board PNGs.

Read-only evidence helper for docs/verification/2026-10-24-figma-ui-alignment/.
Verifies that the `color/*` variable values recorded in the file's build record
are actually rendered in the 2026-10-24 exports (824x1830, scale 2).
"""
import json
import os
from collections import Counter

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SCREENS = os.path.normpath(os.path.join(HERE, "..", "screens"))

# Current values from mcp__figma__get_variable_defs (node 12:138, 2026-10-24)
# plus creation-time palette for tokens not applied on 12:138.
TOKENS = {
    "color/bg": "#F7F8F3",
    "color/surface": "#FFFFFF",
    "color/ink": "#223C32",
    "color/muted": "#5F7166",          # changed 2026-10-18 from #65766D
    "color/primary": "#356B53",
    "color/soft": "#E7F0E6",
    "color/line": "#DDE4DA",
    "color/apricot": "#FAEAD8",
    "color/amber": "#80582C",
    "color/danger": "#A44238",
    "color/dangerSoft": "#FCECE7",
}


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def main():
    files = sorted(f for f in os.listdir(SCREENS) if f.endswith(".png"))
    result = {}
    for f in files:
        img = Image.open(os.path.join(SCREENS, f)).convert("RGB")
        px = list(img.getdata())
        counts = Counter(px)
        exact = {}
        for name, hx in TOKENS.items():
            rgb = hex2rgb(hx)
            n = counts.get(rgb, 0)
            if n:
                exact[name] = n
        # nearest-color check: sample dominant colors, report those within distance 6
        near = {}
        for rgb, n in counts.most_common(400):
            if n < 60:
                continue
            for name, hx in TOKENS.items():
                ref = hex2rgb(hx)
                if rgb == ref:
                    continue
                d = sum((a - b) ** 2 for a, b in zip(rgb, ref)) ** 0.5
                if d <= 6:
                    near.setdefault(name, []).append(
                        {"rgb": "#%02X%02X%02X" % rgb, "px": n, "dist": round(d, 2)})
        result[f] = {
            "size": list(img.size),
            "pixels": len(px),
            "distinctColors": len(counts),
            "exactTokenPixelCounts": exact,
            "nearTokenColors": near,
        }
        print(f, img.size, "distinct=", len(counts), "exact=", exact)
    out = os.path.join(HERE, "color-evidence.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
    print("wrote", out)


if __name__ == "__main__":
    main()
