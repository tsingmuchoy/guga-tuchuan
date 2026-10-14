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
    // v0.5.1 数字放大（纯显示层）：displayRect() 在 FIT_CENTER 基础矩形上再施加
    // 「绕画面中心缩放 zoomScale + 平移 (zoomTx, zoomTy)」；lvImage 用同样的 scale/translation
    // （其 pivot 同为画面中心）渲染，两边共用这一套口径，点按对焦经 mapToFrame 逆映射回帧坐标。
    private float zoomScale = 1f, zoomTx = 0f, zoomTy = 0f;
    private float afNx = -1, afNy = -1; // 归一化坐标，<0 表示不显示
    // v0.5 对焦框三态（仿手机相机）：黄=对焦中、黄→绿=合焦、红=未合焦；判定来源是相机 AfDrive 响应码
    public static final int AF_FOCUSING = 0;
    public static final int AF_OK = 1;
    public static final int AF_FAIL = 2;
    private int afState = AF_FOCUSING;
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

    /** 显示/更新对焦框：点按瞬间先 AF_FOCUSING（黄），相机响应回来再以同坐标改判 AF_OK（绿）/ AF_FAIL（红） */
    public void showAfState(float nx, float ny, int state) {
        afNx = nx; afNy = ny; afState = state; afAtMs = System.currentTimeMillis();
        invalidate();
        postDelayed(() -> { afNx = -1; invalidate(); }, 2500); // 框显示 2.5s 后自动收起
    }

    /** FIT_CENTER 基础矩形（未施加数字放大变换）；帧尺寸未知时为整个 View */
    public RectF baseRect() {
        float vw = getWidth(), vh = getHeight();
        if (frameW <= 0 || frameH <= 0 || vw <= 0 || vh <= 0) return new RectF(0, 0, vw, vh);
        float scale = Math.min(vw / frameW, vh / frameH);
        float w = frameW * scale, h = frameH * scale;
        float l = (vw - w) / 2f, t = (vh - h) / 2f;
        return new RectF(l, t, l + w, t + h);
    }

    /** 图像实际显示矩形 = 基础矩形施加数字放大变换（绕基础矩形中心缩放 + 平移）；绘制与点按换算共用 */
    public RectF displayRect() {
        RectF b = baseRect();
        if (zoomScale == 1f && zoomTx == 0f && zoomTy == 0f) return b;
        float cx = b.centerX(), cy = b.centerY();
        float w = b.width() * zoomScale, h = b.height() * zoomScale;
        float l = cx + zoomTx - w / 2f, t = cy + zoomTy - h / 2f;
        return new RectF(l, t, l + w, t + h);
    }

    public float getZoomScale() { return zoomScale; }
    public float getZoomTx() { return zoomTx; }
    public float getZoomTy() { return zoomTy; }

    /** 设置数字放大变换并钳制平移（放大后的画面不许被拖到完全脱离视野：中心偏移不超过画面尺寸一半 + 半屏） */
    public void setZoom(float scale, float tx, float ty) {
        zoomScale = Math.max(1f, Math.min(4f, scale));
        if (zoomScale == 1f) { tx = 0f; ty = 0f; }
        RectF b = baseRect();
        float maxTx = b.width() * zoomScale / 2f + getWidth() / 4f;
        float maxTy = b.height() * zoomScale / 2f + getHeight() / 4f;
        zoomTx = Math.max(-maxTx, Math.min(maxTx, tx));
        zoomTy = Math.max(-maxTy, Math.min(maxTy, ty));
        invalidate();
    }

    public void resetZoom() { zoomScale = 1f; zoomTx = 0f; zoomTy = 0f; invalidate(); }

    /**
     * 屏幕点 → 帧归一化坐标（点按对焦唯一映射口径，1x 与放大状态共用）：
     * 先验点在显示矩形内，再逆变换回基础矩形求归一化；点在画面外（黑边/拖出区）返回 null。
     */
    public float[] mapToFrame(float px, float py) {
        RectF d = displayRect();
        if (d.width() <= 0 || px < d.left || px > d.right || py < d.top || py > d.bottom) return null;
        RectF b = baseRect();
        // 逆变换：p_base_centered = (p_disp - center - T) / scale
        float ux = b.centerX() + (px - b.centerX() - zoomTx) / zoomScale;
        float uy = b.centerY() + (py - b.centerY() - zoomTy) / zoomScale;
        float nx = (ux - b.left) / b.width();
        float ny = (uy - b.top) / b.height();
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return new float[]{nx, ny};
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
            afPaint.setColor(afState == AF_OK ? Ui.OK : afState == AF_FAIL ? Ui.ERR : Ui.ACCENT);
            canvas.drawRect(cx - half, cy - half * 0.75f, cx + half, cy + half * 0.75f, afPaint);
        }
    }
}
