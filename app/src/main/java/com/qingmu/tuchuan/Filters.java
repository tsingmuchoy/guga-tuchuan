package com.qingmu.tuchuan;

import android.graphics.ColorMatrix;

/**
 * 滤镜目录（v0.6 起为全 App 唯一数据源：出片编辑页与遥控监看共用，禁止两处各维护一份清单）。
 *
 * v0.4 的 14 款（12 创意 + 2 Log 通用近似）索引 0..13 不变、配方不变；
 * v0.6 新增 22 款（索引 14..35）：富士 10 / 徕卡 3 / 经典胶片 3 / 黑白 5 / 创意 1，
 * 参数来自开源实测数据的本机拟合（调研规格 qingmu-filters-v060/FILTERS_SPEC.md）：
 *  - 监看与小样：一律用拟合 4x5 矩阵（ColorMatrixColorFilter 渲染层，零成本）。
 *  - 出片：带 LUT 的 19 款走 17^3 三维查找表（assets/filters/*.lut.gz，LutEngine 逐像素
 *    三线性采样，相对源数据 RMSE 0.3~1.0/255，近无损）；无 LUT 的款（徕卡 3 款构造参数
 *    与原 14 款）走矩阵路径。三明治（曲线+矩阵）参数虽已拟合，但全量档下凡有三明治的
 *    款都有 LUT，出片不启用三明治，避免维护第二条管线。
 *  - 诚实标注：徕卡 4 款（含高反差黑白）为公式构造参数、无实测数据，标「风格致敬」；
 *    经 F-Log2 桥接的 5 款（经典负片/怀旧负片/电影/Reala 王牌/漂白旁路）标「近似」；
 *    原 Log 两款标「通用近似」（非尼康官方 N-Log 曲线）。
 * 强度：矩阵按 单位阵 + (滤镜阵 − 单位阵) × 强度% 混合；LUT 按输出混合（见 LutEngine）。
 */
public final class Filters {

    // ---------- 分组 ----------
    public static final int G_FUJI = 0, G_LEICA = 1, G_FILM = 2, G_BW = 3, G_CREATIVE = 4, G_LOG = 5;
    public static final String[] GROUP_NAMES = {"富士", "徕卡", "经典胶片", "黑白", "创意", "Log"};
    /** 分组在选择器中的展示顺序 */
    public static final int[] GROUP_ORDER = {G_FUJI, G_LEICA, G_FILM, G_BW, G_CREATIVE, G_LOG};

    // ---------- 标注 ----------
    public static final int NOTE_NONE = 0, NOTE_APPROX = 1, NOTE_TRIBUTE = 2, NOTE_LOG = 3;

    public static final String[] NAMES = {
            // 0..11 原创意（v0.4）
            "黑白", "高反差黑白", "褪色胶片", "日系清新", "青橙", "暖阳",
            "冷调", "复古棕", "鲜艳", "柔光", "夜幕", "粉彩",
            // 12..13 原 Log（v0.4）
            "Log 还原·标准", "Log 还原·柔和",
            // 14..23 富士（v0.6）
            "富士 · 标准", "富士 · 鲜艳", "富士 · 柔和", "富士 · 经典克罗姆", "富士 · 经典负片",
            "富士 · 怀旧负片", "富士 · 电影", "富士 · 专业负片 柔", "富士 · 专业负片 硬", "富士 · Reala 王牌",
            // 24..26 徕卡（v0.6，构造参数）
            "徕卡 · 自然", "徕卡 · 鲜艳", "徕卡 · M 经典",
            // 27..29 经典胶片（v0.6）
            "柯达 · 人像 400", "富士 · 专业 400H", "富士 · 胶片阿斯蒂亚",
            // 30..34 黑白（v0.6）
            "富士 · 阿克罗斯", "富士 · 黑白", "胶片 · 尼奥潘阿克罗斯", "徕卡 · 高反差黑白", "伊尔福 · HP5",
            // 35 创意新增（v0.6）
            "富士 · 漂白旁路"
    };
    public static final int COUNT = NAMES.length; // 36
    public static final int LOG_START = 12;

    /** 与 NAMES 同序的代号（与调研规格 §8 一致） */
    private static final String[] CODES = {
            "BW_OLD", "BW_HC_OLD", "FADE", "JP_FRESH", "TEAL_ORANGE", "WARM_SUN",
            "COOL", "SEPIA", "VIVID_OLD", "SOFT", "NIGHT", "PASTEL",
            "LOG_STD", "LOG_SOFT",
            "FUJI_PROVIA", "FUJI_VELVIA", "FUJI_ASTIA", "FUJI_CHROME", "FUJI_CLASSIC_NEG",
            "FUJI_NOSTALGIC_NEG", "FUJI_ETERNA", "FUJI_PRONEG_STD", "FUJI_PRONEG_HI", "FUJI_REALA_ACE",
            "LEICA_NATURAL", "LEICA_VIVID", "LEICA_M_CLASSIC",
            "FILM_PORTRA400", "FILM_PRO400H", "FILM_ASTIA100F",
            "BW_ACROS", "BW_MONO", "BW_NEOPAN_ACROS", "LEICA_HC_BW", "BW_HP5",
            "CREATIVE_BLEACH"
    };

    private static final int[] GROUPS = {
            G_CREATIVE, G_CREATIVE, G_CREATIVE, G_CREATIVE, G_CREATIVE, G_CREATIVE,
            G_CREATIVE, G_CREATIVE, G_CREATIVE, G_CREATIVE, G_CREATIVE, G_CREATIVE,
            G_LOG, G_LOG,
            G_FUJI, G_FUJI, G_FUJI, G_FUJI, G_FUJI, G_FUJI, G_FUJI, G_FUJI, G_FUJI, G_FUJI,
            G_LEICA, G_LEICA, G_LEICA,
            G_FILM, G_FILM, G_FILM,
            G_BW, G_BW, G_BW, G_BW, G_BW,
            G_CREATIVE
    };

    private static final int[] NOTES = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            NOTE_LOG, NOTE_LOG,
            0, 0, 0, 0, NOTE_APPROX, NOTE_APPROX, NOTE_APPROX, 0, 0, NOTE_APPROX,
            NOTE_TRIBUTE, NOTE_TRIBUTE, NOTE_TRIBUTE,
            0, 0, 0,
            0, 0, 0, NOTE_TRIBUTE, 0,
            NOTE_APPROX
    };

    /** 出片 LUT 资产（assets 内相对路径）；null = 出片走矩阵。19 款有 LUT，生成口径见 tools/gen_filter_luts.py */
    private static final String[] LUT_ASSETS = new String[COUNT];
    static {
        LUT_ASSETS[14] = "filters/fuji_provia.lut.gz";
        LUT_ASSETS[15] = "filters/fuji_velvia.lut.gz";
        LUT_ASSETS[16] = "filters/fuji_astia.lut.gz";
        LUT_ASSETS[17] = "filters/fuji_chrome.lut.gz";
        LUT_ASSETS[18] = "filters/fuji_classic_neg.lut.gz";
        LUT_ASSETS[19] = "filters/fuji_nostalgic_neg.lut.gz";
        LUT_ASSETS[20] = "filters/fuji_eterna.lut.gz";
        LUT_ASSETS[21] = "filters/fuji_proneg_std.lut.gz";
        LUT_ASSETS[22] = "filters/fuji_proneg_hi.lut.gz";
        LUT_ASSETS[23] = "filters/fuji_reala_ace.lut.gz";
        LUT_ASSETS[27] = "filters/film_portra400.lut.gz";
        LUT_ASSETS[28] = "filters/film_pro400h.lut.gz";
        LUT_ASSETS[29] = "filters/film_astia100f.lut.gz";
        LUT_ASSETS[30] = "filters/bw_acros.lut.gz";
        LUT_ASSETS[31] = "filters/bw_mono.lut.gz";
        LUT_ASSETS[32] = "filters/bw_neopan_acros.lut.gz";
        LUT_ASSETS[33] = "filters/leica_hc_bw.lut.gz";
        LUT_ASSETS[34] = "filters/bw_hp5.lut.gz";
        LUT_ASSETS[35] = "filters/creative_bleach.lut.gz";
    }

    private Filters() {}

    public static boolean isLog(int idx) { return idx >= LOG_START && idx < LOG_START + 2; }
    public static String name(int idx) { return idx >= 0 && idx < COUNT ? NAMES[idx] : "原图"; }
    public static String code(int idx) { return CODES[idx]; }
    public static int group(int idx) { return GROUPS[idx]; }
    public static int note(int idx) { return NOTES[idx]; }
    public static boolean hasLut(int idx) { return idx >= 0 && idx < COUNT && LUT_ASSETS[idx] != null; }
    public static String lutAsset(int idx) { return hasLut(idx) ? LUT_ASSETS[idx] : null; }

    /** 标注后缀（选择器/诊断用）：近似 / 风格致敬 / 通用近似 */
    public static String noteSuffix(int idx) {
        switch (note(idx)) {
            case NOTE_APPROX: return "（近似）";
            case NOTE_TRIBUTE: return "（风格致敬）";
            case NOTE_LOG: return "（通用近似）";
            default: return "";
        }
    }

    /** 按分组展示顺序返回某组内的目录索引（升序即目录序） */
    public static java.util.List<Integer> indicesInGroup(int g) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < COUNT; i++) if (GROUPS[i] == g) out.add(i);
        return out;
    }

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

    // ---------- 原语矩阵（4x5，行主序 20 元，0..255 量纲） ----------

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

    // ---------- v0.6 新增 22 款的拟合矩阵（数值由 fit_results.json 生成，偏移列已 ×255） ----------

    private static final float[] M_FUJI_PROVIA = {
            1.0854f, -0.2378f, 0.252f, 0f, 2.5f,
            0.1389f, 0.7546f, 0.2468f, 0f, 0.43f,
            0.0661f, -0.0947f, 1.1505f, 0f, -0.87f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_VELVIA = {
            1.131f, -0.2924f, 0.222f, 0f, 6.27f,
            0.1202f, 0.8592f, 0.1917f, 0f, -7.09f,
            -0.0048f, -0.1857f, 1.2481f, 0f, 4.84f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_ASTIA = {
            1.0525f, -0.0954f, 0.182f, 0f, -2.4f,
            0.1374f, 0.7173f, 0.2528f, 0f, 5.38f,
            0.0457f, -0.0902f, 1.1413f, 0f, 5.99f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_CHROME = {
            1.0136f, -0.0899f, 0.2557f, 0f, -6.12f,
            0.1983f, 0.6329f, 0.3365f, 0f, 1.89f,
            0.1489f, 0.0707f, 0.9499f, 0f, -2.83f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_CLASSIC_NEG = {
            0.8375f, 0.0701f, 0.2274f, 0f, 21.55f,
            0.0683f, 0.7964f, 0.2308f, 0f, 18.74f,
            -0.1066f, 0.4063f, 0.8253f, 0f, 11.65f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_NOSTALGIC_NEG = {
            1.0125f, -0.1875f, 0.1113f, 0f, 18.74f,
            -0.0247f, 0.8068f, 0.2131f, 0f, 6.43f,
            -0.2144f, 0.1281f, 0.9932f, 0f, 14.38f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_ETERNA = {
            0.7347f, 0.1293f, 0.0371f, 0f, 37.77f,
            0.0507f, 0.74f, 0.1184f, 0f, 33.41f,
            -0.0025f, 0.118f, 0.8332f, 0f, 21.83f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_PRONEG_STD = {
            0.9481f, -0.1024f, 0.2934f, 0f, 0.08f,
            0.1527f, 0.6757f, 0.2816f, 0f, 7.52f,
            0.1374f, -0.0646f, 1.0272f, 0f, 7.57f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_PRONEG_HI = {
            1.0184f, -0.1111f, 0.2702f, 0f, -5.36f,
            0.1735f, 0.7126f, 0.2769f, 0f, -1.28f,
            0.1297f, -0.0846f, 1.0984f, 0f, 0.15f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FUJI_REALA_ACE = {
            0.7886f, 0.2498f, -0.0183f, 0f, 31.93f,
            0.1121f, 0.8571f, 0.1295f, 0f, 12.7f,
            -0.0162f, 0.1892f, 0.8727f, 0f, 15.07f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_LEICA_NATURAL = {
            0.9844f, -0.173f, 0.2466f, 0f, 6.86f,
            0.1484f, 0.7035f, 0.242f, 0f, 5.02f,
            0.0841f, -0.0466f, 1.0402f, 0f, 3.88f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_LEICA_VIVID = {
            1.2698f, -0.3665f, 0.2574f, 0f, -3.95f,
            0.116f, 0.8432f, 0.2511f, 0f, -6.48f,
            0.0273f, -0.1921f, 1.3527f, 0f, -8.06f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_LEICA_M_CLASSIC = {
            0.9952f, -0.044f, 0.1914f, 0f, 12.29f,
            0.0853f, 0.8729f, 0.1985f, 0f, -2.4f,
            -0.038f, 0.1495f, 0.9107f, 0f, 15.99f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FILM_PORTRA400 = {
            0.9662f, -0.0427f, 0.1858f, 0f, 9.95f,
            0.0853f, 0.8729f, 0.1985f, 0f, -4.44f,
            -0.0392f, 0.1541f, 0.9389f, 0f, 14.38f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FILM_PRO400H = {
            0.8364f, -0.0335f, 0.2208f, 0f, 23.54f,
            0.0717f, 0.8393f, 0.1486f, 0f, 5.74f,
            -0.0177f, 0.1254f, 0.9297f, 0f, 3.52f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_FILM_ASTIA100F = {
            0.8057f, 0.3529f, -0.1443f, 0f, 15.63f,
            -0.0669f, 1.0338f, -0.0527f, 0f, -0.26f,
            -0.0595f, 0.383f, 0.4955f, 0f, 3.11f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_BW_ACROS = {
            0.3547f, 0.4159f, 0.3662f, 0f, 6.66f,
            0.3547f, 0.4159f, 0.3662f, 0f, 6.66f,
            0.3547f, 0.4159f, 0.3662f, 0f, 6.66f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_BW_MONO = {
            0.3201f, 0.4231f, 0.3616f, 0f, 7.78f,
            0.3201f, 0.4231f, 0.3616f, 0f, 7.78f,
            0.3201f, 0.4231f, 0.3616f, 0f, 7.78f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_BW_NEOPAN_ACROS = {
            0.151f, 0.7921f, 0.0685f, 0f, -7.06f,
            0.151f, 0.7921f, 0.0685f, 0f, -7.06f,
            0.151f, 0.7921f, 0.0685f, 0f, -7.06f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_LEICA_HC_BW = {
            0.1646f, 0.5045f, 0.3394f, 0f, 14.71f,
            0.1646f, 0.5045f, 0.3394f, 0f, 14.71f,
            0.1646f, 0.5045f, 0.3394f, 0f, 14.71f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_BW_HP5 = {
            0.2508f, 0.5556f, 0.4149f, 0f, -9.18f,
            0.2508f, 0.5556f, 0.4149f, 0f, -9.18f,
            0.2508f, 0.5556f, 0.4149f, 0f, -9.18f,
            0, 0, 0, 1, 0,
    };

    private static final float[] M_CREATIVE_BLEACH = {
            0.5664f, 0.3955f, 0.1618f, 0f, 20.35f,
            0.1554f, 0.8078f, 0.1865f, 0f, 12.29f,
            0.1358f, 0.4317f, 0.578f, 0f, 9.49f,
            0, 0, 0, 1, 0,
    };

    /** 下标 = 目录索引 − 14；须声明在全部 M_* 之后（静态初始化按声明顺序） */
    private static final float[][] NEW_BASE = {
            M_FUJI_PROVIA, M_FUJI_VELVIA, M_FUJI_ASTIA, M_FUJI_CHROME, M_FUJI_CLASSIC_NEG,
            M_FUJI_NOSTALGIC_NEG, M_FUJI_ETERNA, M_FUJI_PRONEG_STD, M_FUJI_PRONEG_HI, M_FUJI_REALA_ACE,
            M_LEICA_NATURAL, M_LEICA_VIVID, M_LEICA_M_CLASSIC,
            M_FILM_PORTRA400, M_FILM_PRO400H, M_FILM_ASTIA100F,
            M_BW_ACROS, M_BW_MONO, M_BW_NEOPAN_ACROS, M_LEICA_HC_BW, M_BW_HP5,
            M_CREATIVE_BLEACH
    };

    // ---------- 36 个预设的配方 ----------

    private static float[] base(int idx) {
        if (idx >= 14 && idx < COUNT) return NEW_BASE[idx - 14];
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
