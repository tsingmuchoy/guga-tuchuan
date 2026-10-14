package com.qingmu.tuchuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.4 出片编辑页：大图预览 + 底部三工具（水印边框 / 滤镜 / 保存与分享）。
 * 入口：照片页「出片」按钮，Intent data = 相册里已下载 JPG 的 Uri，extra "name" = 原文件名。
 * 原图永不覆盖；成品只在点保存时经 ImageEditor 全尺寸重算并发布到 Pictures/QingmuTuchuan/Edited。
 * 预览链：降采样转正图（≤1600px）→ 滤镜 Paint + 边框模板合成，纯内存、尺寸封顶。
 */
public class EditorActivity extends Activity {

    private static final int PREVIEW_MAX = 1600;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final AtomicInteger renderSeq = new AtomicInteger();

    private Uri srcUri;
    private String srcName = "photo.jpg";
    private ExifMeta meta = new ExifMeta();
    private Bitmap srcPreview;      // 已转正的降采样原图
    private Bitmap rendered;        // 当前合成预览（可能与 srcPreview 同一张，回收时判等）

    private int template = Frames.WHITE;
    private int filterIdx = -1;     // -1=原图
    private int strength = 100;
    // v0.4.3：导出画质三档（默认 97），选择持久化到 prefs 的 edit_quality
    private static final int[] QUALITY_VALUES = {100, 97, 88};
    private static final String[] QUALITY_LABELS = {"高画质 100", "高质量 97", "省空间 88"};
    private int quality = 97;
    private final List<TextView> qualityChips = new ArrayList<>();

    private Uri savedUri;
    private boolean dirty = true;   // 当前设置尚未保存
    private boolean exporting = false;
    private boolean pendingShare = false;

    private ImageView previewIv;
    private TextView statusTv, strengthValTv, saveInfoTv, exportTv;
    private ProgressBar exportBar;
    private Button saveBtn, shareBtn;
    private final TextView[] toolTabs = new TextView[3];
    private final View[] toolPanels = new View[3];
    private final List<TextView> frameChips = new ArrayList<>();
    private final List<TextView> filterChips = new ArrayList<>();
    private SeekBar strengthBar;
    // v0.4.1：滤镜缩略图条（小样只算一次并缓存，约 180px；长按大图看原图对比）
    private static final int THUMB_MAX = 180;
    private Bitmap thumbBase;                    // 小样底图（srcPreview 的进一步降采样）
    private final List<Bitmap> filterThumbs = new ArrayList<>(); // 与 Filters 目录同序的 36 张小样（原图格直接用 thumbBase）
    private final List<ImageView> filterThumbIvs = new ArrayList<>();
    private final List<View> filterThumbCells = new ArrayList<>();
    private TextView compareHintTv;
    private boolean comparing = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        srcUri = getIntent().getData();
        String n = getIntent().getStringExtra("name");
        if (n != null && !n.isEmpty()) srcName = n;
        int savedQ = getSharedPreferences("guga_tuchuan", MODE_PRIVATE).getInt("edit_quality", 97);
        for (int q : QUALITY_VALUES) if (q == savedQ) quality = q; // 非法值回落默认 97
        buildUi();
        if (srcUri == null) {
            statusTv.setText("没有收到照片（入口参数缺失），请回照片页重新点「出片」");
            return;
        }
        Diag.op("EDIT", "打开出片编辑页：" + srcName);
        bg.execute(() -> {
            meta = ImageEditor.loadMeta(this, srcUri);
            Bitmap bmp = ImageEditor.loadPreview(this, srcUri, meta.orientation, PREVIEW_MAX);
            runOnUiThread(() -> {
                if (bmp == null) {
                    statusTv.setText("这张照片解码失败，无法出片（文件可能已损坏或被移动）");
                    return;
                }
                srcPreview = bmp;
                String exifNote = meta.isEmpty() ? "（未读到 EXIF，水印只显示文件名）" : "";
                statusTv.setText(meta.summary(srcName) + exifNote);
                buildThumbStrip();
                scheduleRender();
            });
        });
    }

    @Override protected void onDestroy() {
        bg.shutdownNow();
        if (rendered != null && rendered != srcPreview) rendered.recycle();
        if (srcPreview != null) srcPreview.recycle();
        for (Bitmap t : filterThumbs) if (t != null && t != thumbBase) t.recycle();
        if (thumbBase != null) thumbBase.recycle();
        super.onDestroy();
    }

    // ---------- UI ----------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView txt(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private Drawable icon(int res, int tint) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(tint);
        return d;
    }

    private Button btn(String label, int iconRes, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (primary) {
            b.setBackground(getDrawable(R.drawable.bg_btn_primary));
            b.setTextColor(Ui.ACCENT_TX);
        } else {
            b.setBackground(getDrawable(R.drawable.bg_btn_ghost));
            b.setTextColor(Ui.TEXT);
        }
        if (iconRes != 0) {
            Drawable d = icon(iconRes, primary ? Ui.ACCENT_TX : Ui.TEXT);
            int s = dp(19);
            d.setBounds(0, 0, s, s);
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        // 顶栏：返回 + 标题
        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        Button backBtn = btn("返回", R.drawable.ic_back, false);
        backBtn.setOnClickListener(v -> finish());
        topRow.addView(backBtn);
        TextView title = txt("出片 · " + srcName, 16, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.setMargins(dp(10), 0, 0, 0);
        topRow.addView(title, tlp);
        root.addView(topRow);

        // 大图预览
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Ui.PANEL);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        stlp.setMargins(0, dp(10), 0, 0);
        previewIv = new ImageView(this);
        previewIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // v0.4.1：长按大图显示原图（无边框无滤镜）对比，松手恢复当前效果
        previewIv.setOnLongClickListener(v -> {
            if (srcPreview == null || comparing) return false;
            comparing = true;
            previewIv.setImageBitmap(srcPreview);
            if (compareHintTv != null) compareHintTv.setText("正在看原图，松手恢复当前效果");
            return true;
        });
        previewIv.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (comparing && (act == android.view.MotionEvent.ACTION_UP
                    || act == android.view.MotionEvent.ACTION_CANCEL)) {
                comparing = false;
                if (rendered != null) previewIv.setImageBitmap(rendered);
                if (compareHintTv != null) compareHintTv.setText("长按预览图看原图对比，松手恢复");
            }
            return false; // 不拦截：让长按识别照常工作
        });
        stage.addView(previewIv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        exportBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportBar.setIndeterminate(true);
        exportBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        exportBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams eblp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        stage.addView(exportBar, eblp);
        root.addView(stage, stlp);

        statusTv = txt("正在读取照片…", 12, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svlp.setMargins(0, dp(8), 0, 0);
        root.addView(statusTv, svlp);
        exportTv = txt("", 12, Ui.ACCENT, false);
        root.addView(exportTv);

        // 工具页签
        LinearLayout tabRow = new LinearLayout(this);
        String[] tabNames = {"水印边框", "滤镜", "保存与分享"};
        int[] tabIcons = {R.drawable.ic_badge_image, R.drawable.ic_filter, R.drawable.ic_share};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = txt(tabNames[i], 14, Ui.TEXT_DIM, true);
            t.setGravity(Gravity.CENTER);
            Drawable d = icon(tabIcons[i], Ui.TEXT_DIM);
            int s = dp(20);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawables(null, d, null, null);
            t.setCompoundDrawablePadding(dp(3));
            t.setPadding(0, dp(6), 0, dp(6));
            t.setOnClickListener(v -> showTool(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tabRow.addView(t, lp);
            toolTabs[i] = t;
        }
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.setMargins(0, dp(6), 0, 0);
        root.addView(tabRow, trlp);

        FrameLayout panelFrame = new FrameLayout(this);
        panelFrame.setBackground(getDrawable(R.drawable.bg_card));
        LinearLayout.LayoutParams pflp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)); // v0.4.3：保存页签多了画质行，三页签共用高度小幅上调
        pflp.setMargins(0, dp(4), 0, 0);
        root.addView(panelFrame, pflp);
        toolPanels[0] = buildFramePanel();
        toolPanels[1] = buildFilterPanel();
        toolPanels[2] = buildSavePanel();
        for (View p : toolPanels) {
            panelFrame.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        showTool(0);
        setContentView(root);
    }

    private void showTool(int idx) {
        for (int i = 0; i < 3; i++) {
            toolPanels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            toolTabs[i].setTextColor(i == idx ? Ui.ACCENT : Ui.TEXT_DIM);
            Drawable d = toolTabs[i].getCompoundDrawables()[1];
            if (d != null) d.setTint(i == idx ? Ui.ACCENT : Ui.TEXT_DIM);
        }
    }

    /** 横向滚动的胶囊选项条（边框模板/滤镜共用样式） */
    private TextView chip(String label) {
        TextView t = txt(label, 13, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        return t;
    }

    private void styleChip(TextView t, boolean on) {
        t.setBackground(getDrawable(on ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost));
        t.setTextColor(on ? Ui.ACCENT_TX : Ui.TEXT);
    }

    private LinearLayout panelCol() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        return c;
    }

    private View buildFramePanel() {
        LinearLayout col = panelCol();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < Frames.NAMES.length; i++) {
            final int idx = i;
            TextView c = chip(Frames.NAMES[i]);
            c.setOnClickListener(v -> {
                template = idx;
                dirty = true;
                refreshFrameChips();
                Diag.op("EDIT", "边框模板 → " + Frames.NAMES[idx]);
                scheduleRender();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMargins(dp(8), 0, 0, 0);
            row.addView(c, lp);
            frameChips.add(c);
        }
        scroll.addView(row);
        col.addView(scroll);
        col.addView(txt("参数来自照片 EXIF（机型/镜头/光圈/快门/ISO/焦距），读不到的项自动省略、不编造。导出按原图全尺寸绘制。", 12, Ui.TEXT_DIM, false),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshFrameChips();
        return col;
    }

    private void refreshFrameChips() {
        for (int i = 0; i < frameChips.size(); i++) styleChip(frameChips.get(i), i == template);
    }

    /** 滤镜条一格：catalogIdx=-1 为「原图」格；视图存进与目录同序的数组（槽位 = 目录索引+1） */
    private void addFilterCell(LinearLayout row, final int catalogIdx, String label, boolean first) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(Ui.PANEL_ALT);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(46)));
        TextView name = txt(label, 11, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(0, dp(3), 0, 0);
        cell.addView(name, nlp);
        cell.setOnClickListener(v -> setFilter(catalogIdx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) lp.setMargins(dp(6), 0, 0, 0);
        row.addView(cell, lp);
        int slot = catalogIdx + 1;
        filterChips.set(slot, name);
        filterThumbIvs.set(slot, iv);
        filterThumbCells.set(slot, cell);
    }

    private View buildFilterPanel() {
        LinearLayout col = panelCol();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        // v0.6：与 Filters 目录同序——「原图」格在前，其后按分组（富士/徕卡/经典胶片/黑白/创意/Log）
        // 依次排：分组竖排小标题 + 该组滤镜格。视图数组按「目录索引+1」存取（0=原图），添加顺序只决定显示。
        for (int i = 0; i <= Filters.COUNT; i++) {
            filterChips.add(null);
            filterThumbIvs.add(null);
            filterThumbCells.add(null);
        }
        addFilterCell(row, -1, "原图", true);
        for (int g : Filters.GROUP_ORDER) {
            java.util.List<Integer> idxs = Filters.indicesInGroup(g);
            if (idxs.isEmpty()) continue;
            StringBuilder vn = new StringBuilder();
            String gn = Filters.GROUP_NAMES[g];
            for (int c = 0; c < gn.length(); c++) {
                if (c > 0) vn.append('\n');
                vn.append(gn.charAt(c));
            }
            TextView gh = txt(vn.toString(), 10, Ui.TEXT_DIM, true);
            gh.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ghlp = new LinearLayout.LayoutParams(dp(16),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            ghlp.setMargins(dp(8), 0, dp(2), 0);
            row.addView(gh, ghlp);
            for (int idx : idxs) addFilterCell(row, idx, Filters.NAMES[idx], false);
        }
        scroll.addView(row);
        col.addView(scroll);
        LinearLayout srow = new LinearLayout(this);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        srow.addView(txt("强度", 13, Ui.TEXT_DIM, false));
        strengthBar = new SeekBar(this);
        strengthBar.setMax(100);
        strengthBar.setProgress(100);
        strengthBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        strengthBar.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        sblp.setMargins(dp(10), 0, dp(10), 0);
        srow.addView(strengthBar, sblp);
        strengthValTv = txt("100", 13, Ui.TEXT, true);
        srow.addView(strengthValTv);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.setMargins(0, dp(6), 0, 0);
        col.addView(srow, srlp);
        compareHintTv = txt("长按预览图看原图对比，松手恢复；小样为当前照片的实时渲染", 11, Ui.TEXT_DIM, false);
        col.addView(compareHintTv);
        strengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                strength = progress;
                strengthValTv.setText(String.valueOf(progress));
                if (fromUser && filterIdx >= 0) { dirty = true; scheduleRender(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        refreshFilterChips();
        return col;
    }

    /**
     * 滤镜小样生成——把预览图再降采样到约 180px 作底图，逐滤镜 100% 强度各渲染一张。
     * 只算一次并缓存（filterThumbs 与目录同序），切换强度不重算小样；换照片（新开编辑页）才重算。
     * 小样一律走矩阵求快（规格口径：出片才用 LUT 高精度）；大图预览/导出则按 LUT 渲染，所见即所得。
     * 内存：底图约 180×120×4B ≈ 86KB，v0.6 起 36 张小样 + 底图合计约 3.2MB，用完随页面销毁回收。
     */
    private void buildThumbStrip() {
        if (srcPreview == null) return;
        final Bitmap src = srcPreview;
        bg.execute(() -> {
            int w = src.getWidth(), h = src.getHeight();
            float k = Math.min(1f, (float) THUMB_MAX / Math.max(w, h));
            Bitmap base = k < 1f ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * k)),
                    Math.max(1, Math.round(h * k)), true) : src;
            List<Bitmap> thumbs = new ArrayList<>(); // 与 Filters 目录同序（不含原图格）
            for (int i = 0; i < Filters.COUNT; i++) {
                Bitmap t = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(t);
                android.graphics.Paint p = new android.graphics.Paint();
                p.setFilterBitmap(true);
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(Filters.matrix(i, 100)));
                cv.drawBitmap(base, 0, 0, p);
                thumbs.add(t);
            }
            runOnUiThread(() -> {
                if (isFinishing()) {
                    for (Bitmap t : thumbs) t.recycle();
                    if (base != src) base.recycle();
                    return;
                }
                thumbBase = base;
                filterThumbs.clear();
                filterThumbs.addAll(thumbs);
                if (filterThumbIvs.get(0) != null) filterThumbIvs.get(0).setImageBitmap(base);
                for (int i = 0; i < thumbs.size(); i++) {
                    ImageView iv = filterThumbIvs.get(i + 1);
                    if (iv != null) iv.setImageBitmap(thumbs.get(i));
                }
                Diag.op("EDIT", "滤镜小样生成完成：" + (thumbs.size() + 1) + " 张（含原图，约 " + THUMB_MAX
                        + "px，100% 强度、矩阵口径；切换强度不重算）");
            });
        });
    }

    private void setFilter(int idx) {
        filterIdx = idx;
        dirty = true;
        refreshFilterChips();
        Diag.op("EDIT", "静态滤镜 → " + (idx < 0 ? "原图" : Filters.NAMES[idx]
                + (Filters.isLog(idx) ? "（通用近似，非尼康官方 N-Log 曲线）" : Filters.noteSuffix(idx))
                + (idx >= 0 && Filters.hasLut(idx) ? "（出片走 17³ LUT 高精度）" : "") + "，强度 " + strength + "%"));
        scheduleRender();
    }

    private void refreshFilterChips() {
        // 选中态：小样格黄描边底 + 名称变黄（数组槽位 = 目录索引+1，0=原图对应 filterIdx=-1）
        for (int i = 0; i < filterThumbCells.size(); i++) {
            View cell = filterThumbCells.get(i);
            if (cell == null) continue;
            boolean on = i == filterIdx + 1;
            cell.setBackground(getDrawable(on
                    ? R.drawable.bg_thumb_selected : R.drawable.bg_chip));
            TextView nm = i < filterChips.size() ? filterChips.get(i) : null;
            if (nm != null) nm.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        }
    }

    private void refreshQualityChips() {
        for (int i = 0; i < qualityChips.size(); i++) {
            styleChip(qualityChips.get(i), QUALITY_VALUES[i] == quality);
        }
    }

    private View buildSavePanel() {
        LinearLayout col = panelCol();
        // v0.4.3：导出画质三档（自绘胶囊，样式与边框/滤镜选项一致），选择即持久化
        LinearLayout qrow = new LinearLayout(this);
        qrow.setGravity(Gravity.CENTER_VERTICAL);
        qrow.addView(txt("导出画质", 13, Ui.TEXT_DIM, false));
        for (int i = 0; i < QUALITY_VALUES.length; i++) {
            final int q = QUALITY_VALUES[i];
            TextView c = chip(QUALITY_LABELS[i]);
            c.setOnClickListener(v -> {
                quality = q;
                dirty = true; // 已保存的成品是旧画质，分享前需按新画质重导
                getSharedPreferences("guga_tuchuan", MODE_PRIVATE).edit()
                        .putInt("edit_quality", q).apply();
                refreshQualityChips();
                Diag.op("EDIT", "导出画质 → " + q);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), 0, 0, 0);
            qrow.addView(c, lp);
            qualityChips.add(c);
        }
        col.addView(qrow);
        refreshQualityChips();
        saveInfoTv = txt("成品保存到相册 QingmuTuchuan/Edited，文件名 " + ImageEditor.editedName(srcName)
                + "；原图不覆盖。导出为原图全尺寸重算，24MP 约需十几秒；成品会带回原图 EXIF 参数。", 12, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        silp.setMargins(0, dp(8), 0, 0);
        col.addView(saveInfoTv, silp);
        LinearLayout row = new LinearLayout(this);
        saveBtn = btn("保存到相册", R.drawable.ic_save, true);
        shareBtn = btn("分享", R.drawable.ic_share, false);
        row.addView(saveBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        shlp.setMargins(dp(8), 0, 0, 0);
        row.addView(shareBtn, shlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(10), 0, 0);
        col.addView(row, rlp);
        saveBtn.setOnClickListener(v -> startExport(false));
        shareBtn.setOnClickListener(v -> {
            if (savedUri != null && !dirty) doShare();
            else startExport(true); // 设置变过/还没存：先按当前设置导出，再自动分享
        });
        return col;
    }

    // ---------- 渲染与导出 ----------

    private void scheduleRender() {
        if (srcPreview == null) return;
        final int seq = renderSeq.incrementAndGet();
        final int tpl = template, fidx = filterIdx, str = strength;
        final Bitmap src = srcPreview;
        final ExifMeta m = meta;
        bg.execute(() -> {
            Bitmap out;
            if (tpl == Frames.NONE && fidx < 0) {
                out = src; // 无边框无滤镜：直接用原预览，不额外分配
            } else {
                out = ImageEditor.renderFromBitmap(this, src, tpl, fidx, str, m, srcName);
            }
            final Bitmap fout = out;
            runOnUiThread(() -> {
                if (seq != renderSeq.get() || isFinishing()) {
                    if (fout != src) fout.recycle();
                    return;
                }
                Bitmap old = rendered;
                rendered = fout;
                previewIv.setImageBitmap(fout);
                if (old != null && old != srcPreview && old != fout) old.recycle();
            });
        });
    }

    private void startExport(final boolean thenShare) {
        if (exporting || srcUri == null) return;
        exporting = true;
        pendingShare = thenShare;
        saveBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        exportBar.setVisibility(View.VISIBLE);
        exportTv.setText("正在按原图全尺寸导出…");
        Diag.op("EDIT", "开始导出（模板=" + Frames.NAMES[template] + "，滤镜="
                + (filterIdx >= 0 ? Filters.NAMES[filterIdx] + Filters.noteSuffix(filterIdx) + " " + strength + "%" : "无")
                + "，画质=" + quality + "）");
        final int tpl = template, fidx = filterIdx, str = strength, q = quality;
        final ExifMeta m = meta;
        bg.execute(() -> {
            try {
                final ImageEditor.ExportResult r = ImageEditor.exportFull(
                        EditorActivity.this, srcUri, tpl, fidx, str, m, srcName, q);
                runOnUiThread(() -> {
                    exporting = false;
                    saveBtn.setEnabled(true);
                    shareBtn.setEnabled(true);
                    exportBar.setVisibility(View.GONE);
                    savedUri = r.uri;
                    dirty = false;
                    String note = "已保存到相册 QingmuTuchuan/Edited/" + r.displayName
                            + "（" + r.width + "×" + r.height + (r.scaledDown ? "，内存不足降尺寸导出" : "") + "）";
                    exportTv.setText(note);
                    Toast.makeText(EditorActivity.this, note, Toast.LENGTH_LONG).show();
                    if (pendingShare) { pendingShare = false; doShare(); }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    exporting = false;
                    pendingShare = false;
                    saveBtn.setEnabled(true);
                    shareBtn.setEnabled(true);
                    exportBar.setVisibility(View.GONE);
                    String tip = ImageEditor.friendlyError(e);
                    exportTv.setText("导出失败：" + tip + "（细节见诊断页 EDIT 行）");
                    Toast.makeText(EditorActivity.this, "导出失败：" + tip, Toast.LENGTH_LONG).show();
                });
                Diag.op("EDIT", "导出失败（原始异常）：" + e);
            }
        });
    }

    private void doShare() {
        if (savedUri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, savedUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Diag.op("EDIT", "调起系统分享面板：" + savedUri);
        startActivity(Intent.createChooser(send, "分享成品"));
    }
}
