#!/usr/bin/env python3
"""
清木图传 v0.6.0：出片用 17^3 LUT 资产生成器。

口径与调研规格 ~/workspace/qingmu-filters-v060/FILTERS_SPEC.md §2.3 / §7 完全一致：
  - 源函数：Sowerby X-Trans III HaldCLUT（显示域实测）、Pat David 胶片 HaldCLUT
    （CC BY-SA 4.0，含 rollfilm 33 节点稀疏化版）、rollfilm 场景域立方体经
    F-Log2 桥接（曝光倍率取 fit_results.json 的 _bridge.exposure，本机标定值 1.5）。
  - LUT：在源函数上取 17^3 节点，uint8 RGB，R 最快（index = (b*17+g)*17+r），
    单款原始 14,739 B，gzip(mtime=0, level 9) 后写入 app/src/main/assets/filters/。
  - 徕卡 · 高反差黑白（LEICA_HC_BW）：源函数 = Tri-X 函数 ∘ 后置 S 曲线
    （结点取 fit_results.json 的 _leica.leica_hc_bw.post_curve_knots），复合后采样。
校验：每款生成后用三线性采样在验证集（25^3 网格 + 3 万随机点，种子同调研脚本）
  上与源函数比对 RMSE/p95，并与 lut17_sizes.json 的规格值对照打印。
"""
import gzip
import io
import json
import os
import sys

import numpy as np
from PIL import Image

REPO = os.path.expanduser("~/workspace/nikon-transfer")
RES = os.path.expanduser("~/workspace/qingmu-filters-v060")
LUTDIR = os.path.join(RES, "luts")
OUTDIR = os.path.join(REPO, "app/src/main/assets/filters")
N = 17

rng = np.random.default_rng(20261014)

# ---------------------------------------------------------------- 源函数加载（同 fit_filters.py）

def load_hald(path):
    im = Image.open(path)
    side = im.size[0]
    L = round(side ** (1 / 3))
    assert L ** 3 == side, (path, side)
    n = L * L
    arr = np.asarray(im.convert("RGB")).astype(np.float64) / 255.0
    lut = arr.reshape(-1, 3).reshape(n, n, n, 3)
    return np.ascontiguousarray(lut.transpose(2, 1, 0, 3))  # axes [r,g,b]

def load_npy(path):
    a = np.load(path).astype(np.float64)
    assert a.ndim == 4 and a.shape[3] == 3, (path, a.shape)
    return np.ascontiguousarray(a)

def sample_lut(lut, pts):
    n = lut.shape[0]
    p = np.clip(pts, 0.0, 1.0) * (n - 1)
    i0 = np.floor(p).astype(int)
    frac = p - i0
    i1 = np.minimum(i0 + 1, n - 1)
    out = np.zeros((len(pts), 3))
    for corner in range(8):
        dr, dg, db = corner & 1, (corner >> 1) & 1, (corner >> 2) & 1
        w = ((frac[:, 0] if dr else 1 - frac[:, 0])
             * (frac[:, 1] if dg else 1 - frac[:, 1])
             * (frac[:, 2] if db else 1 - frac[:, 2]))
        out += w[:, None] * lut[i1[:, 0] if dr else i0[:, 0],
                                i1[:, 1] if dg else i0[:, 1],
                                i1[:, 2] if db else i0[:, 2]]
    return out

# ---------------------------------------------------------------- F-Log2 桥接（常数同规格 §2.2）

FA, FB, FC, FD = 5.555556, 0.064829, 0.245281, 0.384316
FE, FF, CUT1 = 8.799461, 0.092864, 0.000889

def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)

def _rgb2xyz_matrix(prim, white):
    def xyz(x, y):
        return np.array([x / y, 1.0, (1 - x - y) / y])
    P = np.column_stack([xyz(*p) for p in prim])
    S = np.linalg.solve(P, xyz(*white))
    return P * S

M_SRGB2FGAMUT = (np.linalg.inv(_rgb2xyz_matrix([(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)], (0.3127, 0.3290)))
                 @ _rgb2xyz_matrix([(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)], (0.3127, 0.3290)))

def flog2_encode(x):
    log_part = FC * np.log10(FA * np.maximum(x, 0) + FB) + FD
    return np.where(x >= CUT1, log_part, FE * x + FF)

def bridge_sample(cube, pts_srgb, exposure):
    lin = srgb_to_linear(pts_srgb) * exposure
    fg = lin @ M_SRGB2FGAMUT.T
    code = np.clip(flog2_encode(fg), 0.0, 1.0)
    return sample_lut(cube, code)

# ---------------------------------------------------------------- 网格与校验

def grid_rfast(n):
    """17^3 节点，顺序 R 最快：index = (b*n+g)*n+r"""
    g = np.linspace(0, 1, n)
    R, G, B = np.meshgrid(g, g, g, indexing="ij")
    return np.stack([R, G, B], -1).transpose(2, 1, 0, 3).reshape(-1, 3)

def grid_pts(n):
    g = np.linspace(0, 1, n)
    R, G, B = np.meshgrid(g, g, g, indexing="ij")
    return np.stack([R.ravel(), G.ravel(), B.ravel()], 1)

VAL_PTS = np.vstack([grid_pts(25), rng.uniform(0, 1, (30000, 3))])

def lut_from_fn(fn):
    vals = np.clip(fn(grid_rfast(N)), 0, 1)
    raw = (vals * 255).round().astype(np.uint8)
    assert raw.shape == (N ** 3, 3), raw.shape
    return raw.tobytes()

def sample_my_lut(raw_bytes, pts):
    lut = np.frombuffer(raw_bytes, dtype=np.uint8).astype(np.float64).reshape(N, N, N, 3) / 255.0
    # 字节序 R 最快 -> axes [b,g,r]，转回 [r,g,b]
    lut = np.ascontiguousarray(lut.transpose(2, 1, 0, 3))
    return sample_lut(lut, pts)

def gz_bytes(raw):
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(raw)
    return buf.getvalue()

# ---------------------------------------------------------------- 主流程

def main():
    fit = json.load(open(os.path.join(RES, "fit_results.json")))
    spec_sizes = json.load(open(os.path.join(RES, "lut17_sizes.json")))
    expo = fit["_bridge"]["exposure"]
    print(f"bridge exposure = {expo}")

    import glob
    def sowerby(name):
        p = glob.glob(os.path.join(LUTDIR, "sowerby3", "*", f"Fuji XTrans III - {name}.png"))[0]
        return load_hald(p)

    def hald_fn(lut):
        return lambda X: sample_lut(lut, X)

    def bridge_fn(fname):
        cube = load_npy(os.path.join(LUTDIR, fname))
        return lambda X: bridge_sample(cube, X, exposure=expo)

    trix = load_npy(os.path.join(LUTDIR, "rollfilm_clut_kodak_tri_x_400.npy"))
    hc_knots = np.array(fit["_leica"]["leica_hc_bw"]["post_curve_knots"][0], dtype=np.float64)
    xs17 = np.linspace(0, 1, 17)

    def hc_bw_fn(X):
        y = sample_lut(trix, X)
        return np.stack([np.interp(y[:, c], xs17, hc_knots) for c in range(3)], 1)

    # 目录代号 -> (源函数, 规格校验键)
    jobs = [
        ("fuji_provia",        hald_fn(sowerby("Provia")),         "sowerby_provia"),
        ("fuji_velvia",        hald_fn(sowerby("Velvia")),         "sowerby_velvia"),
        ("fuji_astia",         hald_fn(sowerby("Astia")),          "sowerby_astia"),
        ("fuji_chrome",        hald_fn(sowerby("Classic Chrome")), "sowerby_classic_chrome"),
        ("fuji_proneg_std",    hald_fn(sowerby("Pro Neg Std")),    "sowerby_pro_neg_std"),
        ("fuji_proneg_hi",     hald_fn(sowerby("Pro Neg Hi")),     "sowerby_pro_neg_hi"),
        ("bw_acros",           hald_fn(sowerby("Acros")),          "sowerby_acros"),
        ("bw_mono",            hald_fn(sowerby("Mono")),           "sowerby_mono"),
        ("film_portra400",     hald_fn(load_hald(os.path.join(LUTDIR, "patdavid_kodak_portra_400.png"))), "patdavid_portra400"),
        ("film_pro400h",       hald_fn(load_hald(os.path.join(LUTDIR, "patdavid_fuji_400h.png"))),         "patdavid_pro400h"),
        ("film_astia100f",     hald_fn(load_npy(os.path.join(LUTDIR, "rollfilm_clut_fuji_astia_100f.npy"))),     "clut_fuji_astia_100f"),
        ("bw_neopan_acros",    hald_fn(load_npy(os.path.join(LUTDIR, "rollfilm_clut_fuji_neopan_acros_100.npy"))), "clut_fuji_neopan_acros_100"),
        ("bw_hp5",             hald_fn(load_npy(os.path.join(LUTDIR, "rollfilm_clut_ilford_hp5_plus_400.npy"))),   "clut_ilford_hp5_plus_400"),
        ("fuji_classic_neg",   bridge_fn("rollfilm_official_classic_neg.npy"),          "bridge_classic_neg"),
        ("fuji_eterna",        bridge_fn("rollfilm_official_eterna.npy"),               "bridge_eterna"),
        ("creative_bleach",    bridge_fn("rollfilm_official_eterna_bleach_bypass.npy"), "bridge_eterna_bleach"),
        ("fuji_reala_ace",     bridge_fn("rollfilm_official_reala_ace.npy"),            "bridge_reala_ace"),
        ("fuji_nostalgic_neg", bridge_fn("rollfilm_derived_nostalgic_neg.npy"),         "bridge_nostalgic_neg"),
        ("leica_hc_bw",        hc_bw_fn,                                                "clut_kodak_tri_x_400"),
    ]

    os.makedirs(OUTDIR, exist_ok=True)
    total = 0
    print(f"{'asset':22s} {'gzip B':>8s} {'spec B':>8s} {'rmse':>6s} {'spec':>6s} {'p95':>6s} {'spec':>6s}")
    for code, fn, spec_key in jobs:
        raw = lut_from_fn(fn)
        assert len(raw) == 14739, (code, len(raw))
        data = gz_bytes(raw)
        with open(os.path.join(OUTDIR, code + ".lut.gz"), "wb") as f:
            f.write(data)
        # 校验：本 LUT 三线性 vs 源函数
        pred = sample_my_lut(raw, VAL_PTS)
        true = fn(VAL_PTS)
        d = (pred - true) * 255.0
        rmse = float(np.sqrt((d ** 2).mean()))
        p95 = float(np.percentile(np.abs(d), 95))
        sp = spec_sizes[spec_key]
        total += len(data)
        print(f"{code:22s} {len(data):8d} {sp['gzip_bytes']:8d} {rmse:6.2f} {sp['interp_rmse_val']:6.2f} "
              f"{p95:6.2f} {sp['interp_p95_val']:6.2f}")
    print(f"TOTAL gzip = {total} B ({total/1024:.1f} KiB), files = {len(jobs)}")

if __name__ == "__main__":
    sys.exit(main())
