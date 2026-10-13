package com.qingmu.tuchuan;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * v0.4 水印边框模板（4 款）：只负责「画布几何 + 装饰绘制」，照片本体由调用方先画好。
 * 几何约定：输入照片显示尺寸 imgW×imgH，边距按图宽 u=imgW 的比例：
 *   margins(template) → {side, top, bottom}（均为 u 的倍数）
 *   输出宽 = u + 2×side，输出高 = imgH + (top+bottom)×u
 * 装饰文字大小同样按 u 比例，预览（小图）与导出（全尺寸）共用同一套代码、观感一致。
 * 文字内容全部来自 ExifMeta：参数缺失就省略对应行/项，绝不编造。
 */
public final class Frames {

    public static final int NONE = 0;
    public static final int WHITE = 1;   // 经典白框
    public static final int BLACK = 2;   // 纯黑框
    public static final int BAR = 3;     // 极简无框 + 底部参数条
    public static final int FILM = 4;    // 胶片风（齿孔 + 日期戳）

    public static final String[] NAMES = {"无边框", "经典白框", "纯黑框", "极简参数条", "胶片风"};

    private Frames() {}

    public static float[] margins(int template) {
        switch (template) {
            case WHITE: return new float[]{0.050f, 0.050f, 0.170f};
            case BLACK: return new float[]{0.045f, 0.045f, 0.160f};
            case BAR:   return new float[]{0f, 0f, 0.088f};
            case FILM:  return new float[]{0.030f, 0.088f, 0.128f};
            default:    return new float[]{0f, 0f, 0f};
        }
    }

    public static int bgColor(int template) {
        switch (template) {
            case WHITE: return 0xFFFFFFFF;
            case BLACK: return 0xFF000000;
            case BAR:   return 0xFF000000;
            case FILM:  return 0xFF101010;
            default:    return 0xFF000000;
        }
    }

    /**
     * 画装饰层（照片已画在 imgRect 内）。fallbackName 在 EXIF 全缺时充当标题。
     * 有防重叠处理：极简参数条在标题+参数放不下时优先保参数、标题截断省略。
     */
    public static void drawDecor(Canvas canvas, RectF img, float outW, float outH,
                                 int template, ExifMeta meta, String fallbackName) {
        if (template == NONE || meta == null) return;
        float u = img.width();
        String title = meta.titleLine(fallbackName);
        String params = meta.paramsLine();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (template) {
            case WHITE:
                bandText(canvas, img, outW, outH, u, title, params,
                        0xFF141414, 0xFF5A5A5A, p);
                break;
            case BLACK:
                bandText(canvas, img, outW, outH, u, title, params,
                        0xFFFFFFFF, Ui.ACCENT, p);
                break;
            case BAR: {
                float bandTop = img.bottom;
                float bandH = outH - bandTop;
                float cy = bandTop + bandH / 2f;
                p.setTextSize(0.030f * u);
                p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                p.setColor(0xFFFFFFFF);
                p.setTextAlign(Paint.Align.LEFT);
                float leftX = 0.032f * u;
                float rightEdge = outW - 0.032f * u;
                // 先量参数宽，标题可用宽 = 右界 − 参数宽 − 间隔；标题过长直接截到可用宽（省略号由测宽截断实现）
                float paramsW = params.isEmpty() ? 0 : p.measureText(params);
                float titleAvail = rightEdge - leftX - (paramsW > 0 ? paramsW + 0.05f * u : 0);
                String t = title;
                if (titleAvail > 0 && p.measureText(t) > titleAvail) {
                    while (t.length() > 1 && p.measureText(t + "…") > titleAvail) t = t.substring(0, t.length() - 1);
                    t = t + "…";
                }
                canvas.drawText(t, leftX, baselineCenter(cy, p), p);
                if (!params.isEmpty()) {
                    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    p.setColor(0xFFCFCFCF);
                    p.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText(params, rightEdge, baselineCenter(cy, p), p);
                }
                break;
            }
            case FILM: {
                // 齿孔：上下两带内等距圆角矩形（深灰，模拟胶片齿孔）
                p.setColor(0xFF2C2C2C);
                float holeW = 0.052f * u, holeGap = 0.108f * u;
                drawHoles(canvas, p, outW, 0, img.top, holeW, holeGap, u);
                drawHoles(canvas, p, outW, img.bottom, outH, holeW, holeGap, u);
                // 底部带：左=品牌小字，右=日期戳（琥珀色等宽字体，胶片打戳感）；参数行居中偏左小字
                float bandTop = img.bottom;
                float bandH = outH - bandTop;
                float textCy = bandTop + bandH * 0.68f;
                p.setTextAlign(Paint.Align.LEFT);
                p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                p.setTextSize(0.024f * u);
                p.setColor(0xFF8A8A8A);
                canvas.drawText("QINGMU TUCHUAN", 0.032f * u, baselineCenter(textCy, p), p);
                String date = meta.dateLabel();
                if (!date.isEmpty()) {
                    p.setTextAlign(Paint.Align.RIGHT);
                    p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
                    p.setTextSize(0.030f * u);
                    p.setColor(0xFFFFB54D);
                    canvas.drawText(date, outW - 0.032f * u, baselineCenter(textCy, p), p);
                }
                if (!params.isEmpty()) {
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    p.setTextSize(0.024f * u);
                    p.setColor(0xFF9E9E9E);
                    canvas.drawText(params, outW / 2f, baselineCenter(bandTop + bandH * 0.30f, p), p);
                }
                break;
            }
            default:
                break;
        }
    }

    /** 白框/黑框共用：底部带内两行居中（标题粗、参数次级色）；参数缺失时标题单行居中 */
    private static void bandText(Canvas canvas, RectF img, float outW, float outH, float u,
                                 String title, String params, int titleColor, int paramsColor, Paint p) {
        float bandTop = img.bottom;
        float bandH = outH - bandTop;
        p.setTextAlign(Paint.Align.CENTER);
        if (params.isEmpty()) {
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(0.036f * u);
            p.setColor(titleColor);
            canvas.drawText(title, outW / 2f, baselineCenter(bandTop + bandH / 2f, p), p);
            return;
        }
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextSize(0.037f * u);
        p.setColor(titleColor);
        canvas.drawText(title, outW / 2f, baselineCenter(bandTop + bandH * 0.36f, p), p);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(0.030f * u);
        p.setColor(paramsColor);
        canvas.drawText(params, outW / 2f, baselineCenter(bandTop + bandH * 0.74f, p), p);
    }

    private static void drawHoles(Canvas canvas, Paint p, float outW, float bandTop, float bandBottom,
                                  float holeW, float gap, float u) {
        float bandH = bandBottom - bandTop;
        if (bandH <= 0) return;
        float holeH = bandH * 0.52f;
        float y0 = bandTop + (bandH - holeH) / 2f;
        float r = 0.010f * u;
        for (float x = gap * 0.45f; x + holeW <= outW; x += gap) {
            canvas.drawRoundRect(new RectF(x, y0, x + holeW, y0 + holeH), r, r, p);
        }
    }

    /** 让文字在 cy 处垂直居中时的 baseline */
    private static float baselineCenter(float cy, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }
}
