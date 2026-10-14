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
        /** 全量属性面板数据（v0.5：相机自报全部属性 + 剩余张数文案 + 电量百分比，电量读不到为 -1） */
        void onProps(List<PropItem> items, String remainText, int battery);
        void onPropUpdated(PropItem item, boolean ok, String msg);
        /** v0.5.1 增量刷新：事件/轮询/机身变挡引起的属性变化（已同步进中枢缓存），UI 据此更新浮层速览与打开中的面板行 */
        void onPropsChanged(List<PropItem> changed);
        /** v0.5.1：剩余张数/电量单独刷新（拍完后走事件路径，不再触发全量重读） */
        void onRemainChanged(String remainText, int battery);
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
    // v0.5 呈现档判定新口径的样本：拉帧往返耗时（拉帧是请求-响应背靠背，其往返即拉帧间隔的近似）。
    // 拉帧线程往两个窗口各塞一份，呈现线程在 2s 评估 / 5s 汇总时各自快照清零；判定改看拉帧 p95，不再只看呈现 fps。
    private final List<Long> pullRtEval = new ArrayList<>();
    private final List<Long> pullRtWin5 = new ArrayList<>();
    private volatile long lastPullP95Ms = -1;
    private volatile long lastConsumedSeq = 0, lastShownSeq = 0;
    private volatile int lvSizeDatatype = PtpIpClient.DTC_UINT8;
    private volatile float lastDisplayFps = 0;
    private volatile int lastFrameW = 0, lastFrameH = 0;
    private volatile boolean recording = false;
    private long lastPropRefreshMs = 0;
    private volatile int storageId = 0;
    /** 相机 DeviceInfo 自报支持的属性码全集（连接装配时由上层注入；为空则参数面板回退到 CamProps 目录试探） */
    private volatile List<Integer> supportedPropCodes = new ArrayList<>();

    // ---- v0.5.1 属性缓存与增量刷新（事件/轮询/写入共用一条路径，禁止两套刷新逻辑） ----
    /** 最近一次全量读取的属性行缓存：事件与轮询只更新其中个别行，UI 增量回调也以此为准 */
    private volatile List<PropItem> cachedItems = new ArrayList<>();
    private volatile String cachedRemain = null;
    private volatile int cachedBattery = -1;
    /** 属性码 → 最近一次收到 0x4006 事件的时间（轮询据此判断「变了但没事件」并留诊断证据） */
    private final ConcurrentHashMap<Integer, Long> lastEventAt = new ConcurrentHashMap<>();
    /** 属性码 → 最近一次增量刷新执行时间 + 待合并标记（同属性 300ms 内多次事件只刷一次） */
    private final ConcurrentHashMap<Integer, Long> lastRefreshAt = new ConcurrentHashMap<>();
    private final Set<Integer> refreshPending = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService debounce =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qingmu-prop-debounce"); t.setDaemon(true); return t;
            });
    /** AF/拍摄命令在飞标记（UI 线程置位、exec 任务结束清零）：轮询见到任一为真就跳过本轮，不与命令抢通道 */
    private volatile boolean afBusy = false;
    private volatile boolean captureBusy = false;
    /** 点按对焦最新点位（连点只保留最新一个；归一化坐标 + 换算所用显示矩形说明，执行时再换算像素） */
    private final java.util.concurrent.atomic.AtomicReference<float[]> pendingAfPoint =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> pendingAfRect =
            new java.util.concurrent.atomic.AtomicReference<>("");
    private final Object afLock = new Object();
    private volatile boolean afRunning = false;
    /** 点按对焦前自动切单点区域（0x501C=0x8010）的开关，由参数面板复选框经 setAutoSinglePoint 注入 */
    private volatile boolean autoSinglePoint = true;
    private volatile boolean clockWarnLogged = false;

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

    /** 注入相机自报的属性码全集（DeviceInfo.devicePropertiesSupported），参数面板据此全量枚举 */
    public void setSupportedPropCodes(List<Integer> codes) {
        supportedPropCodes = codes != null ? new ArrayList<>(codes) : new ArrayList<>();
    }

    /** v0.5.1：轮询门控——AF 或拍摄命令在飞时为 true，UI 层据此跳过本轮轮询 */
    public boolean isCommandBusy() { return afBusy || captureBusy || afRunning; }

    /** v0.5.1：参数面板复选框——点按对焦前是否自动把对焦区域切到单点（仅 0x501C 可靠识别时生效） */
    public void setAutoSinglePoint(boolean on) { autoSinglePoint = on; }

    /** 缓存中按码查属性行（UI 增量渲染/AF 区域判定用）；无则 null */
    public PropItem cachedProp(int code) {
        for (PropItem it : cachedItems) if (it.def.code == code) return it;
        return null;
    }

    /** 把一行属性并入缓存（有则原位替换 desc，无则新建行追加），返回缓存中的行 */
    private PropItem upsertCache(CamProps.Def def, PtpIpClient.DevicePropDesc desc) {
        synchronized (cachedItems) {
            for (PropItem it : cachedItems) {
                if (it.def.code == def.code) { it.desc = desc; return it; }
            }
            PropItem it = new PropItem();
            it.def = def;
            it.desc = desc;
            List<PropItem> copy = new ArrayList<>(cachedItems);
            copy.add(it);
            cachedItems = copy;
            return it;
        }
    }

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

    /**
     * 全量读取相机属性（v0.5 起）：枚举范围 = 相机 DeviceInfo 自报支持的属性码全集
     * （上层经 setSupportedPropCodes 注入）∪ 经典 DEFS，逐个 GetDevicePropDesc；
     * 相机没报告支持列表时回退到 CamProps 目录逐个试探（读不到的即视为无此属性）。
     * 0xD1AC 显式跳过：取景帧尺寸由尺寸档开关 + 本类状态机专管，面板再开写入入口会状态脱同步。
     * 返回顺序：按 CamProps 分组（曝光/对焦/图像/视频/其他）→ 组内目录序 → 未知码升序，
     * UI 层直接顺序渲染即可。读取失败项：权威列表（相机自报）里的保留为置灰行，回退试探的直接略过。
     */
    public void loadProps(final int storageId) {
        if (!featureAllowed(F_PROPS_READ)) { ui.onStatus("状态面板被机型 profile 锁定"); return; }
        exec.execute(() -> {
            boolean authoritative = !supportedPropCodes.isEmpty();
            java.util.LinkedHashSet<Integer> codes = new java.util.LinkedHashSet<>();
            if (authoritative) codes.addAll(supportedPropCodes);
            else codes.addAll(CamProps.catalogCodes());
            for (CamProps.Def d : CamProps.DEFS) codes.add(d.code); // 经典 8 项保底（相机列表偶有遗漏时仍给出置灰行）
            // v0.5.1：尼康 DeviceInfo 的属性清单历来不全——0x501C（对焦区域模式）不在清单里，
            // 但相机 AF 后会发它的 0x4006 事件，故无条件加入试探集（读不到按权威清单外条目处理，不留假行）
            codes.add(PtpIpClient.PROP_FOCUS_METERING);
            codes.remove(PtpIpClient.PROP_NIKON_LIVE_VIEW_IMAGE_SIZE);
            java.util.Map<Integer, PtpIpClient.DevicePropDesc> fetched = new java.util.HashMap<>();
            int skipped = 0;
            for (int code : codes) {
                try {
                    fetched.put(code, client.getDevicePropDesc(code));
                } catch (Exception e) {
                    skipped++;
                    if (authoritative || CamProps.DEFS.contains(CamProps.byCode(code))) {
                        // 自报支持却读不到 desc：值得点名（面板会置灰），但只汇总记一行防刷屏
                    }
                }
            }
            List<PropItem> items = new ArrayList<>();
            // 组装顺序：分组 → 目录序；目录外的码（厂商私有）按码升序落其他组末尾
            List<Integer> unknown = new ArrayList<>();
            for (int code : codes) if (CamProps.byCode(code) == null) unknown.add(code);
            java.util.Collections.sort(unknown);
            for (int g = 0; g < CamProps.GROUP_NAMES.length; g++) {
                for (CamProps.Def def : CamProps.CATALOG) {
                    if (def.group != g || !codes.contains(def.code)) continue;
                    PropItem it = mkPropItem(def, fetched.get(def.code), authoritative);
                    if (it != null) items.add(it);
                }
                if (g == CamProps.GROUP_OTHER) {
                    for (int code : unknown) {
                        PropItem it = mkPropItem(CamProps.defOf(code), fetched.get(code), authoritative);
                        if (it != null) items.add(it);
                    }
                }
            }
            int got = 0;
            for (PropItem it : items) if (it.available()) got++;
            Diag.op("REMOTE", "全量属性读取完成：候选 " + codes.size() + " 个（来源 "
                    + (authoritative ? "相机 DeviceInfo 自报" : "目录回退试探") + "），成功 " + got
                    + " 个，读不到 " + skipped + " 个" + (authoritative ? "（自报项读不到的在面板置灰）" : ""));
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
            int battery = client.getBatteryLevel(); // 失败返回 -1 不抛（client 内部已吞异常）
            lastPropRefreshMs = System.currentTimeMillis();
            cachedItems = items;
            cachedRemain = remain;
            if (battery >= 0) cachedBattery = battery;
            checkCameraClock(items);
            ui.onProps(items, remain, battery);
        });
    }

    /**
     * v0.5.1 相机时钟体检：0x5011 年份早于手机当前年份（典型：相机从未设过时钟、停在出厂 2021 年）→
     * 诊断记一行（每会话一次）+ 面板在该行下给同步提示。这里只报告、绝不自动写——同步必须用户在面板点按。
     */
    private void checkCameraClock(List<PropItem> items) {
        if (clockWarnLogged) return;
        for (PropItem it : items) {
            if (it.def.code != PtpIpClient.PROP_DATETIME || !it.available()
                    || it.desc.curVal == null || it.desc.curVal.str == null) continue;
            int camYear = parseClockYear(it.desc.curVal.str);
            int phoneYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            if (camYear > 0 && camYear < phoneYear) {
                clockWarnLogged = true;
                Diag.op("REMOTE", "相机时钟疑似未设置：相机时间 " + it.desc.curVal.str + "（" + camYear
                        + " 年，早于手机 " + phoneYear + " 年），拍出的照片 EXIF 时间会全错；"
                        + "可在参数面板「其他」组点『同步手机时间』一键对齐（不会自动写）");
            }
        }
    }

    /** 解析 PTP DateTime 串（yyyyMMdd'T'HHmmss）的年份；解析不了返回 -1 */
    static int parseClockYear(String s) {
        if (s == null || s.length() < 4) return -1;
        try { return Integer.parseInt(s.substring(0, 4)); } catch (Exception e) { return -1; }
    }

    /**
     * v0.5.1 同步相机时钟（用户在参数面板点按触发）：把手机当前时间按 PTP 格式写 0x5011，
     * 写后回读验证并记诊断；结果经 onPropUpdated 回 UI（行刷新 + 状态行文案）。不自动调用。
     */
    public void syncCameraClock() {
        exec.execute(() -> {
            PropItem item = cachedProp(PtpIpClient.PROP_DATETIME);
            String now = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            PtpIpClient.PropValue v = new PtpIpClient.PropValue(PtpIpClient.DTC_STR);
            v.str = now;
            int rc = client.setDevicePropValue(PtpIpClient.PROP_DATETIME, v);
            if (item == null) {
                item = upsertCache(CamProps.defOf(PtpIpClient.PROP_DATETIME), null);
            }
            if (rc != PtpIpClient.RC_OK) {
                Diag.op("REMOTE", "相机时钟同步失败：Set 0x5011=" + now + " rc=0x" + Integer.toHexString(rc));
                ui.onPropUpdated(item, false, "同步失败，相机返回 0x" + Integer.toHexString(rc));
                return;
            }
            try {
                item.desc = client.getDevicePropDesc(PtpIpClient.PROP_DATETIME);
                String back = item.desc.curVal != null ? item.desc.curVal.str : null;
                boolean match = now.equals(back);
                Diag.op("REMOTE", "相机时钟同步：写入 " + now + "，回读 " + back
                        + (match ? "（一致 ✓）" : "（不一致，以相机回读为准）"));
                clockWarnLogged = match; // 同步成功后不再重复警告
                ui.onPropUpdated(item, true, match ? "相机时钟已同步为 " + now : "已写入，相机回读为 " + back);
            } catch (Exception e) {
                Diag.op("REMOTE", "相机时钟同步：已写入 " + now + "，回读失败：" + e.getMessage());
                ui.onPropUpdated(item, true, "已写入（回读失败：" + e.getMessage() + "）");
            }
        });
    }

    /**
     * 造一行属性。desc 为 null（读不到）时：相机自报支持（权威）或经典 DEFS 项 → 保留为置灰行（如实显示「相机未提供」）；
     * 纯回退试探猜的码 → 返回 null 丢弃，避免拿猜测冒充相机属性清单。
     */
    private PropItem mkPropItem(CamProps.Def def, PtpIpClient.DevicePropDesc desc, boolean authoritative) {
        if (desc == null && !authoritative) {
            boolean classic = false;
            for (CamProps.Def d : CamProps.DEFS) if (d.code == def.code) { classic = true; break; }
            if (!classic) return null;
        }
        PropItem it = new PropItem();
        it.def = def;
        it.desc = desc;
        return it;
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
                upsertCache(item.def, item.desc); // v0.5.1：写入结果同步进缓存，事件/轮询比对以此为准
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
        setLvSizeIndex(smooth ? 0 : lvSizeValues.size() - 1);
    }

    /** v0.5：按下标切任意一档帧尺寸（面板/浮层列全相机自报的档位，不再只有最小/最大两极）；成功后帧率档重置回 30 重试锁定 */
    public void setLvSizeIndex(final int idx) {
        if (!lvSizeSupported || lvSizeValues.isEmpty()) { ui.onStatus("相机未提供帧尺寸切换（0xD1AC）"); return; }
        if (idx < 0 || idx >= lvSizeValues.size()) return;
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
            synchronized (pullRtEval) { pullRtEval.clear(); }
            synchronized (pullRtWin5) { pullRtWin5.clear(); }
            lastPullP95Ms = -1;
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
                // v0.5.1：帧头字段（含是否有相机 AF 回执框）未经 libgphoto2 核实——其实现只在数据里
                // 扫描 JPEG SOI、不解析尼康帧头——故不猜字段，只把完整帧头 hex 整份打进诊断（16B/行，
                // 每 LV 会话一份），供后续拿真机数据离线比对分析。
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < f.jpegOff && i < f.data.length; i++) {
                    if (i % 16 == 0) hex.append(String.format("\n  +%03d: ", i));
                    hex.append(String.format("%02x ", f.data[i] & 0xFF));
                }
                Diag.op("LIVEVIEW", "首帧：总长 " + f.data.length + "B，帧头 " + f.jpegOff
                        + "B，完整帧头 hex（每 LV 会话一份，供 AF 框字段离线分析）：" + hex);
            }
            RawFrame rf = new RawFrame();
            rf.data = f.data; rf.jpegOff = f.jpegOff; rf.jpegLen = f.jpegLen;
            rf.roundTripMs = rt;
            rf.seq = ++frameSeq;
            RawFrame prev = rawSlot.getAndSet(rf);
            if (prev != null && prev.seq > lastConsumedSeq) droppedRaw++; // 旧帧没等到解码就被覆盖
            pullWinCount++; pullWinSumRt += rt;
            synchronized (pullRtEval) {
                pullRtEval.add(rt);
                if (pullRtEval.size() > 1200) pullRtEval.subList(0, 600).clear(); // 防异常堆积，窗口样本足够即可
            }
            synchronized (pullRtWin5) {
                pullRtWin5.add(rt);
                if (pullRtWin5.size() > 1200) pullRtWin5.subList(0, 600).clear();
            }
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
            // —— 自适应锁档：每约 2 秒评估（v0.5 起判据以拉帧 p95 间隔为主，呈现 fps/抖动为辅） ——
            if (now - evalStart >= 2000 && evalShown >= 10) {
                double fps = evalShown * 1000.0 / (now - evalStart);
                lastDisplayFps = (float) fps;
                List<Long> rtSnap;
                synchronized (pullRtEval) { rtSnap = new ArrayList<>(pullRtEval); pullRtEval.clear(); }
                long p95 = p95Of(rtSnap);
                if (p95 >= 0) lastPullP95Ms = p95;
                double rtMean = 0;
                for (long v : rtSnap) rtMean += v;
                rtMean = rtSnap.isEmpty() ? 0 : rtMean / rtSnap.size();
                unstableStreak = evaluateStability(fps, evalIntervals, unstableStreak,
                        p95, rtMean, rtSnap.size());
                evalStart = now; evalShown = 0; evalIntervals.clear();
            }
            // —— 5 秒诊断汇总：能证明「稳」的数字都在这一行（v0.5 在拉帧段内补间隔p95，字段顺序不变可跨版对比） ——
            if (now - winStart >= 5000) {
                int pulled = pullWinCount; long sumRt = pullWinSumRt;
                int decoded = decodeWinCount; long sumDec = decodeWinSum;
                pullWinCount = 0; pullWinSumRt = 0; decodeWinCount = 0; decodeWinSum = 0;
                List<Long> rtWin5;
                synchronized (pullRtWin5) { rtWin5 = new ArrayList<>(pullRtWin5); pullRtWin5.clear(); }
                long p95w = p95Of(rtWin5);
                long dRaw = droppedRaw - dropRawSeen; dropRawSeen = droppedRaw;
                long dDisp = droppedDisp - dropDispSeen; dropDispSeen = droppedDisp;
                float shownFps = shownWin * 1000f / (now - winStart);
                lastDisplayFps = shownFps;
                Diag.op("LIVEVIEW", "5s汇总（档位 " + lvSizeLabel() + "·" + targetFps() + "fps）：呈现 "
                        + shownWin + " 帧=" + String.format("%.1f", shownFps) + "fps｜拉帧 " + pulled
                        + " 帧 往返均值 " + (pulled > 0 ? sumRt / pulled : 0) + "ms 间隔p95 "
                        + (p95w >= 0 ? p95w + "ms" : "无样本") + "｜解码 " + decoded
                        + " 帧 均值 " + (decoded > 0 ? sumDec / decoded : 0) + "ms｜呈现间隔 "
                        + intervalStats(winIntervals) + "｜丢弃 未解码 " + dRaw + " / 未呈现 " + dDisp);
                winStart = now; shownWin = 0; winIntervals.clear();
            }
        }
        Diag.op("LIVEVIEW", "呈现线程退出");
    }

    /** 拉帧往返样本的 p95（样本不足返回 -1）；拉帧为请求-响应背靠背，往返即拉帧间隔近似 */
    private static long p95Of(List<Long> samples) {
        if (samples == null || samples.size() < 8) return -1;
        List<Long> s = new ArrayList<>(samples);
        java.util.Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) (s.size() * 0.95)));
    }

    /**
     * 稳定性评估与自动降档（稳定 > 帧率 > 分辨率，会话内只降不升防震荡）：
     * v0.5 判据改口径——以「拉帧 p95 间隔」为主：拉帧样本足够且 p95 ≤ 档位间隔×1.25、均值 ≤ 间隔×1.05
     * 视为「拉帧跟得上」，此时呈现 fps 略低于目标多半是 pacing 相位损耗，不再据此误降档
     * （Z fc 实测教训：QVGA 拉帧往返均值 33–35ms≈29fps，旧口径只看呈现 fps 把 30 档误锁到 24）。
     * 仍判不稳的三种情况：①呈现间隔抖动 σ 超间隔 0.6（真抖，宁降档不抖）；②拉帧跟不上且呈现 fps<目标 93%；
     * ③拉帧跟得上但呈现 fps 跌破目标 80%（解码/呈现侧真出问题）。连续 2 窗不稳 → 降档：
     * 30fps 档时先降帧尺寸（保帧率牺牲分辨率）；尺寸已最低或帧率档已降过，则降帧率档（30→24→20→15）。
     * 返回新的 unstableStreak。
     */
    private int evaluateStability(double fps, List<Float> ivs, int streak,
                                  long pullP95Ms, double pullMeanMs, int pullSamples) {
        int target = targetFps();
        double interval = 1000.0 / target;
        double mean = 0;
        for (float v : ivs) mean += v;
        mean = ivs.isEmpty() ? 0 : mean / ivs.size();
        double var = 0;
        for (float v : ivs) var += (v - mean) * (v - mean);
        double std = ivs.isEmpty() ? 0 : Math.sqrt(var / ivs.size());
        boolean pullKnown = pullP95Ms >= 0 && pullSamples >= 8;
        boolean pullKeepsUp = pullKnown && pullP95Ms <= interval * 1.25 && pullMeanMs <= interval * 1.05;
        boolean jittery = std > interval * 0.6;
        boolean unstable;
        if (jittery) unstable = true;
        else if (pullKnown && !pullKeepsUp) unstable = fps < target * 0.93;
        else if (pullKeepsUp) unstable = fps < target * 0.80;
        else unstable = fps < target * 0.93 || std > interval * 0.5; // 拉帧样本不足 → 回退旧口径
        if (!unstable) {
            if (pullKeepsUp && fps < target * 0.93) {
                Diag.op("LIVEVIEW", "稳定性评估：呈现 " + String.format("%.1f", fps) + "fps/目标 " + target
                        + "，但拉帧跟得上（往返均值 " + String.format("%.1f", pullMeanMs) + "ms p95 " + pullP95Ms
                        + "ms ≤ 档位间隔 " + String.format("%.1f", interval) + "ms×1.25），按拉帧口径保持当前档不降");
            }
            return 0;
        }
        streak++;
        Diag.op("LIVEVIEW", "稳定性评估：呈现 " + String.format("%.1f", fps) + "fps/目标 " + target
                + "，呈现间隔均值 " + String.format("%.1f", mean) + "ms 标准差 " + String.format("%.1f", std)
                + "ms，拉帧往返均值 " + String.format("%.1f", pullMeanMs) + "ms p95 "
                + (pullP95Ms >= 0 ? pullP95Ms + "ms" : "样本不足") + "（" + (pullKeepsUp ? "拉帧跟得上" : "拉帧跟不上")
                + "）→ 不稳观察 " + streak + "/2");
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
     * 点按对焦（v0.5.1 串行化重写）：入参为归一化坐标（0..1，相对当前帧画面，UI 层已按实际显示矩形换算）
     * + 换算所用显示矩形说明（进诊断备查）。
     * v0.5.0 的问题（Z fc 日志实证）：用户连点时上一次 AF 未完，ChangeAfArea 回 DeviceBusy(0x2019)，
     * 旧代码不看 rc1 继续发 AfDrive → 在旧区域对焦、表现为「对焦不准」。
     * 现在：连点只保留最新点位排队串行执行；ChangeAfArea 回 0x2019 → 等 DeviceReady 后用最新点位重试一次；
     * 区域设置最终失败则**不发** AfDrive 并经 onAfResult(false) 把对焦框置红。
     */
    public void tapFocus(final float nx, final float ny, final String rectDesc) {
        if (!lvOn) { ui.onStatus("先开启取景再点按对焦"); return; }
        if (!featureAllowed(F_FOCUS)) { ui.onStatus("点按对焦在当前机型未验证/已降级"); return; }
        if (lastFrameW <= 0 || lastFrameH <= 0) { ui.onStatus("尚无取景帧"); return; }
        pendingAfPoint.set(new float[]{nx, ny});
        if (rectDesc != null) pendingAfRect.set(rectDesc);
        synchronized (afLock) {
            if (afRunning) {
                Diag.op("REMOTE", "点按对焦连点合并：AF 进行中，只保留最新点位 (" + nx + "," + ny + ") 排队");
                return;
            }
            afRunning = true;
            afBusy = true;
        }
        exec.execute(this::runAfLoop);
    }

    /** AF 串行主循环：把排队中的最新点位逐个执行完；退出前复查防竞态丢点 */
    private void runAfLoop() {
        while (true) {
            float[] p;
            while ((p = pendingAfPoint.getAndSet(null)) != null) {
                doAfOnce(p[0], p[1], pendingAfRect.get());
            }
            synchronized (afLock) {
                if (pendingAfPoint.get() != null) continue; // 复查期间又来了新点，继续跑
                afRunning = false;
                afBusy = false;
                return;
            }
        }
    }

    private int afPxX(float nx) { return Math.max(0, Math.min(lastFrameW - 1, Math.round(nx * (lastFrameW - 1)))); }
    private int afPxY(float ny) { return Math.max(0, Math.min(lastFrameH - 1, Math.round(ny * (lastFrameH - 1)))); }

    /** 执行一次完整 AF：[自动切单点] → ChangeAfArea（Busy 重试一次）→ AfDrive → 回读 0x501C 记诊断 */
    private void doAfOnce(float nx, float ny, String rectDesc) {
        int x = afPxX(nx), y = afPxY(ny);
        Diag.op("REMOTE", "点按对焦：归一化(" + nx + "," + ny + ") → 像素(" + x + "," + y + ") @帧 "
                + lastFrameW + "x" + lastFrameH + "｜换算所用显示矩形 " + rectDesc);
        maybeAutoSinglePoint();
        int rc1 = client.nikonChangeAfArea(x, y);
        if (rc1 == PtpIpClient.RC_DEVICE_BUSY) {
            Diag.op("REMOTE", "ChangeAfArea 回 DeviceBusy(0x2019)（上一次相机操作未完），"
                    + "等 DeviceReady 后用最新点位重试一次");
            int wrc = client.nikonWaitBusy(3000);
            Diag.op("REMOTE", "DeviceReady 轮询结果 rc=0x" + Integer.toHexString(wrc));
            float[] np = pendingAfPoint.getAndSet(null);
            float ux = nx, uy = ny;
            if (np != null) {
                ux = np[0]; uy = np[1];
                Diag.op("REMOTE", "忙等待期间收到新点按，重试改用最新点位 (" + ux + "," + uy + ")");
            }
            x = afPxX(ux); y = afPxY(uy);
            rc1 = client.nikonChangeAfArea(x, y);
            Diag.op("REMOTE", "ChangeAfArea 重试：像素(" + x + "," + y + ") rc=0x" + Integer.toHexString(rc1));
        }
        if (rc1 == PtpIpClient.RC_NIKON_NOT_LIVE_VIEW) {
            ui.onAfResult(false, "相机不在取景状态（0xA00B）");
            return;
        }
        if (rc1 != PtpIpClient.RC_OK) {
            // v0.5.1：区域都没设成功就绝不发 AfDrive（旧版照发 → 在旧区域对焦，是「对不准」的实锤路径之一）
            noteFailure(F_FOCUS, "ChangeAfArea rc=0x" + Integer.toHexString(rc1));
            ui.onAfResult(false, "对焦区域设置失败 rc=0x" + Integer.toHexString(rc1) + "，未驱动对焦");
            return;
        }
        int rc2 = client.nikonAfDrive();
        readFocusMeteringAfterAf();
        if (rc2 == PtpIpClient.RC_OK) {
            noteSuccess(F_FOCUS);
            ui.onAfResult(true, "对焦完成");
        } else if (rc2 == PtpIpClient.RC_NIKON_OUT_OF_FOCUS) {
            ui.onAfResult(false, "未合焦（相机回 0xA002）");
        } else {
            noteFailure(F_FOCUS, "AfDrive rc=0x" + Integer.toHexString(rc2));
            ui.onAfResult(false, "对焦失败：AF 驱动 rc=0x" + Integer.toHexString(rc2));
        }
    }

    /**
     * 点按对焦前自动切单点（仅当能可靠识别时）：对焦区域属性是 0x501C（FocusMeteringMode，
     * libgphoto2 ptp.h:1968 核实；尼康 Z 取值 0x8010=单点 AF，config.c Z 表核实）。
     * 条件全齐才动手：缓存里该属性可读、可写、当前值有名可辨且非单点类、枚举里确有 0x8010。
     * 任一不满足就不自动改——当前模式可辨时在状态行提示它可能修正对焦点，不可辨时只记诊断。
     * （注：0x500C 经核实是闪光模式，与 AF 区域无关，绝不拿它做此事。）
     */
    private void maybeAutoSinglePoint() {
        PropItem fm = cachedProp(PtpIpClient.PROP_FOCUS_METERING);
        if (fm == null || !fm.available() || fm.desc == null || fm.desc.curVal == null) {
            if (fm == null) Diag.op("REMOTE", "对焦区域模式（0x501C）未读到，跳过自动切单点（不猜）");
            return;
        }
        long cur = fm.desc.curVal.num;
        if (CamProps.isSinglePointClass(cur)) return;
        String curName = CamProps.focusMeteringName(cur);
        if (!autoSinglePoint) return;
        boolean hasSingle = false;
        for (PtpIpClient.PropValue pv : fm.desc.enumValues) if (pv.num == 0x8010) hasSingle = true;
        if (curName == null || !fm.desc.writable() || !hasSingle) {
            if (curName != null) {
                Diag.op("REMOTE", "当前对焦区域=" + curName + "，无法自动切单点（可写="
                        + fm.desc.writable() + "，枚举含单点=" + hasSingle + "），相机可能自行修正对焦点");
                ui.onStatus("当前对焦区域为「" + curName + "」，相机可能自行修正对焦点（可在参数面板切到单点 AF）");
            }
            return;
        }
        int rc = client.setDevicePropValue(PtpIpClient.PROP_FOCUS_METERING,
                new PtpIpClient.PropValue(fm.desc.datatype, 0x8010));
        if (rc == PtpIpClient.RC_OK) {
            try { fm.desc = client.getDevicePropDesc(PtpIpClient.PROP_FOCUS_METERING); } catch (Exception ignore) {}
            upsertCache(fm.def, fm.desc);
            Diag.op("REMOTE", "点按对焦前自动切换对焦区域：" + curName + " → 单点 AF（0x501C=0x8010）");
            List<PropItem> ch = new ArrayList<>();
            ch.add(fm);
            ui.onPropsChanged(ch);
            ui.onStatus("已切换为单点对焦（原：" + curName + "）");
        } else {
            Diag.op("REMOTE", "自动切单点失败 rc=0x" + Integer.toHexString(rc) + "，按当前区域模式（" + curName + "）继续对焦");
        }
    }

    /**
     * AfDrive 后回读一次 0x501C 记诊断：AfDrive 的 rc=0x2001 只表示「受理」，不是合焦证明；
     * 合焦与否目前只能以响应码（0xA002=未合焦）与后续 0x501C 变化事件旁证，本读值供核对区域模式未被相机改动。
     * （尼康 LV 帧头里若含相机 AF 回执框，其字段偏移未经 libgphoto2 核实——gphoto 只扫 JPEG SOI 不解析帧头——不猜、不画。）
     */
    private void readFocusMeteringAfterAf() {
        PropItem fm = cachedProp(PtpIpClient.PROP_FOCUS_METERING);
        int dt = fm != null && fm.desc != null ? fm.desc.datatype : PtpIpClient.DTC_UINT16;
        try {
            PtpIpClient.PropValue v = client.getDevicePropValueTyped(PtpIpClient.PROP_FOCUS_METERING, dt);
            String nm = CamProps.focusMeteringName(v.num);
            Diag.op("REMOTE", "AfDrive 后回读 0x501C（对焦区域模式）=" + v
                    + (nm != null ? "（" + nm + "）" : "（名称未核实，只记原值）"));
        } catch (Exception e) {
            Diag.op("REMOTE", "AfDrive 后回读 0x501C 失败：" + e.getMessage());
        }
    }

    public void capture() {
        if (!featureAllowed(F_CAPTURE)) { ui.onStatus("遥控快门已降级禁用"); return; }
        captureBusy = true; // v0.5.1：轮询据此跳过本轮，不与拍摄命令抢通道
        exec.execute(() -> {
            try {
                int rc = client.nikonCapture();
                if (rc == PtpIpClient.RC_OK) {
                    noteSuccess(F_CAPTURE);
                    ui.onStatus("已发出拍摄指令，等待相机写入存储卡…");
                } else {
                    noteFailure(F_CAPTURE, "capture rc=0x" + Integer.toHexString(rc));
                    ui.onStatus("拍摄失败 rc=0x" + Integer.toHexString(rc) + "（详见诊断）");
                }
            } finally {
                captureBusy = false;
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

    // ---------- v0.5.1 增量刷新引擎（事件 / 轮询 / 写入共用一条路径） ----------

    /** 曝光组里可写性随模式挡位翻转的属性：模式（0x500E）变化时除读新值外还要补读它们的 Desc */
    private static final int[] MODE_LINKED_PROPS = {
            PtpIpClient.PROP_F_NUMBER, PtpIpClient.PROP_EXPOSURE_TIME,
            PtpIpClient.PROP_EXPOSURE_INDEX, PtpIpClient.PROP_EXPOSURE_BIAS};
    /** 轮询兜底的速览四值（只读值不读 Desc） */
    private static final int[] QUICK_PROPS = {
            PtpIpClient.PROP_EXPOSURE_PROGRAM, PtpIpClient.PROP_F_NUMBER,
            PtpIpClient.PROP_EXPOSURE_TIME, PtpIpClient.PROP_EXPOSURE_INDEX};
    /** 待刷新属性集（事件风暴合并）：300ms 窗口内的多次事件只合成一次刷新 */
    private final Set<Integer> dirtyProps = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 0x4006 事件入口：记事件时间 + 标脏合并调度（不再全量重读——见 flushDirtyProps） */
    private void onPropChangedEvent(int code) {
        lastEventAt.put(code, System.currentTimeMillis());
        dirtyProps.add(code);
        if (flushScheduled.compareAndSet(false, true)) {
            try {
                debounce.schedule(this::flushDirtyProps, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                flushScheduled.set(false);
                flushDirtyProps();
            }
        }
    }

    private void flushDirtyProps() {
        flushScheduled.set(false);
        if (dirtyProps.isEmpty()) return;
        List<Integer> codes = new ArrayList<>(dirtyProps);
        dirtyProps.clear();
        exec.execute(() -> { for (int c : codes) refreshOneProp(c, false); });
    }

    /**
     * 单属性增量刷新：只重读这一个属性的 Desc（值+可写性+取值集，约一次事务），
     * 并入缓存后经 onPropsChanged 直达 UI 的浮层速览与打开中的面板行。
     * code=0x500E（模式换挡）时额外补读曝光组关联属性 Desc——转盘换挡会让光圈/快门可写性翻转。
     */
    private void refreshOneProp(int code, boolean fromPoll) {
        long t0 = System.currentTimeMillis();
        CamProps.Def def = CamProps.defOf(code);
        PropItem before = cachedProp(code);
        String oldText = before != null && before.available() ? before.currentText() : null;
        PtpIpClient.DevicePropDesc desc;
        try {
            desc = client.getDevicePropDesc(code);
        } catch (Exception e) {
            Diag.op("EVENT", "属性 0x" + Integer.toHexString(code) + "（" + def.name + "）增量重读失败："
                    + e.getMessage());
            return;
        }
        PropItem item = upsertCache(def, desc);
        List<PropItem> changed = new ArrayList<>();
        changed.add(item);
        String extra = "";
        if (code == PtpIpClient.PROP_EXPOSURE_PROGRAM) {
            int n = 0;
            for (int linked : MODE_LINKED_PROPS) {
                try {
                    changed.add(upsertCache(CamProps.defOf(linked), client.getDevicePropDesc(linked)));
                    n++;
                } catch (Exception ignore) { /* 关联属性读不到就不带，目标属性本身已刷新 */ }
            }
            extra = "，并补读曝光组 " + n + " 个关联属性 Desc（模式换挡的可写性翻转）";
        }
        lastRefreshAt.put(code, t0);
        Diag.op("EVENT", "属性变化处理：0x" + Integer.toHexString(code) + "（" + def.name + "）"
                + (oldText != null ? oldText + " → " : "") + item.currentText() + extra
                + "，耗时 " + (System.currentTimeMillis() - t0) + "ms" + (fromPoll ? "（轮询触发）" : ""));
        ui.onPropsChanged(changed);
    }

    /**
     * v0.5.1 轮询兜底（UI 层约 2s 调一次，本方法只在遥控页前台时被调用）：
     * 只 GetDevicePropValue 读速览四值与缓存比对；值变了就走与事件相同的 refreshOneProp 路径，
     * 若该属性近 2.6s 内没收到过 0x4006 事件，记一行 [EVENT] 轮询发现变化——为判断
     * 「机身转盘变挡到底发不发事件」留证据。AF/拍摄在飞时整轮跳过，不与命令抢通道。
     */
    public void pollQuickProps() {
        if (isCommandBusy() || !client.isConnected()) return;
        exec.execute(() -> {
            if (isCommandBusy()) return;
            for (int code : QUICK_PROPS) {
                PropItem cached = cachedProp(code);
                if (cached == null || !cached.available() || cached.desc == null
                        || cached.desc.curVal == null || cached.desc.datatype == PtpIpClient.DTC_STR) continue;
                PtpIpClient.PropValue v;
                try {
                    v = client.getDevicePropValueTyped(code, cached.desc.datatype);
                } catch (Exception e) {
                    continue; // 读不到就跳过该项，下轮再试，不刷屏
                }
                if (v.num == cached.desc.curVal.num) continue;
                String oldText = cached.currentText();
                Long evAt = lastEventAt.get(code);
                boolean eventSeen = evAt != null && System.currentTimeMillis() - evAt < 2600;
                if (!eventSeen) {
                    Diag.op("EVENT", "轮询发现变化：0x" + Integer.toHexString(code) + "（"
                            + CamProps.defOf(code).name + "）" + oldText + " → "
                            + CamProps.format(code, v) + "（未收到对应 0x4006 事件）");
                }
                refreshOneProp(code, true);
            }
        });
    }

    /** 拍完（CaptureComplete）后刷剩余张数/电量：存储信息只在此与连接、列表加载时读，不进普通事件路径 */
    private void refreshRemainAfterCapture() {
        if (storageId == 0) return;
        final int sid = storageId;
        exec.execute(() -> {
            try {
                PtpIpClient.StorageInfo si = client.getStorageInfo(sid);
                if (si.freeImages > 0) {
                    cachedRemain = "约 " + si.freeImages + " 张（来源：存储信息，相机估算）";
                }
                int b = client.getBatteryLevel();
                if (b >= 0) cachedBattery = b;
                ui.onRemainChanged(cachedRemain, cachedBattery);
            } catch (Exception e) {
                Diag.op("EVENT", "拍完刷新剩余张数失败：" + e.getMessage());
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
                            ? ThumbCache.orientByExif(BitmapFactory.decodeByteArray(thumb, 0, thumb.length), thumb)
                            : null; // v0.5：与网格缩略图同一口径按 EXIF 方向转正（仅显示位图）
                    Diag.op("REMOTE", "拍完即览：新对象 0x" + Integer.toHexString(handle) + " "
                            + oi.filename + " 缩略图 " + (bmp != null ? bmp.getWidth() + "x" + bmp.getHeight() : "无"));
                    ui.onNewPhoto(handle, bmp, oi.filename);
                } catch (Exception e) {
                    Diag.op("REMOTE", "拍完即览拉取失败 handle=0x" + Integer.toHexString(handle) + "：" + e.getMessage());
                }
            });
        } else if (code == PtpIpClient.EC_CAPTURE_COMPLETE) {
            ui.onStatus("拍摄完成，照片已写入存储卡");
            refreshRemainAfterCapture(); // v0.5.1：存储信息刷新只留在拍完这一刻，不进普通属性事件路径
        } else if (code == PtpIpClient.EC_DEVICE_PROP_CHANGED) {
            // v0.5.1 重写（原实现：每次事件全量重读 21 个属性 Desc + GetStorageInfo，约 0.7s 事务，
            // P 挡快门随光线抖动时形成事件风暴，与拉帧抢同一命令通道、把帧率拖垮）：
            // 现在只标脏合并（300ms 窗口）， flush 时按属性逐个单读；GetStorageInfo 已移出此路径。
            if (params != null && params.length >= 1) onPropChangedEvent(params[0]);
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
        debounce.shutdownNow();
        exec.shutdownNow();
        Diag.op("REMOTE", "遥控中枢已关闭");
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
