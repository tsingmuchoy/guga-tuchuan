package com.qingmu.tuchuan;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 清木图传主界面（单 Activity 三页：连接 / 照片 / 诊断）。
 * 风格：B 尼康黄黑风 —— 纯黑底、尼康黄强调、黄边胶囊连接状态条；颜色全走 Ui 常量。
 * 图标：全部 VectorDrawable（R.drawable.ic_*），禁止 emoji/字符充数。
 * 连接双入口：①相机 AP（WifiNetworkSpecifier + 192.168.1.1）②「连接至 PC」/STA（手动 IP+端口）。
 */
public class MainActivity extends Activity {

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ThumbCache thumbCache;

    // 头部/页签
    private LinearLayout capsule;
    private ImageView capsuleIcon;
    private TextView capsuleText;
    private TextView[] tabBtns = new TextView[4];
    private View[] pages = new View[4];

    // 连接页
    private TextView modeApBtn, modeStaBtn;
    private LinearLayout apFields, staFields;
    private EditText ssidEt, passEt, hostEt, portEt;
    private CheckBox wuCheck;
    private Button connectBtn, disconnectBtn, readPhotosBtn;
    private TextView statusTv, devModelTv, devFwTv, devBatteryTv, devStorageTv, profileTv;
    private boolean staMode = false;

    // 照片页
    private TextView selInfoTv;
    private GridView grid;
    private PhotoAdapter adapter;
    private CheckBox diskCacheCheck;
    private TextView dlFileTv;
    private ProgressBar dlBar;
    private Button dlBtn;

    // 诊断页
    private TextView diagTv;
    private ScrollView diagScroll;
    private CheckBox expCheck;

    // 遥控页（v0.2）
    private RemoteController remote;
    private TextView rcStatusTv, rcRemainTv, lvFpsTv, lvStateTv, lastShotTv;
    private LinearLayout rcPropsBox, rcAdjBox;
    private FrameLayout lvStage, rcOverlay;
    private ImageView lvImage, lastShotIv;
    private HudView hud;
    private Button lvToggleBtn, captureBtn, movieBtn;
    private Button lvSizeSmoothBtn, lvSizeClearBtn;
    private TextView lvNoteTv, lvHintTv, ovTitleTv;
    private LinearLayout ovList;
    private CheckBox gridCheck, histCheck;
    private volatile List<RemoteController.PropItem> propItems = new ArrayList<>();
    private float pendingAfNx = -1, pendingAfNy = -1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("guga_tuchuan", MODE_PRIVATE);
        thumbCache = new ThumbCache(this);
        AppState.get().wifi = new CameraWifi(this);
        buildUi();
        Diag.setListener(() -> runOnUiThread(this::refreshDiag));
        Diag.log("清木图传 v0.2.0 启动（首测机型 Z fc，其余机型标准协议兼容待验证；遥控功能待真机逐项验证）");
        DownloadService.listener = new DownloadService.Listener() {
            @Override public void onJobStart(int index, int total, DownloadService.Job job) {
                runOnUiThread(() -> {
                    dlFileTv.setText("下载 " + index + "/" + total + "：" + job.filename);
                    dlBar.setProgress(0);
                });
            }
            @Override public void onProgress(DownloadService.Job job, long fileDone) {
                runOnUiThread(() -> {
                    if (job.size > 0) dlBar.setProgress((int) (fileDone * 100 / job.size));
                    dlFileTv.setText(job.filename + "  " + PhotoItem.fmtSize(fileDone) + "/" + PhotoItem.fmtSize(job.size));
                });
            }
            @Override public void onJobDone(DownloadService.Job job, boolean ok, String error) {
                runOnUiThread(() -> {
                    markDownloaded(job.filename, ok);
                    if (!ok) Toast.makeText(MainActivity.this, job.filename + " 失败：" + error, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onQueueDone(int okCount, int failCount) {
                runOnUiThread(() -> {
                    dlFileTv.setText("下载结束：成功 " + okCount + "，失败 " + failCount);
                    dlBar.setProgress(0);
                    Toast.makeText(MainActivity.this, "下载结束：成功 " + okCount + "，失败 " + failCount, Toast.LENGTH_SHORT).show();
                });
            }
        };
    }

    @Override protected void onDestroy() {
        teardownRemote();
        Diag.setListener(null);
        DownloadService.listener = null;
        super.onDestroy();
    }

    // ================= UI 搭建 =================

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

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.bg_card));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_DIM);
        e.setTextSize(15);
        e.setBackground(getDrawable(R.drawable.bg_input));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        // 标题行：黄相机图标 + 大字标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView titleIcon = new ImageView(this);
        titleIcon.setImageDrawable(icon(R.drawable.ic_camera, Ui.ACCENT));
        titleRow.addView(titleIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView title = txt("清木图传", 22, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(title, tlp);
        TextView ver = txt("v0.2.0", 11, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(dp(8), dp(6), 0, 0);
        titleRow.addView(ver, vlp);
        root.addView(titleRow);

        // 连接状态胶囊条（黄边描边）
        capsule = new LinearLayout(this);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setBackground(getDrawable(R.drawable.bg_capsule_off));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(10), 0, dp(6));
        capsule.setLayoutParams(clp);
        capsuleIcon = new ImageView(this);
        capsuleIcon.setImageDrawable(icon(R.drawable.ic_wifi, Ui.TEXT_DIM));
        capsule.addView(capsuleIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        capsuleText = txt("未连接", 14, Ui.TEXT, true);
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctlp.setMargins(dp(8), 0, 0, 0);
        capsule.addView(capsuleText, ctlp);
        root.addView(capsule);

        // 页签
        LinearLayout tabs = new LinearLayout(this);
        int[] tabIcons = {R.drawable.ic_wifi, R.drawable.ic_badge_image, R.drawable.ic_remote, R.drawable.ic_diag};
        String[] tabNames = {"连接", "照片", "遥控", "诊断"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = txt(tabNames[i], 14, Ui.TEXT_DIM, true);
            t.setGravity(Gravity.CENTER);
            Drawable d = icon(tabIcons[i], Ui.TEXT_DIM);
            int s = dp(20);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawables(null, d, null, null);
            t.setCompoundDrawablePadding(dp(3));
            t.setPadding(0, dp(6), 0, dp(6));
            t.setOnClickListener(v -> showPage(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tabs.addView(t, lp);
            tabBtns[i] = t;
        }
        root.addView(tabs);

        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        pages[0] = buildConnectPage();
        pages[1] = buildPhotosPage();
        pages[2] = buildRemotePage();
        pages[3] = buildDiagPage();
        for (View p : pages) frame.addView(p);
        showPage(0);
        setContentView(root);
    }

    private void showPage(int idx) {
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabBtns[i].setTextColor(i == idx ? Ui.ACCENT : Ui.TEXT_DIM);
            Drawable d = tabBtns[i].getCompoundDrawables()[1];
            if (d != null) d.setTint(i == idx ? Ui.ACCENT : Ui.TEXT_DIM);
        }
        // 切离遥控页时停取景，避免后台占用命令通道拖慢图传
        if (idx != 2 && remote != null && remote.isLiveViewOn()) {
            remote.stopLiveView();
        }
    }

    // ---------- 连接页 ----------
    private View buildConnectPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);

        // 入口切换
        LinearLayout modeRow = new LinearLayout(this);
        modeApBtn = txt("相机热点（AP）", 14, Ui.ACCENT_TX, true);
        modeApBtn.setGravity(Gravity.CENTER);
        modeApBtn.setBackground(getDrawable(R.drawable.bg_btn_primary));
        modeApBtn.setPadding(dp(10), dp(9), dp(10), dp(9));
        modeStaBtn = txt("连接至 PC（填 IP）", 14, Ui.TEXT, true);
        modeStaBtn.setGravity(Gravity.CENTER);
        modeStaBtn.setBackground(getDrawable(R.drawable.bg_btn_ghost));
        modeStaBtn.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        half.setMargins(0, 0, dp(6), 0);
        modeRow.addView(modeApBtn, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        modeRow.addView(modeStaBtn, half2);
        modeApBtn.setOnClickListener(v -> setMode(false));
        modeStaBtn.setOnClickListener(v -> setMode(true));
        col.addView(modeRow);

        // AP 字段
        apFields = new LinearLayout(this);
        apFields.setOrientation(LinearLayout.VERTICAL);
        ssidEt = input("相机 Wi-Fi 名称（SSID）", false);
        passEt = input("相机 Wi-Fi 密码", true);
        apFields.addView(ssidEt, matchWrap());
        LinearLayout.LayoutParams plp = matchWrap(); plp.setMargins(0, dp(8), 0, 0);
        apFields.addView(passEt, plp);
        apFields.addView(txt("在相机上：设置菜单 → Wi-Fi →「建立 Wi-Fi 连接」，屏幕会显示 SSID 与密码。联调前请先强行停止 SnapBridge，它会抢连接。", 12, Ui.TEXT_DIM, false), wrapMargin(8));
        col.addView(apFields);

        // STA 字段
        staFields = new LinearLayout(this);
        staFields.setOrientation(LinearLayout.VERTICAL);
        hostEt = input("相机 IP（如 192.168.0.105）", false);
        portEt = input("端口（默认 15740）", false);
        portEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        staFields.addView(hostEt, matchWrap());
        LinearLayout.LayoutParams plp2 = matchWrap(); plp2.setMargins(0, dp(8), 0, 0);
        staFields.addView(portEt, plp2);
        staFields.addView(txt("相机用「连接至 PC」加入与手机相同的局域网后，相机屏上会显示其 IP，填到这里。手机无需连相机热点。", 12, Ui.TEXT_DIM, false), wrapMargin(8));
        staFields.setVisibility(View.GONE);
        col.addView(staFields);

        wuCheck = new CheckBox(this);
        wuCheck.setText("经 WU-1a/1b 无线适配器连接（老机型）");
        wuCheck.setTextColor(Ui.TEXT);
        wuCheck.setTextSize(13);
        wuCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        col.addView(wuCheck, wrapMargin(6));

        LinearLayout btnRow = new LinearLayout(this);
        connectBtn = btn("连接相机", R.drawable.ic_wifi, true);
        disconnectBtn = btn("断开", R.drawable.ic_clear, false);
        btnRow.addView(connectBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        dlp.setMargins(dp(8), 0, 0, 0);
        btnRow.addView(disconnectBtn, dlp);
        LinearLayout.LayoutParams brlp = matchWrap(); brlp.setMargins(0, dp(10), 0, 0);
        col.addView(btnRow, brlp);
        connectBtn.setOnClickListener(v -> onConnectClicked());
        disconnectBtn.setOnClickListener(v -> onDisconnectClicked());

        statusTv = txt("状态：未连接", 13, Ui.TEXT_DIM, false);
        col.addView(statusTv, wrapMargin(10));

        // 设备信息卡
        LinearLayout devCard = card();
        LinearLayout devHead = new LinearLayout(this);
        devHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView camIc = new ImageView(this);
        camIc.setImageDrawable(icon(R.drawable.ic_camera, Ui.ACCENT));
        devHead.addView(camIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView devTitle = txt("相机信息", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams dhlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dhlp.setMargins(dp(8), 0, 0, 0);
        devHead.addView(devTitle, dhlp);
        devCard.addView(devHead);
        devModelTv = txt("型号：—", 13, Ui.TEXT, false);
        devFwTv = txt("固件：—", 13, Ui.TEXT_DIM, false);
        devCard.addView(devModelTv, wrapMargin(8));
        devCard.addView(devFwTv, wrapMargin(2));
        LinearLayout batRow = new LinearLayout(this);
        batRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView batIc = new ImageView(this);
        batIc.setImageDrawable(icon(R.drawable.ic_battery, Ui.ACCENT));
        batRow.addView(batIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        devBatteryTv = txt("电量：—", 13, Ui.TEXT, false);
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btlp.setMargins(dp(8), 0, 0, 0);
        batRow.addView(devBatteryTv, btlp);
        devCard.addView(batRow, wrapMargin(4));
        LinearLayout sdRow = new LinearLayout(this);
        sdRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView sdIc = new ImageView(this);
        sdIc.setImageDrawable(icon(R.drawable.ic_sdcard, Ui.ACCENT));
        sdRow.addView(sdIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        devStorageTv = txt("存储卡：—", 13, Ui.TEXT, false);
        LinearLayout.LayoutParams sdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sdlp.setMargins(dp(8), 0, 0, 0);
        sdRow.addView(devStorageTv, sdlp);
        devCard.addView(sdRow, wrapMargin(4));
        profileTv = txt("", 12, Ui.TEXT_DIM, false);
        devCard.addView(profileTv, wrapMargin(6));
        col.addView(devCard);

        readPhotosBtn = btn("读取照片", R.drawable.ic_badge_image, true);
        LinearLayout.LayoutParams rlp = matchWrap(); rlp.setMargins(0, dp(8), 0, 0);
        col.addView(readPhotosBtn, rlp);
        readPhotosBtn.setOnClickListener(v -> loadPhotos());

        // 关于/兼容性卡（诚实标注）
        LinearLayout aboutCard = card();
        LinearLayout abHead = new LinearLayout(this);
        abHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView abIc = new ImageView(this);
        abIc.setImageDrawable(icon(R.drawable.ic_settings, Ui.ACCENT));
        abHead.addView(abIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView abTitle = txt("关于与兼容性", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ablp.setMargins(dp(8), 0, 0, 0);
        abHead.addView(abTitle, ablp);
        aboutCard.addView(abHead);
        aboutCard.addView(txt("首测机型：Nikon Z fc。其余尼康机型（Z 系列、内置 Wi-Fi 单反、Coolpix Wi-Fi 机型）走标准 PTP/IP 协议兼容、待真机验证；连接后会按相机型号自动匹配机型 profile（见诊断页 PROFILE 行），未知机型用标准默认档直连、不会拦截。", 12, Ui.TEXT_DIM, false), wrapMargin(8));
        aboutCard.addView(txt("无 Wi-Fi 的机型（如仅蓝牙的 D3400/D3500）没有任何 PTP/IP 入口，物理上无法使用本 App。老机型插上 WU-1a/1b 无线适配器后，勾选上方「经 WU 适配器」再连接。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        col.addView(aboutCard);

        // 回填上次连接信息
        ssidEt.setText(prefs.getString("ssid", ""));
        passEt.setText(prefs.getString("pass", ""));
        hostEt.setText(prefs.getString("host", ""));
        portEt.setText(prefs.getString("port", "15740"));
        wuCheck.setChecked(prefs.getBoolean("wu", false));
        setMode(prefs.getBoolean("sta", false));
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapMargin(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topDp), 0, 0);
        return lp;
    }

    private void setMode(boolean sta) {
        staMode = sta;
        apFields.setVisibility(sta ? View.GONE : View.VISIBLE);
        staFields.setVisibility(sta ? View.VISIBLE : View.GONE);
        modeApBtn.setBackground(getDrawable(sta ? R.drawable.bg_btn_ghost : R.drawable.bg_btn_primary));
        modeApBtn.setTextColor(sta ? Ui.TEXT : Ui.ACCENT_TX);
        modeStaBtn.setBackground(getDrawable(sta ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost));
        modeStaBtn.setTextColor(sta ? Ui.ACCENT_TX : Ui.TEXT);
    }

    // ---------- 照片页 ----------
    private View buildPhotosPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        selInfoTv = txt("已选 0 张 · 0 B", 14, Ui.TEXT, true);
        topRow.addView(selInfoTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button allBtn = btn("全选", R.drawable.ic_check, false);
        Button noneBtn = btn("清空", R.drawable.ic_clear, false);
        topRow.addView(allBtn);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(dp(8), 0, 0, 0);
        topRow.addView(noneBtn, nlp);
        allBtn.setOnClickListener(v -> { for (PhotoItem p : AppState.get().photos) p.selected = true; updateSelInfo(); adapter.notifyDataSetChanged(); });
        noneBtn.setOnClickListener(v -> { for (PhotoItem p : AppState.get().photos) p.selected = false; updateSelInfo(); adapter.notifyDataSetChanged(); });
        col.addView(topRow);

        LinearLayout cacheRow = new LinearLayout(this);
        cacheRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView setIc = new ImageView(this);
        setIc.setImageDrawable(icon(R.drawable.ic_settings, Ui.TEXT_DIM));
        cacheRow.addView(setIc, new LinearLayout.LayoutParams(dp(18), dp(18)));
        diskCacheCheck = new CheckBox(this);
        diskCacheCheck.setText("缩略图磁盘缓存");
        diskCacheCheck.setTextColor(Ui.TEXT_DIM);
        diskCacheCheck.setTextSize(13);
        diskCacheCheck.setChecked(true);
        diskCacheCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        diskCacheCheck.setOnCheckedChangeListener((b, checked) -> {
            thumbCache.diskEnabled = checked;
            Diag.op("THUMB", "磁盘缓存 " + (checked ? "开启" : "关闭"));
        });
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.setMargins(dp(6), 0, 0, 0);
        cacheRow.addView(diskCacheCheck, chlp);
        col.addView(cacheRow);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(0, dp(6), 0, dp(6));
        adapter = new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            PhotoItem p = AppState.get().photos.get(position);
            p.selected = !p.selected;
            updateSelInfo();
            adapter.notifyDataSetChanged();
        });
        col.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dlFileTv = txt("未开始下载", 12, Ui.TEXT_DIM, false);
        col.addView(dlFileTv);
        dlBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlBar.setMax(100);
        dlBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams blp = matchWrap(); blp.setMargins(0, dp(4), 0, dp(8));
        col.addView(dlBar, blp);
        dlBtn = btn("下载所选到相册", R.drawable.ic_download, true);
        col.addView(dlBtn);
        dlBtn.setOnClickListener(v -> onDownloadClicked());
        return col;
    }

    // ---------- 诊断页 ----------
    private View buildDiagPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        Button copyBtn = btn("复制全部", R.drawable.ic_copy, true);
        Button clearBtn = btn("清空", R.drawable.ic_clear, false);
        row.addView(copyBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clp2.setMargins(dp(8), 0, 0, 0);
        row.addView(clearBtn, clp2);
        col.addView(row);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cmg = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cmg.setPrimaryClip(ClipData.newPlainText("guga-diag", Diag.getAllText()));
            Toast.makeText(this, "诊断日志已复制", Toast.LENGTH_SHORT).show();
        });
        clearBtn.setOnClickListener(v -> { Diag.clear(); refreshDiag(); });
        // v0.2 实验开关：非 Z 系机型（profile=BASIC）默认锁定未验证的遥控功能，用户在此手动放行尝试
        expCheck = new CheckBox(this);
        expCheck.setText("实验：为非 Z 系机型开启全部遥控功能（LiveView/对焦/录像/参数调节，未验证）");
        expCheck.setTextColor(Ui.TEXT);
        expCheck.setTextSize(13);
        expCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        expCheck.setChecked(prefs.getBoolean("remote_exp", false));
        expCheck.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("remote_exp", checked).apply();
            if (remote != null) remote.setExperimental(checked);
            else Diag.op("REMOTE", "实验开关 → " + checked + "（连接相机后生效）");
        });
        col.addView(expCheck, wrapMargin(8));
        diagScroll = new ScrollView(this);
        diagScroll.setBackground(getDrawable(R.drawable.bg_card));
        diagTv = txt("", 11, Ui.TEXT_DIM, false);
        diagTv.setTypeface(Typeface.MONOSPACE);
        diagScroll.addView(diagTv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        slp.setMargins(0, dp(10), 0, 0);
        col.addView(diagScroll, slp);
        return col;
    }

    private void refreshDiag() {
        if (diagTv == null) return;
        diagTv.setText(Diag.getAllText());
        if (diagScroll != null) diagScroll.post(() -> diagScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ================= 遥控页（v0.2） =================

    private Button smallGhostBtn(String label) {
        Button b = btn(label, 0, false);
        b.setTextSize(13);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        return b;
    }

    private View buildRemotePage() {
        FrameLayout pageRoot = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        pageRoot.addView(scroll);

        rcStatusTv = txt("未连接相机（先到「连接」页连接）", 13, Ui.TEXT_DIM, false);
        col.addView(rcStatusTv, wrapMargin(2));

        // —— 相机状态面板 ——
        LinearLayout stCard = card();
        LinearLayout stHead = new LinearLayout(this);
        stHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView stIc = new ImageView(this);
        stIc.setImageDrawable(icon(R.drawable.ic_camera, Ui.ACCENT));
        stHead.addView(stIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView stTitle = txt("相机状态", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        stlp.setMargins(dp(8), 0, 0, 0);
        stHead.addView(stTitle, stlp);
        Button refreshBtn = smallGhostBtn("刷新");
        refreshBtn.setOnClickListener(v -> {
            if (remote != null) remote.loadProps(AppState.get().storageId);
            else Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show();
        });
        stHead.addView(refreshBtn);
        stCard.addView(stHead);
        rcRemainTv = txt("剩余可拍：—", 13, Ui.TEXT, false);
        stCard.addView(rcRemainTv, wrapMargin(8));
        rcPropsBox = new LinearLayout(this);
        rcPropsBox.setOrientation(LinearLayout.VERTICAL);
        stCard.addView(rcPropsBox, wrapMargin(4));
        col.addView(stCard);

        // —— 实时取景 ——
        LinearLayout lvCard = card();
        LinearLayout lvHead = new LinearLayout(this);
        lvHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView lvIc = new ImageView(this);
        lvIc.setImageDrawable(icon(R.drawable.ic_remote, Ui.ACCENT));
        lvHead.addView(lvIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView lvTitle = txt("实时取景", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams lvtlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lvtlp.setMargins(dp(8), 0, 0, 0);
        lvHead.addView(lvTitle, lvtlp);
        lvStateTv = txt("未开启", 12, Ui.ACCENT, true);
        lvHead.addView(lvStateTv);
        lvCard.addView(lvHead);

        lvStage = new FrameLayout(this);
        lvStage.setBackgroundColor(0xFF050505);
        lvStage.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(252)));
        lvImage = new ImageView(this);
        lvImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lvStage.addView(lvImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hud = new HudView(this);
        lvStage.addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lvHintTv = txt("点下方「开启取景」开始监看\n取景中点画面任意位置即点按对焦", 13, Ui.TEXT_DIM, false);
        lvHintTv.setGravity(Gravity.CENTER);
        lvStage.addView(lvHintTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lvStage.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != MotionEvent.ACTION_UP) return true;
            if (remote == null || !remote.isLiveViewOn()) return true;
            RectF r = hud.displayRect();
            float px = ev.getX(), py = ev.getY();
            if (r.width() <= 0 || px < r.left || px > r.right || py < r.top || py > r.bottom) {
                rcStatusTv.setText("点在画面外（黑边），已忽略");
                return true;
            }
            float nx = (px - r.left) / r.width();
            float ny = (py - r.top) / r.height();
            pendingAfNx = nx; pendingAfNy = ny;
            hud.showAf(nx, ny, true); // 先显示黄框占位，相机结果回来按成败改色
            remote.tapFocus(nx, ny);
            return true;
        });
        LinearLayout.LayoutParams stglp = matchWrap(); stglp.setMargins(0, dp(8), 0, 0);
        lvCard.addView(lvStage, stglp);

        LinearLayout lvOptRow = new LinearLayout(this);
        lvOptRow.setGravity(Gravity.CENTER_VERTICAL);
        gridCheck = new CheckBox(this);
        gridCheck.setText("网格线");
        gridCheck.setTextColor(Ui.TEXT);
        gridCheck.setTextSize(13);
        gridCheck.setChecked(true);
        gridCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        gridCheck.setOnCheckedChangeListener((b, c) -> hud.setGridOn(c));
        lvOptRow.addView(gridCheck);
        histCheck = new CheckBox(this);
        histCheck.setText("直方图");
        histCheck.setTextColor(Ui.TEXT);
        histCheck.setTextSize(13);
        histCheck.setChecked(true);
        histCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        histCheck.setOnCheckedChangeListener((b, c) -> {
            hud.setHistOn(c);
            if (remote != null) remote.setHistogramEnabled(c);
        });
        LinearLayout.LayoutParams hclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hclp.setMargins(dp(14), 0, 0, 0);
        lvOptRow.addView(histCheck, hclp);
        lvCard.addView(lvOptRow, wrapMargin(6));

        // 帧尺寸档：流畅（小帧锁帧率）/ 清晰（大帧看细节）；相机未提供 0xD1AC 时整行置灰
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLbl = txt("帧尺寸", 13, Ui.TEXT_DIM, false);
        sizeRow.addView(sizeLbl);
        lvSizeSmoothBtn = smallGhostBtn("流畅");
        lvSizeClearBtn = smallGhostBtn("清晰");
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.setMargins(dp(10), 0, 0, 0);
        sizeRow.addView(lvSizeSmoothBtn, sblp);
        LinearLayout.LayoutParams sblp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp2.setMargins(dp(8), 0, 0, 0);
        sizeRow.addView(lvSizeClearBtn, sblp2);
        lvSizeSmoothBtn.setOnClickListener(v -> { if (remote != null) remote.setLvSmooth(true); });
        lvSizeClearBtn.setOnClickListener(v -> { if (remote != null) remote.setLvSmooth(false); });
        lvCard.addView(sizeRow, wrapMargin(4));
        lvNoteTv = txt("", 12, Ui.ACCENT, false);
        lvCard.addView(lvNoteTv, wrapMargin(4));

        lvToggleBtn = btn("开启取景", R.drawable.ic_remote, true);
        lvToggleBtn.setOnClickListener(v -> {
            if (remote == null) { Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show(); return; }
            if (remote.isLiveViewOn()) remote.stopLiveView(); else remote.startLiveView();
        });
        lvCard.addView(lvToggleBtn, wrapMargin(8));
        col.addView(lvCard);

        // —— 拍摄 ——
        LinearLayout shotCard = card();
        LinearLayout shotRow = new LinearLayout(this);
        captureBtn = btn("拍摄", R.drawable.ic_shutter, true);
        movieBtn = btn("录像", R.drawable.ic_rec, false);
        shotRow.addView(captureBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams mvlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mvlp.setMargins(dp(8), 0, 0, 0);
        shotRow.addView(movieBtn, mvlp);
        shotCard.addView(shotRow);
        shotCard.addView(txt("拍摄后新照片会自动出现在下方「拍完即览」（只拉预览图，原图到照片页下载）。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        captureBtn.setOnClickListener(v -> { if (remote != null) remote.capture(); });
        movieBtn.setOnClickListener(v -> { if (remote != null) remote.toggleMovie(); });
        col.addView(shotCard);

        // —— 参数调节 ——
        LinearLayout adjCard = card();
        LinearLayout adjHead = new LinearLayout(this);
        adjHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView adjIc = new ImageView(this);
        adjIc.setImageDrawable(icon(R.drawable.ic_settings, Ui.ACCENT));
        adjHead.addView(adjIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView adjTitle = txt("参数调节", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams adjlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adjlp.setMargins(dp(8), 0, 0, 0);
        adjHead.addView(adjTitle, adjlp);
        adjCard.addView(adjHead);
        adjCard.addView(txt("选项全部来自相机自报的取值集合，写入后自动回读确认；相机当前模式不允许改的参数会置灰。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        rcAdjBox = new LinearLayout(this);
        rcAdjBox.setOrientation(LinearLayout.VERTICAL);
        adjCard.addView(rcAdjBox, wrapMargin(4));
        col.addView(adjCard);

        // —— 拍完即览 ——
        LinearLayout lastCard = card();
        TextView lastTitle = txt("拍完即览", 15, Ui.TEXT, true);
        lastCard.addView(lastTitle);
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setGravity(Gravity.CENTER_VERTICAL);
        lastShotIv = new ImageView(this);
        lastShotIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastShotIv.setBackgroundColor(Ui.PANEL_ALT);
        lastRow.addView(lastShotIv, new LinearLayout.LayoutParams(dp(96), dp(64)));
        lastShotTv = txt("暂无（遥控拍摄后显示）", 13, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams lstlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lstlp.setMargins(dp(10), 0, 0, 0);
        lastRow.addView(lastShotTv, lstlp);
        lastCard.addView(lastRow, wrapMargin(8));
        col.addView(lastCard);

        // —— 参数选择弹层（自绘深色卡，禁系统灰弹窗） ——
        rcOverlay = new FrameLayout(this);
        rcOverlay.setBackgroundColor(0xB3000000);
        rcOverlay.setVisibility(View.GONE);
        LinearLayout ovCard = new LinearLayout(this);
        ovCard.setOrientation(LinearLayout.VERTICAL);
        ovCard.setBackground(getDrawable(R.drawable.bg_card));
        ovCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        FrameLayout.LayoutParams ovlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        ovlp.setMargins(dp(22), 0, dp(22), 0);
        ovTitleTv = txt("选择", 16, Ui.TEXT, true);
        ovCard.addView(ovTitleTv);
        ScrollView ovScroll = new ScrollView(this);
        ovList = new LinearLayout(this);
        ovList.setOrientation(LinearLayout.VERTICAL);
        ovScroll.addView(ovList);
        ovScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        ovCard.addView(ovScroll);
        ovCard.setOnClickListener(v -> { /* 点卡片本身不关闭，拦截冒泡 */ });
        Button ovClose = btn("取消", 0, false);
        ovClose.setOnClickListener(v -> rcOverlay.setVisibility(View.GONE));
        LinearLayout.LayoutParams ovclp = matchWrap(); ovclp.setMargins(0, dp(10), 0, 0);
        ovCard.addView(ovClose, ovclp);
        rcOverlay.addView(ovCard, ovlp);
        rcOverlay.setOnClickListener(v -> rcOverlay.setVisibility(View.GONE));
        pageRoot.addView(rcOverlay);

        refreshRemoteEnabled();
        return pageRoot;
    }

    /** 按连接/门控状态刷新遥控页按钮可用性（未连接一律禁用，功能降级/锁定由 renderProps 与状态文案体现） */
    private void refreshRemoteEnabled() {
        boolean conn = AppState.get().sessionReady() && remote != null;
        if (lvToggleBtn != null) lvToggleBtn.setEnabled(conn);
        if (captureBtn != null) captureBtn.setEnabled(conn && (remote == null || remote.featureAllowed(RemoteController.F_CAPTURE)));
        if (movieBtn != null) movieBtn.setEnabled(conn && (remote == null || remote.featureAllowed(RemoteController.F_MOVIE)));
        if (!conn && rcStatusTv != null && AppState.get().client == null) {
            rcStatusTv.setText("未连接相机（先到「连接」页连接）");
        }
    }

    private void updateLvSizeButtons(List<String> options, int currentIdx, boolean supported) {
        boolean conn = AppState.get().sessionReady() && remote != null;
        if (lvSizeSmoothBtn == null) return;
        lvSizeSmoothBtn.setEnabled(conn && supported && options != null && options.size() > 1);
        lvSizeClearBtn.setEnabled(conn && supported && options != null && options.size() > 1);
        if (options != null && options.size() > 1) {
            lvSizeSmoothBtn.setText("流畅（" + options.get(0) + "）");
            lvSizeClearBtn.setText("清晰（" + options.get(options.size() - 1) + "）");
        } else {
            lvSizeSmoothBtn.setText("流畅");
            lvSizeClearBtn.setText("清晰");
        }
        if (remote != null) lvStateTv.setText(remote.lvSizeLabel());
    }

    /** 渲染状态面板（只读项）与参数调节区（可调项）；remainText=null 表示不更新剩余张数行 */
    private void renderProps(String remainText) {
        if (rcPropsBox == null) return;
        if (remainText != null) rcRemainTv.setText("剩余可拍：" + remainText);
        rcPropsBox.removeAllViews();
        rcAdjBox.removeAllViews();
        boolean writeAllowed = remote != null && remote.featureAllowed(RemoteController.F_PROPS_WRITE);
        for (RemoteController.PropItem it : propItems) {
            // 只读展示行
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = txt(it.def.name, 13, Ui.TEXT, false);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView val = txt(it.available() ? it.currentText() : "—（相机未提供）",
                    13, it.available() ? Ui.TEXT : Ui.TEXT_DIM, true);
            row.addView(val);
            rcPropsBox.addView(row, wrapMargin(3));
            // 调节行
            if (it.def.adjustable) {
                LinearLayout arow = new LinearLayout(this);
                arow.setGravity(Gravity.CENTER_VERTICAL);
                TextView an = txt(it.def.name, 14, Ui.TEXT, false);
                arow.addView(an, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView av = txt(it.available() ? it.currentText() : "—",
                        14, it.available() ? Ui.ACCENT : Ui.TEXT_DIM, true);
                arow.addView(av);
                Button adj = smallGhostBtn("调整");
                String why = null;
                if (!it.available()) why = "相机未提供该属性";
                else if (!it.canWrite()) why = "相机报告不可写/无取值集（当前模式可能受限）";
                else if (!writeAllowed) why = "当前机型未验证，诊断页开实验开关后可调";
                adj.setEnabled(why == null);
                if (why != null) {
                    TextView wt = txt(why, 11, Ui.TEXT_DIM, false);
                    LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    wlp.setMargins(dp(8), 0, 0, 0);
                    arow.addView(wt, wlp);
                } else {
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    alp.setMargins(dp(8), 0, 0, 0);
                    arow.addView(adj, alp);
                    final RemoteController.PropItem fit = it;
                    adj.setOnClickListener(v -> showPropPicker(fit));
                }
                rcAdjBox.addView(arow, wrapMargin(6));
            }
        }
        refreshRemoteEnabled();
    }

    /** 参数选择弹层：选项严格来自相机 desc（枚举原样列全；范围型按步进生成并封顶） */
    private void showPropPicker(final RemoteController.PropItem item) {
        PtpIpClient.DevicePropDesc d = item.desc;
        if (d == null) return;
        ovList.removeAllViews();
        ovTitleTv.setText("选择" + item.def.name + "（当前 " + item.currentText() + "）");
        List<PtpIpClient.PropValue> opts = new ArrayList<>();
        if (d.formFlag == 2) {
            opts.addAll(d.enumValues);
        } else if (d.formFlag == 1 && d.rangeMin != null && d.rangeMax != null && d.rangeStep != null) {
            long min = d.rangeMin.num, max = d.rangeMax.num, step = d.rangeStep.num;
            if (step <= 0) step = 1;
            if ((max - min) / step > 60) step = Math.max(step, (max - min) / 60); // 封顶 60 项防弹层过长
            for (long v = min; v <= max; v += step) opts.add(new PtpIpClient.PropValue(d.datatype, v));
        }
        if (opts.isEmpty()) {
            ovList.addView(txt("相机未给出可选值集合，无法调节。", 13, Ui.TEXT_DIM, false), wrapMargin(8));
        }
        for (final PtpIpClient.PropValue pv : opts) {
            boolean cur = d.curVal != null && pv.datatype == PtpIpClient.DTC_STR
                    ? String.valueOf(pv.str).equals(String.valueOf(d.curVal.str))
                    : d.curVal != null && pv.num == d.curVal.num;
            Button b = btn(CamProps.format(item.def.code, pv) + (cur ? "（当前）" : ""), 0, cur);
            b.setOnClickListener(v -> {
                rcOverlay.setVisibility(View.GONE);
                if (remote != null) remote.setProp(item, pv);
            });
            LinearLayout.LayoutParams blp = matchWrap(); blp.setMargins(0, dp(6), 0, 0);
            ovList.addView(b, blp);
        }
        rcOverlay.setVisibility(View.VISIBLE);
    }

    private final RemoteController.Ui remoteUi = new RemoteController.Ui() {
        @Override public void onProps(List<RemoteController.PropItem> items, String remainText) {
            propItems = items;
            runOnUiThread(() -> renderProps(remainText));
        }
        @Override public void onPropUpdated(RemoteController.PropItem item, boolean ok, String msg) {
            runOnUiThread(() -> {
                rcStatusTv.setText(item.def.name + "：" + msg);
                renderProps(null); // desc 已被中枢回读更新，原样重渲染
            });
        }
        @Override public void onFrame(Bitmap bmp, float fps) {
            if (pages[2].getVisibility() != View.VISIBLE) return; // 不在遥控页不做 UI 工作
            runOnUiThread(() -> {
                lvImage.setImageBitmap(bmp);
                hud.setFrameSize(bmp.getWidth(), bmp.getHeight());
                if (lvHintTv.getVisibility() == View.VISIBLE) lvHintTv.setVisibility(View.GONE);
            });
        }
        @Override public void onHistogram(float[][] bins) {
            runOnUiThread(() -> hud.setHistogram(bins));
        }
        @Override public void onStatus(String msg) {
            runOnUiThread(() -> rcStatusTv.setText(msg));
        }
        @Override public void onAfResult(boolean ok, String msg) {
            runOnUiThread(() -> {
                rcStatusTv.setText(msg);
                if (pendingAfNx >= 0) hud.showAf(pendingAfNx, pendingAfNy, ok);
            });
        }
        @Override public void onNewPhoto(int handle, Bitmap thumb, String filename) {
            runOnUiThread(() -> {
                if (thumb != null) lastShotIv.setImageBitmap(thumb);
                lastShotTv.setText(filename + "（预览；原图在照片页下载）");
            });
        }
        @Override public void onMovie(boolean recording) {
            runOnUiThread(() -> {
                movieBtn.setText(recording ? "停止录像" : "录像");
                Drawable d = icon(recording ? R.drawable.ic_stop : R.drawable.ic_rec, Ui.ERR);
                int s = dp(19);
                d.setBounds(0, 0, s, s);
                movieBtn.setCompoundDrawables(d, null, null, null);
            });
        }
        @Override public void onLiveView(boolean on, String msg) {
            runOnUiThread(() -> {
                lvToggleBtn.setText(on ? "关闭取景" : "开启取景");
                if (!on) {
                    lvStateTv.setText("未开启");
                    lvHintTv.setVisibility(View.VISIBLE);
                }
                rcStatusTv.setText(msg);
            });
        }
        @Override public void onFeatureStateChanged() {
            runOnUiThread(() -> { refreshRemoteEnabled(); renderProps(null); });
        }
        @Override public void onLvState(String sizeLabel, int targetFps, float displayFps, String note) {
            runOnUiThread(() -> {
                lvStateTv.setText(sizeLabel + " · " + String.format("%.0f", displayFps) + "fps");
                if (note != null) lvNoteTv.setText(note);
            });
        }
        @Override public void onLvSizes(List<String> options, int currentIdx, boolean supported) {
            runOnUiThread(() -> updateLvSizeButtons(options, currentIdx, supported));
        }
    };

    /** 断开/换连时拆遥控中枢（停取景与录像，摘事件监听）；图传主链路不受影响 */
    private void teardownRemote() {
        if (remote != null) {
            try { remote.shutdown(); } catch (Exception ignore) {}
            remote = null;
        }
        runOnUiThread(() -> {
            propItems = new ArrayList<>();
            if (rcPropsBox != null) { rcPropsBox.removeAllViews(); rcAdjBox.removeAllViews(); }
            if (rcRemainTv != null) rcRemainTv.setText("剩余可拍：—");
            if (rcStatusTv != null) rcStatusTv.setText("未连接相机（先到「连接」页连接）");
            if (lvToggleBtn != null) lvToggleBtn.setText("开启取景");
            if (lvStateTv != null) lvStateTv.setText("未开启");
            if (lvNoteTv != null) lvNoteTv.setText("");
            refreshRemoteEnabled();
        });
    }


    // ================= 连接流程 =================

    private byte[] initiatorGuid() {
        String hex = prefs.getString("guid", null);
        if (hex != null && hex.length() == 32) {
            byte[] g = new byte[16];
            for (int i = 0; i < 16; i++) g[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return g;
        }
        byte[] g = new byte[16];
        new Random().nextBytes(g);
        StringBuilder sb = new StringBuilder();
        for (byte b : g) sb.append(String.format("%02x", b & 0xFF));
        prefs.edit().putString("guid", sb.toString()).apply();
        return g;
    }

    private void setStatus(String s, int color) {
        runOnUiThread(() -> { statusTv.setText("状态：" + s); statusTv.setTextColor(color); });
    }

    private void setCapsule(boolean on, String text) {
        runOnUiThread(() -> {
            capsule.setBackground(getDrawable(on ? R.drawable.bg_capsule_on : R.drawable.bg_capsule_off));
            capsuleIcon.setImageDrawable(icon(R.drawable.ic_wifi, on ? Ui.ACCENT : Ui.TEXT_DIM));
            capsuleText.setText(text);
            capsuleText.setTextColor(on ? Ui.TEXT : Ui.TEXT_DIM);
        });
    }

    private void onConnectClicked() {
        String ssid = ssidEt.getText().toString().trim();
        String pass = passEt.getText().toString();
        String host = hostEt.getText().toString().trim();
        String portS = portEt.getText().toString().trim();
        prefs.edit().putString("ssid", ssid).putString("pass", pass)
                .putString("host", host).putString("port", portS)
                .putBoolean("wu", wuCheck.isChecked()).putBoolean("sta", staMode).apply();

        if (Build.VERSION.SDK_INT >= 33) {
            List<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 1001);
                Toast.makeText(this, "授权后再点一次连接", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        int port = 15740;
        try { port = Integer.parseInt(portS); } catch (Exception ignore) {}

        if (staMode) {
            if (host.isEmpty()) { Toast.makeText(this, "先填相机 IP", Toast.LENGTH_SHORT).show(); return; }
            setStatus("STA 模式连接 " + host + ":" + port + " …", Ui.TEXT_DIM);
            final int fp = port;
            bg.execute(() -> doPtpConnect(null, host, fp));
            return;
        }
        // AP 模式
        if (Build.VERSION.SDK_INT >= 29) {
            if (ssid.isEmpty()) { Toast.makeText(this, "先填相机 SSID", Toast.LENGTH_SHORT).show(); return; }
            setStatus("正在请求连接相机热点…（系统弹窗请确认）", Ui.TEXT_DIM);
            AppState.get().wifi.connect(ssid, pass, new CameraWifi.Callback() {
                @Override public void onAvailable(Network network) {
                    bg.execute(() -> doPtpConnect(network, PtpIpClient.DEFAULT_HOST, PtpIpClient.DEFAULT_PORT));
                }
                @Override public void onLost() {
                    setCapsule(false, "未连接（热点丢失）");
                    setStatus("相机热点连接丢失", Ui.ERR);
                }
                @Override public void onUnavailable(String reason) {
                    setStatus(reason, Ui.ERR);
                }
            });
        } else {
            // 低版本：用户已在系统设置手动连热点（或默认网络即相机 AP），直连默认地址
            setStatus("低版本直连 " + PtpIpClient.DEFAULT_HOST + " …", Ui.TEXT_DIM);
            Network n = AppState.get().wifi.findWifiNetwork();
            bg.execute(() -> doPtpConnect(n, PtpIpClient.DEFAULT_HOST, PtpIpClient.DEFAULT_PORT));
        }
    }

    private void doPtpConnect(Network network, String host, int port) {
        AppState st = AppState.get();
        try {
            if (st.client != null) { try { st.client.disconnect(); } catch (Exception ignore) {} }
            PtpIpClient client = new PtpIpClient(initiatorGuid(), "QingmuTuchuan");
            st.client = client;
            st.network = network;
            client.setConnectionListener(reason -> {
                setCapsule(false, "连接已断开");
                setStatus("连接断开：" + reason + "（下载中会自动重连）", Ui.ERR);
                teardownRemote();
            });
            setStatus("PTP/IP 握手中…", Ui.TEXT_DIM);
            client.connect(network, host, port);
            client.openSession();
            PtpIpClient.DeviceInfo di = client.getDeviceInfo();
            CameraProfile profile = CameraProfile.match(di.model, wuCheck.isChecked());
            client.applyProfile(profile);
            st.deviceInfo = di;
            st.profile = profile;
            st.battery = client.getBatteryLevel();
            int[] sids = client.getStorageIds();
            if (sids.length > 0) {
                st.storageId = sids[0];
                PtpIpClient.StorageInfo si = client.getStorageInfo(sids[0]);
                st.storageDesc = (si.description != null && !si.description.isEmpty()) ? si.description : "存储卡";
                final String storageLine = st.storageDesc + " · 剩余 " + PhotoItem.fmtSize(si.freeBytes)
                        + " / " + PhotoItem.fmtSize(si.maxCapacity);
                runOnUiThread(() -> devStorageTv.setText("存储卡：" + storageLine));
                if (sids.length > 1) Diag.op("PTP", "共 " + sids.length + " 个存储，v0.1 先用第一个");
            }
            final String model = (di.manufacturer + " " + di.model).trim();
            runOnUiThread(() -> {
                devModelTv.setText("型号：" + model + "（序列号 " + di.serial + "）");
                devFwTv.setText("固件：" + di.version);
                devBatteryTv.setText("电量：" + (st.battery >= 0 ? st.battery + "%" : "—"));
                profileTv.setText("机型 profile：" + profile.id + "（" + profile.note + "）");
            });
            setCapsule(true, "已连接 · " + (client.getCameraName().isEmpty() ? model : client.getCameraName()));
            // v0.2：装配遥控中枢（查帧尺寸档、自动读一次状态面板）；失败只记诊断，不影响图传主链路
            try {
                teardownRemote();
                remote = new RemoteController(client, profile, prefs.getBoolean("remote_exp", false), remoteUi);
                remote.setStorageId(st.storageId);
                remote.queryLvSize();
                remote.loadProps(st.storageId);
                runOnUiThread(() -> {
                    rcStatusTv.setText("已连接 · " + model + "（遥控功能待真机逐项验证）");
                    refreshRemoteEnabled();
                });
            } catch (Exception e) {
                Diag.op("REMOTE", "遥控中枢装配失败（图传不受影响）：" + e.getMessage());
            }
            if (!client.isEventChannelOk()) {
                setStatus("已连接（功能受限：事件通道未建立，纯命令通道模式）", Ui.ACCENT);
            } else {
                setStatus("已连接，可以读取照片", Ui.OK);
            }
        } catch (Exception e) {
            Diag.op("PTPIP", "连接失败：" + e.getMessage());
            setStatus("连接失败：" + e.getMessage() + "（细节见诊断页）", Ui.ERR);
            setCapsule(false, "未连接");
        }
    }

    private void onDisconnectClicked() {
        bg.execute(() -> {
            teardownRemote();
            AppState st = AppState.get();
            if (st.client != null) { try { st.client.disconnect(); } catch (Exception ignore) {} st.client = null; }
            st.wifi.disconnect();
            st.network = null;
            st.resetSession();
            runOnUiThread(() -> {
                setCapsule(false, "未连接");
                setStatus("已断开", Ui.TEXT_DIM);
                devModelTv.setText("型号：—");
                devFwTv.setText("固件：—");
                devBatteryTv.setText("电量：—");
                devStorageTv.setText("存储卡：—");
                profileTv.setText("");
                adapter.notifyDataSetChanged();
                updateSelInfo();
            });
        });
    }

    // ================= 照片加载/下载 =================

    private void loadPhotos() {
        AppState st = AppState.get();
        if (!st.sessionReady()) { Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show(); return; }
        showPage(1);
        selInfoTv.setText("正在读取照片列表…");
        bg.execute(() -> {
            try {
                int[] handles = st.client.getObjectHandles(st.storageId);
                List<PhotoItem> list = new ArrayList<>();
                int done = 0;
                for (int h : handles) {
                    try {
                        PtpIpClient.ObjectInfo oi = st.client.getObjectInfo(h);
                        if (oi.format == 0x3001) continue; // 文件夹/关联对象跳过
                        list.add(new PhotoItem(h, oi));
                    } catch (Exception e) {
                        Diag.op("PTP", "GetObjectInfo 失败 handle=0x" + Integer.toHexString(h) + "：" + e.getMessage());
                    }
                    if (++done % 100 == 0) Diag.op("PTP", "ObjectInfo 进度 " + done + "/" + handles.length);
                }
                Collections.sort(list, (a, b) -> b.sortKey().compareTo(a.sortKey())); // 新→旧
                st.photos.clear();
                st.photos.addAll(list);
                Diag.op("PTP", "照片列表就绪：" + list.size() + " 张");
                runOnUiThread(() -> { adapter.notifyDataSetChanged(); updateSelInfo(); });
            } catch (Exception e) {
                Diag.op("PTP", "读取照片失败：" + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateSelInfo() {
        int n = 0; long bytes = 0;
        for (PhotoItem p : AppState.get().photos) if (p.selected) { n++; bytes += p.size(); }
        selInfoTv.setText("已选 " + n + " 张 · " + PhotoItem.fmtSize(bytes)
                + "（共 " + AppState.get().photos.size() + " 张）");
    }

    private void markDownloaded(String filename, boolean ok) {
        if (!ok) return;
        for (PhotoItem p : AppState.get().photos) {
            if (p.filename().equals(filename)) { p.downloaded = true; p.selected = false; }
        }
        adapter.notifyDataSetChanged();
        updateSelInfo();
    }

    private void onDownloadClicked() {
        List<DownloadService.Job> jobs = new ArrayList<>();
        for (PhotoItem p : AppState.get().photos) {
            if (p.selected) jobs.add(new DownloadService.Job(p.handle, p.filename(), p.size()));
        }
        if (jobs.isEmpty()) { Toast.makeText(this, "先勾选要下载的照片", Toast.LENGTH_SHORT).show(); return; }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
            Toast.makeText(this, "授权存储后再点一次下载", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadService.enqueue(this, jobs);
        Toast.makeText(this, "开始下载 " + jobs.size() + " 张（见通知栏进度）", Toast.LENGTH_SHORT).show();
    }

    // ================= 照片墙 Adapter =================

    private class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return AppState.get().photos.size(); }
        @Override public Object getItem(int pos) { return AppState.get().photos.get(pos); }
        @Override public long getItemId(int pos) { return AppState.get().photos.get(pos).handle; }

        @Override public View getView(int pos, View convertView, ViewGroup parent) {
            final PhotoItem item = AppState.get().photos.get(pos);
            FrameLayout cell;
            ImageView thumbIv, checkIv, badgeIv;
            TextView nameTv;
            View selFrame;
            if (convertView == null) {
                cell = new FrameLayout(MainActivity.this);
                int h = (grid.getWidth() / 3) > 0 ? grid.getWidth() / 3 : dp(110);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h));
                cell.setBackgroundColor(Ui.PANEL);
                thumbIv = new ImageView(MainActivity.this);
                thumbIv.setId(1);
                thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(thumbIv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                selFrame = new View(MainActivity.this);
                selFrame.setId(4);
                selFrame.setBackground(getDrawable(R.drawable.bg_thumb_selected));
                selFrame.setVisibility(View.GONE);
                cell.addView(selFrame, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                checkIv = new ImageView(MainActivity.this);
                checkIv.setId(2);
                checkIv.setImageResource(R.drawable.ic_check_badge);
                FrameLayout.LayoutParams chlp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
                chlp.setMargins(0, dp(4), dp(4), 0);
                cell.addView(checkIv, chlp);
                LinearLayout bottom = new LinearLayout(MainActivity.this);
                bottom.setGravity(Gravity.CENTER_VERTICAL);
                bottom.setBackgroundColor(0xB3000000);
                badgeIv = new ImageView(MainActivity.this);
                badgeIv.setId(3);
                bottom.addView(badgeIv, new LinearLayout.LayoutParams(dp(15), dp(15)));
                nameTv = new TextView(MainActivity.this);
                nameTv.setId(5);
                nameTv.setTextSize(9);
                nameTv.setTextColor(Ui.TEXT);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.setMargins(dp(4), 0, 0, 0);
                bottom.addView(nameTv, nlp);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(20), Gravity.BOTTOM);
                cell.addView(bottom, blp);
            } else {
                cell = (FrameLayout) convertView;
                thumbIv = cell.findViewById(1);
                checkIv = cell.findViewById(2);
                badgeIv = cell.findViewById(3);
                selFrame = cell.findViewById(4);
                nameTv = cell.findViewById(5);
            }
            checkIv.setVisibility(item.selected ? View.VISIBLE : View.GONE);
            selFrame.setVisibility(item.selected ? View.VISIBLE : View.GONE);
            nameTv.setText(item.filename() + (item.downloaded ? "（已传）" : ""));
            int badgeRes, badgeTint;
            switch (item.kind()) {
                case NEF: badgeRes = R.drawable.ic_badge_nef; badgeTint = Ui.BADGE_NEF; break;
                case MP4: badgeRes = R.drawable.ic_badge_video; badgeTint = Ui.BADGE_MP4; break;
                default: badgeRes = R.drawable.ic_badge_image; badgeTint = Ui.BADGE_JPG; break;
            }
            Drawable bd = icon(badgeRes, badgeTint);
            badgeIv.setImageDrawable(bd);
            // 缩略图：缓存命中直接用；未命中先放矢量占位（格式徽标放大），异步加载回来再换
            Bitmap bmp = thumbCache.getMem(item.handle);
            thumbIv.setTag(item.handle);
            if (bmp != null) {
                thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbIv.setImageBitmap(bmp);
            } else {
                thumbIv.setScaleType(ImageView.ScaleType.CENTER);
                thumbIv.setImageDrawable(icon(badgeRes, Ui.TEXT_DIM));
                final ImageView target = thumbIv;
                thumbCache.load(AppState.get().client, item.handle, AppState.get().profile,
                        (handle, bitmap) -> runOnUiThread(() -> {
                            Object tag = target.getTag();
                            if (tag instanceof Integer && (Integer) tag == handle) {
                                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                target.setImageBitmap(bitmap);
                            }
                        }));
            }
            return cell;
        }
    }
}
