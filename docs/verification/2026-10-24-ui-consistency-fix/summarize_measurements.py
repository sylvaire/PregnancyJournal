"""Print a compact human-readable summary of measurements.json (P0-1/P0-3/P0-6)."""
import json
import os

BASE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(BASE, "measurements.json"), encoding="utf-8") as f:
    report = json.load(f)

print("== dirs ==")
for k, v in report["dirs"].items():
    print(f"{k}: exists={v['exists']} count={v.get('count')}")

files = report["files"]
s = report["summary"]


def show_tags(label, name):
    d = files.get(label, {}).get(name)
    if not d:
        return
    sel = d["tags_selected"]
    uns = d["tags_unselected"]
    old = d["tag_candidates_soft_old"]
    print(f"{label}/{name}: selected={[(t['h_dp'], t['w_dp']) for t in sel]} "
          f"unselected_h={[t['h_dp'] for t in uns]} "
          f"old_soft_32dp={[(t['h_dp'], t['x0_dp'], t['y0_dp'], t['w_dp']) for t in old]}")


print("\n== P0-1 tag heights: before (theme-fix / figma-alignment) vs after ==")
for name in ["timeline.png", "trash-empty.png", "security-settings.png"]:
    show_tags("theme-fix", name)
    show_tags("figma-alignment", name)
    show_tags("after", name)
for name in ["02-visits.png", "06-intakes.png", "07-measurements.png", "08-visit-documents.png"]:
    show_tags("after-supplementary", name)
show_tags("navigation", "c04-timeline.png")

print("\n== P0-1 selected tag detail (after) ==")
for label, name in [("after", "timeline.png"), ("after", "trash-empty.png"), ("after", "security-settings.png"),
                    ("after-supplementary", "02-visits.png"), ("after-supplementary", "06-intakes.png"),
                    ("after-supplementary", "07-measurements.png"), ("after-supplementary", "08-visit-documents.png"),
                    ("navigation", "c04-timeline.png")]:
    d = files.get(label, {}).get(name)
    if not d:
        continue
    for t in d["tags_selected"]:
        print(f"{label}/{name}: h={t['h_dp']}dp w={t['w_dp']}dp x0={t['x0_dp']}dp y0={t['y0_dp']}dp "
              f"fill_px={t['fill_px']} white_text_px={t['white_text_px']} top_inset={t['top_row_inset_px']}px")
    for t in d["tags_unselected"]:
        print(f"{label}/{name}: UNSEL h={t['h_dp']}dp w={t['w_dp']}dp x0={t['x0_dp']}dp "
              f"white_fill_ratio={t['white_ratio_of_bbox']} muted_text_px={t['muted_text_px']} top_inset={t['top_row_inset_px']}px")

print("\n== P0-3 buttons: before (theme-fix) vs after ==")
for name in ["local-backup.png", "restore-wizard.png", "restore-wizard-key.png", "export-screen.png", "export-screen-disabled.png"]:
    for label in ("theme-fix", "after"):
        d = files.get(label, {}).get(name)
        if not d:
            continue
        print(f"{label}/{name}:")
        for k in ("primary_full_width", "soft_full_width", "outline_full_width", "other_full_width", "outline_rings"):
            for b in d["buttons"][k]:
                print(f"   {k}: h={b['h_dp']}dp w={b['w_dp']}dp x0={b['x0_dp']}dp y0={b['y0_px']} color={b.get('color', '-')}")

print("\n== P0-6 page margin: after ==")
for name, m in sorted(s["page_margin_after"].items()):
    print(f"{name}: x0={m['x0_dp']}dp ({m['x0_px']}px, votes={m['votes']}/{m['rows']})")

print("\n== P0-6 white card x0: after vs before(theme-fix) ==")
for label in ("after", "after-supplementary", "navigation"):
    for name in report["dirs"].get(label, {}).get("files", []):
        cards = files[label][name]["white_cards"]
        if cards:
            print(f"{label}/{name}: cards={[(c['x0_dp'], c['h_dp']) for c in cards]}")
print("-- before(theme-fix) --")
for name in report["dirs"].get("theme-fix", {}).get("files", []):
    cards = files["theme-fix"][name]["white_cards"]
    if cards:
        print(f"theme-fix/{name}: cards={[(c['x0_dp'], c['h_dp']) for c in cards]}")

print("\n== P0-6 page margin: before (figma-alignment = pre-P0-2; theme-fix = post-P0-2) ==")
for label in ("figma-alignment", "theme-fix"):
    print(f"-- {label} --")
    for name, m in sorted(s["page_margin_before"].items()):
        if not name.startswith(label + "/"):
            continue
        print(f"{name}: x0={m['x0_dp']}dp ({m['x0_px']}px)")

print("\n== sha256 before(theme-fix) vs after ==")
same = []
diff = []
for name in report["dirs"]["after"]["files"]:
    a = files["after"][name]["sha256"]
    b = files.get("theme-fix", {}).get(name, {}).get("sha256")
    if b is None:
        continue
    (same if a == b else diff).append(name)
print(f"identical={len(same)} changed={len(diff)}")
print("changed:", ", ".join(diff))
