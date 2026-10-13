package com.qingmu.tuchuan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 遥控取景叠加层（v0.2）：三分网格 + 点按对焦框 + 实时直方图，全部 Canvas 自绘、不落盘。
 * 坐标约定：本 View 与取景 ImageView 同尺寸叠放；帧宽高经 setFrameSize 告知后，
 * 按 FIT_CENTER 规则算出图像实际显示矩形，网格/AF 框都只在该矩形内绘制，
 * 点击换算也共用 displayRect()，保证点哪儿对哪儿。
 */
public class HudView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint afPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histBg = new Paint();
    private final Paint[] histPaints = new Paint[3];

    private boolean gridOn = true;
    private boolean histOn = true;
    private float[][] hist; // [3][64] 0..1
    private int frameW, frameH;
    private float afNx = -1, afNy = -1; // 归一化坐标，<0 表示不显示
    private boolean afOk = true;
    private long afAtMs = 0;

    public HudView(Context c) {
        super(c);
        gridPaint.setColor(0x59FFFFFF);
        gridPaint.setStrokeWidth(dp(1));
        afPaint.setStyle(Paint.Style.STROKE);
        afPaint.setStrokeWidth(dp(2));
        histBg.setColor(0xA6000000);
        int[] cols = {0x99FF5040, 0x9950FF80, 0x995080FF}; // R/G/B 半透明叠加（摄影直方图惯例）
        for (int i = 0; i < 3; i++) { histPaints[i] = new Paint(); histPaints[i].setColor(cols[i]); }
    }

    private float dp(int v) { return v * getResources().getDisplayMetrics().density; }

    public void setGridOn(boolean on) { gridOn = on; invalidate(); }
    public void setHistOn(boolean on) { histOn = on; invalidate(); }
    public void setHistogram(float[][] bins) { hist = bins; invalidate(); }
    public void setFrameSize(int w, int h) { frameW = w; frameH = h; invalidate(); }

    public void showAf(float nx, float ny, boolean ok) {
        afNx = nx; afNy = ny; afOk = ok; afAtMs = System.currentTimeMillis();
        invalidate();
        postDelayed(() -> { afNx = -1; invalidate(); }, 2500); // 框显示 2.5s 后自动收起
    }

    /** 图像在当前 View 内的实际显示矩形（FIT_CENTER）；帧尺寸未知时为整个 View */
    public RectF displayRect() {
        float vw = getWidth(), vh = getHeight();
        if (frameW <= 0 || frameH <= 0 || vw <= 0 || vh <= 0) return new RectF(0, 0, vw, vh);
        float scale = Math.min(vw / frameW, vh / frameH);
        float w = frameW * scale, h = frameH * scale;
        float l = (vw - w) / 2f, t = (vh - h) / 2f;
        return new RectF(l, t, l + w, t + h);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0) return;
        RectF r = displayRect();
        if (gridOn) {
            for (int i = 1; i <= 2; i++) {
                float x = r.left + r.width() * i / 3f;
                float y = r.top + r.height() * i / 3f;
                canvas.drawLine(x, r.top, x, r.bottom, gridPaint);
                canvas.drawLine(r.left, y, r.right, y, gridPaint);
            }
        }
        if (histOn && hist != null) {
            float hw = dp(96), hh = dp(54);
            float l = r.right - hw - dp(6), t = r.bottom - hh - dp(6);
            canvas.drawRect(l, t, l + hw, t + hh, histBg);
            float bw = hw / 64f;
            for (int ch = 0; ch < 3; ch++) {
                for (int i = 0; i < 64; i++) {
                    float bh = hist[ch][i] * hh;
                    if (bh > 0.5f) canvas.drawRect(l + i * bw, t + hh - bh, l + (i + 1) * bw, t + hh, histPaints[ch]);
                }
            }
        }
        if (afNx >= 0 && System.currentTimeMillis() - afAtMs < 2600) {
            float cx = r.left + afNx * r.width();
            float cy = r.top + afNy * r.height();
            float half = r.width() * 0.08f;
            afPaint.setColor(afOk ? Ui.ACCENT : Ui.ERR);
            canvas.drawRect(cx - half, cy - half * 0.75f, cx + half, cy + half * 0.75f, afPaint);
        }
    }
}
