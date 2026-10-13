package com.qingmu.tuchuan;

import android.graphics.ColorMatrix;

/**
 * v0.4 出片滤镜库：12 个创意滤镜 + 2 个「Log 还原」通用近似预设。
 *
 * 实现：全部用 4x5 ColorMatrix 数学合成（饱和/对比/色温/棕调/黑白原语矩阵连乘），
 * 不依赖任何位图/LUT 资源文件——同一套矩阵既给静态出片（全尺寸重算）用，
 * 也给 LiveView 监看预览（ImageView 的 ColorMatrixColorFilter 渲染层叠加）用，效果一致。
 * 强度：最终矩阵 = 单位阵 + (滤镜阵 − 单位阵) × 强度%，0=原图、100=全效。
 *
 * 「Log 还原」两档只是通用的对比+饱和拉升近似（把发灰的 Log 画面拉回正常观感），
 * 不是尼康官方 N-Log 曲线，UI 与文档里都按「通用近似」如实标注。
 */
public final class Filters {

    public static final String[] NAMES = {
            "黑白", "高反差黑白", "褪色胶片", "日系清新", "青橙", "暖阳",
            "冷调", "复古棕", "鲜艳", "柔光", "夜幕", "粉彩",
            "Log 还原·标准", "Log 还原·柔和"
    };
    public static final int COUNT = NAMES.length; // 14
    public static final int LOG_START = 12;

    private Filters() {}

    public static boolean isLog(int idx) { return idx >= LOG_START && idx < COUNT; }

    /** 构造滤镜矩阵（strength 0..100）；idx 越界返回单位阵 */
    public static ColorMatrix matrix(int idx, int strength) {
        return new ColorMatrix(matrixArray(idx, strength));
    }

    public static float[] matrixArray(int idx, int strength) {
        float[] m = base(idx);
        float k = Math.max(0, Math.min(100, strength)) / 100f;
        float[] id = identity();
        float[] out = new float[20];
        for (int i = 0; i < 20; i++) out[i] = id[i] + (m[i] - id[i]) * k;
        return out;
    }

    // ---------- 原语矩阵（4x5，行主序 20 元） ----------

    private static float[] identity() {
        return new float[]{
                1, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 1, 0};
    }

    /** 饱和度（与 android.graphics.ColorMatrix.setSaturation 同公式，亮度权重 0.213/0.715/0.072） */
    private static float[] sat(float s) {
        float inv = 1 - s;
        float r = 0.213f * inv, g = 0.715f * inv, b = 0.072f * inv;
        return new float[]{
                r + s, g, b, 0, 0,
                r, g + s, b, 0, 0,
                r, g, b + s, 0, 0,
                0, 0, 0, 1, 0};
    }

    /** 对比度 + 亮度偏移（bright 单位为 0..255 色阶） */
    private static float[] cb(float contrast, float bright) {
        float off = (1 - contrast) * 127.5f + bright;
        return new float[]{
                contrast, 0, 0, 0, off,
                0, contrast, 0, 0, off,
                0, 0, contrast, 0, off,
                0, 0, 0, 1, 0};
    }

    /** 色温/通道缩放 */
    private static float[] temp(float r, float g, float b) {
        return new float[]{
                r, 0, 0, 0, 0,
                0, g, 0, 0, 0,
                0, 0, b, 0, 0,
                0, 0, 0, 1, 0};
    }

    /** 黑白（Rec.709 亮度） */
    private static float[] bw() {
        return new float[]{
                0.2126f, 0.7152f, 0.0722f, 0, 0,
                0.2126f, 0.7152f, 0.0722f, 0, 0,
                0.2126f, 0.7152f, 0.0722f, 0, 0,
                0, 0, 0, 1, 0};
    }

    /** 复古棕（标准 sepia 系数） */
    private static float[] sepia() {
        return new float[]{
                0.393f, 0.769f, 0.189f, 0, 0,
                0.349f, 0.686f, 0.168f, 0, 0,
                0.272f, 0.534f, 0.131f, 0, 0,
                0, 0, 0, 1, 0};
    }

    /** 青橙近似：通道串扰把阴影推青、肤色区保暖，再靠调用方叠饱和/对比 */
    private static float[] tealOrange() {
        return new float[]{
                1.06f, 0.04f, -0.06f, 0, 6,
                -0.02f, 1.00f, 0.10f, 0, 0,
                -0.06f, 0.14f, 1.06f, 0, 8,
                0, 0, 0, 1, 0};
    }

    /** a∘b：先作用 b 再作用 a（矩阵积 a×b） */
    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[20];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[r * 5 + k] * b[k * 5 + c];
                o[r * 5 + c] = s;
            }
            float off = a[r * 5 + 4];
            for (int k = 0; k < 4; k++) off += a[r * 5 + k] * b[k * 5 + 4];
            o[r * 5 + 4] = off;
        }
        return o;
    }

    /** 按「先列先作用」的顺序连乘：chain(m1, m2, m3) 表示先 m1、再 m2、最后 m3 */
    private static float[] chain(float[]... ms) {
        float[] acc = identity();
        for (float[] m : ms) acc = mul(m, acc);
        return acc;
    }

    // ---------- 14 个预设的配方 ----------

    private static float[] base(int idx) {
        switch (idx) {
            case 0:  // 黑白
                return bw();
            case 1:  // 高反差黑白
                return chain(bw(), cb(1.38f, -10));
            case 2:  // 褪色胶片：压对比抬黑位、降饱和、微暖
                return chain(cb(0.82f, 26), sat(0.72f), temp(1.05f, 1.0f, 0.93f));
            case 3:  // 日系清新：提亮、低对比、降饱和、青绿微偏
                return chain(cb(0.88f, 14), sat(0.82f), temp(0.97f, 1.03f, 1.05f));
            case 4:  // 青橙
                return chain(tealOrange(), sat(1.16f), cb(1.08f, 0));
            case 5:  // 暖阳
                return chain(temp(1.13f, 1.02f, 0.87f), sat(1.13f), cb(1.03f, 4));
            case 6:  // 冷调
                return chain(temp(0.91f, 1.0f, 1.13f), sat(0.90f), cb(1.06f, 0));
            case 7:  // 复古棕
                return chain(sepia(), cb(0.92f, 10), sat(0.88f));
            case 8:  // 鲜艳
                return chain(sat(1.45f), cb(1.08f, 0));
            case 9:  // 柔光：低对比、抬亮、微暖降饱和（柔焦的静态近似）
                return chain(cb(0.80f, 18), sat(0.90f), temp(1.04f, 1.01f, 0.96f));
            case 10: // 夜幕：压暗、加对比、偏蓝
                return chain(cb(1.12f, -20), temp(0.90f, 0.98f, 1.16f), sat(1.05f));
            case 11: // 粉彩：高亮、低对比、大幅降饱和
                return chain(cb(0.78f, 24), sat(0.68f), temp(1.03f, 1.0f, 1.02f));
            case 12: // Log 还原·标准（通用近似：强对比+饱和拉升、黑位略沉）
                return chain(cb(1.28f, -6), sat(1.32f));
            case 13: // Log 还原·柔和（通用近似）
                return chain(cb(1.16f, 0), sat(1.18f));
            default:
                return identity();
        }
    }
}
