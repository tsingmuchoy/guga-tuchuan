package com.qingmu.tuchuan;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    /** v0.6.2：后台线程工厂——未捕获异常只记 [GUARD] 不交系统默认处理器（池线程异常直达默认处理器会杀进程，
     *  是 STA「点任何处就退出」的现实路径之一）；RemoteController 共用此工厂 */
    static java.util.concurrent.ThreadFactory guardedThreads(final String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, ex) ->
                    Diag.op("GUARD", "后台线程 " + th.getName() + " 未捕获异常（已拦截，App 未退出）：" + ex));
            return t;
        };
    }

    private final ExecutorService bg = Executors.newSingleThreadExecutor(guardedThreads("qingmu-bg"));
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
    private Button editBtn; // v0.4 出片入口
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
    /** v0.6.1：最近一次照片枚举的失败/为空原因（列表为空时显示在照片页空状态卡，禁止静默空白） */
    private volatile String photoLoadNote = null;
    private static final int FILTER_ALL = 0, FILTER_NOT_DL = 1, FILTER_JPG = 2, FILTER_RAW = 3, FILTER_DATE = 4;
    private static final int REQ_PICK_PHOTO = 4101; // v0.4.1 出片导入系统照片选择器
    private int filterMode = FILTER_ALL;
    private String filterDate = null; // yyyyMMdd
    private TextView[] filterChips = new TextView[5];

    // 连接页 v0.3：相机管理（格式化）
    private Button formatBtn;
    private TextView formatWarnTv;
    private ConfirmCard connConfirm;

    // 连接页 v0.4.1：最近作品（Edited 成品缩略图）
    private LinearLayout worksRow;
    private TextView worksEmptyTv;
    private Button worksEmptyBtn;
    private FrameLayout homeOverlay;
    private ImageView homeBigIv;
    private TextView homeBigNameTv;

    // 照片页 v0.4.1：空状态引导卡
    private LinearLayout photosEmptyCard;
    private TextView photosEmptyTv;

    // 遥控页 v0.4.1：未连接引导卡
    private LinearLayout rcHintCardRef;

    // 遥控页 v0.3：间隔拍摄
    private EditText ivSecEt, ivCountEt;
    private Button ivBtn;
    private TextView ivStatusTv;

    // 诊断页
    private TextView diagTv;
    private ScrollView diagScroll;
    private CheckBox expCheck;

    // 遥控页（v0.5：相机式全屏取景——全幅舞台 + 浮层 + 底部上滑参数面板）
    private RemoteController remote;
    private FrameLayout lvStage, rcOverlay, rcBigOverlay;
    private ImageView lvImage, lastShotIv, rcBigIv, movieIconIv;
    private View shutterInner;
    private HudView hud;
    private TextView rcStatusTv, rcTopTitleTv, rcTopSubTv, rcTopParamsTv, rcLvStateTv;
    // v0.5.1 真全屏：App 框架三件（标题行/连接胶囊/页签栏）与根布局的引用，进遥控页时整体隐藏
    private View chromeTitle, chromeTabs;
    private LinearLayout rootLayout;
    private int currentPage = 0;
    private View rcBackBtn;
    private int lvFrameW = 0, lvFrameH = 0; // 帧尺寸变化检测（变了就重置数字放大）
    // v0.5.1 速览轮询兜底：遥控页前台 + 已连接 + 无命令在飞时约 2s 一轮（中枢内再自检一次）
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() {
        @Override public void run() {
            if (currentPage == 2 && remote != null && AppState.get().sessionReady()
                    && !remote.isCommandBusy()) {
                remote.pollQuickProps();
            }
            pollHandler.postDelayed(this, 2000);
        }
    };
    private TextView lvNoteTv, lvHintTv, ovTitleTv, rcRecTv, rcBigNameTv, rcPanelSummaryTv;
    private LinearLayout ovList, rcPanelBox;
    private View rcPanel, captureBtn, movieBtn;
    private View railLvBtn, railGridBtn, railHistBtn, railFilterBtn, railSizeBtn, railPropsBtn;
    private TextView railSizeTv;
    private boolean gridOn = true, histOn = true;
    private boolean rcPanelOpen = false;
    private String rcRemainText = "—";
    private int rcBattery = -1;
    private Bitmap lastShotBmp;
    private String lastShotName = "";
    private List<String> lvSizeOptions = new ArrayList<>();
    private int lvSizeCurrent = -1;
    private boolean lvSizeSupportedUi = false;
    // 录像计时（顶部 REC 红点 + 时长）
    private final Handler recHandler = new Handler(Looper.getMainLooper());
    private long recStartMs = 0;
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (rcRecTv == null) return;
            long s = (System.currentTimeMillis() - recStartMs) / 1000;
            rcRecTv.setText("REC " + String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
            recHandler.postDelayed(this, 500);
        }
    };
    // 遥控页 v0.4：监看滤镜（View 层 ColorMatrixColorFilter 叠加，默认关）
    private int lvFilterIdx = -1; // -1=关
    // v0.6：监看滤镜横向胶囊行（底部栏上方展开；与出片共用 Filters 唯一目录，行内带口径小字）
    private View rcFilterBar;
    private final List<TextView> lvFilterCaps = new ArrayList<>(); // 槽位=目录索引+1，0=关
    private volatile List<RemoteController.PropItem> propItems = new ArrayList<>();
    private float pendingAfNx = -1, pendingAfNy = -1;

    // 照片页 v0.5：自动回传卡折叠
    private LinearLayout autoBody;
    private TextView autoToggleTv;
    private boolean autoExpanded = false;

    // 在线更新（v0.5）
    private FrameLayout pageFrame;
    private FrameLayout updateOverlay;
    private TextView updTitleTv, updNotesTv, updStatusTv;
    private ProgressBar updBar;
    private Button updGoBtn, updLaterBtn;
    private Updater.ReleaseInfo pendingUpdate;
    private File pendingApk;
    private boolean updateChecking = false;
    private boolean updateDownloading = false;
    private boolean updatePromptSuppressed = false; // 「以后再说」：本会话自动检查不再弹
    private boolean installLaunched = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this); // v0.6.1：未捕获异常先落盘再交系统，下次启动回放进诊断 [CRASH]
        prefs = getSharedPreferences("guga_tuchuan", MODE_PRIVATE);
        thumbCache = new ThumbCache(this);
        DownloadRecord.init(this);
        AppState.get().wifi = new CameraWifi(this);
        buildUi();
        pollHandler.postDelayed(pollTick, 2000); // v0.5.1 速览轮询兜底（Runnable 内自带页面/连接/忙碌门控）
        Diag.setListener(() -> runOnUiThread(this::refreshDiag));
        CrashLog.dumpPending(this); // v0.6.1：回放上次崩溃堆栈（STA 点按钮退出定性靠它）
        Diag.log("清木图传 v0.6.2 启动（Z fc 已于 2026-10-14 真机实测通过：连接/profile/属性 Desc/LiveView 帧率/点按对焦/空闲保活/69 张照片列表；v0.6.2 针对 Z 30 外部复测三项：渐进式列表加载/大文件下载断点续传/退出路径审计与掉线取证，均待复测）");
        // v0.5 在线更新：启动自动检查一次（延迟避开首帧；静默口径——失败只记 [UPDATE] 诊断不打扰）
        new Handler(Looper.getMainLooper()).postDelayed(() -> checkUpdate(false), 1500);
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

    @Override protected void onResume() {
        super.onResume();
        refreshRecentWorks(); // 出片页保存成品后回到连接页，最近作品区立即跟上
        // 在线更新：若刚从「安装未知应用」设置页回来且更新卡还开着、安装包已下好，自动续上拉起安装器
        if (updateOverlay != null && updateOverlay.getVisibility() == View.VISIBLE
                && pendingApk != null && !installLaunched
                && getPackageManager().canRequestPackageInstalls()) {
            tryInstallUpdate();
        }
    }

    @Override protected void onDestroy() {
        pollHandler.removeCallbacks(pollTick);
        teardownRemote();
        Diag.setListener(null);
        DownloadService.listener = null;
        IntervalService.listener = null;
        super.onDestroy();
    }

    // ================= UI 搭建 =================

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    /**
     * v0.6.1 操作入口异常兜底（Z 30 STA「点任何按钮就退出」对策）：
     * 遥控/照片等入口统一经此执行——任何异常只记 [GUARD] 诊断 + 弹中文错误，绝不许把 App 弄崩或整页退出。
     */
    private void guarded(final String what, final Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Diag.op("GUARD", what + " 异常已拦截（App 未退出）：" + t);
            Toast.makeText(this, what + "出错，已记录到诊断页：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

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
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        // v0.5：根布局不再统一留边——照片页网格/遥控页取景要全幅铺满；标题/页签/各页内容各自补内边距
        root.setPadding(0, dp(12), 0, 0);

        // 标题行：黄相机图标 + 大字标题
        LinearLayout titleRow = new LinearLayout(this);
        chromeTitle = titleRow; // v0.5.1：遥控全屏时整行隐藏
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(14), 0, dp(14), 0);
        ImageView titleIcon = new ImageView(this);
        titleIcon.setImageDrawable(icon(R.drawable.ic_camera, Ui.ACCENT));
        titleRow.addView(titleIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView title = txt("清木图传", 22, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(8), 0, 0, 0);
        titleRow.addView(title, tlp);
        TextView ver = txt("v0.6.2", 11, Ui.TEXT_DIM, false);
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
        clp.setMargins(dp(14), dp(10), dp(14), dp(6));
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
        chromeTabs = tabs; // v0.5.1：遥控全屏时整行隐藏
        tabs.setPadding(dp(14), 0, dp(14), 0);
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
            t.setOnClickListener(v -> guarded("切换页面", () -> showPage(idx)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tabs.addView(t, lp);
            tabBtns[i] = t;
        }
        root.addView(tabs);

        pageFrame = new FrameLayout(this);
        root.addView(pageFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        pages[0] = buildConnectPage();
        pages[1] = buildPhotosPage();
        pages[2] = buildRemotePage();
        pages[3] = buildDiagPage();
        for (View p : pages) pageFrame.addView(p);
        buildUpdateOverlay(pageFrame); // 更新卡挂页面区顶层，任意页签下可弹
        showPage(0);
        setContentView(root);
    }

    private void showPage(int idx) {
        currentPage = idx;
        setRemoteFullscreen(idx == 2); // v0.5.1：遥控页 = 真全屏沉浸取景，进页藏尽 App 框架与系统栏
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabBtns[i].setTextColor(i == idx ? Ui.ACCENT : Ui.TEXT_DIM);
            Drawable d = tabBtns[i].getCompoundDrawables()[1];
            if (d != null) d.setTint(i == idx ? Ui.ACCENT : Ui.TEXT_DIM);
        }
        // 切离遥控页时停取景，避免后台占用命令通道拖慢图传；面板/大图/选择弹层一并收起
        if (idx != 2) {
            if (remote != null && remote.isLiveViewOn()) remote.stopLiveView();
            if (rcPanelOpen) setPanelOpen(false);
            if (rcBigOverlay != null) rcBigOverlay.setVisibility(View.GONE);
            if (rcOverlay != null) rcOverlay.setVisibility(View.GONE);
        }
        // 相机式体验：进遥控页即开取景（门控不允许时中枢自己回状态文案，不硬闯）
        if (idx == 2 && remote != null && AppState.get().sessionReady()
                && !remote.isLiveViewOn() && remote.featureAllowed(RemoteController.F_LIVEVIEW)) {
            remote.startLiveView();
        }
        if (idx == 0) refreshRecentWorks();
        if (idx == 1) updatePhotosEmptyState();
    }

    /**
     * v0.5.1 真全屏：隐藏标题行/连接胶囊/页签栏 + 根顶部留白，并用系统 UI 标志进沉浸式
     * （状态栏/导航栏一并藏起，用户从边缘滑动可临时呼出属系统行为）；退出时全部恢复。
     * 遥控页「‹ 返回」与系统返回键都走 showPage(0)，与既有切页 teardown（停取景）同一条路。
     */
    private void setRemoteFullscreen(boolean fs) {
        if (chromeTitle != null) chromeTitle.setVisibility(fs ? View.GONE : View.VISIBLE);
        if (capsule != null) capsule.setVisibility(fs ? View.GONE : View.VISIBLE);
        if (chromeTabs != null) chromeTabs.setVisibility(fs ? View.GONE : View.VISIBLE);
        if (rootLayout != null) rootLayout.setPadding(0, fs ? 0 : dp(12), 0, 0);
        View decor = getWindow().getDecorView();
        if (fs) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override public void onBackPressed() {
        if (currentPage == 2) { showPage(0); return; } // 全屏取景中按返回 = 退出全屏回连接页
        super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && currentPage == 2) setRemoteFullscreen(true); // 从安装器/系统栏呼出回来后重进沉浸
    }

    // ---------- 连接页 ----------
    private View buildConnectPage() {
        FrameLayout pageRoot = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(14), dp(10)); // v0.5：根布局不再统一留边，各页自补
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
        connectBtn.setOnClickListener(v -> guarded("连接相机", this::onConnectClicked));
        disconnectBtn.setOnClickListener(v -> guarded("断开连接", this::onDisconnectClicked));

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

        // —— 功能总览卡组（v0.4.1）：四张卡一字排开，角标如实标注可用状态 ——
        col.addView(txt("功能总览", 13, Ui.TEXT_DIM, true), wrapMargin(12));
        LinearLayout featRow = new LinearLayout(this);
        featRow.addView(featureCard(R.drawable.ic_download, "图传", "照片/视频下载到相册",
                "需连接相机", false, () -> showPage(0)), featureLp(0));
        featRow.addView(featureCard(R.drawable.ic_remote, "遥控监看", "实时取景与遥控拍摄",
                "需连接相机", false, () -> showPage(2)), featureLp(1));
        featRow.addView(featureCard(R.drawable.ic_edit, "出片", "水印边框与滤镜",
                "可直接使用", true, () -> openEditorImport()), featureLp(1));
        featRow.addView(featureCard(R.drawable.ic_sdcard, "相机管理", "格式化与存储卡信息",
                "需连接相机", false, () -> showPage(0)), featureLp(1));
        col.addView(featRow);

        // —— 最近作品（v0.4.1）：Pictures/QingmuTuchuan/Edited 最近成品 ——
        LinearLayout worksCard = card();
        LinearLayout wkHead = new LinearLayout(this);
        wkHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView wkIc = new ImageView(this);
        wkIc.setImageDrawable(icon(R.drawable.ic_edit, Ui.ACCENT));
        wkHead.addView(wkIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView wkTitle = txt("最近作品", 15, Ui.TEXT, true);
        LinearLayout.LayoutParams wklp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wklp.setMargins(dp(8), 0, 0, 0);
        wkHead.addView(wkTitle, wklp);
        worksCard.addView(wkHead);
        android.widget.HorizontalScrollView wkScroll = new android.widget.HorizontalScrollView(this);
        wkScroll.setHorizontalScrollBarEnabled(false);
        worksRow = new LinearLayout(this);
        wkScroll.addView(worksRow);
        worksCard.addView(wkScroll, wrapMargin(8));
        worksEmptyTv = txt("还没有出片作品。点下方按钮，挑一张手机里的照片就能加水印边框、套滤镜，不用等相机。", 12, Ui.TEXT_DIM, false);
        worksCard.addView(worksEmptyTv, wrapMargin(8));
        worksEmptyBtn = btn("挑一张照片出片", R.drawable.ic_edit, true);
        worksEmptyBtn.setOnClickListener(v -> openEditorImport());
        LinearLayout.LayoutParams weblp = matchWrap(); weblp.setMargins(0, dp(8), 0, 0);
        worksCard.addView(worksEmptyBtn, weblp);
        col.addView(worksCard);

        readPhotosBtn = btn("读取照片", R.drawable.ic_badge_image, true);
        LinearLayout.LayoutParams rlp = matchWrap(); rlp.setMargins(0, dp(8), 0, 0);
        col.addView(readPhotosBtn, rlp);
        readPhotosBtn.setOnClickListener(v -> guarded("读取照片", this::loadPhotos));

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
        aboutCard.addView(txt("当前版本 v" + Updater.currentVersion(this) + "。在线更新走 GitHub 发布源：启动时自动检查一次，连着相机热点（无互联网）时会自动跳过、不打扰；更新包下载到缓存目录，校验大小后经系统安装器安装。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        aboutCard.addView(txt("滤镜（共 " + Filters.COUNT + " 款）：带 LUT 的 19 款出片时用 17³ 三维查找表高精度渲染，监看预览为矩阵近似。参数来源：富士数字模拟剖面由 Stuart Sowerby 免费发布（blog.sowerby.me）后拟合；柯达 Portra 400、富士 Pro 400H / Astia 100F、Kodak Tri-X 400、Ilford HP5 Plus、Fuji Neopan Acros 100 源自 Pat David 胶片仿真合集（署名 Pat David、Pavlov Dmitry、Michael Ezra，CC BY-SA 4.0）；经典负片等桥接款经 rollfilm（MIT）转存的富士官方 LUT 数据换算、属风格近似，其中怀旧负片的源数据含 GPL-3 衍生背景；徕卡 4 款无公开实测数据，为公式构造的「风格致敬」。各品牌名称仅作风格描述，不代表官方关联。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
        Button updBtn = btn("检查更新", R.drawable.ic_download, false);
        updBtn.setOnClickListener(v -> checkUpdate(true));
        LinearLayout.LayoutParams ublp2 = matchWrap(); ublp2.setMargins(0, dp(8), 0, 0);
        aboutCard.addView(updBtn, ublp2);
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
        formatBtn.setOnClickListener(v -> guarded("格式化", this::onFormatClicked));
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
        buildHomeOverlay(pageRoot);
        return pageRoot;
    }

    /** v0.4.1：大图查看浮层（点最近作品弹出，看图/分享/关闭，自绘深色层非系统弹窗） */
    private void buildHomeOverlay(FrameLayout pageRoot) {
        homeOverlay = new FrameLayout(this);
        homeOverlay.setBackgroundColor(0xF2000000);
        homeOverlay.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(14));
        homeBigNameTv = txt("", 14, Ui.TEXT, true);
        col.addView(homeBigNameTv);
        homeBigIv = new ImageView(this);
        homeBigIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams bivlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bivlp.setMargins(0, dp(10), 0, 0);
        col.addView(homeBigIv, bivlp);
        LinearLayout row = new LinearLayout(this);
        Button shareBtn = btn("分享", R.drawable.ic_share, true);
        Button closeBtn = btn("关闭", R.drawable.ic_clear, false);
        row.addView(shareBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cblp.setMargins(dp(8), 0, 0, 0);
        row.addView(closeBtn, cblp);
        LinearLayout.LayoutParams rlp2 = matchWrap(); rlp2.setMargins(0, dp(10), 0, 0);
        col.addView(row, rlp2);
        shareBtn.setOnClickListener(v -> {
            Uri uri = (Uri) homeOverlay.getTag();
            if (uri == null) return;
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/jpeg");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享作品"));
        });
        closeBtn.setOnClickListener(v -> homeOverlay.setVisibility(View.GONE));
        homeOverlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageRoot.addView(homeOverlay);
    }

    /** v0.4.1：最近作品数据（仅内存：Uri + 名字 + 缩略图，LruCache 硬顶约 3MB） */
    private static class WorkItem { Uri uri; String name; Bitmap thumb; }

    private final android.util.LruCache<String, Bitmap> worksThumbCache =
            new android.util.LruCache<String, Bitmap>(3 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
            };
    private final java.util.Set<String> worksLoading = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 刷新连接页「最近作品」：后台查 MediaStore Edited 目录（新→旧，最多 8 张），UI 只绑缓存 */
    private void refreshRecentWorks() {
        if (worksRow == null) return;
        bg.execute(() -> {
            List<WorkItem> items = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 29) {
                String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                try (Cursor c = getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.DATE_MODIFIED},
                        sel, new String[]{"Pictures/QingmuTuchuan/Edited/"},
                        MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
                    if (c != null) {
                        int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                        int nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                        while (c.moveToNext() && items.size() < 8) {
                            WorkItem it = new WorkItem();
                            it.uri = ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol));
                            it.name = c.getString(nameCol);
                            it.thumb = worksThumbCache.get(it.uri.toString());
                            items.add(it);
                        }
                    }
                } catch (Exception e) {
                    Diag.op("EDIT", "最近作品 MediaStore 查询失败：" + e.getMessage());
                }
            } else {
                // API 26-28：成品文件就落在公共目录，直接列文件（新→旧）
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "QingmuTuchuan/Edited");
                File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                if (files != null) {
                    java.util.Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    for (File f : files) {
                        if (items.size() >= 8) break;
                        WorkItem it = new WorkItem();
                        it.uri = Uri.fromFile(f);
                        it.name = f.getName();
                        it.thumb = worksThumbCache.get(it.uri.toString());
                        items.add(it);
                    }
                }
            }
            // 缺缩略图的在后台解码（约 240px 降采样，一张约 230KB）再回 UI 重绑
            boolean needReload = false;
            for (final WorkItem it : items) {
                if (it.thumb != null) continue;
                final String key = it.uri.toString();
                synchronized (worksLoading) {
                    if (worksLoading.contains(key)) continue;
                    worksLoading.add(key);
                }
                needReload = true;
                try {
                    Bitmap t = ImageEditor.loadPreview(this, it.uri, 1, 240);
                    if (t != null) worksThumbCache.put(key, t);
                } catch (Exception e) {
                    Diag.op("EDIT", "最近作品缩略图失败 " + it.name + "：" + e.getMessage());
                } finally {
                    worksLoading.remove(key);
                }
                it.thumb = worksThumbCache.get(key);
            }
            final List<WorkItem> fit = items;
            final boolean again = needReload;
            runOnUiThread(() -> bindRecentWorks(fit, again));
        });
    }

    private void bindRecentWorks(List<WorkItem> items, boolean reloadOnceMore) {
        if (worksRow == null) return;
        worksRow.removeAllViews();
        boolean empty = items.isEmpty();
        worksEmptyTv.setVisibility(empty ? View.VISIBLE : View.GONE);
        worksEmptyBtn.setVisibility(empty ? View.VISIBLE : View.GONE);
        for (final WorkItem it : items) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Ui.PANEL_ALT);
            if (it.thumb != null) iv.setImageBitmap(it.thumb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
            lp.setMargins(0, 0, dp(8), 0);
            iv.setOnClickListener(v -> showWorkBig(it));
            worksRow.addView(iv, lp);
        }
        // 二次绑定：个别缩略图后到时补刷一次（bindRecentWorks 本身不再触发加载，无循环）
        if (reloadOnceMore) bindRecentWorks(items, false);
    }

    private void showWorkBig(WorkItem it) {
        if (homeOverlay == null) return;
        homeOverlay.setTag(it.uri);
        homeBigNameTv.setText(it.name);
        homeBigIv.setImageBitmap(null);
        homeOverlay.setVisibility(View.VISIBLE);
        Diag.op("EDIT", "最近作品查看：" + it.name);
        bg.execute(() -> {
            final Bitmap b = ImageEditor.loadPreview(this, it.uri, 1, 1600);
            runOnUiThread(() -> {
                if (homeOverlay.getVisibility() == View.VISIBLE && it.uri.equals(homeOverlay.getTag())) {
                    homeBigIv.setImageBitmap(b);
                } else if (b != null) {
                    b.recycle();
                }
            });
        });
    }

    /** v0.4.1 功能总览小卡：矢量图标 + 名称 + 一句话 + 状态角标，点击跳对应页 */
    private View featureCard(int iconRes, String name, String desc, String badge, boolean free, Runnable go) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.bg_card));
        c.setPadding(dp(8), dp(10), dp(8), dp(10));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon(iconRes, Ui.ACCENT));
        c.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView n = txt(name, 14, Ui.TEXT, true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(0, dp(6), 0, 0);
        c.addView(n, nlp);
        TextView d = txt(desc, 11, Ui.TEXT_DIM, false);
        d.setMinLines(2);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(2), 0, 0);
        c.addView(d, dlp);
        TextView b = txt(badge, 10, free ? Ui.ACCENT_TX : Ui.TEXT_DIM, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(3), dp(6), dp(3));
        b.setBackground(getDrawable(free ? R.drawable.bg_btn_primary : R.drawable.bg_chip));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(6), 0, 0);
        c.addView(b, blp);
        c.setOnClickListener(v -> go.run());
        return c;
    }

    private LinearLayout.LayoutParams featureLp(int pos) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (pos > 0) lp.setMargins(dp(6), 0, 0, 0);
        return lp;
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

        // —— 空状态引导卡（v0.4.1）：未连接/还没有照片时说明能做什么，不留整页空白 ——
        photosEmptyCard = new LinearLayout(this);
        photosEmptyCard.setOrientation(LinearLayout.VERTICAL);
        photosEmptyCard.setBackground(getDrawable(R.drawable.bg_card));
        photosEmptyCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams elp0 = matchWrap(); elp0.setMargins(dp(10), 0, dp(10), dp(8));
        photosEmptyCard.setLayoutParams(elp0);
        photosEmptyTv = txt("", 13, Ui.TEXT_DIM, false);
        photosEmptyCard.addView(photosEmptyTv);
        Button emptyEditBtn = btn("挑一张照片出片（不用相机）", R.drawable.ic_edit, true);
        emptyEditBtn.setOnClickListener(v -> openEditorImport());
        LinearLayout.LayoutParams eelp = matchWrap(); eelp.setMargins(0, dp(10), 0, 0);
        photosEmptyCard.addView(emptyEditBtn, eelp);
        col.addView(photosEmptyCard);

        // —— 拍完自动回传卡（v0.5：可折叠——关闭/未开启时只剩一行标题状态条，点「展开」才露范围与说明） ——
        LinearLayout autoCard = card();
        LinearLayout.LayoutParams acLp = (LinearLayout.LayoutParams) autoCard.getLayoutParams();
        acLp.setMargins(dp(10), dp(8), dp(10), dp(4)); // 全幅页里卡片留一点呼吸边
        autoCard.setLayoutParams(acLp);
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
        autoToggleTv = txt("展开", 12, Ui.ACCENT, true);
        autoToggleTv.setPadding(dp(10), dp(6), dp(2), dp(6));
        autoToggleTv.setOnClickListener(v -> { autoExpanded = !autoExpanded; updateAutoUi(); });
        autoHead.addView(autoToggleTv);
        autoCard.addView(autoHead);
        autoExpanded = prefs.getBoolean("auto_dl", false); // 开启状态默认展开，关闭默认折叠成一行
        autoBody = new LinearLayout(this);
        autoBody.setOrientation(LinearLayout.VERTICAL);
        autoBody.addView(txt("连接期间相机拍的新照片（遥控拍摄或直接按相机快门都算）自动排队下载到相册；与已下载记录去重，不会重复传。默认关。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
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
        autoBody.addView(autoModeRow, wrapMargin(8));
        autoHintTv = txt("", 12, Ui.TEXT_DIM, false);
        autoBody.addView(autoHintTv, wrapMargin(6));
        autoCard.addView(autoBody);
        col.addView(autoCard);
        autoDlCheck.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("auto_dl", checked).apply();
            autoExpanded = checked; // 开→展开露出范围选择；关→自动折叠回一行状态条
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

        // —— 紧凑工具条（v0.5：已选信息+全选/清空一行、筛选+磁盘缓存一行，两行并成一区，不再三行吃高度） ——
        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setOrientation(LinearLayout.VERTICAL);
        toolBar.setPadding(dp(10), dp(8), dp(10), dp(2));
        LinearLayout toolRow1 = new LinearLayout(this);
        toolRow1.setGravity(Gravity.CENTER_VERTICAL);
        selInfoTv = txt("已选 0 个文件 · 0 B", 12, Ui.TEXT, true);
        selInfoTv.setSingleLine(true);
        selInfoTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolRow1.addView(selInfoTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button allBtn = smallGhostBtn("全选");
        Button noneBtn = smallGhostBtn("清空");
        toolRow1.addView(allBtn);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(dp(6), 0, 0, 0);
        toolRow1.addView(noneBtn, nlp);
        allBtn.setOnClickListener(v -> { for (PhotoItem p : AppState.get().photos) p.selected = true; updateSelInfo(); adapter.notifyDataSetChanged(); });
        noneBtn.setOnClickListener(v -> { for (PhotoItem p : AppState.get().photos) p.selected = false; updateSelInfo(); adapter.notifyDataSetChanged(); });
        toolBar.addView(toolRow1);
        LinearLayout toolRow2 = new LinearLayout(this);
        toolRow2.setGravity(Gravity.CENTER_VERTICAL);
        ImageView fIc = new ImageView(this);
        fIc.setImageDrawable(icon(R.drawable.ic_filter, Ui.TEXT_DIM));
        toolRow2.addView(fIc, new LinearLayout.LayoutParams(dp(16), dp(16)));
        android.widget.HorizontalScrollView fScroll = new android.widget.HorizontalScrollView(this);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipsBox = new LinearLayout(this);
        String[] chipNames = {"全部", "未下载", "仅 JPG", "仅 RAW", "日期"};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView chip = txt(chipNames[i], 12, Ui.TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            chip.setOnClickListener(v -> onFilterChip(idx));
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.setMargins(dp(5), 0, 0, 0);
            chipsBox.addView(chip, chp);
            filterChips[i] = chip;
        }
        fScroll.addView(chipsBox);
        LinearLayout.LayoutParams fslp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        fslp.setMargins(dp(4), 0, 0, 0);
        toolRow2.addView(fScroll, fslp);
        diskCacheCheck = new CheckBox(this);
        diskCacheCheck.setText("磁盘缓存");
        diskCacheCheck.setTextColor(Ui.TEXT_DIM);
        diskCacheCheck.setTextSize(12);
        diskCacheCheck.setChecked(true);
        diskCacheCheck.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        diskCacheCheck.setOnCheckedChangeListener((b, checked) -> {
            thumbCache.diskEnabled = checked;
            Diag.op("THUMB", "磁盘缓存 " + (checked ? "开启" : "关闭"));
        });
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dclp.setMargins(dp(6), 0, 0, 0);
        toolRow2.addView(diskCacheCheck, dclp);
        toolBar.addView(toolRow2, wrapMargin(4));
        col.addView(toolBar);
        refreshFilterChips();

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        // v0.5：全幅铺满——左右贴边、间距压到 2dp；底部留白给悬浮操作栏让位（滚动到底最后一行不被遮）
        grid.setPadding(dp(2), dp(4), dp(2), dp(96));
        adapter = new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> guarded("选择照片", () -> {
            if (position < 0 || position >= displayRows.size()) return;
            GalleryRow row = displayRows.get(position);
            // v0.6.2：骨架条目（信息回填中）不可勾选
            if (row.a.infoPending) {
                Toast.makeText(this, "照片信息正在读取中，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            // v0.6.1：信息读取失败的占位条目不可勾选，给中文原因（失败码在诊断里）
            if (row.a.infoFailed) {
                Toast.makeText(this, "这张照片的信息读取失败（码 0x" + Integer.toHexString(row.a.infoFailRc)
                        + "），暂时无法预览和下载，细节见诊断页", Toast.LENGTH_LONG).show();
                return;
            }
            if (row.isPair()) { showPairChooser(row); return; }
            row.a.selected = !row.a.selected;
            updateSelInfo();
            adapter.notifyDataSetChanged();
        }));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            guarded("批量选择照片", () -> {
                if (position < 0 || position >= displayRows.size()) return;
                GalleryRow row = displayRows.get(position);
                boolean target = row.selectedCount() < row.files().size();
                for (PhotoItem p : row.files()) if (!p.infoFailed && !p.infoPending) p.selected = target;
                updateSelInfo();
                adapter.notifyDataSetChanged();
            });
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // —— 底部悬浮紧凑栏（v0.5：不再占整行大按钮高度——下载状态一行小字 + 细进度条 + 三个紧凑按钮，浮在网格之上） ——
        LinearLayout floatBar = new LinearLayout(this);
        floatBar.setOrientation(LinearLayout.VERTICAL);
        floatBar.setBackground(getDrawable(R.drawable.bg_card));
        floatBar.setPadding(dp(12), dp(8), dp(12), dp(10));
        dlFileTv = txt("未开始下载", 11, Ui.TEXT_DIM, false);
        dlFileTv.setSingleLine(true);
        dlFileTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        floatBar.addView(dlFileTv);
        dlBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlBar.setMax(100);
        dlBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        blp.setMargins(0, dp(4), 0, 0);
        floatBar.addView(dlBar, blp);
        LinearLayout dlRow = new LinearLayout(this);
        dlRow.setGravity(Gravity.CENTER_VERTICAL);
        delBtn = btn("删除所选", R.drawable.ic_delete, false);
        delBtn.setTextColor(Ui.ERR);
        editBtn = btn("出片", R.drawable.ic_edit, false);
        dlBtn = btn("下载所选到相册", R.drawable.ic_download, true);
        for (Button b : new Button[]{delBtn, editBtn, dlBtn}) {
            b.setTextSize(13);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
        }
        dlRow.addView(delBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams edlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        edlp.setMargins(dp(8), 0, 0, 0);
        dlRow.addView(editBtn, edlp);
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        drlp.setMargins(dp(8), 0, 0, 0);
        dlRow.addView(dlBtn, drlp);
        LinearLayout.LayoutParams frlp = matchWrap(); frlp.setMargins(0, dp(8), 0, 0);
        floatBar.addView(dlRow, frlp);
        dlBtn.setOnClickListener(v -> guarded("下载照片", this::onDownloadClicked));
        delBtn.setOnClickListener(v -> guarded("删除照片", this::onDeleteClicked));
        editBtn.setOnClickListener(v -> guarded("出片", this::onEditClicked));
        FrameLayout.LayoutParams floatLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        floatLp.setMargins(dp(8), 0, dp(8), dp(8));
        floatBar.setLayoutParams(floatLp);

        pageRoot.addView(floatBar); // 悬浮栏压在网格之上、弹层之下

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
        updatePhotosEmptyState();
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
        // v0.5 折叠同步：主体（说明+范围+提示）按 autoExpanded 显隐，折叠时卡片只剩标题一行状态条
        if (autoBody != null) autoBody.setVisibility(autoExpanded ? View.VISIBLE : View.GONE);
        if (autoToggleTv != null) autoToggleTv.setText(autoExpanded ? "收起" : "展开");
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
        updatePhotosEmptyState();
    }

    /** v0.4.1：照片页空状态——无任何行时显示引导（连线后能下什么）+ 出片直达，有行则收起 */
    private void updatePhotosEmptyState() {
        if (photosEmptyCard == null) return;
        boolean empty = displayRows.isEmpty();
        photosEmptyCard.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        boolean connected = AppState.get().client != null && AppState.get().client.isConnected();
        if (AppState.get().photos.isEmpty()) {
            if (photoLoadNote != null) {
                // v0.6.1：枚举失败/为空的具体原因（相机返回 0 个对象、失败码等），禁止静默空白
                photosEmptyTv.setText(photoLoadNote);
            } else {
                photosEmptyTv.setText(connected
                        ? "已连接相机，但还没读取照片清单：点连接页的「读取照片」，或回到本页再点一次读取，相机卡上的照片会出现在这里，勾选后即可批量下载到相册（支持断点续传）。"
                        : "还没有照片：先连接相机（连接页，相机热点或「连接至 PC」均可），再点「读取照片」，这里会出现相机卡里的照片墙，可勾选批量下载 JPEG/NEF 原图到相册，带断点续传与自动重连。");
            }
        } else {
            photosEmptyTv.setText("当前筛选下没有照片（筛选条切回「全部」看全部 " + AppState.get().photos.size() + " 张）。");
        }
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

    // ---------- v0.4 出片入口 ----------

    /**
     * 出片入口分发：选中了已下载 JPG → 一张直达、多张弹选择；没选中 → 列全部已下载 JPG。
     * 出片对象是相册里的本地副本（Pictures/QingmuTuchuan），与相机连接状态无关；
     * NEF 没有可编辑的 JPG 本体，不进列表（配对时用同名 JPG 成员）。
     * v0.4.1 起还可从「从手机选照片」导入系统相册任意 JPG（见 onEditChooserImport 按钮）。
     */
    private void onEditClicked() {
        List<String> picked = new ArrayList<>();
        for (PhotoItem p : selectedFiles()) {
            if (p.kind() == PhotoItem.Kind.JPG && p.downloaded) picked.add(p.filename());
        }
        if (picked.size() == 1) { openEditorFor(picked.get(0)); return; }
        if (picked.size() > 1) {
            showEditChooser(picked, "已勾选多张已下载 JPG，选一张出片（一次编辑一张）。");
            return;
        }
        List<String> all = new ArrayList<>();
        for (String name : DownloadRecord.names()) {
            if (PhotoItem.kindOf(name) == PhotoItem.Kind.JPG) all.add(name);
        }
        Collections.sort(all, Collections.reverseOrder());
        if (all.isEmpty()) {
            phOverlayShow("出片 · 选择照片");
            phOverlayAddNote("还没有已下载的 JPG。不用等相机：直接从手机里挑一张照片就能出片——加 EXIF 水印边框、套滤镜，成品存相册 Edited 目录，原图不覆盖。");
            phOverlayAddBtn("从手机选一张照片", true, this::openEditorImport);
            Diag.op("EDIT", "出片入口：无已下载 JPG，给出系统照片导入入口");
            return;
        }
        showEditChooser(all, "选择一张已下载的 JPG 出片（加 EXIF 水印边框 / 滤镜，成品存相册 Edited 目录，原图不覆盖）。也可以从手机相册导入任意照片。");
    }

    private void showEditChooser(List<String> names, String note) {
        phOverlayShow("出片 · 选择照片");
        phOverlayAddNote(note);
        phOverlayAddBtn("从手机选一张照片（导入）", true, this::openEditorImport);
        for (final String name : names) {
            phOverlayAddBtn(name, false, () -> openEditorFor(name));
        }
    }

    /** 解析相册副本的 Uri（MediaStore 查询在后台线程），找到后打开编辑页 */
    private void openEditorFor(final String filename) {
        Diag.op("EDIT", "出片入口选中：" + filename + "，解析相册 Uri 中");
        bg.execute(() -> {
            Uri uri = findGalleryUri(filename);
            if (uri == null) {
                // API 26-28 或 MediaStore 未索引时退到文件路径（编辑器按 Uri 统一打开，file:// 亦可）
                File f = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "QingmuTuchuan/" + filename);
                if (f.exists()) uri = Uri.fromFile(f);
            }
            final Uri fUri = uri;
            runOnUiThread(() -> {
                if (fUri == null) {
                    Toast.makeText(this, "在相册里找不到 " + filename + "（可能已被移动或删除）", Toast.LENGTH_LONG).show();
                    Diag.op("EDIT", "出片入口失败：相册里找不到 " + filename);
                    return;
                }
                Intent i = new Intent(MainActivity.this, EditorActivity.class);
                i.setData(fUri);
                i.putExtra("name", filename);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            });
        });
    }

    /** 在 MediaStore 里按文件名找 Pictures/QingmuTuchuan（不含 Edited 子目录）下的副本 Uri */
    private Uri findGalleryUri(String filename) {
        if (Build.VERSION.SDK_INT < 29) return null; // RELATIVE_PATH 列 29+ 才有，低版本走文件路径兜底
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH};
        try (Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                proj, MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{filename}, null)) {
            if (c == null) return null;
            int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int rpCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            while (c.moveToNext()) {
                String rp = c.getString(rpCol);
                if (rp != null && rp.contains("Edited")) continue; // 成品不再当原图编辑
                return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), c.getLong(idCol));
            }
        } catch (Exception e) {
            Diag.op("EDIT", "MediaStore 查询 " + filename + " 失败：" + e.getMessage());
        }
        return null;
    }

    /** v0.4.1：系统照片选择器导入（任意来源 JPG）→ 直接打开出片编辑页 */
    private void openEditorImport() {
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
            pick.setType("image/jpeg");
        } else {
            pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/jpeg");
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        Diag.op("EDIT", "出片导入：打开系统照片选择器（" + (Build.VERSION.SDK_INT >= 33 ? "Photo Picker" : "OPEN_DOCUMENT") + "）");
        startActivityForResult(pick, REQ_PICK_PHOTO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_PHOTO) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Diag.op("EDIT", "出片导入：用户取消");
            return;
        }
        Uri uri = data.getData();
        if (Build.VERSION.SDK_INT < 33) {
            // 文档 Uri 尽量拿持久读权限（重启 App 后分享/重进仍可读；失败不阻断本次编辑）
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Diag.op("EDIT", "出片导入：持久读权限获取失败（本次仍可编辑）：" + e.getMessage());
            }
        }
        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) name = "imported.jpg";
        Diag.op("EDIT", "出片导入选中：" + name + "（" + uri + "）");
        Intent i = new Intent(this, EditorActivity.class);
        i.setData(uri);
        i.putExtra("name", name);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0) return c.getString(col);
            }
        } catch (Exception e) {
            Diag.op("EDIT", "出片导入：取文件名失败：" + e.getMessage());
        }
        return null;
    }

    // ---------- 诊断页 ----------
    private View buildDiagPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(14), dp(10)); // v0.5：根布局不再统一留边，各页自补
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

    /** 圆形浮层按钮（取景浮层开关列）：半透明黑圆底 + 居中矢量图标，开关态由 setRailOn 切换黄底黑图 */
    private FrameLayout railBtn(int iconRes) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(getDrawable(R.drawable.bg_circle_btn));
        f.setClickable(true);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon(iconRes, Ui.TEXT));
        f.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        f.setTag(iv);
        return f;
    }

    private void setRailOn(View btn, boolean on) {
        if (btn == null) return;
        btn.setBackground(getDrawable(on ? R.drawable.bg_circle_btn_on : R.drawable.bg_circle_btn));
        Object tag = btn.getTag();
        if (tag instanceof ImageView) {
            ImageView iv = (ImageView) tag;
            if (iv.getDrawable() != null) iv.getDrawable().setTint(on ? Ui.ACCENT_TX : Ui.TEXT);
        }
    }

    /**
     * v0.5.1 点按对焦入口（手势层确认是单击后调用）：坐标经 HudView.mapToFrame 逆映射
     * （与绘制共用 displayRect 口径，含数字放大变换），点在显示画面外直接忽略；
     * 把实际所用显示矩形一并传给中枢写进 [REMOTE] 诊断，备查坐标口径。
     */
    private void handleLvTapFocus(float px, float py) {
        if (remote == null || !remote.isLiveViewOn()) return;
        float[] n = hud.mapToFrame(px, py);
        if (n == null) {
            rcStatusTv.setText("点在画面外（黑边），已忽略");
            return;
        }
        pendingAfNx = n[0]; pendingAfNy = n[1];
        hud.showAfState(n[0], n[1], HudView.AF_FOCUSING); // 黄=对焦中，相机 AfDrive 响应回来再改判绿/红
        RectF d = hud.displayRect();
        String rectDesc = "left=" + (int) d.left + " top=" + (int) d.top
                + " w=" + (int) d.width() + " h=" + (int) d.height()
                + "（数字放大 ×" + String.format(Locale.US, "%.2f", hud.getZoomScale()) + "）";
        remote.tapFocus(n[0], n[1], rectDesc);
    }

    /** v0.5.1 数字放大：把变换同步给 HudView（钳制在此）与 lvImage（同 scale/translation、pivot 同为画面中心） */
    private void applyLvZoom(float scale, float tx, float ty) {
        hud.setZoom(scale, tx, ty);
        lvImage.setScaleX(hud.getZoomScale());
        lvImage.setScaleY(hud.getZoomScale());
        lvImage.setTranslationX(hud.getZoomTx());
        lvImage.setTranslationY(hud.getZoomTy());
    }

    /** 以某个屏幕点为锚放大/缩小：锚点下的帧内容保持不动（捏合与双击共用） */
    private void applyLvZoomAt(float newScale, float fx, float fy) {
        RectF b = hud.baseRect();
        float cx = b.centerX(), cy = b.centerY();
        float s0 = hud.getZoomScale();
        float ux = (fx - cx - hud.getZoomTx()) / s0;
        float uy = (fy - cy - hud.getZoomTy()) / s0;
        float ns = Math.max(1f, Math.min(4f, newScale));
        applyLvZoom(ns, fx - cx - ux * ns, fy - cy - uy * ns);
    }

    private void resetLvZoom() {
        if (hud != null) hud.resetZoom();
        if (lvImage != null) {
            lvImage.setScaleX(1f); lvImage.setScaleY(1f);
            lvImage.setTranslationX(0f); lvImage.setTranslationY(0f);
        }
    }

    /** 参数面板开/关：底部上滑半屏（不压暗取景），打开时按最新 propItems 重渲染一次 */
    private void setPanelOpen(boolean open) {
        rcPanelOpen = open;
        if (rcPanel == null) return;
        if (open) {
            renderPropsPanel();
            rcPanel.setVisibility(View.VISIBLE);
            rcPanel.post(() -> {
                rcPanel.setTranslationY(rcPanel.getHeight());
                rcPanel.animate().translationY(0).setDuration(180).start();
            });
        } else {
            rcPanel.setVisibility(View.GONE);
        }
    }

    /** 最近一拍大图浮层（点底部控制栏左下缩略图打开；位图即 GetThumb 预览，原图在照片页下载） */
    private void showLastShotBig() {
        if (lastShotBmp == null) {
            Toast.makeText(this, "还没有新照片：遥控拍一张，或在相机上拍一张（事件通道正常时会自动送达）", Toast.LENGTH_SHORT).show();
            return;
        }
        rcBigIv.setImageBitmap(lastShotBmp);
        rcBigNameTv.setText(lastShotName + "（预览图；点任意处关闭，原图在照片页下载）");
        rcBigOverlay.setVisibility(View.VISIBLE);
    }

    /** 录像 UI 状态（按钮图标 + 顶部 REC 红点计时）；onMovie 回调与 teardown 共用 */
    private void updateMovieUi(boolean recording) {
        if (movieIconIv != null) {
            movieIconIv.setImageDrawable(icon(recording ? R.drawable.ic_stop : R.drawable.ic_rec, Ui.ERR));
        }
        if (rcRecTv == null) return;
        if (recording) {
            recStartMs = System.currentTimeMillis();
            rcRecTv.setVisibility(View.VISIBLE);
            recHandler.removeCallbacks(recTick);
            recHandler.post(recTick);
        } else {
            rcRecTv.setVisibility(View.GONE);
            recHandler.removeCallbacks(recTick);
        }
    }

    private View buildRemotePage() {
        FrameLayout pageRoot = new FrameLayout(this);
        pageRoot.setBackgroundColor(Ui.BG);

        // —— 取景舞台（全幅）：画面按帧原始宽高比 FIT_CENTER 居中、余下纯黑 letterbox ——
        lvStage = new FrameLayout(this);
        lvStage.setBackgroundColor(0xFF000000);
        pageRoot.addView(lvStage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lvImage = new ImageView(this);
        lvImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lvStage.addView(lvImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hud = new HudView(this);
        lvStage.addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lvHintTv = txt("取景未开启\n点右侧开关开启取景；取景中点画面任意位置即点按对焦，双击画面可放大查看（捏合/拖动同样可用）", 13, Ui.TEXT_DIM, false);
        lvHintTv.setGravity(Gravity.CENTER);
        lvStage.addView(lvHintTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // v0.5.1 手势层：点按对焦（onSingleTapConfirmed，自带双击消歧等待）/ 双击 1x↔2.5x /
        // 双指捏合 1–4x / 放大后单指拖动平移。坐标换算只走 HudView.mapToFrame 一条口径
        // （含放大变换逆映射），与 1x 共用，禁止另算一套。
        final GestureDetector lvGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                handleLvTapFocus(e.getX(), e.getY());
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (hud.getZoomScale() > 1.05f) {
                    applyLvZoom(1f, 0f, 0f);
                    rcStatusTv.setText("已恢复 1× 显示");
                } else {
                    applyLvZoomAt(2.5f, e.getX(), e.getY());
                    rcStatusTv.setText("已放大 2.5× 查看（双击恢复，单指拖动平移，点按仍可对焦）");
                }
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (hud.getZoomScale() > 1f && e2.getPointerCount() == 1) {
                    applyLvZoom(hud.getZoomScale(), hud.getZoomTx() - dx, hud.getZoomTy() - dy);
                    return true;
                }
                return false;
            }
        });
        final ScaleGestureDetector lvScale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                applyLvZoomAt(hud.getZoomScale() * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        lvStage.setOnTouchListener((v, ev) -> {
            lvScale.onTouchEvent(ev);
            lvGestures.onTouchEvent(ev);
            return true;
        });

        // —— 顶部浮层（v0.5.1 紧凑单行）：左=‹返回 + 型号·电量·剩余；右=模式·光圈·快门·ISO·AF区域 速览 + 尺寸档·fps + REC ——
        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xB3000000);
        topBar.setPadding(dp(8), dp(6), dp(10), dp(6));
        topBar.setClickable(true); // 吞掉落在浮层上的点按，防穿透到舞台触发对焦
        FrameLayout.LayoutParams tblp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        pageRoot.addView(topBar, tblp);
        TextView backTv = txt("‹ 返回", 13, Ui.TEXT, true);
        backTv.setBackground(getDrawable(R.drawable.bg_chip));
        backTv.setPadding(dp(10), dp(5), dp(10), dp(5));
        backTv.setClickable(true);
        backTv.setOnClickListener(v -> guarded("退出全屏", () -> showPage(0))); // 退出全屏：恢复框架/系统栏 + 既有切页 teardown 停取景
        rcBackBtn = backTv;
        topBar.addView(backTv);
        rcTopSubTv = txt("电量 — · 剩余可拍 —", 11, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tslp.setMargins(dp(8), 0, dp(6), 0);
        topBar.addView(rcTopSubTv, tslp);
        rcTopParamsTv = txt("参数 —", 11, Ui.TEXT, true);
        rcTopParamsTv.setGravity(Gravity.END);
        topBar.addView(rcTopParamsTv);
        rcLvStateTv = txt("取景未开启", 11, Ui.ACCENT, false);
        rcLvStateTv.setGravity(Gravity.END);
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lslp.setMargins(dp(6), 0, 0, 0);
        topBar.addView(rcLvStateTv, lslp);
        rcRecTv = txt("REC 00:00", 12, Ui.ERR, true);
        Drawable recDot = icon(R.drawable.ic_rec, Ui.ERR);
        int rds = dp(11);
        recDot.setBounds(0, 0, rds, rds);
        rcRecTv.setCompoundDrawables(recDot, null, null, null);
        rcRecTv.setCompoundDrawablePadding(dp(5));
        rcRecTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rrlp.setMargins(dp(8), 0, 0, 0);
        topBar.addView(rcRecTv, rrlp);

        // —— 右侧竖排浮层开关：取景电源/网格/直方图/监看滤镜/帧尺寸档/参数面板 ——
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClickable(true);
        FrameLayout.LayoutParams rallp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        rallp.setMargins(0, 0, dp(10), 0);
        pageRoot.addView(rail, rallp);
        railLvBtn = railBtn(R.drawable.ic_remote);
        rail.addView(railLvBtn, railItemLp(false));
        railGridBtn = railBtn(R.drawable.ic_grid);
        rail.addView(railGridBtn, railItemLp(true));
        railHistBtn = railBtn(R.drawable.ic_hist);
        rail.addView(railHistBtn, railItemLp(true));
        railFilterBtn = railBtn(R.drawable.ic_filter);
        rail.addView(railFilterBtn, railItemLp(true));
        // 帧尺寸档按钮：圆底 + 当前档名小字（点开列全部档位）
        FrameLayout sizeBtn = new FrameLayout(this);
        sizeBtn.setBackground(getDrawable(R.drawable.bg_circle_btn));
        sizeBtn.setClickable(true);
        railSizeTv = txt("尺寸", 10, Ui.TEXT, true);
        railSizeTv.setGravity(Gravity.CENTER);
        sizeBtn.addView(railSizeTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        railSizeBtn = sizeBtn;
        rail.addView(railSizeBtn, railItemLp(true));
        railPropsBtn = railBtn(R.drawable.ic_settings);
        rail.addView(railPropsBtn, railItemLp(true));
        railLvBtn.setOnClickListener(v -> guarded("开关取景", () -> {
            if (remote == null) { Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show(); return; }
            if (remote.isLiveViewOn()) remote.stopLiveView(); else remote.startLiveView();
        }));
        railGridBtn.setOnClickListener(v -> guarded("网格线开关", () -> {
            gridOn = !gridOn;
            hud.setGridOn(gridOn);
            setRailOn(railGridBtn, gridOn);
        }));
        railHistBtn.setOnClickListener(v -> guarded("直方图开关", () -> {
            histOn = !histOn;
            hud.setHistOn(histOn);
            if (remote != null) remote.setHistogramEnabled(histOn);
            setRailOn(railHistBtn, histOn);
        }));
        railFilterBtn.setOnClickListener(v -> guarded("监看滤镜", this::toggleLvFilterBar));
        railSizeBtn.setOnClickListener(v -> guarded("帧尺寸选择", this::showSizePicker));
        railPropsBtn.setOnClickListener(v -> guarded("参数面板", () -> setPanelOpen(!rcPanelOpen)));
        setRailOn(railGridBtn, true);
        setRailOn(railHistBtn, true);

        // —— 底部：状态小字 + 相机式控制栏（左缩略图 / 中大快门 / 右录像） ——
        LinearLayout bottomWrap = new LinearLayout(this);
        bottomWrap.setOrientation(LinearLayout.VERTICAL);
        bottomWrap.setClickable(true);
        FrameLayout.LayoutParams bwlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        pageRoot.addView(bottomWrap, bwlp);
        lvNoteTv = txt("", 11, Ui.ACCENT, false);
        lvNoteTv.setGravity(Gravity.CENTER);
        lvNoteTv.setBackground(getDrawable(R.drawable.bg_chip));
        lvNoteTv.setPadding(dp(8), dp(3), dp(8), dp(3));
        lvNoteTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams nllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nllp.gravity = Gravity.CENTER_HORIZONTAL;
        bottomWrap.addView(lvNoteTv, nllp);
        rcStatusTv = txt("未连接相机（先到「连接」页连接）", 12, Ui.TEXT, false);
        rcStatusTv.setGravity(Gravity.CENTER);
        rcStatusTv.setBackground(getDrawable(R.drawable.bg_chip));
        rcStatusTv.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams stlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp2.gravity = Gravity.CENTER_HORIZONTAL;
        stlp2.setMargins(0, dp(6), 0, dp(8));
        bottomWrap.addView(rcStatusTv, stlp2);
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xB3000000);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(12));
        bottomWrap.addView(bottomBar);
        lastShotIv = new ImageView(this);
        lastShotIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastShotIv.setBackgroundColor(Ui.PANEL_ALT);
        lastShotIv.setImageDrawable(icon(R.drawable.ic_badge_image, Ui.TEXT_DIM));
        lastShotIv.setOnClickListener(v -> showLastShotBig());
        bottomBar.addView(lastShotIv, new LinearLayout.LayoutParams(dp(54), dp(54)));
        bottomBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        FrameLayout shutter = new FrameLayout(this);
        shutter.setClickable(true);
        View ring = new View(this);
        ring.setBackground(getDrawable(R.drawable.bg_shutter_ring));
        shutter.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shutterInner = new View(this);
        shutterInner.setBackground(getDrawable(R.drawable.bg_shutter_inner));
        shutter.addView(shutterInner, new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER));
        shutter.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) shutterInner.setAlpha(0.55f);
            else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) shutterInner.setAlpha(1f);
            return false; // 不吞事件，click 照常触发
        });
        shutter.setOnClickListener(v -> guarded("遥控拍摄", () -> { if (remote != null) remote.capture(); }));
        captureBtn = shutter;
        bottomBar.addView(shutter, new LinearLayout.LayoutParams(dp(76), dp(76)));
        bottomBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        FrameLayout movie = new FrameLayout(this);
        movie.setBackground(getDrawable(R.drawable.bg_circle_btn));
        movie.setClickable(true);
        movieIconIv = new ImageView(this);
        movieIconIv.setImageDrawable(icon(R.drawable.ic_rec, Ui.ERR));
        movie.addView(movieIconIv, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        movie.setOnClickListener(v -> guarded("录像", () -> { if (remote != null) remote.toggleMovie(); }));
        movieBtn = movie;
        bottomBar.addView(movie, new LinearLayout.LayoutParams(dp(54), dp(54)));

        // —— 监看滤镜横向胶囊行（v0.6）：默认收起，点右侧栏滤镜钮展开到状态行上方；
        //    「关」+ 与出片完全同一份 Filters 目录（按分组排），全款用拟合矩阵走渲染层 ——
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.VERTICAL);
        filterBar.setBackgroundColor(0xB3000000);
        filterBar.setPadding(dp(10), dp(8), dp(10), dp(6));
        filterBar.setVisibility(View.GONE);
        HorizontalScrollView fScroll = new HorizontalScrollView(this);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fRow = new LinearLayout(this);
        fRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i <= Filters.COUNT; i++) lvFilterCaps.add(null);
        addLvFilterCapsule(fRow, -1, "关", true);
        for (int g : Filters.GROUP_ORDER) {
            List<Integer> idxs = Filters.indicesInGroup(g);
            if (idxs.isEmpty()) continue;
            TextView gh = txt(Filters.GROUP_NAMES[g], 10, Ui.TEXT_DIM, true);
            LinearLayout.LayoutParams ghlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ghlp.setMargins(dp(10), 0, dp(2), 0);
            fRow.addView(gh, ghlp);
            for (int idx : idxs) {
                addLvFilterCapsule(fRow, idx, Filters.NAMES[idx] + Filters.noteSuffix(idx), false);
            }
        }
        fScroll.addView(fRow);
        filterBar.addView(fScroll);
        TextView fNote = txt("监看滤镜仅预览，照片为相机原片，出片时套用高精度版", 10, Ui.TEXT_DIM, false);
        LinearLayout.LayoutParams fnlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fnlp.setMargins(0, dp(5), 0, 0);
        filterBar.addView(fNote, fnlp);
        bottomWrap.addView(filterBar, 0);
        rcFilterBar = filterBar;
        refreshLvFilterCaps();

        // —— 未连接引导卡（浮层居中；连上即收起） ——
        LinearLayout rcHintCard = new LinearLayout(this);
        rcHintCard.setOrientation(LinearLayout.VERTICAL);
        rcHintCard.setBackground(getDrawable(R.drawable.bg_card));
        rcHintCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        rcHintCard.setClickable(true);
        FrameLayout.LayoutParams rhclp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        rhclp.setMargins(dp(26), 0, dp(26), 0);
        rcHintCard.setLayoutParams(rhclp);
        rcHintCard.addView(txt("连接相机后，这里就是取景器：全屏实时取景、点按对焦、大快门拍摄与录像、右侧浮层开关（网格/直方图/监看滤镜/帧尺寸），底部上滑参数面板可调相机自报的全部参数，顶部还有电量与剩余张数。", 12, Ui.TEXT_DIM, false));
        Button rcGoConnect = btn("去连接相机", R.drawable.ic_wifi, true);
        rcGoConnect.setOnClickListener(v -> showPage(0));
        LinearLayout.LayoutParams rgclp = matchWrap(); rgclp.setMargins(0, dp(10), 0, 0);
        rcHintCard.addView(rcGoConnect, rgclp);
        rcHintCard.addView(txt("不想等相机？出片不用连接：给手机里任意照片加 EXIF 水印边框、套滤镜。", 12, Ui.TEXT_DIM, false), wrapMargin(10));
        Button rcGoEdit = btn("挑一张照片出片", R.drawable.ic_edit, false);
        rcGoEdit.setOnClickListener(v -> openEditorImport());
        LinearLayout.LayoutParams rgelp = matchWrap(); rgelp.setMargins(0, dp(8), 0, 0);
        rcHintCard.addView(rcGoEdit, rgelp);
        rcHintCardRef = rcHintCard;
        pageRoot.addView(rcHintCard);

        // —— 参数面板（底部上滑半屏，自绘深色，不压暗取景；内含全量分组属性 + 间隔拍摄） ——
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(getDrawable(R.drawable.bg_panel_sheet));
        panel.setPadding(dp(14), dp(6), dp(14), dp(12));
        panel.setClickable(true);
        int panelH = (int) (getResources().getDisplayMetrics().heightPixels * 0.56f);
        FrameLayout.LayoutParams panlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelH, Gravity.BOTTOM);
        panel.setLayoutParams(panlp);
        panel.setVisibility(View.GONE);
        View handle = new View(this);
        handle.setBackground(getDrawable(R.drawable.bg_handle));
        LinearLayout.LayoutParams hdlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hdlp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, hdlp);
        LinearLayout panHead = new LinearLayout(this);
        panHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView panTitle = txt("相机参数", 15, Ui.TEXT, true);
        panHead.addView(panTitle);
        rcPanelSummaryTv = txt("电量 — · 剩余可拍 —", 12, Ui.TEXT_DIM, false);
        rcPanelSummaryTv.setGravity(Gravity.END);
        LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pslp.setMargins(dp(10), 0, dp(8), 0);
        panHead.addView(rcPanelSummaryTv, pslp);
        Button panRefresh = smallGhostBtn("刷新");
        panRefresh.setOnClickListener(v -> {
            if (remote != null) remote.loadProps(AppState.get().storageId);
            else Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show();
        });
        panHead.addView(panRefresh);
        Button panClose = smallGhostBtn("收起");
        panClose.setOnClickListener(v -> setPanelOpen(false));
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pclp.setMargins(dp(8), 0, 0, 0);
        panHead.addView(panClose, pclp);
        LinearLayout.LayoutParams phlp = matchWrap(); phlp.setMargins(0, dp(8), 0, 0);
        panel.addView(panHead, phlp);
        panel.addView(txt("下列为相机自报支持的全部属性（分组陈列）：可写的给「调整」（选项严格来自相机取值集合，写入后回读确认）；置灰项为相机报告只读或未提供，原因标在行尾。", 11, Ui.TEXT_DIM, false), wrapMargin(4));
        ScrollView panScroll = new ScrollView(this);
        LinearLayout panCol = new LinearLayout(this);
        panCol.setOrientation(LinearLayout.VERTICAL);
        panScroll.addView(panCol);
        panel.addView(panScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        rcPanelBox = new LinearLayout(this);
        rcPanelBox.setOrientation(LinearLayout.VERTICAL);
        panCol.addView(rcPanelBox);
        // 间隔拍摄（v0.3 保留，收进面板下半区滚动）
        LinearLayout ivHead = new LinearLayout(this);
        ivHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ivIc = new ImageView(this);
        ivIc.setImageDrawable(icon(R.drawable.ic_timer, Ui.ACCENT));
        ivHead.addView(ivIc, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ivTitle = txt("间隔拍摄", 14, Ui.TEXT, true);
        LinearLayout.LayoutParams ivtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ivtlp.setMargins(dp(8), 0, 0, 0);
        ivHead.addView(ivTitle, ivtlp);
        LinearLayout.LayoutParams ivhlp = matchWrap(); ivhlp.setMargins(0, dp(16), 0, 0);
        panCol.addView(ivHead, ivhlp);
        panCol.addView(txt("按固定间隔自动遥控拍摄（前台服务保活，锁屏也能继续）。与照片页「拍完自动回传」同开可拍一张传一张。任一张拍摄失败会立刻报错并停下，不静默重试。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
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
        panCol.addView(ivInputRow, wrapMargin(8));
        ivBtn = btn("开始间隔拍摄", R.drawable.ic_timer, true);
        ivBtn.setOnClickListener(v -> guarded("间隔拍摄", this::onIntervalToggle));
        panCol.addView(ivBtn, wrapMargin(8));
        ivStatusTv = txt("未开始", 12, Ui.TEXT_DIM, false);
        panCol.addView(ivStatusTv, wrapMargin(6));
        rcPanel = panel;
        pageRoot.addView(panel);

        // —— 最近一拍大图浮层 ——
        rcBigOverlay = new FrameLayout(this);
        rcBigOverlay.setBackgroundColor(0xF2000000);
        rcBigOverlay.setVisibility(View.GONE);
        rcBigOverlay.setClickable(true);
        rcBigIv = new ImageView(this);
        rcBigIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rcBigOverlay.addView(rcBigIv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rcBigNameTv = txt("", 12, Ui.TEXT, false);
        rcBigNameTv.setGravity(Gravity.CENTER);
        rcBigNameTv.setBackground(getDrawable(R.drawable.bg_chip));
        rcBigNameTv.setPadding(dp(10), dp(5), dp(10), dp(5));
        FrameLayout.LayoutParams bnlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bnlp.setMargins(dp(20), 0, dp(20), dp(26));
        rcBigOverlay.addView(rcBigNameTv, bnlp);
        rcBigOverlay.setOnClickListener(v -> rcBigOverlay.setVisibility(View.GONE));
        pageRoot.addView(rcBigOverlay);

        // —— 通用选择弹层（参数/滤镜/帧尺寸共用，自绘深色卡，禁系统灰弹窗） ——
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

    private LinearLayout.LayoutParams railItemLp(boolean marginTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        if (marginTop) lp.setMargins(0, dp(9), 0, 0);
        return lp;
    }

    /** 按连接/门控状态刷新遥控页控件可用性（未连接一律禁用快门/录像，浮层开关与面板保留可看） */
    private void refreshRemoteEnabled() {
        boolean conn = AppState.get().sessionReady() && remote != null;
        if (captureBtn != null) {
            boolean cap = conn && remote.featureAllowed(RemoteController.F_CAPTURE);
            captureBtn.setEnabled(cap);
            captureBtn.setAlpha(cap ? 1f : 0.35f);
        }
        if (movieBtn != null) {
            boolean mov = conn && remote.featureAllowed(RemoteController.F_MOVIE);
            movieBtn.setEnabled(mov);
            movieBtn.setAlpha(mov ? 1f : 0.35f);
        }
        if (railLvBtn != null) railLvBtn.setEnabled(conn);
        if (railSizeBtn != null) railSizeBtn.setEnabled(conn && lvSizeSupportedUi && lvSizeOptions.size() > 1);
        if (rcHintCardRef != null) rcHintCardRef.setVisibility(conn ? View.GONE : View.VISIBLE);
        updateTopSummary();
        if (!conn && rcStatusTv != null && AppState.get().client == null) {
            rcStatusTv.setText("未连接相机（先到「连接」页连接）");
        }
    }

    private RemoteController.PropItem findProp(int code) {
        for (RemoteController.PropItem it : propItems) if (it.def.code == code) return it;
        return null;
    }

    /** 剩余张数文案的紧凑版（浮层单行用）：截掉括号里的来源说明，面板顶部仍用全文 */
    private static String shortRemain(String s) {
        if (s == null) return "—";
        int p = s.indexOf('（');
        return p > 0 ? s.substring(0, p) : s;
    }

    /** 顶部浮层速览与面板顶部状态行：型号·电量·剩余（紧凑行）、模式·光圈·快门·ISO·AF区域（只拼相机真报到的项，缺项省略不编造） */
    private void updateTopSummary() {
        if (rcTopSubTv == null) return;
        boolean conn = AppState.get().client != null && AppState.get().client.isConnected();
        String model = AppState.get().deviceInfo != null ? AppState.get().deviceInfo.model : "";
        String subFull = "电量 " + (rcBattery >= 0 ? rcBattery + "%" : "—") + " · 剩余可拍 " + rcRemainText;
        rcTopSubTv.setText((conn ? (model.isEmpty() ? "已连接" : model) + " · " : "")
                + "电量 " + (rcBattery >= 0 ? rcBattery + "%" : "—") + " · 剩余 " + shortRemain(rcRemainText));
        if (rcPanelSummaryTv != null) rcPanelSummaryTv.setText(subFull);
        StringBuilder sb = new StringBuilder();
        RemoteController.PropItem mode = findProp(PtpIpClient.PROP_EXPOSURE_PROGRAM);
        if (mode != null && mode.available() && mode.desc.curVal != null) {
            String ps = CamProps.programShort(mode.desc.curVal.num);
            sb.append(ps != null ? ps + " 挡" : CamProps.format(PtpIpClient.PROP_EXPOSURE_PROGRAM, mode.desc.curVal));
        }
        appendPropSeg(sb, findProp(PtpIpClient.PROP_F_NUMBER));
        appendPropSeg(sb, findProp(PtpIpClient.PROP_EXPOSURE_TIME));
        RemoteController.PropItem iso = findProp(PtpIpClient.PROP_EXPOSURE_INDEX);
        if (iso != null && iso.available() && iso.desc.curVal != null
                && iso.desc.curVal.datatype != PtpIpClient.DTC_STR) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("ISO ").append(iso.desc.curVal.num);
        }
        // v0.5.1：对焦区域模式进速览（机身或面板一改，这里立刻能看到，配合事件/轮询增量刷新）
        RemoteController.PropItem fm = findProp(PtpIpClient.PROP_FOCUS_METERING);
        if (fm != null && fm.available() && fm.desc.curVal != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("AF区域 ").append(fm.currentText());
        }
        rcTopParamsTv.setText(sb.length() > 0 ? sb.toString() : "参数 —");
    }

    private void appendPropSeg(StringBuilder sb, RemoteController.PropItem it) {
        if (it == null || !it.available()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(it.currentText());
    }

    /** 帧尺寸档数据更新（中枢 queryLvSize/切档/降档后回调）：浮层按钮只显示当前档名，点开列全部档位 */
    private void updateLvSizeButtons(List<String> options, int currentIdx, boolean supported) {
        lvSizeOptions = options != null ? options : new ArrayList<>();
        lvSizeCurrent = currentIdx;
        lvSizeSupportedUi = supported;
        if (railSizeTv != null) {
            railSizeTv.setText(supported && currentIdx >= 0 && currentIdx < lvSizeOptions.size()
                    ? lvSizeOptions.get(currentIdx) : "尺寸");
        }
        boolean conn = AppState.get().sessionReady() && remote != null;
        if (railSizeBtn != null) railSizeBtn.setEnabled(conn && supported && lvSizeOptions.size() > 1);
    }

    /** v0.4 监看滤镜开关：只给 lvImage 挂/摘 ColorFilter（渲染层），不动取景三线程与诊断口径。
     *  v0.6：目录扩到 36 款，全款用拟合矩阵近似（LUT 款出片才走高精度，监看不读 LUT 资产）。 */
    private void applyLvFilter(int idx) {
        lvFilterIdx = idx;
        if (lvImage != null) {
            if (idx < 0) lvImage.setColorFilter(null);
            else lvImage.setColorFilter(new ColorMatrixColorFilter(Filters.matrix(idx, 100)));
        }
        refreshLvFilterCaps();
        if (idx < 0) {
            Diag.op("LVFILTER", "监看滤镜 → 关（默认）");
        } else {
            Diag.op("LVFILTER", "监看滤镜 → " + Filters.NAMES[idx]
                    + (Filters.isLog(idx) ? "（通用近似，非尼康官方 N-Log 曲线）" : Filters.noteSuffix(idx))
                    + (Filters.hasLut(idx) ? "（监看为矩阵近似，出片走 17³ LUT 高精度）" : "")
                    + "：ImageView 渲染层 ColorMatrix 叠加，不进拉帧/解码/呈现链；帧率与抖动诊断照常记录，真机若拖累帧率由自适应锁档处理");
            if (rcStatusTv != null) rcStatusTv.setText("监看滤镜：" + Filters.NAMES[idx] + "（仅预览，不影响原图）");
        }
    }

    /** 监看滤镜胶囊行展开/收起（v0.6：横向滚动胶囊行取代旧竖排弹层） */
    private void toggleLvFilterBar() {
        if (rcFilterBar == null) return;
        boolean show = rcFilterBar.getVisibility() != View.VISIBLE;
        rcFilterBar.setVisibility(show ? View.VISIBLE : View.GONE);
        setRailOn(railFilterBtn, show);
        if (show) refreshLvFilterCaps();
    }

    private void addLvFilterCapsule(LinearLayout row, final int catalogIdx, String label, boolean first) {
        TextView c = txt(label, 12, Ui.TEXT, true);
        c.setGravity(Gravity.CENTER);
        c.setSingleLine(true);
        c.setPadding(dp(11), dp(6), dp(11), dp(6));
        c.setClickable(true);
        c.setOnClickListener(v -> applyLvFilter(catalogIdx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) lp.setMargins(dp(6), 0, 0, 0);
        row.addView(c, lp);
        lvFilterCaps.set(catalogIdx + 1, c);
    }

    private void refreshLvFilterCaps() {
        for (int i = 0; i < lvFilterCaps.size(); i++) {
            TextView c = lvFilterCaps.get(i);
            if (c == null) continue;
            boolean on = (i - 1) == lvFilterIdx;
            c.setBackground(getDrawable(on ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost));
            c.setTextColor(on ? Ui.ACCENT_TX : Ui.TEXT);
        }
    }

    /** 帧尺寸档选择弹层：列相机自报的全部档位（不再只有流畅/清晰两极），点选走中枢 setLvSizeIndex */
    private void showSizePicker() {
        if (remote == null) { Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show(); return; }
        if (!lvSizeSupportedUi || lvSizeOptions.size() <= 1) {
            Toast.makeText(this, "相机未提供多档帧尺寸切换（0xD1AC）", Toast.LENGTH_SHORT).show();
            return;
        }
        ovList.removeAllViews();
        ovTitleTv.setText("帧尺寸档（相机自报，升序）");
        for (int i = 0; i < lvSizeOptions.size(); i++) {
            final int idx = i;
            String label = lvSizeOptions.get(i)
                    + (i == 0 ? "（流畅）" : i == lvSizeOptions.size() - 1 ? "（清晰）" : "")
                    + (idx == lvSizeCurrent ? "（当前）" : "");
            Button b = btn(label, 0, idx == lvSizeCurrent);
            b.setOnClickListener(v -> guarded("切换帧尺寸", () -> { rcOverlay.setVisibility(View.GONE); if (remote != null) remote.setLvSizeIndex(idx); }));
            LinearLayout.LayoutParams blp = matchWrap(); blp.setMargins(0, dp(6), 0, 0);
            ovList.addView(b, blp);
        }
        rcOverlay.setVisibility(View.VISIBLE);
    }

    /**
     * 参数面板渲染（v0.5 全量分组）：propItems 已由中枢按分组排好序，这里顺序渲染——
     * 分组标题 + 每行（名称/当前值/「调整」或置灰原因）。可写判定严格按相机 desc 自报：
     * 可写且有取值集且门控放行 → 调整按钮（点开 showPropPicker，选项来自相机枚举/范围，写入走 Set+回读）；
     * 只读行置灰并在行尾标原因。曝光组末尾按拍摄模式补「自动决定」说明（仅当光圈/快门确为只读且非 M 挡时）。
     */
    private void renderPropsPanel() {
        if (rcPanelBox == null) return;
        updateTopSummary();
        rcPanelBox.removeAllViews();
        boolean writeAllowed = remote != null && remote.featureAllowed(RemoteController.F_PROPS_WRITE);
        if (propItems.isEmpty()) {
            rcPanelBox.addView(txt("暂无属性数据：先连接相机，连接后会自动读取相机自报的全部属性。", 12, Ui.TEXT_DIM, false), wrapMargin(6));
            return;
        }
        for (int g = 0; g < CamProps.GROUP_NAMES.length; g++) {
            List<RemoteController.PropItem> group = new ArrayList<>();
            for (RemoteController.PropItem it : propItems) if (it.def.group == g) group.add(it);
            if (group.isEmpty()) continue;
            TextView gh = txt(CamProps.GROUP_NAMES[g], 13, Ui.ACCENT, true);
            rcPanelBox.addView(gh, wrapMargin(g == CamProps.GROUP_EXPOSURE ? 2 : 12));
            for (final RemoteController.PropItem it : group) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                boolean avail = it.available();
                TextView name = txt(it.def.name, 13, avail ? Ui.TEXT : Ui.TEXT_DIM, false);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView val = txt(avail ? it.currentText() : "—",
                        13, avail ? Ui.TEXT : Ui.TEXT_DIM, true);
                row.addView(val);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(dp(8), 0, 0, 0);
                if (it.def.code == PtpIpClient.PROP_DATETIME && avail) {
                    // v0.5.1 相机时钟：STR 无取值集走不了「调整」弹层，给专用「同步手机时间」按钮
                    // （写手机当前时间 + 回读验证在中枢，中枢绝不自动写、只此按钮触发）
                    Button sync = smallGhostBtn("同步手机时间");
                    sync.setOnClickListener(v -> {
                        if (remote != null) remote.syncCameraClock();
                        else Toast.makeText(this, "先连接相机", Toast.LENGTH_SHORT).show();
                    });
                    row.addView(sync, rlp);
                } else if (avail && it.canWrite()) {
                    if (writeAllowed) {
                        Button adj = smallGhostBtn("调整");
                        adj.setOnClickListener(v -> showPropPicker(it));
                        row.addView(adj, rlp);
                    } else {
                        String why = remote != null && remote.featureLockedByProfile(RemoteController.F_PROPS_WRITE)
                                ? "未验证机型锁定（诊断页开实验开关后可调）" : "该功能已降级禁用";
                        row.addView(txt(why, 11, Ui.TEXT_DIM, false), rlp);
                    }
                } else {
                    String why;
                    if (!avail) why = "相机未提供";
                    else if (!it.desc.writable()) why = "只读（相机报告不可写）";
                    else why = "只读（无取值集）";
                    row.addView(txt(why, 11, Ui.TEXT_DIM, false), rlp);
                }
                rcPanelBox.addView(row, wrapMargin(5));
                if (it.def.code == PtpIpClient.PROP_DATETIME && avail && it.desc.curVal != null
                        && it.desc.curVal.str != null) {
                    int camYear = RemoteController.parseClockYear(it.desc.curVal.str);
                    int phoneYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                    if (camYear > 0 && camYear < phoneYear) {
                        rcPanelBox.addView(txt("⚠ 相机时钟停在 " + camYear + " 年（手机为 " + phoneYear
                                + " 年），照片的 EXIF 拍摄时间会全错。点上方『同步手机时间』即可一键对齐，不会自动修改。", 11, Ui.ACCENT, false), wrapMargin(3));
                    }
                }
            }
            if (g == CamProps.GROUP_EXPOSURE) addAutoDecideNote();
            if (g == CamProps.GROUP_FOCUS) addAfAreaRow();
        }
    }

    /**
     * v0.5.1 对焦组附加行：当前对焦区域模式 + 「点按对焦自动切单点」开关。
     * 自动切单点只在中枢能可靠识别 0x501C（FocusMeteringMode，0x8010=单点）时才真正发生，
     * 这里只负责开关与现状展示；识别不了时中枢只提示不乱改。
     */
    private void addAfAreaRow() {
        RemoteController.PropItem fm = findProp(PtpIpClient.PROP_FOCUS_METERING);
        String cur = fm != null && fm.available() ? fm.currentText() : "未读到（相机未报告 0x501C）";
        rcPanelBox.addView(txt("当前对焦区域：" + cur + "。区域模式非单点时，相机可能自行修正点按对焦点。", 11, Ui.TEXT_DIM, false), wrapMargin(4));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(prefs.getBoolean("af_auto_single", true));
        cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        cb.setOnCheckedChangeListener((bv, checked) -> {
            prefs.edit().putBoolean("af_auto_single", checked).apply();
            if (remote != null) remote.setAutoSinglePoint(checked);
        });
        row.addView(cb);
        row.addView(txt("点按对焦前自动切换为单点 AF（仅当相机区域模式可可靠识别时）", 12, Ui.TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rcPanelBox.addView(row, wrapMargin(2));
    }

    /**
     * 曝光组补充说明：拍摄模式非 M 挡、且光圈/快门至少一项被相机报告只读时，
     * 如实说明这些参数当前由相机自动决定。用的是相机自报的模式值（PTP 标准映射），不编造。
     */
    private void addAutoDecideNote() {
        RemoteController.PropItem mode = findProp(PtpIpClient.PROP_EXPOSURE_PROGRAM);
        RemoteController.PropItem f = findProp(PtpIpClient.PROP_F_NUMBER);
        RemoteController.PropItem s = findProp(PtpIpClient.PROP_EXPOSURE_TIME);
        if (mode == null || !mode.available() || mode.desc.curVal == null) return;
        long mv = mode.desc.curVal.num;
        boolean fRo = f != null && f.available() && !f.desc.writable();
        boolean sRo = s != null && s.available() && !s.desc.writable();
        if (mv == 1 || (!fRo && !sRo)) return; // M 挡或全可写时不提示，避免把机身其它限制误说成挡位问题
        String what = fRo && sRo ? "光圈与快门" : fRo ? "光圈" : "快门";
        String note = "提示：当前为 " + CamProps.format(PtpIpClient.PROP_EXPOSURE_PROGRAM, mode.desc.curVal)
                + " 挡，" + what + "由相机自动决定（相机报告为只读）；切到 A/S/M 挡后，若相机报告可写即可在此调节（以相机实际报告为准）。";
        rcPanelBox.addView(txt(note, 11, Ui.ACCENT, false), wrapMargin(6));
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
        @Override public void onProps(List<RemoteController.PropItem> items, String remainText, int battery) {
            propItems = items;
            if (remainText != null) rcRemainText = remainText;
            if (battery >= 0) rcBattery = battery;
            runOnUiThread(() -> renderPropsPanel());
        }
        @Override public void onPropUpdated(RemoteController.PropItem item, boolean ok, String msg) {
            runOnUiThread(() -> {
                rcStatusTv.setText(item.def.name + "：" + msg);
                renderPropsPanel(); // desc 已被中枢回读更新，原样重渲染
            });
        }
        @Override public void onFrame(Bitmap bmp, float fps) {
            if (pages[2].getVisibility() != View.VISIBLE) return; // 不在遥控页不做 UI 工作
            runOnUiThread(() -> {
                if (bmp.getWidth() != lvFrameW || bmp.getHeight() != lvFrameH) {
                    lvFrameW = bmp.getWidth(); lvFrameH = bmp.getHeight();
                    resetLvZoom(); // 帧尺寸/比例变了（如切了尺寸档），旧放大变换不再对位，重置回 1×
                }
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
                // 三态收尾：AfDrive 响应码 OK→绿（合焦）/ 0xA002 等→红（未合焦）；判不了更细就以响应码为准
                if (pendingAfNx >= 0) hud.showAfState(pendingAfNx, pendingAfNy, ok ? HudView.AF_OK : HudView.AF_FAIL);
            });
        }
        @Override public void onNewPhoto(int handle, Bitmap thumb, String filename) {
            runOnUiThread(() -> {
                if (thumb != null) {
                    lastShotBmp = thumb;
                    lastShotIv.setImageBitmap(thumb);
                }
                lastShotName = filename;
                rcStatusTv.setText("新照片 " + filename + "（点左下缩略图看大图）");
            });
        }
        @Override public void onMovie(boolean recording) {
            runOnUiThread(() -> updateMovieUi(recording));
        }
        @Override public void onLiveView(boolean on, String msg) {
            runOnUiThread(() -> {
                setRailOn(railLvBtn, on);
                if (!on) resetLvZoom();
                lvHintTv.setVisibility(on ? View.GONE : View.VISIBLE);
                if (!on) rcLvStateTv.setText("取景未开启");
                rcStatusTv.setText(msg);
            });
        }
        @Override public void onPropsChanged(List<RemoteController.PropItem> changed) {
            // v0.5.1 增量刷新直达可见面：并入本地属性表 → 顶部浮层速览即时更新；面板开着则整表重绘
            // （行值与可写置灰状态随之翻转，如机身换挡后光圈/快门从置灰变可调）
            runOnUiThread(() -> {
                for (RemoteController.PropItem c : changed) {
                    boolean found = false;
                    for (int i = 0; i < propItems.size(); i++) {
                        if (propItems.get(i).def.code == c.def.code) { propItems.set(i, c); found = true; break; }
                    }
                    if (!found) propItems.add(c);
                }
                updateTopSummary();
                if (rcPanelOpen) renderPropsPanel();
            });
        }
        @Override public void onRemainChanged(String remainText, int battery) {
            runOnUiThread(() -> {
                if (remainText != null) rcRemainText = remainText;
                if (battery >= 0) rcBattery = battery;
                updateTopSummary();
            });
        }
        @Override public void onFeatureStateChanged() {
            runOnUiThread(() -> { refreshRemoteEnabled(); renderPropsPanel(); });
        }
        @Override public void onLvState(String sizeLabel, int targetFps, float displayFps, String note) {
            runOnUiThread(() -> {
                rcLvStateTv.setText(sizeLabel + " · 约 " + String.format("%.0f", displayFps) + "fps"); // 如实播报所选档的实际呈现帧率
                if (note != null) {
                    lvNoteTv.setText(note);
                    lvNoteTv.setVisibility(View.VISIBLE);
                }
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
            rcRemainText = "—";
            rcBattery = -1;
            if (rcPanelBox != null) rcPanelBox.removeAllViews();
            setPanelOpen(false);
            if (rcOverlay != null) rcOverlay.setVisibility(View.GONE);
            if (rcBigOverlay != null) rcBigOverlay.setVisibility(View.GONE);
            lastShotBmp = null;
            lastShotName = "";
            if (lastShotIv != null) lastShotIv.setImageDrawable(icon(R.drawable.ic_badge_image, Ui.TEXT_DIM));
            updateMovieUi(false);
            setRailOn(railLvBtn, false);
            setRailOn(railGridBtn, gridOn);
            setRailOn(railHistBtn, histOn);
            if (rcLvStateTv != null) rcLvStateTv.setText("取景未开启");
            if (lvNoteTv != null) { lvNoteTv.setText(""); lvNoteTv.setVisibility(View.GONE); }
            if (lvHintTv != null) lvHintTv.setVisibility(View.VISIBLE);
            if (rcStatusTv != null) rcStatusTv.setText("未连接相机（先到「连接」页连接）");
            refreshRemoteEnabled();
        });
    }

    // ================= 在线更新（v0.5） =================

    /** 更新卡（自绘深色层，挂在页面区顶层，哪个页签下都能弹；结构与遥控页选择弹层同规范） */
    private void buildUpdateOverlay(FrameLayout parent) {
        updateOverlay = new FrameLayout(this);
        updateOverlay.setBackgroundColor(0xB3000000);
        updateOverlay.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_card));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.setMargins(dp(22), 0, dp(22), 0);
        updTitleTv = txt("发现新版本", 16, Ui.TEXT, true);
        card.addView(updTitleTv);
        updNotesTv = txt("", 12, Ui.TEXT_DIM, false);
        card.addView(updNotesTv, wrapMargin(8));
        updBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updBar.setMax(100);
        updBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        updBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams ublp = matchWrap(); ublp.setMargins(0, dp(10), 0, 0);
        card.addView(updBar, ublp);
        updStatusTv = txt("", 12, Ui.TEXT_DIM, false);
        updStatusTv.setVisibility(View.GONE);
        card.addView(updStatusTv, wrapMargin(6));
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        updLaterBtn = btn("以后再说", 0, false);
        updLaterBtn.setOnClickListener(v -> {
            updatePromptSuppressed = true; // 本会话自动检查不再弹（手动检查仍可再查）
            updateOverlay.setVisibility(View.GONE);
            Diag.op("UPDATE", "用户选择「以后再说」，本会话不再自动弹窗（手动检查更新仍可再查）");
        });
        btnRow.addView(updLaterBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updGoBtn = btn("立即更新", R.drawable.ic_download, true);
        updGoBtn.setOnClickListener(v -> {
            if (pendingApk != null) tryInstallUpdate();
            else startUpdateDownload();
        });
        LinearLayout.LayoutParams uglp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        uglp.setMargins(dp(8), 0, 0, 0);
        btnRow.addView(updGoBtn, uglp);
        LinearLayout.LayoutParams brlp = matchWrap(); brlp.setMargins(0, dp(12), 0, 0);
        card.addView(btnRow, brlp);
        card.setOnClickListener(v -> { /* 拦截冒泡，点卡片不关 */ });
        updateOverlay.addView(card, clp);
        updateOverlay.setOnClickListener(v -> { if (!updateDownloading) updateOverlay.setVisibility(View.GONE); });
        parent.addView(updateOverlay);
    }

    /**
     * 检查更新（manual=手动入口，auto=启动自动查）。
     * 静默纪律：自动检查失败/无新版一律不打扰（只记 [UPDATE] 诊断）；
     * 手动检查才给反馈——失败一句「检查失败，请确认手机网络可用」，无新版报当前版本号。
     */
    private void checkUpdate(final boolean manual) {
        if (updateChecking) {
            if (manual) Toast.makeText(this, "正在检查中…", Toast.LENGTH_SHORT).show();
            return;
        }
        updateChecking = true;
        Diag.op("UPDATE", (manual ? "手动" : "启动自动") + "检查更新：当前 v" + Updater.currentVersion(this)
                + "（源：GitHub Releases latest；走非相机绑定的互联网网络）");
        Updater.checkAsync(this, (newer, error) -> runOnUiThread(() -> {
            updateChecking = false;
            if (newer != null) {
                if (!manual && updatePromptSuppressed) {
                    Diag.op("UPDATE", "自动检查发现新版 " + newer.tag + "，但本会话已选「以后再说」，不弹窗");
                    return;
                }
                showUpdateCard(newer);
            } else if (manual) {
                Toast.makeText(this, error != null
                        ? "检查失败，请确认手机网络可用"
                        : "已是最新版（v" + Updater.currentVersion(this) + "）", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void showUpdateCard(Updater.ReleaseInfo info) {
        pendingUpdate = info;
        pendingApk = null;
        installLaunched = false;
        updTitleTv.setText("发现新版本 " + info.tag + "（当前 v" + Updater.currentVersion(this) + "）");
        updNotesTv.setText(info.notesExcerpt != null && !info.notesExcerpt.isEmpty()
                ? info.notesExcerpt : "（该版本未附更新说明）");
        updBar.setVisibility(View.GONE);
        updBar.setProgress(0);
        updStatusTv.setVisibility(View.GONE);
        updGoBtn.setText("立即更新");
        updGoBtn.setEnabled(true);
        updLaterBtn.setEnabled(true);
        updateOverlay.setVisibility(View.VISIBLE);
        Diag.op("UPDATE", "已弹出更新卡：" + info.tag + "，资产 " + info.apkName + "（"
                + PhotoItem.fmtSize(info.apkSize) + "，下载到 cacheDir 后校验大小再安装）");
    }

    /** 卡内下载：进度条实时更新；完成即校验大小（Updater 内做）并尝试拉起安装；失败给中文重试 */
    private void startUpdateDownload() {
        if (pendingUpdate == null || updateDownloading) return;
        updateDownloading = true;
        updGoBtn.setEnabled(false);
        updLaterBtn.setEnabled(false);
        updBar.setVisibility(View.VISIBLE);
        updBar.setProgress(0);
        updStatusTv.setVisibility(View.VISIBLE);
        updStatusTv.setText("准备下载…");
        Updater.downloadAsync(this, pendingUpdate, new Updater.DownloadCallback() {
            @Override public void onProgress(final long done, final long total) {
                runOnUiThread(() -> {
                    if (total > 0) updBar.setProgress((int) (done * 100 / total));
                    updStatusTv.setText("下载中 " + PhotoItem.fmtSize(done)
                            + (total > 0 ? " / " + PhotoItem.fmtSize(total) : ""));
                });
            }
            @Override public void onDone(final File apk) {
                runOnUiThread(() -> {
                    updateDownloading = false;
                    pendingApk = apk;
                    updBar.setProgress(100);
                    updStatusTv.setText("下载完成，大小校验通过（" + PhotoItem.fmtSize(apk.length())
                            + "）。点「立即安装」拉起系统安装器。");
                    updGoBtn.setText("立即安装");
                    updGoBtn.setEnabled(true);
                    updLaterBtn.setEnabled(true);
                    tryInstallUpdate();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    updateDownloading = false;
                    updStatusTv.setText("下载失败，请确认手机网络可用后点「重试」");
                    updGoBtn.setText("重试");
                    updGoBtn.setEnabled(true);
                    updLaterBtn.setEnabled(true);
                });
            }
        });
    }

    /**
     * 拉起系统安装器：APK 经 UpdateFileProvider 的 content:// Uri + 临时读授权递出。
     * 未授予「安装未知应用」时先跳系统设置页，回来点「立即安装」（或 onResume 自动续）再装。
     * 装完版本号即新版，检查口径天然不再提示。
     */
    private void tryInstallUpdate() {
        if (pendingApk == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            updStatusTv.setVisibility(View.VISIBLE);
            updStatusTv.setText("需要先允许本应用「安装未知应用」：已为你打开设置页，允许后回来点「立即安装」");
            Diag.op("UPDATE", "安装暂停：未授予「安装未知应用」权限，跳转系统设置页");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Diag.op("UPDATE", "打开安装权限设置失败：" + e.getMessage());
                updStatusTv.setText("打开系统设置失败，请到系统设置→应用→清木图传里手动允许「安装未知应用」后再点立即安装");
            }
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(UpdateFileProvider.uriFor(pendingApk),
                    "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            installLaunched = true;
            updStatusTv.setVisibility(View.VISIBLE);
            updStatusTv.setText("已打开系统安装器，按系统提示完成安装即可（装完即为新版，不会再提示本次更新）");
            Diag.op("UPDATE", "已拉起系统安装器：" + pendingApk.getName());
        } catch (Exception e) {
            updStatusTv.setVisibility(View.VISIBLE);
            updStatusTv.setText("拉起安装器失败，请稍后点「立即安装」重试（详情见诊断 [UPDATE]）");
            Diag.op("UPDATE", "拉起系统安装器失败：" + e.getMessage());
        }
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
            // v0.6.1 审计（AP/STA 路径差异）：STA（network==null）必须走局域网默认路由——
            // 若进程还绑在上一次 AP 会话的相机热点 Network 上（bindProcessToNetwork），默认 Socket 会被
            // 强行走那条已失效的热点、连不上局域网相机。这里在 STA 连接前显式解绑并注销旧热点回调；
            // AP 路径绝不能调 wifi.disconnect()（会拆掉刚建好的热点连接）。
            if (network == null) {
                st.wifi.disconnect();
                Diag.op("PTPIP", "连接模式=STA（连接至 PC）：已清除进程网络绑定与旧热点回调，Socket 走默认局域网路由，目标 "
                        + host + ":" + port);
            } else {
                Diag.op("PTPIP", "连接模式=AP（相机热点）：Socket 经相机热点 Network 创建，目标 " + host + ":" + port);
            }
            if (st.client != null) { try { st.client.disconnect(); } catch (Exception ignore) {} }
            PtpIpClient client = new PtpIpClient(initiatorGuid(), "QingmuTuchuan");
            st.client = client;
            st.network = network;
            client.setConnectionListener(reason -> {
                // v0.6.2：断线回调在 PTP 线程上执行——全程兜底；连接层出问题只许回连接页 + 中文提示，
                // 禁止任何 finish 式退出（v0.6.1 起全仓无连接触发的 finish，此回调是唯一断线入口，一并加固）
                try {
                    setCapsule(false, "连接已断开");
                    setStatus("连接断开：" + reason + "（下载中会自动重连）", Ui.ERR);
                    teardownRemote();
                    runOnUiThread(() -> {
                        if (currentPage == 2) showPage(0); // 全屏取景中掉线：退回连接页，不把人留在死页面
                        Toast.makeText(MainActivity.this, "连接已断开：" + reason, Toast.LENGTH_LONG).show();
                    });
                } catch (Throwable t) {
                    Diag.op("GUARD", "断线回调处理异常已拦截（App 未退出）：" + t);
                }
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
                remote.setAutoSinglePoint(prefs.getBoolean("af_auto_single", true)); // v0.5.1：面板复选框的持久化值
                remote.setStorageId(st.storageId);
                remote.setSupportedPropCodes(di.devicePropertiesSupported); // v0.5：相机自报属性全集 → 参数面板全量枚举
                remote.queryLvSize();
                remote.loadProps(st.storageId);
                applyAutoSettings(); // 把自动回传设置（开关/范围）同步给新装配的中枢 + 刷新横幅
                runOnUiThread(() -> {
                    rcStatusTv.setText("已连接 · " + model + "（进遥控页自动开启取景）");
                    if (rcHintCardRef != null) rcHintCardRef.setVisibility(View.GONE);
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
                // v0.6.2 渐进式列表（Z 30 实测「读取照片用时 7 分钟」对策）：
                // 枚举拿到句柄后立即以骨架条目发布、页面先可操作；ObjectInfo 在后台逐项回填（节流刷新上墙）；
                // 单项只许 1 次短超时重试（回填期命令通道读超时收紧到 10s，卡住不再 30s 空转），失败即占位
                // （复用 v0.6.1 占位机制）；三段耗时（枚举/回填含最慢单项/缩略图）全程打日志供下轮定性。
                // 缩略图不在本阶段预取：网格 getView 按可视区懒加载，且骨架条目不发请求、不与回填抢通道。
                long enumT0 = System.currentTimeMillis();
                int[] handles = null;
                PtpIpClient.PtpException specErr = null;
                try {
                    handles = st.client.getObjectHandles(st.storageId, 0, 0);
                } catch (PtpIpClient.PtpException e) {
                    specErr = e;
                }
                if (handles == null || handles.length == 0) {
                    // 仅在第一次枚举失败/回 0 时才走全通配二次枚举；已返回句柄时禁止（防慢失败翻倍）
                    Diag.op("PTP", "指定存储枚举" + (handles == null
                            ? "失败 rc=0x" + Integer.toHexString(specErr.responseCode)
                            : "返回 0 个对象") + "，降级以全通配（storage=0xFFFFFFFF）重试一次");
                    try {
                        int[] wild = st.client.getObjectHandles(0xFFFFFFFF, 0, 0);
                        if (handles == null || wild.length > 0) handles = wild;
                    } catch (PtpIpClient.PtpException e2) {
                        Diag.op("PTP", "通配枚举同样失败 rc=0x" + Integer.toHexString(e2.responseCode));
                        if (handles == null) {
                            photoLoadNote = "读取照片列表失败（码 0x" + Integer.toHexString(e2.responseCode)
                                    + "）：相机没有返回照片清单，细节见诊断页；可断开重连后再试。";
                            st.photos.clear();
                            runOnUiThread(() -> {
                                rebuildDisplay(); updateSelInfo(); updateAutoUi();
                                Toast.makeText(this, "读取照片列表失败（码 0x"
                                        + Integer.toHexString(e2.responseCode) + "），见诊断页", Toast.LENGTH_LONG).show();
                            });
                            return;
                        }
                    }
                }
                final int totalHandles = handles.length;
                long enumMs = System.currentTimeMillis() - enumT0;
                Diag.op("PTP", "列表加载[1/3 枚举句柄]：共 " + totalHandles + " 个，耗时 " + enumMs + "ms");

                // ---- 骨架先行：句柄倒序（相机句柄通常旧→新，新照片先上墙），信息回填中页面可先操作 ----
                List<PhotoItem> list = new ArrayList<>();
                for (int i = totalHandles - 1; i >= 0; i--) {
                    PtpIpClient.ObjectInfo ph = new PtpIpClient.ObjectInfo();
                    ph.filename = "读取中…";
                    PhotoItem item = new PhotoItem(handles[i], ph);
                    item.infoPending = true;
                    list.add(item);
                }
                st.photos.clear();
                st.photos.addAll(list);
                photoLoadNote = null;
                runOnUiThread(() -> {
                    rebuildDisplay(); updateSelInfo(); updateAutoUi();
                    selInfoTv.setText("已列出 " + totalHandles + " 张，照片信息读取中…");
                });

                // ---- 后台回填 ----
                long fillT0 = System.currentTimeMillis();
                int infoOk = 0, infoFail = 0, folderSkip = 0;
                int firstFailRc = -1;
                String firstFailMsg = null;
                long maxItemMs = 0; int maxItemHandle = 0;
                boolean reconnectTried = false;
                List<PhotoItem> folders = new ArrayList<>();
                st.client.setCmdSoTimeout(10000); // 回填期短超时（无字节停顿口径）
                try {
                    long lastUiMs = 0;
                    for (PhotoItem item : list) {
                        long itemT0 = System.currentTimeMillis();
                        PtpIpClient.ObjectInfo oi = null;
                        Exception fail = null;
                        try {
                            oi = st.client.getObjectInfo(item.handle);
                        } catch (Exception e1) {
                            // 单项只许 1 次重试：IO 型（通道已判死）先重连一次再重试；相机层错误直接重试一次
                            boolean ioDead = e1 instanceof PtpIpClient.PtpException
                                    && ((PtpIpClient.PtpException) e1).responseCode == -1;
                            if (ioDead && !reconnectTried) {
                                reconnectTried = true;
                                Diag.op("PTP", "回填中通道断裂，尝试重连一次后重试该项 handle=0x"
                                        + Integer.toHexString(item.handle));
                                if (!st.client.isConnected()) st.client.reconnect(st.network);
                            }
                            if (!ioDead || st.client.isConnected()) {
                                try { oi = st.client.getObjectInfo(item.handle); }
                                catch (Exception e2) { fail = e2; }
                            } else {
                                fail = e1;
                            }
                        }
                        long itemMs = System.currentTimeMillis() - itemT0;
                        if (itemMs > maxItemMs) { maxItemMs = itemMs; maxItemHandle = item.handle; }
                        if (oi != null) {
                            if (oi.format == 0x3001) { folderSkip++; folders.add(item); }
                            else { item.info = oi; infoOk++; }
                        } else {
                            int rc = fail instanceof PtpIpClient.PtpException
                                    ? ((PtpIpClient.PtpException) fail).responseCode : -1;
                            infoFail++;
                            if (firstFailMsg == null) {
                                firstFailRc = rc;
                                firstFailMsg = fail != null ? fail.getMessage() : "?";
                            }
                            Diag.op("PTP", "GetObjectInfo 失败 handle=0x" + Integer.toHexString(item.handle)
                                    + " rc=0x" + Integer.toHexString(rc) + "："
                                    + (fail != null ? fail.getMessage() : "?") + "（占位保留，不丢弃整表）");
                            PtpIpClient.ObjectInfo ph = new PtpIpClient.ObjectInfo();
                            ph.filename = "未知文件（0x" + Integer.toHexString(item.handle) + "）";
                            item.info = ph;
                            item.infoFailed = true;
                            item.infoFailRc = rc;
                        }
                        item.infoPending = false;
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastUiMs > 600) { // 节流刷新，已回填条目陆续上墙
                            lastUiMs = nowMs;
                            runOnUiThread(() -> { rebuildDisplay(); updateSelInfo(); });
                        }
                    }
                    list.removeAll(folders);
                } finally {
                    st.client.setCmdSoTimeout(30000);
                }
                long fillMs = System.currentTimeMillis() - fillT0;
                Diag.op("PTP", "列表加载[2/3 信息回填]：耗时 " + fillMs + "ms，成功 " + infoOk
                        + "，失败 " + infoFail + "（占位保留）"
                        + (folderSkip > 0 ? "，文件夹跳过 " + folderSkip : "")
                        + "，最慢单项 " + maxItemMs + "ms（handle=0x" + Integer.toHexString(maxItemHandle) + "）"
                        + (firstFailMsg != null ? "，首个失败 rc=0x" + Integer.toHexString(firstFailRc) + "：" + firstFailMsg : ""));
                Diag.op("PTP", "列表加载[3/3 缩略图]：加载阶段不预取，网格按可视区懒加载");
                Collections.sort(list, (a, b) -> b.sortKey().compareTo(a.sortKey())); // 新→旧
                int dlCount = 0;
                for (PhotoItem p : list) {
                    p.downloaded = DownloadRecord.isDownloaded(p.filename());
                    if (p.downloaded) dlCount++;
                }
                st.photos.clear();
                st.photos.addAll(list);
                // v0.6.1：为空必须给出具体原因（空状态卡显示），禁止静默空白
                if (list.isEmpty()) {
                    photoLoadNote = totalHandles == 0
                            ? "相机返回 0 个对象：卡上没有照片，或当前连接方式下相机未开放照片访问（可换另一种连接方式再试）；细节见诊断页。"
                            : "相机返回 " + totalHandles + " 个对象，但全部是文件夹、没有照片文件；细节见诊断页。";
                } else if (infoFail > 0 && infoOk == 0) {
                    photoLoadNote = null; // 占位条目已进列表，数量与原因在条目上可见，不再占空状态
                    Diag.op("PTP", "照片信息全部读取失败，已以占位条目保留 " + list.size() + " 条（首个失败码 0x"
                            + Integer.toHexString(firstFailRc) + "）");
                } else {
                    photoLoadNote = null;
                }
                Diag.op("PTP", "照片列表就绪：" + list.size() + " 张（其中已下载 " + dlCount + " 张，据已下载记录 "
                        + DownloadRecord.count() + " 条比对），枚举+回填总耗时 " + (enumMs + fillMs) + "ms");
                runOnUiThread(() -> { rebuildDisplay(); updateSelInfo(); updateAutoUi(); });
            } catch (Exception e) {
                Diag.op("PTP", "读取照片失败：" + e.getMessage());
                photoLoadNote = "读取照片失败：" + e.getMessage() + "（细节见诊断页）";
                runOnUiThread(() -> {
                    rebuildDisplay(); updateSelInfo();
                    Toast.makeText(this, "读取失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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
        int skippedFailed = 0, skippedPending = 0;
        for (PhotoItem p : selectedFiles()) {
            if (p.infoPending) { skippedPending++; continue; } // v0.6.2：骨架条目（双保险，正常已被勾选守卫挡住）
            if (p.infoFailed) { skippedFailed++; continue; } // v0.6.1：占位条目不入队，给中文提示
            jobs.add(new DownloadService.Job(p.handle, p.filename(), p.size()));
        }
        if (skippedPending > 0) {
            Toast.makeText(this, "有 " + skippedPending + " 张照片信息还在读取中，请稍候再下载", Toast.LENGTH_LONG).show();
        }
        if (skippedFailed > 0) {
            Toast.makeText(this, "有 " + skippedFailed + " 张照片信息读取失败，已跳过（细节见诊断页）", Toast.LENGTH_LONG).show();
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
            // v0.6.1：信息读取失败的占位条目不发缩略图请求（句柄信息不全），角标直接标原因
            // v0.6.2：骨架条目（回填中）同样不发请求——缩略图严格按可视区懒加载且不与回填抢命令通道，
            //         回填完成后整表刷新，可视区条目届时再逐格加载
            boolean thumbBlocked = thumbItem.infoFailed || thumbItem.infoPending;
            Bitmap bmp = thumbBlocked ? null : thumbCache.getMem(thumbItem.handle);
            thumbIv.setTag(thumbItem.handle);
            if (thumbItem.infoFailed) {
                chipTv.setText("信息读取失败");
                chipTv.setTextColor(Ui.ERR);
            } else if (thumbItem.infoPending) {
                chipTv.setText("读取中");
                chipTv.setTextColor(Ui.TEXT_DIM);
            }
            if (bmp != null) {
                thumbIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbIv.setImageBitmap(bmp);
            } else {
                thumbIv.setScaleType(ImageView.ScaleType.CENTER);
                thumbIv.setImageDrawable(icon(badgeResOf(thumbItem.kind()), Ui.TEXT_DIM));
                if (!thumbBlocked) {
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
