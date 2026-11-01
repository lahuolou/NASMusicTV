"""量取「开关组件三选一」参考图（用户 2026-10-18 选定**中间**那款）的几何与配色。

用法：
    python scripts/measure_switch_reference.py <png路径>

只读图、不写文件、输出全 ASCII（Windows CP936 控制台会吃掉非 ASCII，见 AGENTS.md）。

## 为什么要脚本

`docs/settings-reorganization-plan.md` §5.2 的开关尺寸**只能由这张图量出来**，
不许凭眼看写「大概一半宽」。脚本自带自检：识别不到 3 个开关带、或中间带中线
白色游程 <3 段（左描边 / 滑块实心 / 右描边）即退出码 1 —— 空转的测量比没有测量更危险。

## 判读口径

- 「白」= min(R,G,B) >= 170（描边与滑块都是白，滑块是实心白）
- 滑块内的**活动标签**是**页面背景色**（深色字白底），故用 max(R,G,B) < 110 取字身，
  且必须**内缩 8px** 再取 —— 否则滑块圆角的抗锯齿边会污染 bbox（首版就踩了这个）
- 轨道右侧的**非活动标签**是中灰，用 95 < max < 210 取，且窗口必须**避开轨道右端圆弧**
  （弧上的抗锯齿像素同样落在这个亮度区间里，首版量出的 "OFF" 高 45px 就是弧）
"""

import sys
from collections import Counter

from PIL import Image

WHITE = 170


def runs(values, pred):
    out = []
    start = None
    for i, v in enumerate(values):
        on = pred(v)
        if on and start is None:
            start = i
        elif not on and start is not None:
            out.append((start, i - 1))
            start = None
    if start is not None:
        out.append((start, len(values) - 1))
    return out


def main():
    img = Image.open(sys.argv[1]).convert("RGB")
    w, h = img.size
    px = img.load()
    white = lambda p: min(p) >= WHITE  # noqa: E731

    print(f"IMAGE {w}x{h}")

    # ── 1. 按 y 切出三个开关带 ──
    band_rows = [
        any(white(px[x, y]) for x in range(0, w, 2)) for y in range(h)
    ]
    bands = []
    y = 0
    while y < h:
        if band_rows[y]:
            y0 = y
            while y < h and band_rows[y]:
                y += 1
            bands.append((y0, y - 1))
        else:
            y += 1
    merged = []
    for b in bands:
        if merged and b[0] - merged[-1][1] <= 6:
            merged[-1] = (merged[-1][0], b[1])
        else:
            merged.append(b)
    print(f"BANDS {len(merged)} -> {merged}")
    if len(merged) != 3:
        print("FAIL: expected exactly 3 switch bands")
        return 1

    def row_white_runs(yy, x0=0, x1=None):
        if x1 is None:
            x1 = w
        return runs([white(px[x, yy]) for x in range(x0, x1)], lambda v: v)

    # ── 2. 中间开关 ──
    y0, y1 = merged[1]
    cy = (y0 + y1) // 2
    rr = row_white_runs(cy)
    print(f"MID center_y={cy} white_runs={rr}")
    if len(rr) < 3:
        print("FAIL: center row has <3 white runs -> measurement mismatch")
        return 1
    track_x0, track_x1 = rr[0][0], rr[-1][1]
    inner = rr[1:-1]
    thumb_x0 = min(a for a, _ in inner)
    thumb_x1 = max(b for _, b in inner)

    # 滑块上下边界：在滑块内**无字**的列上扫（取滑块右 1/4 处，避开左侧标签）
    probe = thumb_x0 + int((thumb_x1 - thumb_x0) * 0.75)
    colr = runs([white(px[probe, yy]) for yy in range(h)], lambda v: v)
    cand = [r for r in colr if r[1] - r[0] > 20]
    if not cand:
        print("FAIL: thumb vertical extent not found")
        return 1
    thumb_y0, thumb_y1 = cand[0]
    # 轨道上下边界：在轨道内**滑块之外**的列上扫（取轨道右 1/4 处）
    probe2 = thumb_x1 + int((track_x1 - thumb_x1) * 0.5)
    colr2 = [r for r in runs([white(px[probe2, yy]) for yy in range(h)], lambda v: v)
             if r[1] - r[0] <= 6 and merged[1][0] - 4 <= r[0] and r[1] <= merged[1][1] + 4]
    if len(colr2) < 2:
        print("FAIL: track top/bottom strokes not both found")
        return 1
    track_y0, track_y1 = colr2[0][0], colr2[-1][1]

    track_w, track_h = track_x1 - track_x0 + 1, track_y1 - track_y0 + 1
    thumb_w, thumb_h = thumb_x1 - thumb_x0 + 1, thumb_y1 - thumb_y0 + 1
    print(f"MID track_box=({track_x0},{track_y0},{track_x1},{track_y1}) {track_w}x{track_h} "
          f"aspect={track_w / track_h:.3f}")
    print(f"MID thumb_box=({thumb_x0},{thumb_y0},{thumb_x1},{thumb_y1}) {thumb_w}x{thumb_h} "
          f"aspect={thumb_w / thumb_h:.3f}")
    print(f"MID thumb_w/track_w={thumb_w / track_w:.3f} thumb_h/track_h={thumb_h / track_h:.3f}")
    print(f"MID inset_left={thumb_x0 - track_x0} inset_right={track_x1 - thumb_x1} "
          f"inset_top={thumb_y0 - track_y0} inset_bottom={track_y1 - thumb_y1}")
    print(f"MID stroke_left_w={rr[0][1] - rr[0][0] + 1} stroke_right_w={rr[-1][1] - rr[-1][0] + 1}")

    # 完全圆端判定：轨道顶行白段的左端点 ≈ track_x0 + r（r = h/2 时为 stadium）
    top_runs = row_white_runs(track_y0)
    top_span = [r for r in top_runs if r[0] >= track_x0 - 2 and r[1] <= track_x1 + 2]
    if top_span:
        straight_start = top_span[0][0] - track_x0
        print(f"MID top_straight_start_from_left={straight_start} (stadium expects ~h/2={track_h / 2:.1f})")

    # ── 3. 字形（窗口内缩，避开圆角抗锯齿） ──
    def glyph(pred, x0, y0_, x1, y1_, label):
        mnx = mny = 10 ** 9
        mxx = mxy = -1
        n = 0
        hist = Counter()
        for yy in range(y0_, y1_ + 1):
            for xx in range(x0, x1 + 1):
                p = px[xx, yy]
                if pred(p):
                    n += 1
                    hist[p] += 1
                    mnx, mxx = min(mnx, xx), max(mxx, xx)
                    mny, mxy = min(mny, yy), max(mxy, yy)
        if mxx < 0:
            print(f"{label}: NONE")
            return None
        print(f"{label} bbox=({mnx},{mny},{mxx},{mxy}) w={mxx - mnx + 1} h={mxy - mny + 1} n={n} "
              f"top_colors={hist.most_common(3)}")
        return (mnx, mny, mxx, mxy)

    on = glyph(lambda p: max(p) < 110, thumb_x0 + 8, thumb_y0 + 8,
               thumb_x1 - 8, thumb_y1 - 8, "ON_glyph(inside thumb)")
    off = glyph(lambda p: 95 < max(p) < 215, thumb_x1 + 10, track_y0 + 10,
                track_x1 - 12, track_y1 - 10, "OFF_glyph(track right)")
    if on:
        print(f"ON w/track_w={ (on[2]-on[0]+1)/track_w:.3f} h/track_h={(on[3]-on[1]+1)/track_h:.3f} "
              f"left_inset_in_thumb={on[0]-thumb_x0} thumb_right_gap={thumb_x1-on[2]}")
    if off:
        print(f"OFF center_x_rel_track={(off[0]+off[2])/2 - track_x0:.1f} "
              f"(track right-half midpoint={track_w * 0.75:.1f}) h/track_h={(off[3]-off[1]+1)/track_h:.3f}")

    # ── 4. 配色取样（图有对角渐变，绝对值只作相对判读） ──
    print(f"PAGE_bg_topleft={px[6, 6]} PAGE_bg_botright={px[w - 6, h - 6]}")
    print(f"STROKE={px[(rr[0][0] + rr[0][1]) // 2, cy]} THUMB={px[probe, (thumb_y0 + thumb_y1) // 2]}")
    print(f"TRACK_interior_left={px[track_x0 + 6, cy]} TRACK_interior_right={px[track_x1 - 20, track_y0 + 6]}")

    # ── 5. 另外两款的外框（决策留痕） ──
    for idx, name in ((0, "TOP"), (2, "BOT")):
        by0, by1 = merged[idx]
        bcy = (by0 + by1) // 2
        brr = row_white_runs(bcy)
        if brr:
            print(f"{name} band=({by0},{by1}) h={by1 - by0 + 1} x=({brr[0][0]},{brr[-1][1]}) "
                  f"w={brr[-1][1] - brr[0][0] + 1} runs={brr}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
