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

    // 照片页 v0.3：自动回传 / 筛选 / 配对 / 删除 / 相机管理
    private TextView autoBannerTv;
    private CheckBox autoDlCheck;
    private TextView autoJpgBtn, autoAllBtn;
    private TextView autoHintTv;
    private Button delBtn;
    private ConfirmCard phConfirm;
    private FrameLayout phOverlay;
    private TextView phOvTitleTv;
    private LinearLayout phOvList;
    /** 图库展示行（配对折叠后）；PhotoItem 仍是数据本体，行只是视图聚合 */
    private static class GalleryRow {
        PhotoItem a, b; // b 非空 = RAW+JPG 配对行（a/b 顺序为列表出现序）
        GalleryRow(PhotoItem a) { this.a = a; }
        boolean isPair() { return b != null; }
        java.util.List<PhotoItem> files() {
            java.util.List<PhotoItem> r = new ArrayList<>();
            r.add(a); if (b != null) r.add(b);
            return r;
        }
        PhotoItem jpgMember() {
            if (a.kind() == PhotoItem.Kind.JPG) return a;
            if (b != null && b.kind() == PhotoItem.Kind.JPG) return b;
            return a;
        }
        int selectedCount() { int n = 0; for (PhotoItem p : files()) if (p.selected) n++; return n; }
        int downloadedCount() { int n = 0; for (PhotoItem p : files()) if (p.downloaded) n++; return n; }
    }
    private final List<GalleryRow> displayRows = new ArrayList<>();
    private static final int FILTER_ALL = 0, FILTER_NOT_DL = 1, FILTER_JPG = 2, FILTER_RAW = 3, FILTER_DATE = 4;
    private int filterMode = FILTER_ALL;
    private String filterDate = null; // yyyyMMdd
    private TextView[] filterChips = new TextView[5];

    // 连接页 v0.3：相机管理（格式化）
    private Button formatBtn;
    private TextView formatWarnTv;
    private ConfirmCard connConfirm;

    // 遥控页 v0.3：间隔拍摄
    private EditText ivSecEt, ivCountEt;
    private Button ivBtn;
    private TextView ivStatusTv;

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
        DownloadRecord.init(this);
        AppState.get().wifi = new CameraWifi(this);
        buildUi();
        Diag.setListener(() -> runOnUiThread(this::refreshDiag));
        Diag.log("清木图传 v0.3.0 启动（首测机型 Z fc，其余机型标准协议兼容待验证；遥控/图传增强/相机管理功能待真机逐项验证）");
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
        IntervalService.listener = new IntervalService.Listener() {
            @Override public void onProgress(int done, int total) {
                runOnUiThread(() -> {
                    if (ivStatusTv != null) ivStatusTv.setText("间隔拍摄中：已拍 " + done + (total > 0 ? "/" + total : "") + " 张");
                    if (ivBtn != null) ivBtn.setText("停止间隔拍摄");
                });
            }
            @Override public void onFinished(int done, String error) {
                runOnUiThread(() -> {
                    if (ivBtn != null) ivBtn.setText("开始间隔拍摄");
                    if (ivStatusTv != null) ivStatusTv.setText(error != null ? error : "间隔拍摄结束：共拍 " + done + " 张");
                    if (error != null) Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        };
    }

    @Override protected void onDestroy() {
        teardownRemote();
        Diag.setListener(null);
        DownloadService.listener = null;
        IntervalService.listener = null;
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
        TextView ver = txt("v0.3.0", 11, Ui.TEXT_DIM, false);
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
        FrameLayout pageRoot = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        pageRoot.addView(scroll);

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

        // —— 相机管理卡（v0.3）：格式化存储卡。高危：入口被实验开关门控（诊断页，未开不可见） ——
        LinearLayout mgmtCard = card();
        LinearLayout mgHead = new LinearLayout(this);
        mgHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mgIc = new ImageView(this);
        mgIc.setImageDrawable(icon(R.drawable.ic_sdcard, Ui.ACCENT));
        mgHead.addView(mgIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView mgTitle = txt("相机管理", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams mglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mglp.setMargins(dp(8), 0, 0, 0);
        mgHead.addView(mgTitle, mglp);
        mgmtCard.addView(mgHead);
        formatWarnTv = txt("格式化会清空当前存储卡上的全部照片与视频，不可恢复。本功能未经真机验证，强烈建议先拿一张空卡/废卡试，不要对主力卡首次使用。", 12, Ui.TEXT_DIM, false);
        mgmtCard.addView(formatWarnTv, wrapMargin(8));
        formatBtn = btn("格式化存储卡", R.drawable.ic_delete, false);
        formatBtn.setTextColor(Ui.ERR);
        LinearLayout.LayoutParams fblp = matchWrap(); fblp.setMargins(0, dp(8), 0, 0);
        mgmtCard.addView(formatBtn, fblp);
        formatBtn.setOnClickListener(v -> onFormatClicked());
        col.addView(mgmtCard);
        updateFormatVisibility();

        // 回填上次连接信息
        ssidEt.setText(prefs.getString("ssid", ""));
        passEt.setText(prefs.getString("pass", ""));
        hostEt.setText(prefs.getString("host", ""));
        portEt.setText(prefs.getString("port", "15740"));
        wuCheck.setChecked(prefs.getBoolean("wu", false));
        setMode(prefs.getBoolean("sta", false));
        connConfirm = new ConfirmCard(this, pageRoot);
        return pageRoot;
    }

    /** 格式化入口门控：仅当诊断页实验开关开启时可见（默认隐藏，防误触高危操作） */
    private void updateFormatVisibility() {
        boolean on = prefs != null && prefs.getBoolean("remote_exp", false);
        if (formatBtn != null) formatBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (formatWarnTv != null) formatWarnTv.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** 格式化两段确认：①普通确认卡讲清后果 → ②长按 2 秒确认卡 → 才真正发 FormatStore */
    private void onFormatClicked() {
        AppState st = AppState.get();
        if (!st.sessionReady() || st.storageId == 0) {
            Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show();
            return;
        }
        final int sid = st.storageId;
        final String desc = (st.storageDesc != null && !st.storageDesc.isEmpty()) ? st.storageDesc : "存储卡";
        Diag.op("FORMAT", "用户点开格式化流程（尚未执行）：存储=" + desc + " storageId=0x" + Integer.toHexString(sid));
        connConfirm.show("格式化存储卡？",
                "目标：" + desc + "\n\n格式化将清空这张卡上的全部照片与视频，删除后不可恢复（已下载到手机相册的副本不受影响）。\n\n本功能未经真机验证，请勿对主力卡首次使用。",
                "继续", true,
                () -> connConfirm.showLongPress("最后确认：格式化",
                        "再次确认：即将格式化「" + desc + "」，卡上全部内容将被清空。\n\n请按住下方按钮满 2 秒执行，松手即取消。",
                        "长按确认格式化", 2000,
                        () -> doFormat(sid, desc)));
    }

    private void doFormat(final int storageId, final String desc) {
        Diag.op("FORMAT", "双重确认完成，执行 FormatStore storageId=0x" + Integer.toHexString(storageId));
        bg.execute(() -> {
            PtpIpClient client = AppState.get().client;
            if (client == null || !client.isConnected()) {
                runOnUiThread(() -> Toast.makeText(this, "相机未连接，格式化未执行", Toast.LENGTH_LONG).show());
                return;
            }
            int rc = client.formatStore(storageId);
            if (rc == PtpIpClient.RC_OK) {
                // 卡上对象已不存在：照片列表与已下载记录整体失效，一并清掉防同名误判
                AppState.get().photos.clear();
                DownloadRecord.clearAll(this);
                runOnUiThread(() -> {
                    rebuildDisplay();
                    updateSelInfo();
                    devStorageTv.setText("存储卡：已格式化（" + desc + "）");
                    Toast.makeText(this, "格式化完成：相机返回 OK", Toast.LENGTH_LONG).show();
                });
                Diag.op("FORMAT", "格式化成功，本地照片列表与已下载记录已同步清除");
            } else {
                runOnUiThread(() -> Toast.makeText(this,
                        "格式化失败：相机返回 rc=0x" + Integer.toHexString(rc) + "（详见诊断页）", Toast.LENGTH_LONG).show());
            }
        });
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
        FrameLayout pageRoot = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        pageRoot.addView(col);

        // 自动回传状态横幅（开启时在图库页顶部明示）
        autoBannerTv = txt("", 13, Ui.ACCENT_TX, true);
        autoBannerTv.setBackgroundColor(Ui.ACCENT);
        autoBannerTv.setPadding(dp(10), dp(6), dp(10), dp(6));
        autoBannerTv.setVisibility(View.GONE);
        col.addView(autoBannerTv);

        // —— 拍完自动回传卡 ——
        LinearLayout autoCard = card();
        LinearLayout autoHead = new LinearLayout(this);
        autoHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView autoIc = new ImageView(this);
        autoIc.setImageDrawable(icon(R.drawable.ic_auto, Ui.ACCENT));
        autoHead.addView(autoIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView autoTitle = txt("拍完自动回传", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams atlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        atlp.setMargins(dp(8), 0, 0, 0);
        autoHead.addView(autoTitle, atlp);
        autoDlCheck = new CheckBox(this);
        autoDlCheck.setText("开启");
        autoDlCheck.setTextColor(Ui.TEXT);
        autoDlCheck.setTextSize(13);
        autoDlCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        autoDlCheck.setChecked(prefs.getBoolean("auto_dl", false)); // 默认关，先回填再挂监听防误触发
        autoHead.addView(autoDlCheck);
        autoCard.addView(autoHead);
        autoCard.addView(txt("连接期间相机拍的新照片（遥控拍摄或直接按相机快门都算）自动排队下载到相册；与已下载记录去重，不会重复传。默认关。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        LinearLayout autoModeRow = new LinearLayout(this);
        autoModeRow.setGravity(Gravity.CENTER_VERTICAL);
        autoModeRow.addView(txt("范围", 13, Ui.TEXT_DIM, false));
        autoJpgBtn = txt("仅 JPG", 13, Ui.TEXT, true);
        autoJpgBtn.setGravity(Gravity.CENTER);
        autoJpgBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        autoAllBtn = txt("JPG + RAW", 13, Ui.TEXT, true);
        autoAllBtn.setGravity(Gravity.CENTER);
        autoAllBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams amlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amlp.setMargins(dp(10), 0, 0, 0);
        autoModeRow.addView(autoJpgBtn, amlp);
        LinearLayout.LayoutParams amlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        amlp2.setMargins(dp(8), 0, 0, 0);
        autoModeRow.addView(autoAllBtn, amlp2);
        autoCard.addView(autoModeRow, wrapMargin(8));
        autoHintTv = txt("", 12, Ui.TEXT_DIM, false);
        autoCard.addView(autoHintTv, wrapMargin(6));
        col.addView(autoCard);
        autoDlCheck.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("auto_dl", checked).apply();
            Diag.op("AUTO", "自动回传开关 → " + (checked ? "开" : "关（默认关，用户手动开启才生效）"));
            applyAutoSettings();
        });
        autoJpgBtn.setOnClickListener(v -> {
            prefs.edit().putInt("auto_dl_mode", 0).apply();
            applyAutoSettings();
        });
        autoAllBtn.setOnClickListener(v -> {
            prefs.edit().putInt("auto_dl_mode", 1).apply();
            applyAutoSettings();
        });

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        selInfoTv = txt("已选 0 个文件 · 0 B", 14, Ui.TEXT, true);
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

        // —— 筛选条（全部/未下载/仅 JPG/仅 RAW/按日期） ——
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView fIc = new ImageView(this);
        fIc.setImageDrawable(icon(R.drawable.ic_filter, Ui.TEXT_DIM));
        filterRow.addView(fIc, new LinearLayout.LayoutParams(dp(18), dp(18)));
        android.widget.HorizontalScrollView fScroll = new android.widget.HorizontalScrollView(this);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipsBox = new LinearLayout(this);
        String[] chipNames = {"全部", "未下载", "仅 JPG", "仅 RAW", "日期"};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView chip = txt(chipNames[i], 13, Ui.TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setOnClickListener(v -> onFilterChip(idx));
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.setMargins(dp(6), 0, 0, 0);
            chipsBox.addView(chip, chp);
            filterChips[i] = chip;
        }
        fScroll.addView(chipsBox);
        LinearLayout.LayoutParams fslp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        fslp.setMargins(dp(4), 0, 0, 0);
        filterRow.addView(fScroll, fslp);
        col.addView(filterRow, wrapMargin(6));
        refreshFilterChips();

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
        LinearLayout.LayoutParams chlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp2.setMargins(dp(6), 0, 0, 0);
        cacheRow.addView(diskCacheCheck, chlp2);
        col.addView(cacheRow);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(0, dp(6), 0, dp(6));
        adapter = new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= displayRows.size()) return;
            GalleryRow row = displayRows.get(position);
            if (row.isPair()) { showPairChooser(row); return; }
            row.a.selected = !row.a.selected;
            updateSelInfo();
            adapter.notifyDataSetChanged();
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= displayRows.size()) return true;
            GalleryRow row = displayRows.get(position);
            boolean target = row.selectedCount() < row.files().size();
            for (PhotoItem p : row.files()) p.selected = target;
            updateSelInfo();
            adapter.notifyDataSetChanged();
            return true;
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
        LinearLayout dlRow = new LinearLayout(this);
        delBtn = btn("删除所选", R.drawable.ic_delete, false);
        delBtn.setTextColor(Ui.ERR);
        dlBtn = btn("下载所选到相册", R.drawable.ic_download, true);
        dlRow.addView(delBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        drlp.setMargins(dp(8), 0, 0, 0);
        dlRow.addView(dlBtn, drlp);
        col.addView(dlRow);
        dlBtn.setOnClickListener(v -> onDownloadClicked());
        delBtn.setOnClickListener(v -> onDeleteClicked());

        // —— 通用列表弹层（配对选择 / 日期选择共用，自绘深色卡） ——
        phOverlay = new FrameLayout(this);
        phOverlay.setBackgroundColor(0xB3000000);
        phOverlay.setVisibility(View.GONE);
        LinearLayout ovCard = new LinearLayout(this);
        ovCard.setOrientation(LinearLayout.VERTICAL);
        ovCard.setBackground(getDrawable(R.drawable.bg_card));
        ovCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        FrameLayout.LayoutParams ovlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        ovlp.setMargins(dp(22), 0, dp(22), 0);
        phOvTitleTv = txt("选择", 16, Ui.TEXT, true);
        ovCard.addView(phOvTitleTv);
        ScrollView ovScroll = new ScrollView(this);
        phOvList = new LinearLayout(this);
        phOvList.setOrientation(LinearLayout.VERTICAL);
        ovScroll.addView(phOvList);
        ovScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        ovCard.addView(ovScroll);
        ovCard.setOnClickListener(v -> { /* 拦截冒泡 */ });
        Button ovClose = btn("取消", 0, false);
        ovClose.setOnClickListener(v -> phOverlay.setVisibility(View.GONE));
        LinearLayout.LayoutParams ovclp = matchWrap(); ovclp.setMargins(0, dp(10), 0, 0);
        ovCard.addView(ovClose, ovclp);
        phOverlay.addView(ovCard, ovlp);
        phOverlay.setOnClickListener(v -> phOverlay.setVisibility(View.GONE));
        pageRoot.addView(phOverlay);
        phConfirm = new ConfirmCard(this, pageRoot);

        updateAutoUi();
        return pageRoot;
    }

    // ---------- v0.3 自动回传 ----------

    /** 远控中枢的自动回传接管方：在其 exec 线程被调用，只做去重判定 + 非阻塞入队 */
    private final RemoteController.AutoTransferHandler autoTransferHandler = (handle, oi) -> {
        if (DownloadRecord.isDownloaded(oi.filename)) {
            Diag.op("AUTO", oi.filename + " 已在已下载记录中，自动回传跳过（去重）");
            return true;
        }
        if (DownloadService.hasPending(oi.filename)) {
            Diag.op("AUTO", oi.filename + " 已在下载队列中，自动回传跳过（去重）");
            return true;
        }
        try {
            DownloadService.enqueue(MainActivity.this,
                    Collections.singletonList(new DownloadService.Job(handle, oi.filename, oi.size)));
            Diag.op("AUTO", "自动回传入队：" + oi.filename + "（" + PhotoItem.fmtSize(oi.size) + "）");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "自动回传：" + oi.filename, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            // 后台拉起前台服务可能被系统限制：明确记诊断，不静默丢失
            Diag.op("AUTO", "自动回传入队失败（可能被系统后台启动限制，保持 App 在前台可避免）：" + e.getMessage());
        }
        return true;
    };

    /** 把当前自动回传设置同步给遥控中枢 + 刷新横幅/按钮样式（连接装配、开关变更时调用） */
    private void applyAutoSettings() {
        boolean on = prefs.getBoolean("auto_dl", false);
        boolean raw = prefs.getInt("auto_dl_mode", 0) == 1;
        if (remote != null) remote.setAutoTransfer(on, raw, autoTransferHandler);
        updateAutoUi();
    }

    private void styleAutoModeBtn(TextView t, boolean on) {
        if (t == null) return;
        t.setBackground(getDrawable(on ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost));
        t.setTextColor(on ? Ui.ACCENT_TX : Ui.TEXT);
    }

    private void updateAutoUi() {
        if (autoBannerTv == null) return;
        boolean on = prefs.getBoolean("auto_dl", false);
        boolean raw = prefs.getInt("auto_dl_mode", 0) == 1;
        styleAutoModeBtn(autoJpgBtn, !raw);
        styleAutoModeBtn(autoAllBtn, raw);
        autoBannerTv.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) autoBannerTv.setText("自动回传已开启：新照片拍完自动下载到相册（范围：" + (raw ? "JPG + RAW" : "仅 JPG") + "）");
        PtpIpClient c = AppState.get().client;
        if (c == null || !c.isConnected()) {
            autoHintTv.setText("连接相机后生效：靠相机的新照片事件触发回传。");
        } else if (!c.isEventChannelOk()) {
            autoHintTv.setText("注意：当前连接没有事件通道（含 WU 适配器/部分 Coolpix 机型，或连接时事件通道建立失败），相机不会推送新照片事件，自动回传不会触发；手动下载不受影响。");
        } else {
            autoHintTv.setText("事件通道正常，自动回传待命中（拍一张试试）。");
        }
    }

    // ---------- v0.3 筛选与配对 ----------

    private boolean photoVisible(PhotoItem p) {
        switch (filterMode) {
            case FILTER_NOT_DL: return !p.downloaded;
            case FILTER_JPG: return p.kind() == PhotoItem.Kind.JPG;
            case FILTER_RAW: return p.kind() == PhotoItem.Kind.NEF;
            case FILTER_DATE: return filterDate != null && filterDate.equals(p.dateKey());
            default: return true;
        }
    }

    /** 按当前筛选重建展示行：同基名 NEF+JPG 折叠成一条配对行（双成员都可见才成对） */
    private void rebuildDisplay() {
        displayRows.clear();
        java.util.Map<String, GalleryRow> pairMap = new java.util.HashMap<>();
        for (PhotoItem p : AppState.get().photos) {
            if (!photoVisible(p)) continue;
            PhotoItem.Kind k = p.kind();
            if (k == PhotoItem.Kind.JPG || k == PhotoItem.Kind.NEF) {
                GalleryRow r = pairMap.get(p.baseName());
                if (r == null) {
                    r = new GalleryRow(p);
                    pairMap.put(p.baseName(), r);
                    displayRows.add(r);
                } else if (r.b == null && r.a.kind() != k) {
                    r.b = p; // 配对完成
                } else {
                    displayRows.add(new GalleryRow(p)); // 同基名同格式重复（罕见）独立成行
                }
            } else {
                displayRows.add(new GalleryRow(p));
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void refreshFilterChips() {
        for (int i = 0; i < filterChips.length; i++) {
            if (filterChips[i] == null) continue;
            boolean sel = filterMode == i;
            filterChips[i].setBackground(getDrawable(sel ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost));
            filterChips[i].setTextColor(sel ? Ui.ACCENT_TX : Ui.TEXT);
        }
        if (filterChips[FILTER_DATE] != null) {
            filterChips[FILTER_DATE].setText(filterMode == FILTER_DATE
                    ? "日期：" + fmtDateLabel(filterDate) : "日期");
        }
    }

    private static String fmtDateLabel(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() < 8) return "未知日期";
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    private void onFilterChip(int idx) {
        if (idx == FILTER_DATE) { showDateChooser(); return; }
        filterMode = idx;
        Diag.op("FILTER", "筛选 → " + new String[]{"全部", "未下载", "仅 JPG", "仅 RAW", "按日期"}[idx]);
        refreshFilterChips();
        rebuildDisplay();
        updateSelInfo();
    }

    private void phOverlayShow(String title) {
        phOvTitleTv.setText(title);
        phOvList.removeAllViews();
        phOverlay.setVisibility(View.VISIBLE);
    }

    private void phOverlayAddNote(String text) {
        phOvList.addView(txt(text, 12, Ui.TEXT_DIM, false), wrapMargin(6));
    }

    private void phOverlayAddBtn(String label, boolean primary, final Runnable r) {
        Button b = btn(label, 0, primary);
        b.setOnClickListener(v -> { phOverlay.setVisibility(View.GONE); r.run(); });
        LinearLayout.LayoutParams lp = matchWrap(); lp.setMargins(0, dp(6), 0, 0);
        phOvList.addView(b, lp);
    }

    /** 配对行点击：让用户分别选两个文件下哪个（计数按文件数） */
    private void showPairChooser(final GalleryRow row) {
        PhotoItem jpg = null, nef = null;
        for (PhotoItem p : row.files()) {
            if (p.kind() == PhotoItem.Kind.JPG) jpg = p;
            if (p.kind() == PhotoItem.Kind.NEF) nef = p;
        }
        final PhotoItem fJpg = jpg, fNef = nef;
        boolean both = jpg != null && nef != null && jpg.selected && nef.selected;
        boolean jpgOnly = jpg != null && jpg.selected && (nef == null || !nef.selected);
        boolean nefOnly = nef != null && nef.selected && (jpg == null || !jpg.selected);
        phOverlayShow(row.a.baseName() + "（RAW + JPG 配对）");
        phOverlayAddNote("同一次拍摄的两个文件，分别勾选要下载哪个（已选计数按文件数算）。两个都已有「已下载」标记的不会重复下载。");
        phOverlayAddBtn("两个都选（JPG " + PhotoItem.fmtSize(jpg != null ? jpg.size() : 0)
                + " + NEF " + PhotoItem.fmtSize(nef != null ? nef.size() : 0) + "）", both,
                () -> applyPairSelection(row, true, true));
        phOverlayAddBtn("只选 JPG（" + PhotoItem.fmtSize(jpg != null ? jpg.size() : 0) + "）", jpgOnly && !both,
                () -> applyPairSelection(row, true, false));
        phOverlayAddBtn("只选 NEF（" + PhotoItem.fmtSize(nef != null ? nef.size() : 0) + "）", nefOnly && !both,
                () -> applyPairSelection(row, false, true));
        phOverlayAddBtn("两个都不选", !both && !jpgOnly && !nefOnly,
                () -> applyPairSelection(row, false, false));
    }

    private void applyPairSelection(GalleryRow row, boolean jpgSel, boolean nefSel) {
        for (PhotoItem p : row.files()) {
            if (p.kind() == PhotoItem.Kind.JPG) p.selected = jpgSel;
            else if (p.kind() == PhotoItem.Kind.NEF) p.selected = nefSel;
        }
        updateSelInfo();
        adapter.notifyDataSetChanged();
    }

    /** 日期筛选弹层：只列照片里实际存在的日期（自绘深色卡列表，禁系统日期弹窗） */
    private void showDateChooser() {
        phOverlayShow("按日期筛选");
        if (AppState.get().photos.isEmpty()) {
            phOverlayAddNote("还没有照片列表，先点「读取照片」。");
            return;
        }
        phOverlayAddBtn("全部日期（不限）", filterMode != FILTER_DATE, () -> {
            filterMode = FILTER_ALL;
            refreshFilterChips(); rebuildDisplay(); updateSelInfo();
        });
        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>(Collections.reverseOrder());
        for (PhotoItem p : AppState.get().photos) {
            String k = p.dateKey();
            counts.put(k, counts.getOrDefault(k, 0) + 1);
        }
        for (final java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            final String key = e.getKey();
            String label = (key.isEmpty() ? "未知日期" : fmtDateLabel(key)) + " · " + e.getValue() + " 张";
            phOverlayAddBtn(label, filterMode == FILTER_DATE && key.equals(filterDate), () -> {
                filterMode = FILTER_DATE;
                filterDate = key;
                Diag.op("FILTER", "按日期筛选 → " + (key.isEmpty() ? "未知日期" : key));
                refreshFilterChips(); rebuildDisplay(); updateSelInfo();
            });
        }
    }

    // ---------- v0.3 删除照片 ----------

    private List<PhotoItem> selectedFiles() {
        List<PhotoItem> r = new ArrayList<>();
        for (PhotoItem p : AppState.get().photos) if (p.selected) r.add(p);
        return r;
    }

    private void onDeleteClicked() {
        final List<PhotoItem> targets = selectedFiles();
        if (targets.isEmpty()) {
            Toast.makeText(this, "先勾选要删除的照片（配对照片点开可选删哪个）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!AppState.get().sessionReady()) {
            Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("将删除相机存储卡上的 ").append(targets.size()).append(" 个原文件：\n");
        int show = Math.min(6, targets.size());
        for (int i = 0; i < show; i++) {
            body.append("· ").append(targets.get(i).filename())
                    .append("（").append(PhotoItem.fmtSize(targets.get(i).size())).append("）\n");
        }
        if (targets.size() > show) body.append("· ……等共 ").append(targets.size()).append(" 个\n");
        body.append("\n删的是相机卡上的原文件，删除后不可恢复！已下载到手机相册的副本不受影响。");
        phConfirm.show("删除相机照片？", body.toString(), "删除 " + targets.size() + " 个", true,
                () -> doDelete(targets));
    }

    /** 逐张 DeleteObject：成功从列表与已下载记录移除；失败逐张记诊断并在结果里点名 */
    private void doDelete(final List<PhotoItem> targets) {
        Diag.op("DELETE", "批量删除开始：" + targets.size() + " 个文件");
        bg.execute(() -> {
            PtpIpClient client = AppState.get().client;
            if (client == null || !client.isConnected()) {
                runOnUiThread(() -> Toast.makeText(this, "相机未连接，删除未执行", Toast.LENGTH_LONG).show());
                return;
            }
            int ok = 0;
            List<String> fails = new ArrayList<>();
            for (PhotoItem p : targets) {
                int rc = client.deleteObject(p.handle, p.info != null ? p.info.format : 0);
                if (rc == PtpIpClient.RC_OK) {
                    ok++;
                    AppState.get().photos.remove(p);
                    DownloadRecord.remove(p.filename());
                    Diag.op("DELETE", "已删除 " + p.filename());
                } else {
                    fails.add(p.filename() + "（rc=0x" + Integer.toHexString(rc) + "）");
                    Diag.op("DELETE", "删除失败 " + p.filename() + " rc=0x" + Integer.toHexString(rc));
                }
            }
            final int okF = ok;
            final List<String> failsF = fails;
            runOnUiThread(() -> {
                rebuildDisplay();
                updateSelInfo();
                String msg = "删除完成：成功 " + okF + (failsF.isEmpty() ? " 个" : "，失败 " + failsF.size() + " 个（" + failsF.get(0) + (failsF.size() > 1 ? " 等，详见诊断" : "") + "）");
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
            Diag.op("DELETE", "批量删除结束：成功 " + ok + "，失败 " + fails.size());
        });
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
        expCheck.setText("实验：为非 Z 系机型开启全部遥控功能（LiveView/对焦/录像/参数调节，未验证）；同时显示连接页「格式化存储卡」高危入口");
        expCheck.setTextColor(Ui.TEXT);
        expCheck.setTextSize(13);
        expCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        expCheck.setChecked(prefs.getBoolean("remote_exp", false));
        expCheck.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("remote_exp", checked).apply();
            if (remote != null) remote.setExperimental(checked);
            else Diag.op("REMOTE", "实验开关 → " + checked + "（连接相机后生效）");
            updateFormatVisibility();
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

        // —— 间隔拍摄（v0.3） ——
        LinearLayout ivCard = card();
        LinearLayout ivHead = new LinearLayout(this);
        ivHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ivIc = new ImageView(this);
        ivIc.setImageDrawable(icon(R.drawable.ic_timer, Ui.ACCENT));
        ivHead.addView(ivIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ivTitle = txt("间隔拍摄", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams ivtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ivtlp.setMargins(dp(8), 0, 0, 0);
        ivHead.addView(ivTitle, ivtlp);
        ivCard.addView(ivHead);
        ivCard.addView(txt("按固定间隔自动遥控拍摄（前台服务保活，锁屏也能继续）。与照片页「拍完自动回传」同开可拍一张传一张。任一张拍摄失败会立刻报错并停下，不静默重试。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        LinearLayout ivInputRow = new LinearLayout(this);
        ivInputRow.setGravity(Gravity.CENTER_VERTICAL);
        ivInputRow.addView(txt("间隔（秒）", 13, Ui.TEXT_DIM, false));
        ivSecEt = input("5", false);
        ivSecEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        ivSecEt.setText("5");
        LinearLayout.LayoutParams islp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        islp.setMargins(dp(8), 0, 0, 0);
        ivInputRow.addView(ivSecEt, islp);
        TextView ivCntLbl = txt("张数（0=无限）", 13, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams icllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        icllp.setMargins(dp(12), 0, 0, 0);
        ivInputRow.addView(ivCntLbl, icllp);
        ivCountEt = input("10", false);
        ivCountEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        ivCountEt.setText("10");
        LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        iclp.setMargins(dp(8), 0, 0, 0);
        ivInputRow.addView(ivCountEt, iclp);
        ivCard.addView(ivInputRow, wrapMargin(8));
        ivBtn = btn("开始间隔拍摄", R.drawable.ic_timer, true);
        ivBtn.setOnClickListener(v -> onIntervalToggle());
        ivCard.addView(ivBtn, wrapMargin(8));
        ivStatusTv = txt("未开始", 12, Ui.TEXT_DIM, false);
        ivCard.addView(ivStatusTv, wrapMargin(6));
        col.addView(ivCard);

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

    /** 间隔拍摄开关：运行中→请求停止；未运行→校验参数与门控后启动前台服务 */
    private void onIntervalToggle() {
        if (IntervalService.isRunning()) {
            IntervalService.requestStop();
            ivStatusTv.setText("正在停止间隔拍摄…");
            return;
        }
        if (!AppState.get().sessionReady()) {
            Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show();
            return;
        }
        if (remote != null && !remote.featureAllowed(RemoteController.F_CAPTURE)) {
            Toast.makeText(this, "当前机型遥控快门未解锁或已降级，间隔拍摄不可用（见诊断页）", Toast.LENGTH_LONG).show();
            return;
        }
        int sec = 5, cnt = 10;
        try { sec = Integer.parseInt(ivSecEt.getText().toString().trim()); } catch (Exception ignore) {}
        try { cnt = Integer.parseInt(ivCountEt.getText().toString().trim()); } catch (Exception ignore) {}
        if (sec < 1) sec = 1;
        if (cnt < 0) cnt = 0;
        ivSecEt.setText(String.valueOf(sec));
        ivCountEt.setText(String.valueOf(cnt));
        Diag.op("INTERVAL", "用户启动间隔拍摄：间隔 " + sec + "s，张数 " + (cnt > 0 ? cnt : "无限"));
        IntervalService.start(this, sec, cnt);
        ivBtn.setText("停止间隔拍摄");
        ivStatusTv.setText("间隔拍摄启动中…");
    }

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
                applyAutoSettings(); // 把自动回传设置（开关/范围）同步给新装配的中枢 + 刷新横幅
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
                rebuildDisplay();
                updateSelInfo();
                updateAutoUi();
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
                int dlCount = 0;
                for (PhotoItem p : list) {
                    p.downloaded = DownloadRecord.isDownloaded(p.filename());
                    if (p.downloaded) dlCount++;
                }
                st.photos.clear();
                st.photos.addAll(list);
                Diag.op("PTP", "照片列表就绪：" + list.size() + " 张（其中已下载 " + dlCount + " 张，据已下载记录 "
                        + DownloadRecord.count() + " 条比对）");
                runOnUiThread(() -> { rebuildDisplay(); updateSelInfo(); updateAutoUi(); });
            } catch (Exception e) {
                Diag.op("PTP", "读取照片失败：" + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateSelInfo() {
        int n = 0; long bytes = 0;
        for (PhotoItem p : AppState.get().photos) if (p.selected) { n++; bytes += p.size(); }
        String s = "已选 " + n + " 个文件 · " + PhotoItem.fmtSize(bytes)
                + "（共 " + AppState.get().photos.size() + " 张）";
        if (filterMode != FILTER_ALL) s += "｜显示 " + displayRows.size() + " 条";
        selInfoTv.setText(s);
        if (delBtn != null) delBtn.setText(n > 0 ? "删除所选（" + n + "）" : "删除所选");
    }

    private void markDownloaded(String filename, boolean ok) {
        if (!ok) return;
        for (PhotoItem p : AppState.get().photos) {
            if (p.filename().equals(filename)) { p.downloaded = true; p.selected = false; }
        }
        rebuildDisplay(); // 「未下载」筛选下刚下完的应即时消失，故走重建而非普通刷新
        updateSelInfo();
    }

    private void onDownloadClicked() {
        List<DownloadService.Job> jobs = new ArrayList<>();
        for (PhotoItem p : selectedFiles()) {
            jobs.add(new DownloadService.Job(p.handle, p.filename(), p.size()));
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

    // ================= 照片墙 Adapter（数据源为 displayRows：含 RAW+JPG 配对折叠行） =================

    private class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return displayRows.size(); }
        @Override public Object getItem(int pos) { return displayRows.get(pos); }
        @Override public long getItemId(int pos) { return displayRows.get(pos).a.handle; }

        @Override public View getView(int pos, View convertView, ViewGroup parent) {
            final GalleryRow row = displayRows.get(pos);
            final PhotoItem thumbItem = row.jpgMember(); // 配对行缩略图取 JPG 成员
            FrameLayout cell;
            ImageView thumbIv, checkIv, badgeIv, badge2Iv;
            TextView nameTv, chipTv, selTv;
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
                // 已下载状态角标（左上）：已下载=绿 / 未下载=灰 / 配对部分已传=黄
                chipTv = new TextView(MainActivity.this);
                chipTv.setId(6);
                chipTv.setTextSize(9);
                chipTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                chipTv.setBackground(getDrawable(R.drawable.bg_chip));
                chipTv.setPadding(dp(5), dp(2), dp(5), dp(2));
                FrameLayout.LayoutParams chiplp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
                chiplp.setMargins(dp(4), dp(4), 0, 0);
                cell.addView(chipTv, chiplp);
                LinearLayout bottom = new LinearLayout(MainActivity.this);
                bottom.setGravity(Gravity.CENTER_VERTICAL);
                bottom.setBackgroundColor(0xB3000000);
                badgeIv = new ImageView(MainActivity.this);
                badgeIv.setId(3);
                bottom.addView(badgeIv, new LinearLayout.LayoutParams(dp(15), dp(15)));
                badge2Iv = new ImageView(MainActivity.this);
                badge2Iv.setId(7);
                LinearLayout.LayoutParams b2lp = new LinearLayout.LayoutParams(dp(15), dp(15));
                b2lp.setMargins(dp(3), 0, 0, 0);
                bottom.addView(badge2Iv, b2lp);
                nameTv = new TextView(MainActivity.this);
                nameTv.setId(5);
                nameTv.setTextSize(9);
                nameTv.setTextColor(Ui.TEXT);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                nlp.setMargins(dp(4), 0, 0, 0);
                bottom.addView(nameTv, nlp);
                selTv = new TextView(MainActivity.this);
                selTv.setId(8);
                selTv.setTextSize(9);
                selTv.setTextColor(Ui.ACCENT);
                selTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp2.setMargins(dp(4), 0, dp(2), 0);
                bottom.addView(selTv, slp2);
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
                chipTv = cell.findViewById(6);
                badge2Iv = cell.findViewById(7);
                selTv = cell.findViewById(8);
            }
            int selCount = row.selectedCount();
            checkIv.setVisibility(selCount > 0 ? View.VISIBLE : View.GONE);
            selFrame.setVisibility(selCount > 0 ? View.VISIBLE : View.GONE);
            selTv.setVisibility(row.isPair() && selCount > 0 ? View.VISIBLE : View.GONE);
            if (row.isPair()) selTv.setText("已选 " + selCount + "/2");
            // 状态角标
            int dlCount = row.downloadedCount();
            int fileCount = row.files().size();
            if (dlCount == fileCount) { chipTv.setText("已下载"); chipTv.setTextColor(Ui.OK); }
            else if (dlCount == 0) { chipTv.setText("未下载"); chipTv.setTextColor(Ui.TEXT_DIM); }
            else { chipTv.setText("部分已传"); chipTv.setTextColor(Ui.ACCENT); }
            nameTv.setText(row.isPair() ? row.a.baseName() + " · JPG+NEF" : row.a.filename());
            // 格式徽标：单行一个；配对行双徽标（JPG 黄 + NEF 紫）
            setBadge(badgeIv, row.a.kind());
            if (row.isPair()) {
                badge2Iv.setVisibility(View.VISIBLE);
                setBadge(badge2Iv, row.b.kind());
            } else {
                badge2Iv.setVisibility(View.GONE);
            }
            // 缩略图：缓存命中直接用；未命中先放矢量占位（格式徽标放大），异步加载回来再换
            Bitmap bmp = thumbCache.getMem(thumbItem.handle);
            thumbIv.setTag(thumbItem.handle);
            if (bmp != null) {
                thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbIv.setImageBitmap(bmp);
            } else {
                thumbIv.setScaleType(ImageView.ScaleType.CENTER);
                thumbIv.setImageDrawable(icon(badgeResOf(thumbItem.kind()), Ui.TEXT_DIM));
                final ImageView target = thumbIv;
                thumbCache.load(AppState.get().client, thumbItem.handle, AppState.get().profile,
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

        private int badgeResOf(PhotoItem.Kind k) {
            switch (k) {
                case NEF: return R.drawable.ic_badge_nef;
                case MP4: return R.drawable.ic_badge_video;
                default: return R.drawable.ic_badge_image;
            }
        }

        private void setBadge(ImageView iv, PhotoItem.Kind k) {
            int tint;
            switch (k) {
                case NEF: tint = Ui.BADGE_NEF; break;
                case MP4: tint = Ui.BADGE_MP4; break;
                default: tint = Ui.BADGE_JPG; break;
            }
            iv.setImageDrawable(icon(badgeResOf(k), tint));
        }
    }
}
