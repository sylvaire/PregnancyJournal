# 测量证据与复现（Figma UI 对齐评审，2026-10-24）

本文件记录 `README.md` 中所有像素/几何结论的原始数据与复现方法。测量对象：

- 实现：`../app-screens/*.png`（1080×2400 px，420 dpi ⇒ **1 dp = 2.625 px**，屏宽 1080/2.625 = 411.4 dp）。
- 设计：`../figma/screens/*.png`（824×1830 px = 412×915 dp @2x ⇒ **1 dp = 2 px**）。
- 工具：Python 3 + Pillow（`C:\Users\sun\.dsh\dsh-runtimes\...\python.exe`），颜色统计 / 连通域 / 行扫描；未安装 scipy，连通域用 BFS（4 邻域、4× 下采样）。
- 环境基线：`../device-info-baseline.txt`（emulator-5556 / SDK 36 / 1080×2400 / 420 dpi / font_scale 1.0 / night no）。

> 说明：实现截图是 Compose 内容区（无系统状态栏/手势条），设计屏含状态栏/手势条/底部导航，故**只比相对几何**，不比绝对 y 坐标。

---

## 1. 颜色：实现截图完全使用设计令牌色

对 8 张实现截图做全图色值统计（`README.md` §3 G5 证据来源），前 8 主色：

| 截图 | 主色占屏（按面积） |
|---|---|
| `visit-detail.png` | `#F7F8F3` 47.7% / `#E7F0E6` 25.4% / `#FFFFFF` 18.7% / `#356B53` 5.3% / `#223C32` 0.5% / `#5F7166` 0.3% / `#A44238` 0.2% |
| `timeline.png` | `#F7F8F3` 48.3% / `#FFFFFF` 42.1% / `#356B53` 4.9% / `#223C32` 1.5% / `#E7F0E6` 0.5% / `#DDE4DA` 0.4% / `#5F7166` 0.3% |
| `local-backup.png` | `#F7F8F3` 91.5% / `#356B53` 3.8% / `#223C32` 1.9% / `#DDE4DA` 0.3% / `#5F7166` 0.1% |
| `cloud-connection.png` | `#F7F8F3` 89.4% / `#356B53` 2.3% / `#DDE1DB` 2.2% / `#223C32` 1.8% / `#DDE4DA` 1.0% / `#5F7166` 0.4% / `#96A29B` 0.1% |
| `trash-empty.png` | `#F7F8F3` 97.0% / `#E7F0E6` 1.1% / `#223C32` 0.6% / `#356B53` 0.1% / `#5F7166` 0.1% / `#DDE4DA` 0.1% |
| `journal-entry-editor.png` | `#F7F8F3` 91.6% / `#356B53` 4.8% / `#DDE4DA` 1.3% / `#223C32` 0.6% / `#5F7166` 0.3% / `#FFFFFF` 0.1% |
| `export-screen.png` | `#F7F8F3` 95.0% / `#223C32` 1.4% / `#356B53` 0.8% / `#DDE4DA` 0.6% / **`#E6E0E9` 0.2%** / `#5F7166` 0.2% |
| `custom-reminder-editor.png`（整屏含 dim 遮罩） | **`#ECE6F0` 40.7%** / `#636361` 28.8%（dim 后的背景内容）/ `#666666` 22.7%（dim 后的背景内容）/ `#595D59` 4.0% / `#0E1814` 0.5% / `#DDE4DA` 0.4% / `#356B53` 0.4% |

结论：

- 设计令牌 9 色（`#F7F8F3 / #FFFFFF / #223C32 / #5F7166 / #356B53 / #E7F0E6 / #DDE4DA / #FAEAD8 / #80582C`）+ `danger #A44238` 中，实现截图精确复现了除 `apricot/amber` 外的全部颜色（**`#A44238` 与 `color/danger` 一致**，用于危险文本按钮）。
- **`apricot (#FAEAD8)` / `amber (#80582C)` 在实现截图中 0 命中**（对应设计 12 时间线的日常备注卡，实现未使用）。
- **离板色**仅来自 M3 默认容器：`#ECE6F0`（dialog 面，320.0×501.3 dp 连通域，1055455 px）与 `#E6E0E9`（export 页未选中 Switch 轨道，47.6×27.4 dp 连通域，6376 px）——均不在设计色板中。

---

## 2. 组件几何（实现 vs 设计）

| 组件 | 设计（令牌/实测） | 实现（实测） | 判定 |
|---|---|---|---|
| 主按钮高度 | 52 dp（`welcomePrimaryButton.heightDp=52`；全部 29 个实例 364×52） | **52.2 dp**（`security-settings` 关闭应用锁 371.0×52.2；`cloud-connection` 测试连接 179.4×52.2；`reminder-settings-custom` 新建提醒 314.7×52.2） | ✅ 一致（`JournalButton`） |
| 主按钮宽度 | 364 dp（x=24） | `Inset` 页 363 dp / 新页面 371 dp（x0=20.2） | 宽度随页边距（见 G3） |
| 原生 M3 按钮高度 | 设计系统无此规格 | **40.0 dp**（`local-backup` 导出加密备份 371.0×40.0；`restore-wizard` 选择加密备份 371.0×40.0） | ❌ 与 52 dp 体系并存（G6） |
| 筛选标签高度 | **47.5 dp**（`11-Health` 选中标签实测；令牌 48） | **31.6 dp**（`timeline` 选中块 63.6×31.6；`trash-empty` 选中块 153.5×31.6；`security` 选中块 72.8×31.6） | ❌ 32 dp vs 48 dp（G2） |
| 选中标签颜色 | `#356B53` 填充（`04-Visits` 363.5×51.5 区域、`11-Health` 63.5×47.5） | `#E7F0E6` 浅绿底 + `#356B53` 文字（M3 `FilterChip` 默认 secondaryContainer） | ❌（G2） |
| 记录行 | 364×72、图标容器 44×44、圆角 20 | `RecordRow` = min 72 dp、图标容器 44 dp、圆角 20（`Components.kt:42-58`）；截图中 `visit-detail`/`timeline` 未见 72 dp 行（时间线改用大卡片） | ✅ 组件存在；❌ 12 屏未使用 |
| 卡片 | 白卡/浅绿卡 圆角 20、内边距 20 | `JournalCard` 圆角 20、padding 20/16（`Components.kt:36-40`）；若干新页面**不用卡片** | ✅ 组件合规；❌ 覆盖不足 |
| 页面左右边距 | 24 dp | 24.0 dp（`Inset` 系）；**20.2 dp**（Cloud/Export/Recovery/Restore/Security/JournalEntry/TimelineSource/MeasurementEditor）；**28.2 dp**（提醒设置 4+24 叠加） | ❌ 三套（G3） |
| 弹窗圆角/面 | 设计无弹窗画板 | AlertDialog 默认：圆角 28、容器 `#ECE6F0` | 面离板（G5） |
| 输入框高度 | 56 dp（`孕迹/Field`） | ≈56 dp（`visit-editor` 描边行间距：373→517 px = 54.9 dp） | ✅ |

> 设计基准来自 `../figma/_tools/geometry-evidence.json` 与本目录复测：`04-Visits` 主按钮 bbox 363.5×51.5 dp @x=24；`11-Health` 选中标签 63.5×47.5 dp；`03-Today` 记录行图标容器 43.5 dp。

---

## 3. 字形高度（字号推断）

| 对象 | 实测字形高 | 折算字号（按 0.93–0.95 字形/字号比） | 设计令牌 |
|---|---|---|---|
| 设计 05 页面标题 | 53 px @2x = **26.5 dp** | ≈28 sp | `孕迹/title` 28/42 Bold |
| 实现 `visit-detail` AppBar 标题 | 48 px @2.625 = **18.3 dp** | ≈20 sp | `titleLarge` 20/30 Medium（M3 TopAppBar 默认） |
| 设计 05 hero 标题 | 53 px = **26.5 dp** | ≈28 sp | `孕迹/title` |
| 实现 `visit-detail` hero 标题 | 70 px = **26.7 dp** | ≈28 sp | ✅ 与设计一致 |
| 设计 05 分区标题 | 31 px = **15.5 dp** | ≈20 sp Medium | `孕迹/heading` 20/30 Medium |
| 实现 `visit-detail` 分区标题 | 39 px = **14.9 dp** | ≈20 sp | ✅ 一致 |

→ 字号体系一致；**唯一系统性降级是“页面标题”**（28 → 20，G1）；hero/分区标题正确。

---

## 4. 复现脚本（Python + Pillow）

```python
from PIL import Image
import numpy as np, collections

APP = "docs/verification/2026-10-24-figma-ui-alignment/app-screens/"
FIG = "docs/verification/2026-10-24-figma-ui-alignment/figma/screens/"
D_APP = 1080 / 411.4286   # 2.625 px/dp
D_FIG = 2.0               # 824x1830 = 412x915dp @2x

def top_colors(path, n=8):
    im = Image.open(path).convert("RGB"); w, h = im.size
    c = collections.Counter(im.getdata()); tot = w * h
    return [("#%02X%02X%02X" % col, round(100 * cnt / tot, 1)) for col, cnt in c.most_common(n)]

def runs_at_row(path, y, color=(255, 255, 255), tol=2, minpx=100):
    a = np.array(Image.open(path).convert("RGB")).astype(int)
    row = a[y]; m = (np.abs(row - np.array(color)).max(axis=1) <= tol)
    xs = np.where(m)[0]
    if len(xs) == 0: return []
    out, s, p = [], xs[0], xs[0]
    for x in xs[1:]:
        if x != p + 1: out.append((s, p)); s = x
        p = x
    out.append((s, p))
    return [((s) / D_APP, (p - s + 1) / D_APP) for s, p in out if p - s > minpx]

def color_bbox(path, color_hex, tol=1):
    a = np.array(Image.open(path).convert("RGB")).astype(int)
    c = np.array([int(color_hex[i:i+2], 16) for i in (0, 2, 4)])
    m = (np.abs(a - c).max(axis=2) <= tol)
    ys, xs = np.where(m)
    if len(xs) == 0: return None
    return dict(px=(int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())), n=int(m.sum()),
                w_dp=round((xs.max()-xs.min()+1)/D_APP, 1), h_dp=round((ys.max()-ys.min()+1)/D_APP, 1))

# 例：
# runs_at_row(APP + "visit-detail.png", 1100)            -> 白卡 (24.0, 363.4) dp
# color_bbox(APP + "custom-reminder-editor.png", "ECE6F0") -> 320.0 x 501.3 dp 弹窗面
# color_bbox(APP + "export-screen.png", "E6E0E9")         -> 47.6 x 27.4 dp 未选中 Switch 轨道
# color_bbox(APP + "timeline.png", "E7F0E6")              -> 选中 FilterChip 31.6 dp 高
# top_colors(APP + "custom-reminder-editor.png")           -> #ECE6F0 40.7%
```

关键命令结果（已执行，2026-10-24）：

```text
visit-detail soft hero card bbox px (63,296)-(1016,891)  -> 24.0..387.0 dp（363 dp 宽，225 dp 高）
visit-detail white card @y=1100 x=63..1016              -> 左右边距 24.0 dp / 24.0 dp
timeline selected chip bbox                              -> 高 31.6 dp（浅绿 #E7F0E6）
security primary button bbox                             -> 371.0 x 52.2 dp，x0=20.2 dp
cloud primary button bbox                                -> 179.4 x 52.2 dp（并排两按钮之一）
local-backup primary button bbox                         -> 371.0 x 40.0 dp（原生 M3 Button）
restore-wizard primary button bbox                       -> 371.0 x 40.0 dp（原生 M3 Button）
custom-reminder-editor dialog surface #ECE6F0             -> 320.0 x 501.3 dp，占屏 40.7%
export-screen unchecked switch track #E6E0E9             -> 47.6 x 27.4 dp
visit-editor field outline rows                          -> 373/517, 552/697, 756/900, 959/1103, 1162/1306 px ⇒ 字段高 54.9 dp、间距 ~13.3 dp
design 04 primary button                                 -> 363.5 x 51.5 dp @x=24
design 11 selected tag                                   -> 63.5 x 47.5 dp
design 05 page title glyph                               -> 53 px @2x = 26.5 dp ⇒ 28 sp
app visit-detail appbar title glyph                      -> 48 px @2.625 = 18.3 dp ⇒ 20 sp
```
