package com.qingmu.tuchuan;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v0.3 自绘深色确认卡（全 App 高危操作统一入口，禁止系统灰色弹窗）：
 * 挂在某个页面根 FrameLayout 上的半透明遮罩 + bg_card 圆角卡，两种模式——
 *  ① 普通确认：点「确认」按钮即执行；
 *  ② 长按确认：确认按钮必须持续按满 holdMs（格式化用 2000ms），中途松手自动取消，
 *     按钮上方进度条实时显示按压进度、按钮文案倒计时。
 * 回调在 UI 线程触发；重活由调用方自己丢后台线程。
 */
public class ConfirmCard {

    public interface OnConfirm { void onConfirm(); }

    private final Activity act;
    private final FrameLayout overlay;
    private final TextView titleTv, bodyTv;
    private final Button confirmBtn, cancelBtn;
    private final ProgressBar holdBar;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private OnConfirm pending;
    private String confirmLabel = "确认";
    private boolean longPressMode = false;
    private long holdMs = 2000;
    private long pressStartMs = 0;
    private boolean pressFired = false;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!longPressMode || pressStartMs == 0 || pressFired) return;
            long elapsed = System.currentTimeMillis() - pressStartMs;
            if (elapsed >= holdMs) {
                pressFired = true;
                fire();
                return;
            }
            holdBar.setProgress((int) (elapsed * 1000 / holdMs));
            confirmBtn.setText(confirmLabel + "（还剩 " + String.format("%.1f", (holdMs - elapsed) / 1000f) + " 秒）");
            handler.postDelayed(this, 50);
        }
    };

    public ConfirmCard(Activity act, FrameLayout parent) {
        this.act = act;
        overlay = new FrameLayout(act);
        overlay.setBackgroundColor(0xB3000000);
        overlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(act.getDrawable(R.drawable.bg_card));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.setMargins(dp(22), 0, dp(22), 0);
        card.setLayoutParams(clp);

        titleTv = new TextView(act);
        titleTv.setTextSize(16);
        titleTv.setTextColor(Ui.TEXT);
        titleTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        card.addView(titleTv);

        bodyTv = new TextView(act);
        bodyTv.setTextSize(13);
        bodyTv.setTextColor(Ui.TEXT_DIM);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(8), 0, 0);
        card.addView(bodyTv, blp);

        holdBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        holdBar.setMax(1000);
        holdBar.setProgress(0);
        holdBar.setProgressTintList(ColorStateList.valueOf(Ui.ERR));
        holdBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(12), 0, 0);
        card.addView(holdBar, hlp);

        LinearLayout row = new LinearLayout(act);
        cancelBtn = makeBtn("取消", false);
        confirmBtn = makeBtn("确认", true);
        row.addView(cancelBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams cflp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cflp.setMargins(dp(10), 0, 0, 0);
        row.addView(confirmBtn, cflp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(14), 0, 0);
        card.addView(row, rlp);

        card.setOnClickListener(v -> { /* 拦截点击冒泡，点卡片不关 */ });
        overlay.addView(card);
        overlay.setOnClickListener(v -> hide());
        parent.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cancelBtn.setOnClickListener(v -> hide());
        confirmBtn.setOnClickListener(v -> { if (!longPressMode) fire(); });
        confirmBtn.setOnTouchListener((v, ev) -> {
            if (!longPressMode) return false;
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressStartMs = System.currentTimeMillis();
                    pressFired = false;
                    holdBar.setProgress(0);
                    handler.removeCallbacks(ticker);
                    handler.post(ticker);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    long elapsed = System.currentTimeMillis() - pressStartMs;
                    handler.removeCallbacks(ticker);
                    pressStartMs = 0;
                    if (!pressFired) {
                        holdBar.setProgress(0);
                        confirmBtn.setText(confirmLabel + "（按住不放）");
                        if (elapsed > 250) Toast.makeText(act, "未按满 " + (holdMs / 1000) + " 秒，已取消", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f); }

    private Button makeBtn(String label, boolean primary) {
        Button b = new Button(act);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (primary) {
            b.setBackground(act.getDrawable(R.drawable.bg_btn_primary));
            b.setTextColor(Ui.ACCENT_TX);
        } else {
            b.setBackground(act.getDrawable(R.drawable.bg_btn_ghost));
            b.setTextColor(Ui.TEXT);
        }
        return b;
    }

    /** 危险色确认按钮（红字幽灵按钮，区别于普通黄主按钮） */
    private void styleConfirm(boolean danger) {
        if (danger) {
            confirmBtn.setBackground(act.getDrawable(R.drawable.bg_btn_ghost));
            confirmBtn.setTextColor(Ui.ERR);
        } else {
            confirmBtn.setBackground(act.getDrawable(R.drawable.bg_btn_primary));
            confirmBtn.setTextColor(Ui.ACCENT_TX);
        }
    }

    /** 普通确认（danger=true 时确认按钮红色） */
    public void show(String title, String body, String confirmLabel, boolean danger, OnConfirm cb) {
        this.pending = cb;
        this.longPressMode = false;
        this.confirmLabel = confirmLabel;
        titleTv.setText(title);
        bodyTv.setText(body);
        styleConfirm(danger);
        confirmBtn.setText(confirmLabel);
        holdBar.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
    }

    /** 长按确认：确认按钮需按满 holdMs 才执行（格式化存储卡专用） */
    public void showLongPress(String title, String body, String confirmLabel, long holdMs, OnConfirm cb) {
        this.pending = cb;
        this.longPressMode = true;
        this.holdMs = holdMs;
        this.confirmLabel = confirmLabel;
        this.pressStartMs = 0;
        this.pressFired = false;
        titleTv.setText(title);
        bodyTv.setText(body);
        styleConfirm(true);
        confirmBtn.setText(confirmLabel + "（按住不放）");
        holdBar.setProgress(0);
        holdBar.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
    }

    public boolean isShowing() { return overlay.getVisibility() == View.VISIBLE; }

    public void hide() {
        handler.removeCallbacks(ticker);
        pressStartMs = 0;
        overlay.setVisibility(View.GONE);
    }

    private void fire() {
        OnConfirm cb = pending;
        hide();
        if (cb != null) cb.onConfirm();
    }
}
