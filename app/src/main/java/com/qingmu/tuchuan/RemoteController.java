package com.qingmu.tuchuan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.2 遥控中枢：把 PtpIpClient 的尼康私有操作编排成 UI 可用的功能，
 * 并集中处理三件事：
 *  1) 机型 profile 门控（CameraProfile.RemoteLevel + 诊断页实验开关）；
 *  2) 功能级降级：某个遥控操作连续失败只禁用该功能并记诊断，绝不拖垮图传主链路；
 *  3) LiveView 三级流水线（拉帧→解码→均匀呈现）与事件驱动的「拍完即览」。
 *
 * 帧率方针（用户定死）：稳定 > 帧率 > 分辨率，工程目标持续稳定 30fps。
 * 拉帧线程只管以最快速度连续 GetLiveViewImage 塞进单槽缓冲（覆盖未消费的旧帧）；
 * 解码线程独立解码最新帧；呈现线程按锁定帧率档的固定间隔均匀出帧（pacing），
 * 宁可跳帧不积压。持续测呈现帧间隔，守不住当前档就先降帧尺寸档（0xD1AC）、
 * 再降帧率档（30→24→20→15）把间隔锁均匀，会话内只降不升防震荡。
 *
 * 线程模型：所有相机命令走本类单线程 exec（与图传的 bg 分开排队，client 内部另有串行锁兜底）；
 * LiveView 三个流水线线程独立于 exec；PtpIpClient 的事件回调只把事件转投到 exec，不在事件线程发命令。
 */
public class RemoteController {

    public static final String F_PROPS_READ = "propsRead";
    public static final String F_PROPS_WRITE = "propsWrite";
    public static final String F_LIVEVIEW = "liveview";
    public static final String F_FOCUS = "focus";
    public static final String F_CAPTURE = "capture";
    public static final String F_MOVIE = "movie";

    public interface Ui {
        void onProps(List<PropItem> items, String remainText);
        void onPropUpdated(PropItem item, boolean ok, String msg);
        void onFrame(Bitmap bmp, float fps);
        void onHistogram(float[][] bins); // [3][64]，0..1 归一化
        void onStatus(String msg);
        void onAfResult(boolean ok, String msg);
        void onNewPhoto(int handle, Bitmap thumb, String filename);
        void onMovie(boolean recording);
        void onLiveView(boolean on, String msg);
        void onFeatureStateChanged();
        /** 取景档位文案（约每秒推一次）：尺寸档名 + 目标帧率档 + 实测呈现 fps；note 非空=刚发生自动降档的原因（小字提示，不弹框），null=不更新提示行 */
        void onLvState(String sizeLabel, int targetFps, float displayFps, String note);
        /** 帧尺寸档可用性：options 为相机自报的枚举档名（升序），currentIdx 为当前档下标；supported=false 表示相机未提供 0xD1AC，尺寸切换不可用 */
        void onLvSizes(List<String> options, int currentIdx, boolean supported);
    }

    public static class PropItem {
        public CamProps.Def def;
        public PtpIpClient.DevicePropDesc desc; // null = 相机未提供该属性
        public boolean available() { return desc != null; }
        public boolean canWrite() {
            return desc != null && desc.writable()
                    && (desc.formFlag == 2 || desc.formFlag == 1);
        }
        public String currentText() {
            return desc == null ? "—" : CamProps.format(def.code, desc.curVal);
        }
    }

    private final PtpIpClient client;
    private final CameraProfile profile;
    private volatile boolean experimental;
    private final Ui ui;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Set<String> deadFeatures = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Integer> failCounts = new ConcurrentHashMap<>();

    private volatile boolean lvOn = false;
    private Thread pullThread, decodeThread, paceThread;
    private volatile boolean histEnabled = true;

    // ---- LiveView 流水线状态 ----
    /** 拉帧产物：原始数据 + JPEG 切片 + 序号 + 往返耗时（单槽覆盖，旧帧未被解码即丢弃） */
    private static class RawFrame {
        byte[] data; int jpegOff, jpegLen; long seq, roundTripMs;
    }
    private static class DecodedFrame {
        Bitmap bmp; long seq; long decodeMs;
    }
    private final java.util.concurrent.atomic.AtomicReference<RawFrame> rawSlot =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<DecodedFrame> dispSlot =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile long frameSeq = 0;
    private volatile long droppedRaw = 0, droppedDisp = 0;

    // 帧尺寸档（0xD1AC 枚举，升序）；lvSizeSupported=false 表示相机未提供该属性
    private volatile boolean lvSizeSupported = false;
    private volatile List<Integer> lvSizeValues = new ArrayList<>();
    private volatile int lvSizeIdx = -1; // -1 = 未知/相机默认
    // 帧率档：只降不升，会话内防震荡
    private static final int[] FPS_TIERS = {30, 24, 20, 15};
    private volatile int fpsTierIdx = 0;
    private volatile String lvNote = null; // 最近一次自动降档原因（UI 小字提示）
    // 流水线窗口统计（各自线程写、呈现线程 5 秒汇总时读后清零；非原子读清，统计用途允许个位数误差）
    private volatile int pullWinCount = 0, decodeWinCount = 0;
    private volatile long pullWinSumRt = 0, decodeWinSum = 0;
    private volatile long lastConsumedSeq = 0, lastShownSeq = 0;
    private volatile int lvSizeDatatype = PtpIpClient.DTC_UINT8;
    private volatile float lastDisplayFps = 0;
    private volatile int lastFrameW = 0, lastFrameH = 0;
    private volatile boolean recording = false;
    private long lastPropRefreshMs = 0;
    private volatile int storageId = 0;

    // ---- v0.3 拍完自动回传（事件驱动） ----
    /**
     * 自动回传候选回调（在 exec 线程调用，只许做入队这类非阻塞动作）：
     * 返回 true=已接管（已入队下载或已确认下载过）；false=本次跳过。
     */
    public interface AutoTransferHandler { boolean onAutoCandidate(int handle, PtpIpClient.ObjectInfo oi); }
    private volatile boolean autoTransferEnabled = false;
    private volatile boolean autoIncludeRaw = false;
    private volatile AutoTransferHandler autoHandler;
    private final Set<Integer> autoSeen = ConcurrentHashMap.newKeySet();

    /** 开/关自动回传（includeRaw=false 只传 JPG；true 连 NEF 一起传；MP4 两种模式都不自动传） */
    public void setAutoTransfer(boolean enabled, boolean includeRaw, AutoTransferHandler h) {
        autoTransferEnabled = enabled;
        autoIncludeRaw = includeRaw;
        autoHandler = h;
        Diag.op("REMOTE", "自动回传 → " + (enabled ? "开" : "关")
                + (enabled ? "（范围：" + (includeRaw ? "JPG+RAW" : "仅 JPG") + "，监听 ObjectAdded）" : ""));
    }

    public boolean isAutoTransferOn() { return autoTransferEnabled; }

    public void setStorageId(int storageId) { this.storageId = storageId; }

    public RemoteController(PtpIpClient client, CameraProfile profile, boolean experimental, Ui ui) {
        this.client = client;
        this.profile = profile;
        this.experimental = experimental;
        this.ui = ui;
        client.setEventListener((code, params) -> onPtpEvent(code, params));
        Diag.op("REMOTE", "遥控中枢就绪 profile=" + (profile != null ? profile.id : "?")
                + " 等级=" + (profile != null ? profile.remote : "?") + " 实验开关=" + experimental);
    }

    public void setExperimental(boolean on) {
        experimental = on;
        Diag.op("REMOTE", "实验开关 → " + on + "（非 Z 系机型遥控功能" + (on ? "放行尝试" : "恢复锁定") + "）");
        ui.onFeatureStateChanged();
    }

    // ---------- 门控与降级 ----------

    /** 功能当前是否可用（未被 profile 锁定、未被失败降级） */
    public boolean featureAllowed(String f) {
        if (deadFeatures.contains(f)) return false;
        if (profile != null && profile.remote == CameraProfile.RemoteLevel.FULL) return true;
        if (experimental) return true;
        // BASIC 默认只放行：状态面板（只读）+ 遥控快门
        return F_PROPS_READ.equals(f) || F_CAPTURE.equals(f);
    }

    /** 是被 profile 锁定的「未验证」功能（区别于失败降级），UI 据此显示不同文案 */
    public boolean featureLockedByProfile(String f) {
        if (deadFeatures.contains(f)) return false;
        if (profile != null && profile.remote == CameraProfile.RemoteLevel.FULL) return false;
        if (experimental) return false;
        return !(F_PROPS_READ.equals(f) || F_CAPTURE.equals(f));
    }

    public boolean isDead(String f) { return deadFeatures.contains(f); }

    private void noteFailure(String f, String why) {
        int n = failCounts.merge(f, 1, Integer::sum);
        Diag.op("REMOTE", "功能[" + f + "] 失败 " + n + "/3：" + why);
        if (n >= 3 && deadFeatures.add(f)) {
            Diag.op("REMOTE", "功能[" + f + "] 连续失败 3 次，本次会话内降级禁用（不影响图传）");
            ui.onFeatureStateChanged();
            ui.onStatus("功能已降级：" + f + "（" + why + "）");
        }
    }

    private void noteSuccess(String f) { failCounts.remove(f); }

    // ---------- 状态面板 / 参数 ----------

    public void loadProps(final int storageId) {
        if (!featureAllowed(F_PROPS_READ)) { ui.onStatus("状态面板被机型 profile 锁定"); return; }
        exec.execute(() -> {
            List<PropItem> items = new ArrayList<>();
            for (CamProps.Def def : CamProps.DEFS) {
                PropItem it = new PropItem();
                it.def = def;
                try {
                    it.desc = client.getDevicePropDesc(def.code);
                } catch (Exception e) {
                    it.desc = null;
                    Diag.op("REMOTE", "属性 " + def.name + "(0x" + Integer.toHexString(def.code) + ") 读取失败，面板置灰：" + e.getMessage());
                }
                items.add(it);
            }
            // 剩余可拍张数：优先存储信息里的相机估算值（GetStorageInfo 的 NumberOfImages 字段）。
            // 注：0x5013 经 libgphoto2 ptp.h 核实是连拍模式（StillCaptureMode），不是剩余张数，已在面板中按「拍摄驱动」展示。
            String remain = "未知";
            try {
                PtpIpClient.StorageInfo si = client.getStorageInfo(storageId);
                if (si.freeImages > 0) remain = "约 " + si.freeImages + " 张（来源：存储信息，相机估算）";
                else remain = "相机未报告（存储信息为 0）";
            } catch (Exception e) {
                Diag.op("REMOTE", "剩余张数读取失败：" + e.getMessage());
            }
            lastPropRefreshMs = System.currentTimeMillis();
            ui.onProps(items, remain);
        });
    }

    public void setProp(final PropItem item, final PtpIpClient.PropValue chosen) {
        if (!featureAllowed(F_PROPS_WRITE)) { ui.onStatus("参数调节未解锁（机型未验证）"); return; }
        exec.execute(() -> {
            int rc = client.setDevicePropValue(item.def.code, chosen);
            if (rc != PtpIpClient.RC_OK) {
                ui.onPropUpdated(item, false, "相机拒绝写入，返回码 0x" + Integer.toHexString(rc));
                return;
            }
            // 回读确认：以相机实际值为准刷新面板
            try {
                item.desc = client.getDevicePropDesc(item.def.code);
                boolean match = item.desc.curVal != null
                        && (item.desc.curVal.datatype == PtpIpClient.DTC_STR
                            ? String.valueOf(chosen.str).equals(String.valueOf(item.desc.curVal.str))
                            : item.desc.curVal.num == chosen.num);
                Diag.op("REMOTE", "属性 " + item.def.name + " 写入 " + chosen + " 回读 " + item.desc.curVal
                        + (match ? "（一致）" : "（不一致，以相机回读为准——可能被机身模式钳制）"));
                ui.onPropUpdated(item, true, match ? "已生效" : "已写入，相机回读为 " + item.currentText());
            } catch (Exception e) {
                ui.onPropUpdated(item, true, "已写入（回读失败：" + e.getMessage() + "）");
            }
        });
    }

    // ---------- LiveView（三级流水线：拉帧 → 解码 → 均匀呈现） ----------

    public boolean isLiveViewOn() { return lvOn; }
    public void setHistogramEnabled(boolean on) { histEnabled = on; }
    public int targetFps() { return FPS_TIERS[fpsTierIdx]; }

    /** 0xD1AC 枚举值→档名（libgphoto2 config.c nikon_liveviewsize 表：1=QVGA 2=VGA 3=XGA） */
    private static String lvSizeName(int v) {
        switch (v) {
            case 1: return "QVGA";
            case 2: return "VGA";
            case 3: return "XGA";
            default: return "档" + v;
        }
    }

    public String lvSizeLabel() {
        if (!lvSizeSupported || lvSizeIdx < 0 || lvSizeIdx >= lvSizeValues.size()) return "相机默认尺寸";
        int v = lvSizeValues.get(lvSizeIdx);
        String n = lvSizeName(v);
        if (lvSizeValues.size() > 1 && lvSizeIdx == 0) return "流畅档（" + n + "）";
        if (lvSizeValues.size() > 1 && lvSizeIdx == lvSizeValues.size() - 1) return "清晰档（" + n + "）";
        return n + " 档";
    }

    private void pushLvSizesUi() {
        List<String> labels = new ArrayList<>();
        for (int v : lvSizeValues) labels.add(lvSizeName(v));
        ui.onLvSizes(labels, lvSizeIdx, lvSizeSupported);
    }

    /**
     * 查相机是否提供 LiveView 帧尺寸属性 0xD1AC（连接装配时调用一次）。
     * 取值集合严格以相机 desc 自报枚举为准；默认选最小档（流畅优先，先锁帧率与稳定）。
     * 相机不提供该属性时尺寸不可调，自适应只走降帧率档路线，诊断注明。
     */
    public void queryLvSize() {
        exec.execute(() -> {
            try {
                PtpIpClient.DevicePropDesc d = client.getDevicePropDesc(PtpIpClient.PROP_NIKON_LIVE_VIEW_IMAGE_SIZE);
                if (d.enumValues.isEmpty() || d.curVal == null) throw new Exception("desc 无枚举");
                lvSizeDatatype = d.datatype;
                List<Integer> vals = new ArrayList<>();
                for (PtpIpClient.PropValue pv : d.enumValues) vals.add((int) pv.num);
                java.util.Collections.sort(vals);
                lvSizeValues = vals;
                lvSizeSupported = true;
                int cur = (int) d.curVal.num;
                lvSizeIdx = vals.indexOf(cur);
                if (lvSizeIdx < 0) lvSizeIdx = 0;
                if (d.writable() && lvSizeIdx != 0) {
                    int rc = client.setDevicePropValue(PtpIpClient.PROP_NIKON_LIVE_VIEW_IMAGE_SIZE,
                            new PtpIpClient.PropValue(d.datatype, vals.get(0)));
                    Diag.op("LIVEVIEW", "默认流畅档：帧尺寸 " + lvSizeName(cur) + "（值 " + cur + "）→ "
                            + lvSizeName(vals.get(0)) + "（值 " + vals.get(0) + "）rc=0x" + Integer.toHexString(rc));
                    if (rc == PtpIpClient.RC_OK) lvSizeIdx = 0;
                }
                Diag.op("LIVEVIEW", "帧尺寸档（0xD1AC）：相机自报 " + vals + "，当前=" + lvSizeLabel());
            } catch (Exception e) {
                lvSizeSupported = false;
                lvSizeValues = new ArrayList<>();
                lvSizeIdx = -1;
                Diag.op("LIVEVIEW", "相机未提供帧尺寸属性 0xD1AC（" + e.getMessage()
                        + "），尺寸固定为相机默认，自适应改为只降帧率档（30→24→20→15）");
            }
            pushLvSizesUi();
        });
    }

    /** 用户手动切档：smooth=true 取最小档（流畅），false 取最大档（清晰）；换尺寸后帧率档重置回 30 重新尝试锁定 */
    public void setLvSmooth(final boolean smooth) {
        if (!lvSizeSupported || lvSizeValues.isEmpty()) { ui.onStatus("相机未提供帧尺寸切换（0xD1AC）"); return; }
        final int idx = smooth ? 0 : lvSizeValues.size() - 1;
        exec.execute(() -> {
            int v = lvSizeValues.get(idx);
            int rc = client.setDevicePropValue(PtpIpClient.PROP_NIKON_LIVE_VIEW_IMAGE_SIZE,
                    new PtpIpClient.PropValue(lvSizeDatatype, v));
            Diag.op("LIVEVIEW", "手动切档 → " + lvSizeName(v) + "（值 " + v + "）rc=0x" + Integer.toHexString(rc));
            if (rc == PtpIpClient.RC_OK) {
                lvSizeIdx = idx;
                fpsTierIdx = 0;
                lvNote = null;
                pushLvSizesUi();
            } else {
                ui.onStatus("切档失败，相机返回 0x" + Integer.toHexString(rc));
            }
        });
    }

    public void startLiveView() {
        if (lvOn) return;
        if (!featureAllowed(F_LIVEVIEW)) {
            ui.onStatus(featureLockedByProfile(F_LIVEVIEW)
                    ? "实时取景在当前机型未验证（诊断页可开实验开关尝试）" : "实时取景已降级禁用");
            return;
        }
        exec.execute(() -> {
            int rc = client.nikonStartLiveView();
            if (rc != PtpIpClient.RC_OK && rc != PtpIpClient.RC_DEVICE_BUSY) {
                noteFailure(F_LIVEVIEW, "StartLiveView rc=0x" + Integer.toHexString(rc));
                ui.onLiveView(false, "开启取景失败 rc=0x" + Integer.toHexString(rc));
                return;
            }
            noteSuccess(F_LIVEVIEW);
            fpsTierIdx = 0;
            lvNote = null;
            rawSlot.set(null); dispSlot.set(null);
            frameSeq = 0; droppedRaw = 0; droppedDisp = 0;
            lastConsumedSeq = 0; lastShownSeq = 0;
            pullWinCount = 0; pullWinSumRt = 0; decodeWinCount = 0; decodeWinSum = 0;
            lastDisplayFps = FPS_TIERS[0];
            lvOn = true;
            ui.onLiveView(true, "取景已开启（目标：稳定 " + targetFps() + "fps）");
            Diag.op("LIVEVIEW", "流水线启动：拉帧/解码/呈现三线程，尺寸档=" + lvSizeLabel()
                    + "，帧率档=" + targetFps() + "fps（稳定>帧率>分辨率，守不住自动降档）");
            pullThread = new Thread(this::pullLoop, "qingmu-lv-pull");
            decodeThread = new Thread(this::decodeLoop, "qingmu-lv-decode");
            paceThread = new Thread(this::paceLoop, "qingmu-lv-pace");
            pullThread.setDaemon(true); decodeThread.setDaemon(true); paceThread.setDaemon(true);
            pullThread.start(); decodeThread.start(); paceThread.start();
        });
    }

    public void stopLiveView() {
        if (!lvOn) return;
        lvOn = false;
        exec.execute(() -> {
            joinQuiet(pullThread, 500); joinQuiet(decodeThread, 300); joinQuiet(paceThread, 300);
            int rc = client.nikonEndLiveView();
            ui.onLiveView(false, "取景已关闭（EndLiveView rc=0x" + Integer.toHexString(rc) + "）");
        });
    }

    private static void joinQuiet(Thread t, long ms) {
        if (t == null) return;
        try { t.join(ms); } catch (InterruptedException ignore) {}
    }

    /** 拉帧线程：不管解码/呈现进度，以最快速度连续拉帧塞单槽缓冲（PTP 请求-响应串行，瓶颈在相机出帧+传输） */
    private void pullLoop() {
        boolean headerLogged = false;
        int notLvStreak = 0, busyStreak = 0, noJpegStreak = 0;
        while (lvOn && client.isConnected()) {
            long t0 = System.currentTimeMillis();
            PtpIpClient.LiveViewFrame f;
            try {
                f = client.nikonGetLiveViewImage();
            } catch (PtpIpClient.PtpException e) {
                int rc = e.responseCode;
                if (rc == PtpIpClient.RC_NIKON_NOT_LIVE_VIEW) {
                    // libgphoto2 同策略：相机自行退出 LV（如 D7000 长跑后）时自动重启取景
                    notLvStreak++;
                    Diag.op("LIVEVIEW", "相机已退出 LiveView（0xA00B），自动重启取景 第 " + notLvStreak + " 次");
                    if (notLvStreak > 3) { noteFailure(F_LIVEVIEW, "反复 NotLiveView"); lvOn = false; break; }
                    int src = client.nikonStartLiveView();
                    if (src != PtpIpClient.RC_OK && src != PtpIpClient.RC_DEVICE_BUSY) {
                        noteFailure(F_LIVEVIEW, "重启取景 rc=0x" + Integer.toHexString(src)); lvOn = false; break;
                    }
                    continue;
                }
                if (rc == PtpIpClient.RC_DEVICE_BUSY || rc == PtpIpClient.RC_NIKON_INVALID_STATUS) {
                    busyStreak++;
                    if (busyStreak == 50) Diag.op("LIVEVIEW", "相机连续 50 次未就绪（Busy/InvalidStatus），继续等待");
                    sleepQuiet(30);
                    continue;
                }
                if (rc == -6) {
                    noJpegStreak++;
                    Diag.op("LIVEVIEW", "帧内无 JPEG（第 " + noJpegStreak + " 次）");
                    if (noJpegStreak > 5) { noteFailure(F_LIVEVIEW, "帧格式异常（无 JPEG）"); lvOn = false; break; }
                    continue;
                }
                Diag.op("LIVEVIEW", "拉帧失败 rc=0x" + Integer.toHexString(rc) + "：" + e.getMessage());
                noteFailure(F_LIVEVIEW, "拉帧 rc=0x" + Integer.toHexString(rc));
                lvOn = false;
                break;
            }
            long rt = System.currentTimeMillis() - t0;
            busyStreak = 0; noJpegStreak = 0; notLvStreak = 0;
            if (!headerLogged) {
                headerLogged = true;
                StringBuilder hex = new StringBuilder();
                int hn = Math.min(32, f.jpegOff);
                for (int i = 0; i < hn; i++) hex.append(String.format("%02x", f.data[i] & 0xFF));
                Diag.op("LIVEVIEW", "首帧：总长 " + f.data.length + "B，帧头 " + f.jpegOff + "B"
                        + (hn > 0 ? "，帧头前 " + hn + "B=" + hex : "")
                        + "（AF 框字段偏移待真机按此帧头解析，本版先只显示画面）");
            }
            RawFrame rf = new RawFrame();
            rf.data = f.data; rf.jpegOff = f.jpegOff; rf.jpegLen = f.jpegLen;
            rf.roundTripMs = rt;
            rf.seq = ++frameSeq;
            RawFrame prev = rawSlot.getAndSet(rf);
            if (prev != null && prev.seq > lastConsumedSeq) droppedRaw++; // 旧帧没等到解码就被覆盖
            pullWinCount++; pullWinSumRt += rt;
        }
        Diag.op("LIVEVIEW", "拉帧线程退出（共拉 " + frameSeq + " 帧，覆盖丢弃 " + droppedRaw + " 帧）");
        ui.onLiveView(false, "取景已停止");
    }

    /** 解码线程：只解最新帧，解完塞呈现槽（同样单槽覆盖）；直方图在此节流计算，不拖拉帧 */
    private void decodeLoop() {
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.RGB_565; // 监看够用，解码耗时与内存约减半
        long lastSeq = 0;
        int histTick = 0;
        while (lvOn) {
            RawFrame rf = rawSlot.get();
            if (rf == null || rf.seq == lastSeq) { sleepQuiet(2); continue; }
            lastSeq = rf.seq;
            lastConsumedSeq = rf.seq;
            long t0 = System.currentTimeMillis();
            Bitmap bmp = BitmapFactory.decodeByteArray(rf.data, rf.jpegOff, rf.jpegLen, opt);
            long dms = System.currentTimeMillis() - t0;
            if (bmp == null) { Diag.op("LIVEVIEW", "JPEG 解码失败 seq=" + rf.seq); continue; }
            lastFrameW = bmp.getWidth(); lastFrameH = bmp.getHeight();
            DecodedFrame df = new DecodedFrame();
            df.bmp = bmp; df.seq = rf.seq; df.decodeMs = dms;
            DecodedFrame prev = dispSlot.getAndSet(df);
            if (prev != null && prev.seq > lastShownSeq) droppedDisp++; // 解出但没呈现就被更新的帧覆盖
            decodeWinCount++; decodeWinSum += dms;
            if (histEnabled && ++histTick % 5 == 0) {
                float[][] bins = computeHistogram(bmp);
                if (bins != null) ui.onHistogram(bins);
            }
        }
        Diag.op("LIVEVIEW", "解码线程退出");
    }

    /**
     * 呈现线程（pacing）：按当前帧率档的固定间隔均匀出帧，到点只取最新已解码帧，
     * 不追赶、不积压——帧间隔均匀就是「稳」。同时跑自适应锁档评估与 5 秒诊断汇总。
     */
    private void paceLoop() {
        long lastShown = 0;
        long lastShownAtMs = 0;
        long winStart = System.currentTimeMillis();
        List<Float> winIntervals = new ArrayList<>();
        int shownWin = 0;
        long evalStart = System.currentTimeMillis();
        List<Float> evalIntervals = new ArrayList<>();
        int evalShown = 0, unstableStreak = 0;
        long lastUiPush = 0;
        long nextTick = System.currentTimeMillis();
        long dropRawSeen = 0, dropDispSeen = 0;
        Diag.op("LIVEVIEW", "呈现线程开始（pacing 间隔 " + (1000 / targetFps()) + "ms，目标稳定 " + targetFps() + "fps）");
        while (lvOn) {
            long interval = 1000 / targetFps();
            nextTick += interval;
            long wait = nextTick - System.currentTimeMillis();
            if (wait > 0) sleepQuiet(wait);
            else if (wait < -interval) nextTick = System.currentTimeMillis(); // 落后超一帧则重对齐，禁止追赶爆发
            DecodedFrame df = dispSlot.get();
            if (df == null || df.seq == lastShown) continue;
            long now = System.currentTimeMillis();
            if (lastShownAtMs > 0) {
                float iv = now - lastShownAtMs;
                winIntervals.add(iv); evalIntervals.add(iv);
            }
            lastShownAtMs = now;
            lastShown = df.seq;
            lastShownSeq = df.seq;
            shownWin++; evalShown++;
            ui.onFrame(df.bmp, lastDisplayFps);
            if (now - lastUiPush >= 1000) {
                lastUiPush = now;
                ui.onLvState(lvSizeLabel(), targetFps(), lastDisplayFps, lvNote);
                lvNote = null;
            }
            // —— 自适应锁档：每约 2 秒评估呈现稳定性（帧率均值 + 帧间隔标准差） ——
            if (now - evalStart >= 2000 && evalShown >= 10) {
                double fps = evalShown * 1000.0 / (now - evalStart);
                lastDisplayFps = (float) fps;
                unstableStreak = evaluateStability(fps, evalIntervals, unstableStreak);
                evalStart = now; evalShown = 0; evalIntervals.clear();
            }
            // —— 5 秒诊断汇总：能证明「稳」的数字都在这一行 ——
            if (now - winStart >= 5000) {
                int pulled = pullWinCount; long sumRt = pullWinSumRt;
                int decoded = decodeWinCount; long sumDec = decodeWinSum;
                pullWinCount = 0; pullWinSumRt = 0; decodeWinCount = 0; decodeWinSum = 0;
                long dRaw = droppedRaw - dropRawSeen; dropRawSeen = droppedRaw;
                long dDisp = droppedDisp - dropDispSeen; dropDispSeen = droppedDisp;
                float shownFps = shownWin * 1000f / (now - winStart);
                lastDisplayFps = shownFps;
                Diag.op("LIVEVIEW", "5s汇总（档位 " + lvSizeLabel() + "·" + targetFps() + "fps）：呈现 "
                        + shownWin + " 帧=" + String.format("%.1f", shownFps) + "fps｜拉帧 " + pulled
                        + " 帧 往返均值 " + (pulled > 0 ? sumRt / pulled : 0) + "ms｜解码 " + decoded
                        + " 帧 均值 " + (decoded > 0 ? sumDec / decoded : 0) + "ms｜呈现间隔 "
                        + intervalStats(winIntervals) + "｜丢弃 未解码 " + dRaw + " / 未呈现 " + dDisp);
                winStart = now; shownWin = 0; winIntervals.clear();
            }
        }
        Diag.op("LIVEVIEW", "呈现线程退出");
    }

    /**
     * 稳定性评估与自动降档（稳定 > 帧率 > 分辨率，会话内只降不升防震荡）：
     * 呈现 fps 低于目标 93% 或帧间隔标准差超过目标间隔一半，连续 2 个评估窗判不稳 → 降档。
     * 30fps 档时先降帧尺寸（保帧率牺牲分辨率）；尺寸已最低或帧率档已降过，则降帧率档（30→24→20→15）。
     * 返回新的 unstableStreak。
     */
    private int evaluateStability(double fps, List<Float> ivs, int streak) {
        int target = targetFps();
        double interval = 1000.0 / target;
        double mean = 0;
        for (float v : ivs) mean += v;
        mean = ivs.isEmpty() ? 0 : mean / ivs.size();
        double var = 0;
        for (float v : ivs) var += (v - mean) * (v - mean);
        double std = ivs.isEmpty() ? 0 : Math.sqrt(var / ivs.size());
        boolean unstable = fps < target * 0.93 || std > interval * 0.5;
        if (!unstable) return 0;
        streak++;
        Diag.op("LIVEVIEW", "稳定性评估：呈现 " + String.format("%.1f", fps) + "fps/目标 " + target
                + "，间隔均值 " + String.format("%.1f", mean) + "ms 标准差 " + String.format("%.1f", std)
                + "ms → 不稳观察 " + streak + "/2");
        if (streak < 2) return streak;
        if (target == FPS_TIERS[0] && lvSizeSupported && lvSizeIdx > 0) {
            final int newIdx = lvSizeIdx - 1;
            final int v = lvSizeValues.get(newIdx);
            lvSizeIdx = newIdx;
            lvNote = "帧率不稳，已自动降至 " + lvSizeName(v) + " 档锁定 " + target + "fps";
            Diag.op("LIVEVIEW", "自适应降档（保帧率先牺牲分辨率）：尺寸 → " + lvSizeName(v) + "（值 " + v + "）");
            exec.execute(() -> {
                int rc = client.setDevicePropValue(PtpIpClient.PROP_NIKON_LIVE_VIEW_IMAGE_SIZE,
                        new PtpIpClient.PropValue(lvSizeDatatype, v));
                Diag.op("LIVEVIEW", "降档写入 0xD1AC=" + v + " rc=0x" + Integer.toHexString(rc));
            });
            pushLvSizesUi();
        } else if (fpsTierIdx < FPS_TIERS.length - 1) {
            fpsTierIdx++;
            lvNote = "帧率不稳，已改为锁定 " + targetFps() + "fps 均匀呈现";
            Diag.op("LIVEVIEW", "自适应锁档（保稳定降帧率）：帧率档 → " + targetFps() + "fps");
        } else {
            Diag.op("LIVEVIEW", "已到最低档（最小尺寸 + " + targetFps() + "fps）仍不稳，保持尽力呈现并持续监测");
        }
        return 0;
    }

    /** 呈现间隔统计串：min/max/p95/标准差——判断画面是否「稳」的核心证据 */
    private static String intervalStats(List<Float> ivs) {
        if (ivs.isEmpty()) return "无样本";
        List<Float> s = new ArrayList<>(ivs);
        java.util.Collections.sort(s);
        double mean = 0;
        for (float v : s) mean += v;
        mean /= s.size();
        double var = 0;
        for (float v : s) var += (v - mean) * (v - mean);
        double std = Math.sqrt(var / s.size());
        float p95 = s.get(Math.min(s.size() - 1, (int) (s.size() * 0.95)));
        return "min " + String.format("%.0f", s.get(0)) + "/max " + String.format("%.0f", s.get(s.size() - 1))
                + "/p95 " + String.format("%.0f", p95) + "/标准差 " + String.format("%.1f", std) + "ms";
    }

    /** 直方图：降采样到 48x48 采样统计 RGB 三通道各 64 桶；纯内存计算、不落盘 */
    private float[][] computeHistogram(Bitmap bmp) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bmp, 48, 48, false);
            int[] px = new int[48 * 48];
            small.getPixels(px, 0, 48, 0, 0, 48, 48);
            if (small != bmp) small.recycle();
            float[][] bins = new float[3][64];
            for (int p : px) {
                bins[0][((p >> 16) & 0xFF) >> 2]++;
                bins[1][((p >> 8) & 0xFF) >> 2]++;
                bins[2][(p & 0xFF) >> 2]++;
            }
            float max = 1;
            for (float[] ch : bins) for (float v : ch) if (v > max) max = v;
            for (float[] ch : bins) for (int i = 0; i < ch.length; i++) ch[i] /= max;
            return bins;
        } catch (Exception e) {
            return null;
        }
    }
    // ---------- 点按对焦 / 快门 / 录像 ----------

    /**
     * 点按对焦：入参为归一化坐标（0..1，相对当前帧画面，已由 UI 层扣除 FIT_CENTER 黑边）。
     * 换算到 LV 图像像素坐标（N-Link v3.1.0 实证：AF 坐标域=LV 图像像素空间、按帧宽高线性映射）
     * → ChangeAfArea(0x9205) → AfDrive(0x90C1)；结果以相机响应码为准（0xA002=未合焦）。
     */
    public void tapFocus(final float nx, final float ny) {
        if (!lvOn) { ui.onStatus("先开启取景再点按对焦"); return; }
        if (!featureAllowed(F_FOCUS)) { ui.onStatus("点按对焦在当前机型未验证/已降级"); return; }
        if (lastFrameW <= 0 || lastFrameH <= 0) { ui.onStatus("尚无取景帧"); return; }
        final int x = Math.max(0, Math.min(lastFrameW - 1, Math.round(nx * (lastFrameW - 1))));
        final int y = Math.max(0, Math.min(lastFrameH - 1, Math.round(ny * (lastFrameH - 1))));
        exec.execute(() -> {
            Diag.op("REMOTE", "点按对焦：归一化(" + nx + "," + ny + ") → 像素(" + x + "," + y + ") @帧 " + lastFrameW + "x" + lastFrameH);
            int rc1 = client.nikonChangeAfArea(x, y);
            if (rc1 == PtpIpClient.RC_NIKON_NOT_LIVE_VIEW) {
                ui.onAfResult(false, "相机不在取景状态（0xA00B）");
                return;
            }
            int rc2 = client.nikonAfDrive();
            if (rc2 == PtpIpClient.RC_OK) {
                noteSuccess(F_FOCUS);
                ui.onAfResult(true, "对焦完成");
            } else if (rc2 == PtpIpClient.RC_NIKON_OUT_OF_FOCUS) {
                ui.onAfResult(false, "未合焦（相机回 0xA002）");
            } else {
                noteFailure(F_FOCUS, "AfDrive rc=0x" + Integer.toHexString(rc2));
                ui.onAfResult(false, "对焦失败：区域设置 rc=0x" + Integer.toHexString(rc1)
                        + "，AF 驱动 rc=0x" + Integer.toHexString(rc2));
            }
        });
    }

    public void capture() {
        if (!featureAllowed(F_CAPTURE)) { ui.onStatus("遥控快门已降级禁用"); return; }
        exec.execute(() -> {
            int rc = client.nikonCapture();
            if (rc == PtpIpClient.RC_OK) {
                noteSuccess(F_CAPTURE);
                ui.onStatus("已发出拍摄指令，等待相机写入存储卡…");
            } else {
                noteFailure(F_CAPTURE, "capture rc=0x" + Integer.toHexString(rc));
                ui.onStatus("拍摄失败 rc=0x" + Integer.toHexString(rc) + "（详见诊断）");
            }
        });
    }

    public boolean isRecording() { return recording; }

    public void toggleMovie() {
        if (!featureAllowed(F_MOVIE)) {
            ui.onStatus(featureLockedByProfile(F_MOVIE) ? "录像在当前机型未验证（诊断页可开实验开关）" : "录像已降级禁用");
            return;
        }
        exec.execute(() -> {
            if (!recording) {
                int rc = client.nikonStartMovieRec();
                if (rc == PtpIpClient.RC_OK) {
                    recording = true;
                    noteSuccess(F_MOVIE);
                    ui.onMovie(true);
                    ui.onStatus("录像开始");
                } else {
                    noteFailure(F_MOVIE, "StartMovieRec rc=0x" + Integer.toHexString(rc));
                    ui.onStatus("录像开始失败 rc=0x" + Integer.toHexString(rc));
                }
            } else {
                int rc = client.nikonEndMovieRec();
                recording = false;
                ui.onMovie(false);
                ui.onStatus(rc == PtpIpClient.RC_OK ? "录像已停止" : "停止录像返回 rc=0x" + Integer.toHexString(rc));
            }
        });
    }

    // ---------- 事件 → 拍完即览 ----------

    private void onPtpEvent(int code, int[] params) {
        if (code == PtpIpClient.EC_OBJECT_ADDED && params != null && params.length >= 1) {
            final int handle = params[0];
            exec.execute(() -> {
                try {
                    PtpIpClient.ObjectInfo oi = client.getObjectInfo(handle);
                    // v0.3 自动回传：先于预览图判定入队（下载队列与预览各走各的通道，互不阻塞）
                    maybeAutoTransfer(handle, oi);
                    byte[] thumb = client.getThumb(handle);
                    Bitmap bmp = thumb != null && thumb.length > 0
                            ? BitmapFactory.decodeByteArray(thumb, 0, thumb.length) : null;
                    Diag.op("REMOTE", "拍完即览：新对象 0x" + Integer.toHexString(handle) + " "
                            + oi.filename + " 缩略图 " + (bmp != null ? bmp.getWidth() + "x" + bmp.getHeight() : "无"));
                    ui.onNewPhoto(handle, bmp, oi.filename);
                } catch (Exception e) {
                    Diag.op("REMOTE", "拍完即览拉取失败 handle=0x" + Integer.toHexString(handle) + "：" + e.getMessage());
                }
            });
        } else if (code == PtpIpClient.EC_CAPTURE_COMPLETE) {
            ui.onStatus("拍摄完成，照片已写入存储卡");
        } else if (code == PtpIpClient.EC_DEVICE_PROP_CHANGED) {
            // 相机端参数变化 → 非取景时节流刷新状态面板（>3s 才刷；取景中不抢通道影响帧率）
            if (!lvOn && storageId != 0
                    && System.currentTimeMillis() - lastPropRefreshMs > 3000) {
                loadProps(storageId);
            }
        }
    }

    /**
     * 自动回传判定：开关开 + 格式在所选范围内（JPG，或 JPG+NEF）+ 本会话该 handle 没处理过，
     * 才交给处理方（MainActivity 查已下载记录/队列去重后入队 DownloadService）。
     * autoSeen 只防同一事件在本会话内重复触发；跨会话去重靠已下载记录（文件名）。
     */
    private void maybeAutoTransfer(int handle, PtpIpClient.ObjectInfo oi) {
        if (!autoTransferEnabled) return;
        AutoTransferHandler h = autoHandler;
        if (h == null || oi == null) return;
        PhotoItem.Kind k = PhotoItem.kindOf(oi.filename);
        boolean want = k == PhotoItem.Kind.JPG || (autoIncludeRaw && k == PhotoItem.Kind.NEF);
        if (!want) {
            Diag.op("REMOTE", "自动回传跳过 " + oi.filename + "（格式 " + k + " 不在所选范围）");
            return;
        }
        if (!autoSeen.add(handle)) {
            Diag.op("REMOTE", "自动回传去重：handle=0x" + Integer.toHexString(handle) + " 本会话已处理");
            return;
        }
        boolean handled = h.onAutoCandidate(handle, oi);
        Diag.op("REMOTE", "自动回传 " + oi.filename + " → " + (handled ? "已接管（入队或已下载）" : "处理方跳过"));
    }

    public void shutdown() {
        lvOn = false;
        if (recording) {
            try { client.nikonEndMovieRec(); } catch (Exception ignore) {}
            recording = false;
        }
        client.setEventListener(null);
        exec.shutdownNow();
        Diag.op("REMOTE", "遥控中枢已关闭");
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
