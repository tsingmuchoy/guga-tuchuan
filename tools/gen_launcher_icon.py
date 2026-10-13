#!/usr/bin/env python3
# 生成清木图传启动器前景 ic_launcher_foreground.xml —— 点阵相机（用户选定稿）。
# 纯黑底由 ic_launcher_background 提供；前景为统一栅格上的圆点矩阵：
#   白点阵勾勒机身轮廓（双线边带）、黄点阵实心镜头盘 + 白点环、
#   右上黄点方块（闪光/取景窗区）、机顶零星黄点（拨盘/热靴）。
# 设计稿：~/workspace/imagine_media/media-generation-square-app-icon-artwork-center-0-31ab8546....webp
import math, os

VIEW = 108.0          # viewport 108x108
COLS = ROWS = 30      # 栅格 30x30
STEP = VIEW / COLS    # 3.6
R = 1.3               # 点半径（点径 2.6，约 0.72 倍间距，同设计稿）
WHITE = "#FFFFFF"
YELLOW = "#FFDE00"    # 尼康黄（工程主色）

def center(g):
    return STEP / 2 + g * STEP

# ---- 形状判定（格坐标，距离以格为单位） ----
def body_sd(gx, gy):
    # 圆角矩形 SDF：机身 x[5,25] y[8,21]，角半径 2 格
    cx, cy, hx, hy, r = 15.0, 14.5, 10.0, 6.5, 2.0
    qx = abs(gx - cx) - (hx - r)
    qy = abs(gy - cy) - (hy - r)
    ox, oy = max(qx, 0.0), max(qy, 0.0)
    return math.hypot(ox, oy) + min(max(qx, qy), 0.0) - r

yellow = set()
white = set()

# 机顶黄点簇（拨盘/按钮）与右上热靴黄点
for p in [(8,5),(9,5),(8,6),(9,6),(10,6),      # 左簇
          (12,5),(13,5),(12,6),(13,6),          # 中簇
          (23,5),(23,6)]:                       # 右上
    yellow.add(p)
# 右上黄点方块（闪光/取景窗区）4x4
for gx in range(20, 24):
    for gy in range(11, 15):
        yellow.add((gx, gy))
# 镜头黄盘：中心 (15,15)，半径 3.4 格实心
for gx in range(COLS):
    for gy in range(ROWS):
        if math.hypot(gx - 15, gy - 15) <= 3.4:
            yellow.add((gx, gy))

# 机身轮廓白点带（|sd|<=1.05，双线宽）
for gx in range(COLS):
    for gy in range(ROWS):
        if abs(body_sd(gx, gy)) <= 1.05:
            white.add((gx, gy))
# 镜头外圈白点环：3.9 <= dist <= 5.0
for gx in range(COLS):
    for gy in range(ROWS):
        d = math.hypot(gx - 15, gy - 15)
        if 3.9 <= d <= 5.0:
            white.add((gx, gy))

white -= yellow  # 黄点优先，避免同格双色

def circles(points):
    parts = []
    for gx, gy in sorted(points, key=lambda p: (p[1], p[0])):
        cx, cy = center(gx), center(gy)
        parts.append("M%.2f,%.2f a%.2f,%.2f 0 1,0 %.2f,0 a%.2f,%.2f 0 1,0 -%.2f,0 Z"
                     % (cx - R, cy, R, R, 2 * R, R, R, 2 * R))
    return " ".join(parts)

xml = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- 清木图传启动器前景（定稿：点阵相机）：纯黑底由 ic_launcher_background 提供。
         白点阵机身轮廓（双线边带）/ 黄点阵实心镜头盘 + 白点外环 / 右上黄点方块 / 机顶黄点簇。
         本文件由 tools/gen_launcher_icon.py 按 30x30 栅格生成（白点 %d、黄点 %d），勿手改。 -->

    <path android:fillColor="%s" android:pathData="%s" />
    <path android:fillColor="%s" android:pathData="%s" />
</vector>
""" % (len(white), len(yellow), WHITE, circles(white), YELLOW, circles(yellow))

out = os.path.join(os.path.dirname(__file__), "..",
                   "app/src/main/res/drawable/ic_launcher_foreground.xml")
with open(os.path.normpath(out), "w", encoding="utf-8") as f:
    f.write(xml)
print("white dots:", len(white), "yellow dots:", len(yellow), "total:", len(white) + len(yellow))
print("written:", os.path.normpath(out))
