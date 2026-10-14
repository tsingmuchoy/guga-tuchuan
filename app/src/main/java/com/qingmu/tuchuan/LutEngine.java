package com.qingmu.tuchuan;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * v0.6 出片高精度路径：17^3 三维 LUT（三线性插值）逐像素渲染。
 *
 * 资产：assets/filters/<代号小写>.lut.gz —— uint8 RGB、R 最快
 * （index = (b*17+g)*17+r，单款原始 14,739 B），由 tools/gen_filter_luts.py
 * 按调研规格（qingmu-filters-v060/FILTERS_SPEC.md §2.3/§7）离线生成：
 * 在开源实测源函数上取 17^3 节点，相对源数据插值 RMSE 0.3~1.0/255，近无损。
 *
 * 内存纪律：只缓存最近用过的一款 LUT（14,739 B 常驻可忽略），切换滤镜即替换；
 * 应用时按 48 行分块 getPixels/setPixels，像素缓冲 = 宽×48×4B（6000px 宽约 1.2MB），
 * 不产生整图中间缓冲；条带化解码管线里对每条条带就地处理后再绘制。
 * 强度混合（规格 §1）：out = (1-t)·in + t·LUT(in)。
 */
public final class LutEngine {

    public static final int N = 17;
    public static final int RAW_BYTES = N * N * N * 3; // 14,739
    private static final int CHUNK_ROWS = 48;

    private static String cachedAsset;
    private static byte[] cachedData;

    private LutEngine() {}

    /** 按需解压加载某滤镜的 LUT；失败返回 null 并记诊断（调用方应回退矩阵路径，不要让导出失败） */
    public static synchronized byte[] load(Context ctx, int filterIdx) {
        String asset = Filters.lutAsset(filterIdx);
        if (asset == null) return null;
        if (asset.equals(cachedAsset) && cachedData != null) return cachedData;
        try (InputStream in = ctx.getAssets().open(asset);
             GZIPInputStream gz = new GZIPInputStream(in);
             ByteArrayOutputStream bos = new ByteArrayOutputStream(RAW_BYTES)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            if (data.length != RAW_BYTES) {
                Diag.op("EDIT", "LUT 资产长度异常：" + asset + " 解压得 " + data.length
                        + "B（应为 " + RAW_BYTES + "B），回退矩阵路径");
                return null;
            }
            cachedAsset = asset;
            cachedData = data;
            Diag.op("EDIT", "LUT 已载入：" + Filters.name(filterIdx) + "（" + asset + "，17³ 三线性）");
            return data;
        } catch (Exception e) {
            Diag.op("EDIT", "LUT 载入失败：" + asset + "：" + e.getMessage() + "，回退矩阵路径");
            return null;
        }
    }

    /** 就地对位图应用 LUT（t = 强度 0..1）；保留 alpha 通道 */
    public static void applyInPlace(Bitmap bmp, byte[] lut, float t) {
        if (bmp == null || lut == null || t <= 0f) return;
        if (t > 1f) t = 1f;
        final int w = bmp.getWidth(), h = bmp.getHeight();
        final float scale = (N - 1) / 255f;
        int[] buf = new int[w * Math.min(CHUNK_ROWS, h)];
        for (int y0 = 0; y0 < h; y0 += CHUNK_ROWS) {
            int rows = Math.min(CHUNK_ROWS, h - y0);
            bmp.getPixels(buf, 0, w, 0, y0, w, rows);
            int count = w * rows;
            for (int i = 0; i < count; i++) {
                int px = buf[i];
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                float fr = r * scale, fg = g * scale, fb = b * scale;
                int r0 = (int) fr, g0 = (int) fg, b0 = (int) fb;
                if (r0 > N - 2) r0 = N - 2;
                if (g0 > N - 2) g0 = N - 2;
                if (b0 > N - 2) b0 = N - 2;
                float rf = fr - r0, gf = fg - g0, bf = fb - b0;
                // 8 个角的节点基址（R 最快：index = (b*17+g)*17+r，每节点 3B）
                int c000 = ((b0 * N + g0) * N + r0) * 3;
                int c100 = c000 + 3;
                int c010 = c000 + N * 3;
                int c110 = c010 + 3;
                int c001 = c000 + N * N * 3;
                int c101 = c001 + 3;
                int c011 = c001 + N * 3;
                int c111 = c011 + 3;
                float irf = 1 - rf, igf = 1 - gf, ibf = 1 - bf;
                int or = 0, og = 0, ob = 0;
                for (int ch = 0; ch < 3; ch++) {
                    float v000 = lut[c000 + ch] & 0xFF, v100 = lut[c100 + ch] & 0xFF;
                    float v010 = lut[c010 + ch] & 0xFF, v110 = lut[c110 + ch] & 0xFF;
                    float v001 = lut[c001 + ch] & 0xFF, v101 = lut[c101 + ch] & 0xFF;
                    float v011 = lut[c011 + ch] & 0xFF, v111 = lut[c111 + ch] & 0xFF;
                    float v = ((v000 * irf + v100 * rf) * igf + (v010 * irf + v110 * rf) * gf) * ibf
                            + ((v001 * irf + v101 * rf) * igf + (v011 * irf + v111 * rf) * gf) * bf;
                    int src = ch == 0 ? r : ch == 1 ? g : b;
                    int out = Math.round(src + (v - src) * t);
                    if (out < 0) out = 0; else if (out > 255) out = 255;
                    if (ch == 0) or = out; else if (ch == 1) og = out; else ob = out;
                }
                buf[i] = (px & 0xFF000000) | (or << 16) | (og << 8) | ob;
            }
            bmp.setPixels(buf, 0, w, 0, y0, w, rows);
        }
    }
}
