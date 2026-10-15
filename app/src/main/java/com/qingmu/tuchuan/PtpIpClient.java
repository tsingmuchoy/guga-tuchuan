package com.qingmu.tuchuan;

import android.net.Network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PTP/IP 客户端（纯 Java Socket 自实现，无第三方库）。
 *
 * 通道模型（CIPA DC-005 / libgphoto2 ptpip.c 实证）：
 *  - 命令通道：InitCommandRequest(1) → InitCommandAck(2)；之后 OperationRequest(6)/Response(7)
 *    与数据阶段 StartDataPacket(9)/DataPacket(10)/EndDataPacket(12) 都走这条。
 *  - 事件通道：InitEventRequest(3, 带命令通道分到的 connection number) → InitEventAck(4)；
 *    相机 Event(8)、Ping(13)/Pong(14) 走这条。
 * 包头统一：uint32 小端总长度 + uint32 类型，长度含包头 8 字节。
 * OperationRequest 负载：uint32 DataPhaseInfo（接收数据或无数据=1，发送数据=2）+
 * uint16 操作码 + uint32 事务ID + 最多 5 个 uint32 参数。
 *
 * 保活/恢复：事件线程自动回 Pong；本端每 10s 在事件通道发 Ping；45s 无任何事件通道
 * 入流量判死并主动关 socket 逼迫阻塞读抛错。任一通道 IOException → connected=false，
 * 上层（DownloadManager）调 reconnect() 指数退避重连并 OpenSession 重建会话，
 * 下载从 .part 长度用 GetPartialObject 续传。
 */
public class PtpIpClient {

    // ---- PTP/IP 包类型 ----
    static final int PKT_INIT_CMD_REQ  = 1;
    static final int PKT_INIT_CMD_ACK  = 2;
    static final int PKT_INIT_EVT_REQ  = 3;
    static final int PKT_INIT_EVT_ACK  = 4;
    static final int PKT_INIT_FAIL     = 5;
    static final int PKT_OP_REQUEST    = 6;
    static final int PKT_OP_RESPONSE   = 7;
    static final int PKT_EVENT         = 8;
    static final int PKT_START_DATA    = 9;
    static final int PKT_DATA          = 10;
    static final int PKT_CANCEL        = 11;
    static final int PKT_END_DATA      = 12;
    static final int PKT_PING          = 13;
    static final int PKT_PONG          = 14;

    // ---- PTP 操作码 ----
    public static final int OP_GET_DEVICE_INFO   = 0x1001;
    public static final int OP_OPEN_SESSION      = 0x1002;
    public static final int OP_CLOSE_SESSION     = 0x1003;
    public static final int OP_GET_STORAGE_IDS   = 0x1004;
    public static final int OP_GET_STORAGE_INFO  = 0x1005;
    public static final int OP_GET_OBJECT_HANDLES= 0x1007;
    public static final int OP_GET_OBJECT_INFO   = 0x1008;
    public static final int OP_GET_OBJECT        = 0x1009;
    public static final int OP_GET_THUMB         = 0x100A;
    public static final int OP_GET_DEVICE_PROP_VALUE = 0x1015;
    public static final int OP_GET_PARTIAL_OBJECT    = 0x101B;
    // v0.2 新增标准操作码（libgphoto2 ptp.h:237/243/245 核实）
    public static final int OP_INITIATE_CAPTURE      = 0x100E;
    public static final int OP_GET_DEVICE_PROP_DESC  = 0x1014;
    public static final int OP_SET_DEVICE_PROP_VALUE = 0x1016;
    // v0.3 相机管理（libgphoto2 ptp.h:234/238 核实；ptp.c ptp_deleteobject 双参、ptp.h:4476 ptp_formatstore 单参）
    public static final int OP_DELETE_OBJECT         = 0x100B; // DeleteObject(handle, objectFormatCode)
    public static final int OP_FORMAT_STORE          = 0x100F; // FormatStore(storageId)（gphoto 宏只发 1 参）
    public static final int RC_OPERATION_NOT_SUPPORTED = 0x2005;

    public static final int RC_OK = 0x2001;
    public static final int RC_DEVICE_BUSY = 0x2019;
    public static final int RC_PARAMETER_NOT_SUPPORTED = 0x2006;
    public static final int RC_NIKON_OUT_OF_FOCUS = 0xA002;
    public static final int RC_NIKON_INVALID_STATUS = 0xA004;
    public static final int RC_NIKON_NOT_LIVE_VIEW = 0xA00B;

    // ---- 设备属性码（PTP 标准 + 本版用到的） ----
    public static final int PROP_BATTERY_LEVEL = 0x5001;
    public static final int PROP_WHITE_BALANCE = 0x5005;
    public static final int PROP_F_NUMBER      = 0x5007;
    public static final int PROP_FOCUS_MODE    = 0x500A;
    public static final int PROP_EXPOSURE_TIME = 0x500D;
    public static final int PROP_EXPOSURE_PROGRAM = 0x500E; // 拍摄模式 P/A/S/M
    public static final int PROP_EXPOSURE_INDEX   = 0x500F; // ISO
    public static final int PROP_EXPOSURE_BIAS    = 0x5010; // 曝光补偿 INT16 千分之一 EV
    public static final int PROP_STILL_CAPTURE_MODE = 0x5013; // 单拍/连拍（libgphoto2 ptp.h:1959 核实，非剩余张数）
    public static final int PROP_DATETIME = 0x5011; // 相机日期时间（STR，PTP 格式 yyyyMMdd'T'HHmmss）
    public static final int PROP_FOCUS_METERING = 0x501C; // 对焦区域/测光模式 FocusMeteringMode（libgphoto2 ptp.h:1968 核实；尼康 Z 取值 0x8010=单点 AF 等，见 CamProps.focusMeteringName）
    public static final int PROP_NIKON_LIVE_VIEW_STATUS = 0xD1A2; // UINT8，1=LiveView 中
    public static final int PROP_NIKON_LIVE_VIEW_IMAGE_SIZE = 0xD1AC; // UINT8 枚举 1=QVGA/2=VGA/3=XGA（libgphoto2 ptp.h:2643 + config.c nikon_liveviewsize 表核实；相机是否提供以其 desc 自报为准）

    // ---- PTP 数据类型（libgphoto2 ptp.h:1911-1935） ----
    public static final int DTC_INT8 = 0x0001, DTC_UINT8 = 0x0002;
    public static final int DTC_INT16 = 0x0003, DTC_UINT16 = 0x0004;
    public static final int DTC_INT32 = 0x0005, DTC_UINT32 = 0x0006;
    public static final int DTC_INT64 = 0x0007, DTC_UINT64 = 0x0008;
    public static final int DTC_STR = 0xFFFF;

    // ---- 事件码 ----
    public static final int EC_OBJECT_ADDED = 0x4002;
    public static final int EC_DEVICE_PROP_CHANGED = 0x4006;
    public static final int EC_CAPTURE_COMPLETE = 0x400D;

    // ---- 尼康私有操作码（libgphoto2 ptp.h 核实行号见各常量注释；禁止凭记忆改） ----
    public static final int NK_INITIATE_CAPTURE_SDRAM = 0x90C0; // ptp.h:581，1 参 0xFFFFFFFF
    public static final int NK_AF_DRIVE        = 0x90C1; // ptp.h:582 无参
    public static final int NK_CHANGE_CAMERA_MODE = 0x90C2; // ptp.h:583，1 参（1=控制模式）
    public static final int NK_DEVICE_READY    = 0x90C8; // ptp.h:589 无参；wait-busy 轮询用（library.c nikon_wait_busy）
    public static final int NK_START_LIVE_VIEW = 0x9201; // ptp.h:600 无参
    public static final int NK_END_LIVE_VIEW   = 0x9202; // ptp.h:601 无参
    public static final int NK_GET_LIVE_VIEW_IMG = 0x9203; // ptp.h:602 data-in；帧内扫描 FFD8 取 JPEG（library.c gp_camera_capture_preview）
    public static final int NK_CHANGE_AF_AREA  = 0x9205; // ptp.h:604，2 参 x/y（LV 图像像素坐标，config.c 原样透传 + N-Link v3.1.0 实证）
    public static final int NK_AF_DRIVE_CANCEL = 0x9206; // ptp.h:605 无参
    public static final int NK_CAPTURE_TO_MEDIA = 0x9207; // ptp.h:610/5139，2 参 (af?0xFFFFFFFE:0xFFFFFFFF, target card=0/sdram=1)；1 参版 ptp.h:5153
    public static final int NK_START_MOVIE_REC = 0x920A; // ptp.h:614 无参
    public static final int NK_END_MOVIE_REC   = 0x920B; // ptp.h:615 无参

    public static final String DEFAULT_HOST = "192.168.1.1";
    public static final int DEFAULT_PORT = 15740;

    /** PTP 层错误：携带响应码（IO 错误时为 -1） */
    public static class PtpException extends Exception {
        public final int responseCode;
        public PtpException(String msg, int rc) { super(msg); this.responseCode = rc; }
        public PtpException(String msg, Throwable t) { super(msg, t); this.responseCode = -1; }
    }

    public interface DataSink {
        void onTotal(long totalBytes);
        void onData(byte[] buf, int off, int len) throws IOException;
    }

    public interface Progress { void onProgress(long doneBytes); }

    public interface ConnectionListener { void onDisconnected(String reason); }

    /**
     * 相机事件回调（ObjectAdded/CaptureComplete/DevicePropChanged 等）。
     * 在事件线程上调用，只许做轻量通知（转发到自己的工作线程），不许在此直接发 PTP 命令：
     * 命令通道有独立串行锁，在事件线程里阻塞等锁会堵住心跳 Pong 回应。
     */
    public interface EventListener { void onEvent(int code, int[] params); }

    // ---- 数据集模型 ----
    public static class DeviceInfo {
        public String manufacturer = "", model = "", version = "", serial = "";
        public int functionalMode;
        public List<Integer> operationsSupported = new ArrayList<>();
        /** 相机自报支持的设备属性码全集（DeviceInfo 数据集原有字段，v0.5 起存下供参数面板全量枚举；解析位置不变、无协议行为变化） */
        public List<Integer> devicePropertiesSupported = new ArrayList<>();
    }

    public static class StorageInfo {
        public long maxCapacity, freeBytes;
        public int freeImages;
        public String description = "", volumeLabel = "";
    }

    public static class ObjectInfo {
        public int storageId, format, parent;
        public long size;
        public String filename = "", captureDate = "", modDate = "";
        public int imageWidth, imageHeight;
    }

    // ---- 状态 ----
    private final byte[] initiatorGuid;
    private final String initiatorName;
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;

    private Socket cmdSock, evtSock;
    private InputStream cmdIn, evtIn;
    private OutputStream cmdOut, evtOut;
    private Thread evtThread, heartbeatThread;
    private volatile boolean connected = false;
    private volatile boolean eventOk = false;
    private volatile boolean closing = false;
    private volatile CameraProfile profile;
    private volatile long lastEvtRxMs = 0;
    private volatile long lastCmdOkMs = 0;
    private volatile boolean cmdBusy = false;
    private volatile boolean keepAliveInFlight = false;
    private volatile EventListener eventListener;
    private final Object cmdLock = new Object();
    private int transactionId = 0;
    private long connectionNumber = -1;
    private String cameraName = "";
    private String cameraGuidHex = "";
    private int reconnectCount = 0;
    private volatile ConnectionListener connListener;

    // ---- v0.6.2 掉线取证（STA「点任何处退出」定性）：记录最后一个操作/响应码/IO 异常，markDead 时整行打出 ----
    private volatile boolean staSession = false; // 本次连接是否为 STA（network==null，连接至 PC）
    private volatile String lastOpDesc = "（无）";
    private volatile int lastOpRc = 0;
    private volatile String lastIoDetail = "（无）";
    private volatile int cmdSoTimeoutMs = 30000;

    /** 当前连接是否为 STA（连接至 PC）模式 */
    public boolean isStaSession() { return staSession; }

    /** 调节命令通道读超时（socket SO_TIMEOUT 本就是「无字节停顿」超时：每次读到数据即重新计） */
    public synchronized void setCmdSoTimeout(int ms) {
        cmdSoTimeoutMs = ms;
        if (cmdSock != null) {
            try { cmdSock.setSoTimeout(ms); } catch (Exception ignore) {}
        }
    }

    /** 常用操作码可读名（掉线取证/日志用；未列出的回退十六进制） */
    static String opNameOf(int op) {
        switch (op) {
            case OP_GET_DEVICE_INFO: return "GetDeviceInfo";
            case OP_OPEN_SESSION: return "OpenSession";
            case OP_CLOSE_SESSION: return "CloseSession";
            case OP_GET_STORAGE_IDS: return "GetStorageIDs";
            case OP_GET_STORAGE_INFO: return "GetStorageInfo";
            case OP_GET_OBJECT_HANDLES: return "GetObjectHandles";
            case OP_GET_OBJECT_INFO: return "GetObjectInfo";
            case OP_GET_OBJECT: return "GetObject";
            case OP_GET_THUMB: return "GetThumb";
            case OP_GET_PARTIAL_OBJECT: return "GetPartialObject";
            case OP_GET_DEVICE_PROP_VALUE: return "GetDevicePropValue";
            case OP_GET_DEVICE_PROP_DESC: return "GetDevicePropDesc";
            case OP_SET_DEVICE_PROP_VALUE: return "SetDevicePropValue";
            case OP_DELETE_OBJECT: return "DeleteObject";
            case OP_FORMAT_STORE: return "FormatStore";
            case OP_INITIATE_CAPTURE: return "InitiateCapture";
            default: return "op0x" + Integer.toHexString(op);
        }
    }

    public PtpIpClient(byte[] initiatorGuid, String initiatorName) {
        this.initiatorGuid = initiatorGuid != null && initiatorGuid.length == 16
                ? initiatorGuid.clone() : new byte[16];
        this.initiatorName = initiatorName != null ? initiatorName : "QingmuTuchuan";
    }

    public void setConnectionListener(ConnectionListener l) { connListener = l; }
    public void setEventListener(EventListener l) { eventListener = l; }
    public boolean isConnected() { return connected; }
    public boolean isEventChannelOk() { return eventOk; }
    public CameraProfile getProfile() { return profile; }

    /**
     * GetDeviceInfo 后由上层调入命中的机型 profile。
     * profile 声明事件通道不可靠时，主动关掉事件通道走纯命令通道轮询，避免被看门狗误杀。
     */
    public void applyProfile(CameraProfile p) {
        this.profile = p;
        if (p != null && !p.eventReliable && eventOk) {
            Diag.op("PTPIP", "profile[" + p.id + "] 声明事件通道不可靠，主动关闭事件通道");
            eventOk = false;
            if (evtSock != null) try { evtSock.close(); } catch (Exception ignore) {}
            evtSock = null; evtIn = null; evtOut = null;
            if (evtThread != null) { evtThread.interrupt(); evtThread = null; }
            if (heartbeatThread != null) { heartbeatThread.interrupt(); heartbeatThread = null; }
        }
    }
    public String getCameraName() { return cameraName; }
    public String getCameraGuidHex() { return cameraGuidHex; }
    public int getReconnectCount() { return reconnectCount; }

    // ================= 连接建立 =================

    public synchronized void connect(Network network) throws IOException {
        connect(network, host, port);
    }

    public synchronized void connect(Network network, String h, int p) throws IOException {
        closeQuietly();
        closing = false;
        staSession = (network == null);
        this.host = h; this.port = p;
        Diag.op("PTPIP", "连接命令通道 " + h + ":" + p + (network != null ? "（经相机热点 Network）" : "（默认网络）"));
        cmdSock = newSocket(network);
        cmdSock.connect(new InetSocketAddress(h, p), 8000);
        cmdSock.setTcpNoDelay(true);
        cmdSock.setSoTimeout(cmdSoTimeoutMs);
        cmdIn = cmdSock.getInputStream();
        cmdOut = cmdSock.getOutputStream();

        // --- InitCommandRequest ---
        byte[] nameUtf16 = utf16z(initiatorName);
        int len = 8 + 16 + nameUtf16.length + 4;
        byte[] req = new byte[len];
        Le.put32(req, 0, len);
        Le.put32(req, 4, PKT_INIT_CMD_REQ);
        System.arraycopy(initiatorGuid, 0, req, 8, 16);
        System.arraycopy(nameUtf16, 0, req, 24, nameUtf16.length);
        Le.put32(req, 24 + nameUtf16.length, 0x00010000); // 协议版本 1.0（minor u16 + major u16）
        writeAll(cmdOut, req);
        Diag.op("PTPIP", "已发 InitCommandRequest（name=" + initiatorName + "）");

        Packet ack = readPacket(cmdIn);
        if (ack.type == PKT_INIT_FAIL) {
            int reason = ack.payload.length >= 4 ? Le.get32(ack.payload, 0) : -1;
            // N-Link README 实证：STA 模式 reason=1 多为「本机未在相机端注册主机」或相机不在连接等待画面
            Diag.op("PTPIP", "InitFail reason=" + reason + "（STA 模式下常见于未注册主机/相机不在等待画面；AP 模式下多为已有客户端占用，先强停 SnapBridge）");
            throw new IOException("相机拒绝初始化 InitFail reason=" + reason);
        }
        if (ack.type != PKT_INIT_CMD_ACK || ack.payload.length < 20) {
            throw new IOException("InitCommandAck 异常 type=" + ack.type + " len=" + ack.payload.length);
        }
        connectionNumber = Le.get32u(ack.payload, 0);
        byte[] cg = new byte[16];
        System.arraycopy(ack.payload, 4, cg, 0, 16);
        cameraGuidHex = hex(cg);
        cameraName = readUtf16z(ack.payload, 20, ack.payload.length - 20);
        Diag.op("PTPIP", "InitCommandAck：connNum=" + connectionNumber
                + " 相机=" + cameraName + " guid=" + cameraGuidHex);

        // --- InitEventRequest（建不起来只降级，不判连接失败：部分机型/适配器事件通道不可靠） ---
        eventOk = false;
        try {
            Diag.op("PTPIP", "连接事件通道");
            // libgphoto2 ptpip.c 实证：事件通道 connect 被拒（相机未就绪）时重试 2 次再放弃；
            // 这里重试 3 次、间隔 300ms，仍失败才降级纯命令通道。
            IOException lastConnErr = null;
            for (int attempt = 1; attempt <= 3 && evtSock == null; attempt++) {
                Socket s = newSocket(network);
                try {
                    s.connect(new InetSocketAddress(h, p), 8000);
                    evtSock = s;
                } catch (IOException ce) {
                    lastConnErr = ce;
                    try { s.close(); } catch (Exception ignore) {}
                    if (attempt < 3) {
                        Diag.op("PTPIP", "事件通道 connect 第 " + attempt + " 次失败（" + ce.getMessage() + "），300ms 后重试");
                        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                    }
                }
            }
            if (evtSock == null) throw lastConnErr != null ? lastConnErr : new IOException("事件通道 connect 失败");
            evtSock.setTcpNoDelay(true);
            evtIn = evtSock.getInputStream();
            evtOut = evtSock.getOutputStream();
            byte[] ereq = new byte[12];
            Le.put32(ereq, 0, 12);
            Le.put32(ereq, 4, PKT_INIT_EVT_REQ);
            Le.put32(ereq, 8, (int) connectionNumber);
            writeAll(evtOut, ereq);
            Packet eack = readPacket(evtIn);
            if (eack.type == PKT_INIT_FAIL) {
                int ereason = eack.payload.length >= 4 ? Le.get32(eack.payload, 0) : -1;
                Diag.op("PTPIP", "事件通道 InitFail 原始 reason=" + ereason + "（payload " + eack.payload.length + "B）");
                throw new IOException("事件通道 InitFail reason=" + ereason);
            }
            if (eack.type != PKT_INIT_EVT_ACK) throw new IOException("InitEventAck 异常 type=" + eack.type);
            eventOk = true;
            Diag.op("PTPIP", "事件通道就绪");
        } catch (Exception e) {
            Diag.op("PTPIP", "事件通道建立失败，降级为纯命令通道（功能受限：无相机事件推送）：" + e.getMessage());
            if (evtSock != null) try { evtSock.close(); } catch (Exception ignore) {}
            evtSock = null; evtIn = null; evtOut = null;
        }
        lastEvtRxMs = System.currentTimeMillis();
        connected = true;
        if (eventOk) {
            startEventThread();
            startHeartbeat();
        }
    }

    /** 断线重连（指数退避 1/2/4/8/15s），成功后重建 PTP 会话 */
    public synchronized boolean reconnect(Network network) {
        int[] delays = {1000, 2000, 4000, 8000, 15000};
        for (int i = 0; i < delays.length; i++) {
            try { Thread.sleep(delays[i]); } catch (InterruptedException e) { return false; }
            reconnectCount++;
            Diag.op("PTPIP", "第 " + reconnectCount + " 次重连尝试（退避 " + delays[i] + "ms 后）");
            try {
                connect(network);
                openSession();
                Diag.op("PTPIP", "重连成功，会话已重建");
                return true;
            } catch (Exception e) {
                Diag.op("PTPIP", "重连失败：" + e.getMessage());
            }
        }
        return false;
    }

    public synchronized void disconnect() {
        closing = true;
        if (connected) {
            try { closeSession(); } catch (Exception ignore) {}
        }
        closeQuietly();
        connected = false;
        Diag.op("PTPIP", "已断开");
    }

    private void closeQuietly() {
        eventOk = false;
        for (Socket s : new Socket[]{cmdSock, evtSock}) {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
        cmdSock = evtSock = null;
        cmdIn = evtIn = null; cmdOut = evtOut = null;
        if (evtThread != null) { evtThread.interrupt(); evtThread = null; }
        if (heartbeatThread != null) { heartbeatThread.interrupt(); heartbeatThread = null; }
    }

    private Socket newSocket(Network network) throws IOException {
        if (network != null) return network.getSocketFactory().createSocket();
        return new Socket();
    }

    // ================= 事件通道 =================

    private void startEventThread() {
        final InputStream in = evtIn;
        evtThread = new Thread(() -> {
            try {
                while (!closing && connected) {
                    Packet pkt = readPacket(in);
                    lastEvtRxMs = System.currentTimeMillis();
                    switch (pkt.type) {
                        case PKT_EVENT: {
                            int code = pkt.payload.length >= 2 ? Le.get16(pkt.payload, 0) : 0;
                            long tid = pkt.payload.length >= 6 ? Le.get32u(pkt.payload, 2) : 0;
                            int np = pkt.payload.length >= 6 ? (pkt.payload.length - 6) / 4 : 0;
                            int[] eparams = new int[np];
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < np; i++) {
                                eparams[i] = Le.get32(pkt.payload, 6 + 4 * i);
                                if (sb.length() > 0) sb.append(',');
                                sb.append("0x").append(Integer.toHexString(eparams[i]));
                            }
                            Diag.op("EVENT", "相机事件 code=0x" + Integer.toHexString(code)
                                    + " tid=" + tid + (sb.length() > 0 ? " params=" + sb : ""));
                            EventListener el = eventListener;
                            if (el != null) {
                                try { el.onEvent(code, eparams); } catch (Exception cb) {
                                    Diag.op("EVENT", "事件回调异常：" + cb.getMessage());
                                }
                            }
                            break;
                        }
                        case PKT_PING:
                            sendEventPacket(PKT_PONG);
                            break;
                        case PKT_PONG:
                            break; // 心跳回应，静默
                        default:
                            Diag.op("EVENT", "事件通道未知包 type=" + pkt.type + " len=" + pkt.payload.length);
                    }
                }
            } catch (Exception e) {
                if (!closing && connected) {
                    Diag.op("EVENT", "事件通道断裂：" + e.getMessage());
                    markDead("事件通道断裂：" + e.getMessage());
                }
            }
        }, "ptpip-event");
        evtThread.setDaemon(true);
        evtThread.start();
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (!closing && connected) {
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                if (!connected || closing) return;
                try { sendEventPacket(PKT_PING); } catch (Exception e) {
                    Diag.op("EVENT", "心跳 Ping 发送失败：" + e.getMessage());
                    markDead("心跳失败");
                    return;
                }
                long silent = System.currentTimeMillis() - lastEvtRxMs;
                if (silent > 45000) {
                    Diag.op("EVENT", "事件通道静默 " + silent + "ms，判定连接已死");
                    markDead("事件通道静默超时");
                    return;
                }
                // 空闲保活（命令通道）：尼康机身有空闲计时器，长时间无事务会被关会话。
                // 同行实证：N-Link v3.0.0「空闲 10 秒即刷新机身计时器」。这里距上次成功事务
                // >10s 且命令通道空闲（非下载/取景事务中）时发一次轻量电量查询刷新计时器；
                // 放在短线程执行不堵心跳；失败只记诊断，IO 异常由 transact 既有判死逻辑处理。
                if (!cmdBusy && !keepAliveInFlight
                        && System.currentTimeMillis() - lastCmdOkMs > 10000) {
                    keepAliveInFlight = true;
                    new Thread(() -> {
                        try {
                            int b = getBatteryLevel();
                            if (b >= 0) Diag.op("PTPIP", "空闲保活：轻量查询成功（电量 " + b + "%），机身计时器已刷新");
                        } catch (Exception ignore) {
                        } finally { keepAliveInFlight = false; }
                    }, "ptpip-keepalive").start();
                }
            }
        }, "ptpip-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private synchronized void sendEventPacket(int type) throws IOException {
        if (evtOut == null) throw new IOException("事件通道未建立");
        byte[] b = new byte[8];
        Le.put32(b, 0, 8);
        Le.put32(b, 4, type);
        writeAll(evtOut, b);
    }

    /** 通道判死：关 socket 逼阻塞读抛错，通知上层走重连 */
    private void markDead(String reason) {
        if (!connected) return;
        connected = false;
        // v0.6.2 掉线取证：把模式/最后一个操作/最后响应码/socket 异常/事件通道静默时长一次打全，
        // STA 模式下用 [STA] 前缀，与 [CRASH]/[GUARD] 一起构成下轮定性证据链。
        long evtSilent = lastEvtRxMs > 0 ? (System.currentTimeMillis() - lastEvtRxMs) : -1;
        Diag.op(staSession ? "STA" : "PTPIP", "连接判死（模式=" + (staSession ? "STA 连接至PC" : "AP 相机热点")
                + "）：原因=" + reason
                + "｜最后一个操作=" + lastOpDesc
                + "｜最后响应码=0x" + Integer.toHexString(lastOpRc)
                + "｜最后 socket 异常=" + lastIoDetail
                + (evtSilent >= 0 ? "｜事件通道已静默 " + evtSilent + "ms" : ""));
        for (Socket s : new Socket[]{cmdSock, evtSock}) {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
        ConnectionListener l = connListener;
        if (l != null) {
            // 监听方在 PTP 线程上被回调，其异常绝不许反噬杀掉调用线程/进程
            try { l.onDisconnected(reason); } catch (Throwable t) {
                Diag.op("GUARD", "断线回调异常已拦截（App 未退出）：" + t);
            }
        }
    }

    // ================= 操作层 =================

    private static class Result {
        int responseCode; int[] respParams = new int[0]; byte[] data;
    }

    /** 通用事务：发请求 → 收数据阶段 → 收响应。dataSink 非空时数据流式回调，否则攒进 Result.data */
    private Result transact(int opCode, int[] params, DataSink sink) throws PtpException {
        return transactInternal(opCode, params, null, sink);
    }

    /**
     * 发数据阶段的事务（DataPhaseInfo=2）：发请求 → StartData/Data/EndData → 收响应。
     * libgphoto2 ptpip.c ptp_ptpip_senddata 实证：StartData 负载=事务ID u32 + 总长
     * （其实现写 u32 低半 + u32 清零，等价 u64 小端）；Data/EndData 负载=事务ID u32 + 数据，
     * 分块 ≤64KB，最后一块用 EndData。
     */
    private Result transactSendData(int opCode, int[] params, byte[] dataOut) throws PtpException {
        return transactInternal(opCode, params, dataOut != null ? dataOut : new byte[0], null);
    }

    private Result transactInternal(int opCode, int[] params, byte[] dataOut, DataSink sink) throws PtpException {
        synchronized (cmdLock) {
            if (!connected || cmdOut == null) throw new PtpException("未连接", new IOException("not connected"));
            // 事务 ID 由 transact 统一分配递增（v0.2 审计修复：原实现各调用方手动 ++，异常路径会漏增导致 tid 复用）
            int tid = transactionId++;
            cmdBusy = true;
            lastOpDesc = opNameOf(opCode) + "(0x" + Integer.toHexString(opCode) + ") tid=" + tid;
            int n = params == null ? 0 : params.length;
            byte[] req = new byte[18 + 4 * n];
            Le.put32(req, 0, req.length);
            Le.put32(req, 4, PKT_OP_REQUEST);
            Le.put32(req, 8, dataOut != null ? 2 : 1); // DataPhaseInfo：发送数据=2，接收或无数据=1（libgphoto2 ptpip.c 实证）
            Le.put16(req, 12, opCode);
            Le.put32(req, 14, tid);
            for (int i = 0; i < n; i++) Le.put32(req, 18 + 4 * i, params[i]);
            String opName = "0x" + Integer.toHexString(opCode);
            try {
                writeAll(cmdOut, req);
                if (dataOut != null) sendDataPhase(tid, dataOut);
                // 数据阶段 + 响应
                ByteSink mem = sink == null ? new ByteSink() : null;
                DataSink eff = sink != null ? sink : mem;
                int respCode = -1; int[] respParams = new int[0];
                boolean gotResp = false;
                while (!gotResp) {
                    Packet pkt = readPacket(cmdIn);
                    switch (pkt.type) {
                        case PKT_START_DATA: {
                            long total = pkt.payload.length >= 12 ? Le.get64(pkt.payload, 4) : -1;
                            eff.onTotal(total);
                            break;
                        }
                        case PKT_DATA:
                        case PKT_END_DATA: {
                            if (pkt.payload.length > 4) eff.onData(pkt.payload, 4, pkt.payload.length - 4);
                            break;
                        }
                        case PKT_OP_RESPONSE: {
                            respCode = pkt.payload.length >= 2 ? Le.get16(pkt.payload, 0) : -1;
                            int np = pkt.payload.length >= 6 ? (pkt.payload.length - 6) / 4 : 0;
                            respParams = new int[np];
                            for (int i = 0; i < np; i++) respParams[i] = Le.get32(pkt.payload, 6 + 4 * i);
                            gotResp = true;
                            break;
                        }
                        default:
                            Diag.op("PTP", opName + " 数据阶段收到意外包 type=" + pkt.type);
                    }
                }
                lastOpRc = respCode;
                if (respCode != RC_OK) {
                    Diag.op("PTP", opName + " tid=" + tid + " 响应码 0x" + Integer.toHexString(respCode) + "（非 OK）");
                    throw new PtpException("op " + opName + " rc=0x" + Integer.toHexString(respCode), respCode);
                }
                Result r = new Result();
                r.responseCode = respCode; r.respParams = respParams;
                r.data = mem != null ? mem.toByteArray() : null;
                lastCmdOkMs = System.currentTimeMillis();
                cmdBusy = false;
                return r;
            } catch (PtpException e) {
                cmdBusy = false;
                throw e;
            } catch (IOException e) {
                cmdBusy = false;
                lastIoDetail = e.getClass().getSimpleName() + "：" + e.getMessage();
                Diag.op("PTP", opName + " tid=" + tid + " IO 异常：" + e.getMessage());
                markDead("命令通道 IO 异常");
                throw new PtpException("op " + opName + " IO: " + e.getMessage(), e);
            }
        }
    }

    /** 发数据阶段：StartData(tid+u64 总长) → Data 分块(≤64KB) → EndData 收尾（gphoto ptp_ptpip_senddata 对齐） */
    private void sendDataPhase(int tid, byte[] data) throws IOException {
        byte[] start = new byte[20];
        Le.put32(start, 0, 20);
        Le.put32(start, 4, PKT_START_DATA);
        Le.put32(start, 8, tid);
        Le.put32(start, 12, data.length);      // 总长低 32 位
        Le.put32(start, 16, 0);                // 总长高 32 位（与 gphoto 写 u32+清零等价，合成为 u64）
        writeAll(cmdOut, start);
        int off = 0;
        do {
            int chunk = Math.min(65536, data.length - off);
            boolean last = off + chunk >= data.length;
            byte[] pkt = new byte[12 + chunk];
            Le.put32(pkt, 0, pkt.length);
            Le.put32(pkt, 4, last ? PKT_END_DATA : PKT_DATA);
            Le.put32(pkt, 8, tid);
            System.arraycopy(data, off, pkt, 12, chunk);
            writeAll(cmdOut, pkt);
            off += chunk;
        } while (off < data.length);
    }

    private static class ByteSink implements DataSink {
        private byte[] buf = new byte[8192]; private int size = 0;
        public void onTotal(long total) {}
        public void onData(byte[] b, int off, int len) {
            if (size + len > buf.length) {
                byte[] nb = new byte[Math.max(buf.length * 2, size + len)];
                System.arraycopy(buf, 0, nb, 0, size); buf = nb;
            }
            System.arraycopy(b, off, buf, size, len); size += len;
        }
        byte[] toByteArray() {
            byte[] r = new byte[size];
            System.arraycopy(buf, 0, r, 0, size);
            return r;
        }
    }

    // ---- 会话 ----
    /** OpenSession 固定用事务 0（PTP 惯例）；transact 内部自增，后续事务从 1 起 */
    public void openSession() throws PtpException {
        transactionId = 0;
        transact(OP_OPEN_SESSION, new int[]{1}, null);
        Diag.op("PTP", "OpenSession 成功（session=1）");
    }

    public void closeSession() throws PtpException {
        transact(OP_CLOSE_SESSION, null, null);
        Diag.op("PTP", "CloseSession 完成");
    }

    // ---- 设备/存储 ----
    public DeviceInfo getDeviceInfo() throws PtpException {
        Result r = transact(OP_GET_DEVICE_INFO, null, null);
        DeviceInfo di = new DeviceInfo();
        Cursor c = new Cursor(r.data);
        c.u16(); // StandardVersion
        c.u32(); // VendorExtensionID
        c.u16(); // VendorExtensionVersion
        c.str(); // VendorExtensionDesc
        di.functionalMode = c.u16();
        di.operationsSupported = c.u16Array();
        c.u16Array(); // events
        di.devicePropertiesSupported = c.u16Array(); // device properties（v0.5：原样存下供全量参数面板枚举）
        c.u16Array(); // capture formats
        c.u16Array(); // image formats
        di.manufacturer = c.str();
        di.model = c.str();
        di.version = c.str();
        di.serial = c.str();
        Diag.op("PTP", "GetDeviceInfo：厂商=" + di.manufacturer + " 型号=" + di.model
                + " 固件=" + di.version + " 序列号=" + di.serial);
        return di;
    }

    /** 电量（GetDevicePropValue 0x5001 → UINT8 百分比）；失败返回 -1，不抛 */
    public int getBatteryLevel() {
        try {
            Result r = transact(OP_GET_DEVICE_PROP_VALUE, new int[]{PROP_BATTERY_LEVEL}, null);
            if (r.data != null && r.data.length >= 1) return r.data[0] & 0xFF;
        } catch (Exception e) {
            Diag.op("PTP", "电量读取失败（忽略）：" + e.getMessage());
        }
        return -1;
    }

    // ================= v0.2 属性体系（GetDevicePropDesc / 类型化读写） =================

    /**
     * 类型化属性值（DevicePropValue）：数值型统一存 num（按位存，显示层按 datatype 解释符号），
     * 字符串存 str。编解码布局对齐 libgphoto2 ptp-pack.c 的 ptp_unpack_DPV。
     */
    public static class PropValue {
        public final int datatype;
        public long num;
        public String str;
        public PropValue(int datatype) { this.datatype = datatype; }
        public PropValue(int datatype, long num) { this.datatype = datatype; this.num = num; }

        public static int fixedSize(int dt) {
            switch (dt) {
                case DTC_INT8: case DTC_UINT8: return 1;
                case DTC_INT16: case DTC_UINT16: return 2;
                case DTC_INT32: case DTC_UINT32: return 4;
                case DTC_INT64: case DTC_UINT64: return 8;
                default: return -1; // STR 变长
            }
        }

        /** 解码 buf[off..] 写入 v，返回占用字节数；数据不足返回 -1 */
        static int decodeInto(PropValue v, byte[] buf, int off) {
            if (buf == null || off < 0 || off >= buf.length && v.datatype != DTC_STR) return -1;
            switch (v.datatype) {
                case DTC_INT8: v.num = buf[off]; return 1;
                case DTC_UINT8: v.num = buf[off] & 0xFF; return 1;
                case DTC_INT16: v.num = (short) Le.get16(buf, off); return 2;
                case DTC_UINT16: v.num = Le.get16(buf, off); return 2;
                case DTC_INT32: v.num = Le.get32(buf, off); return 4;
                case DTC_UINT32: v.num = Le.get32u(buf, off); return 4;
                case DTC_INT64: case DTC_UINT64: v.num = Le.get64(buf, off); return 8;
                case DTC_STR: {
                    if (off >= buf.length) { v.str = ""; return 1; }
                    int n = buf[off] & 0xFF;
                    if (n == 0) { v.str = ""; return 1; }
                    int bytes = Math.min((n - 1) * 2, Math.max(0, buf.length - off - 1));
                    v.str = new String(buf, off + 1, bytes, StandardCharsets.UTF_16LE);
                    return 1 + n * 2;
                }
                default: return -1;
            }
        }

        public byte[] encode() {
            switch (datatype) {
                case DTC_INT8: case DTC_UINT8: return new byte[]{(byte) num};
                case DTC_INT16: case DTC_UINT16: { byte[] b = new byte[2]; Le.put16(b, 0, (int) num); return b; }
                case DTC_INT32: case DTC_UINT32: { byte[] b = new byte[4]; Le.put32(b, 0, (int) num); return b; }
                case DTC_INT64: case DTC_UINT64: {
                    byte[] b = new byte[8]; Le.put32(b, 0, (int) num); Le.put32(b, 4, (int) (num >> 32)); return b;
                }
                case DTC_STR: {
                    String s = str != null ? str : "";
                    byte[] body = s.getBytes(StandardCharsets.UTF_16LE);
                    byte[] b = new byte[1 + body.length + 2];
                    b[0] = (byte) (s.length() + 1);
                    System.arraycopy(body, 0, b, 1, body.length);
                    return b;
                }
                default: return new byte[0];
            }
        }

        @Override public String toString() {
            return datatype == DTC_STR ? ("\"" + str + "\"") : String.valueOf(num);
        }
    }

    /** GetDevicePropDesc 解析结果（布局对齐 libgphoto2 ptp-pack.c ptp_unpack_DPD） */
    public static class DevicePropDesc {
        public int code, datatype, getSet, formFlag; // formFlag：0 无 / 1 范围 / 2 枚举
        public PropValue defVal, curVal;
        public PropValue rangeMin, rangeMax, rangeStep;
        public final List<PropValue> enumValues = new ArrayList<>();
        public boolean writable() { return getSet != 0; }
    }

    /** 读属性描述（取值集合/当前值/可写性全由相机自报）；失败抛 PtpException（带 rc） */
    public DevicePropDesc getDevicePropDesc(int code) throws PtpException {
        Result r = transact(OP_GET_DEVICE_PROP_DESC, new int[]{code}, null);
        Cursor c = new Cursor(r.data);
        DevicePropDesc d = new DevicePropDesc();
        d.code = c.u16();
        d.datatype = c.u16();
        d.getSet = c.u8();
        d.defVal = c.dpv(d.datatype);
        d.curVal = c.dpv(d.datatype);
        if (c.remaining() >= 1) {
            d.formFlag = c.u8();
            if (d.formFlag == 1) {
                d.rangeMin = c.dpv(d.datatype);
                d.rangeMax = c.dpv(d.datatype);
                d.rangeStep = c.dpv(d.datatype);
            } else if (d.formFlag == 2) {
                int n = c.u16();
                for (int i = 0; i < n; i++) {
                    PropValue v = c.dpv(d.datatype);
                    if (v == null) break;
                    d.enumValues.add(v);
                }
            }
        }
        Diag.op("PROP", "Desc 0x" + Integer.toHexString(code) + " type=0x" + Integer.toHexString(d.datatype)
                + " 可写=" + (d.getSet != 0) + " 当前=" + d.curVal
                + (d.formFlag == 2 ? " 枚举 " + d.enumValues.size() + " 项" : d.formFlag == 1 ? " 范围型" : " 无取值集"));
        return d;
    }

    /** 按已知 datatype 读当前属性值 */
    public PropValue getDevicePropValueTyped(int code, int datatype) throws PtpException {
        Result r = transact(OP_GET_DEVICE_PROP_VALUE, new int[]{code}, null);
        PropValue v = new PropValue(datatype);
        if (PropValue.decodeInto(v, r.data, 0) < 0) throw new PtpException("属性值解码失败 code=0x" + Integer.toHexString(code), -5);
        return v;
    }

    /** 写属性（发数据事务）；返回响应码，成功=0x2001。写入后应回读确认（上层做） */
    public int setDevicePropValue(int code, PropValue v) {
        long t0 = System.currentTimeMillis();
        try {
            transactSendData(OP_SET_DEVICE_PROP_VALUE, new int[]{code}, v.encode());
            Diag.op("PROP", "Set 0x" + Integer.toHexString(code) + "=" + v + " → rc=0x2001 耗时 "
                    + (System.currentTimeMillis() - t0) + "ms");
            return RC_OK;
        } catch (PtpException e) {
            Diag.op("PROP", "Set 0x" + Integer.toHexString(code) + "=" + v + " → rc=0x"
                    + Integer.toHexString(e.responseCode) + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            return e.responseCode;
        }
    }

    // ================= v0.2 尼康私有操作 =================

    /** 尼康无数据操作统一封装：逐个记诊断（名称/操作码/响应码/耗时），返回响应码（IO 错误=-1） */
    private int nikonOp(int op, int[] params, String name) {
        long t0 = System.currentTimeMillis();
        try {
            transact(op, params, null);
            Diag.op("NIKON", name + "(0x" + Integer.toHexString(op) + ") → rc=0x2001 耗时 "
                    + (System.currentTimeMillis() - t0) + "ms");
            return RC_OK;
        } catch (PtpException e) {
            Diag.op("NIKON", name + "(0x" + Integer.toHexString(op) + ") → rc=0x"
                    + Integer.toHexString(e.responseCode) + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            return e.responseCode;
        }
    }

    /** 安静版 DeviceReady（不逐次打日志，wait-busy 汇总一行） */
    private int deviceReadyQuiet() {
        try { transact(NK_DEVICE_READY, null, null); return RC_OK; }
        catch (PtpException e) { return e.responseCode; }
    }

    /** 轮询 DeviceReady(0x90C8) 直到相机不忙（对齐 libgphoto2 library.c nikon_wait_busy：忙=0x2019） */
    public int nikonWaitBusy(int timeoutMs) {
        long t0 = System.currentTimeMillis();
        int rc = RC_DEVICE_BUSY, tries = 0;
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            rc = deviceReadyQuiet();
            tries++;
            if (rc != RC_DEVICE_BUSY) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        Diag.op("NIKON", "WaitBusy 轮询 " + tries + " 次 → rc=0x" + Integer.toHexString(rc)
                + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
        return rc;
    }

    /**
     * 开 LiveView：先 ChangeCameraMode(1) 进控制模式（libgphoto2 library.c enable_liveview 前置动作，
     * 失败只记诊断不中断）→ StartLiveView(0x9201) → WaitBusy(2000ms) 等机身就绪。
     * 返回判定响应码：OK 或 DeviceBusy（可继续拉帧）视为可往下走，由调用方结合首帧结果决定。
     */
    public int nikonStartLiveView() {
        int cm = nikonOp(NK_CHANGE_CAMERA_MODE, new int[]{1}, "ChangeCameraMode(控制)");
        if (cm != RC_OK) Diag.op("NIKON", "ChangeCameraMode 未成功（rc=0x" + Integer.toHexString(cm) + "），继续尝试 StartLiveView");
        int rc = nikonOp(NK_START_LIVE_VIEW, null, "StartLiveView");
        if (rc == RC_OK || rc == RC_DEVICE_BUSY) {
            int w = nikonWaitBusy(2000);
            if (w == RC_OK) return RC_OK;
            return rc == RC_OK ? w : rc;
        }
        return rc;
    }

    public int nikonEndLiveView() { return nikonOp(NK_END_LIVE_VIEW, null, "EndLiveView"); }

    /** LiveView 一帧：原始数据 + JPEG 切片位置（帧头长度=head jpegOff，真机以此为准） */
    public static class LiveViewFrame {
        public byte[] data; public int jpegOff, jpegLen;
    }

    /**
     * 拉一帧 LiveView(0x9203)。JPEG 定位方式与 libgphoto2 完全一致：在整帧数据里扫描
     * SOI(0xFFD8) 起、EOI(0xFFD9) 止（其源码注释自承前置约 128B 帧头未解析 — FIXME）。
     * 找不到 JPEG 抛 PtpException(-6)；响应码非 OK 由 transact 抛（带 rc）。
     */
    public LiveViewFrame nikonGetLiveViewImage() throws PtpException {
        Result r = transact(NK_GET_LIVE_VIEW_IMG, null, null);
        byte[] d = r.data;
        if (d == null || d.length < 4) throw new PtpException("LiveView 帧为空", -6);
        int soi = -1;
        for (int i = 0; i + 1 < d.length; i++) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8) { soi = i; break; }
        }
        if (soi < 0) throw new PtpException("LiveView 帧内未找到 JPEG SOI（帧长 " + d.length + "）", -6);
        int eoi = -1;
        for (int i = d.length - 2; i > soi; i--) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD9) { eoi = i; break; }
        }
        LiveViewFrame f = new LiveViewFrame();
        f.data = d;
        f.jpegOff = soi;
        f.jpegLen = (eoi >= 0 ? eoi + 2 : d.length) - soi;
        return f;
    }

    /** 点按对焦第 1 步：设 AF 区域（x/y 为 LV 图像像素坐标） */
    public int nikonChangeAfArea(int x, int y) {
        return nikonOp(NK_CHANGE_AF_AREA, new int[]{x, y}, "ChangeAfArea(" + x + "," + y + ")");
    }

    /** 点按对焦第 2 步：驱动 AF（0x90C1）；未合焦相机回 0xA002（library.c config 段实证） */
    public int nikonAfDrive() { return nikonOp(NK_AF_DRIVE, null, "AfDrive"); }

    public int nikonAfDriveCancel() { return nikonOp(NK_AF_DRIVE_CANCEL, null, "AfDriveCancel"); }

    /**
     * 遥控拍照（存卡）：InitiateCaptureRecInMedia(0x9207)
     *  ① 2 参版 (af=0xFFFFFFFE 先 AF, target=0 存卡）— libgphoto2 ptp.h:5139 ptp_nikon_capture2；
     *  ② 相机回参数不支持(0x2006) 降级 1 参版 (0xFFFFFFFF) — ptp.h:5153（D3x 实证）；
     *  ③ 再失败降级标准 InitiateCapture(0x100E, storage=0, format=0)。
     * DeviceBusy 时先 WaitBusy(3000) 再整轮重试一次。返回最终响应码。
     */
    public int nikonCapture() {
        for (int round = 0; round < 2; round++) {
            int rc = nikonOp(NK_CAPTURE_TO_MEDIA, new int[]{0xFFFFFFFE, 0}, "CaptureRecInMedia(2参)");
            if (rc == RC_OK) return RC_OK;
            if (rc == RC_PARAMETER_NOT_SUPPORTED) {
                rc = nikonOp(NK_CAPTURE_TO_MEDIA, new int[]{0xFFFFFFFF}, "CaptureRecInMedia(1参)");
                if (rc == RC_OK) return RC_OK;
            }
            if (rc == RC_DEVICE_BUSY && round == 0) {
                nikonWaitBusy(3000);
                continue;
            }
            Diag.op("NIKON", "尼康媒体拍摄未成功（rc=0x" + Integer.toHexString(rc) + "），降级标准 InitiateCapture(0x100E)");
            return nikonOp(OP_INITIATE_CAPTURE, new int[]{0, 0}, "InitiateCapture(标准)");
        }
        return RC_DEVICE_BUSY;
    }

    public int nikonStartMovieRec() { return nikonOp(NK_START_MOVIE_REC, null, "StartMovieRecInCard"); }
    public int nikonEndMovieRec() { return nikonOp(NK_END_MOVIE_REC, null, "EndMovieRec"); }

    // ================= v0.3 相机管理（标准操作，libgphoto2 ptp.h/ptp.c 核实） =================

    /** 通用无数据操作封装（与 nikonOp 同口径：记诊断、返回响应码，IO 错误=-1） */
    private int ptpOp(int op, int[] params, String name) {
        long t0 = System.currentTimeMillis();
        try {
            transact(op, params, null);
            Diag.op("PTP", name + "(0x" + Integer.toHexString(op) + ") → rc=0x2001 耗时 "
                    + (System.currentTimeMillis() - t0) + "ms");
            return RC_OK;
        } catch (PtpException e) {
            Diag.op("PTP", name + "(0x" + Integer.toHexString(op) + ") → rc=0x"
                    + Integer.toHexString(e.responseCode) + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            return e.responseCode;
        }
    }

    /**
     * 删除相机卡上的单个对象：DeleteObject(0x100B)，双参数 (handle, objectFormatCode)，
     * 与 libgphoto2 ptp.c ptp_deleteobject 的 PTP_CNT_INIT(..., handle, ofc) 完全一致
     * （ofc 传对象实际格式码；未知时传 0=未指定格式）。不可逆，调用方必须先经用户确认。
     */
    public int deleteObject(int handle, int formatCode) {
        Diag.op("PTP", "DeleteObject 开始 handle=0x" + Integer.toHexString(handle)
                + " format=0x" + Integer.toHexString(formatCode));
        return ptpOp(OP_DELETE_OBJECT, new int[]{handle, formatCode}, "DeleteObject");
    }

    /**
     * 格式化存储：FormatStore(0x100F)，libgphoto2 ptp.h:4476 宏 ptp_formatstore 只发 1 个参数
     * (storageId)（PTP 规范的第二参 FilesystemFormat 可选，gphoto 不发 → 相机用默认格式）。
     * 高危不可逆：会清空该存储上的全部对象，调用方必须双重确认后才可调用。
     */
    public int formatStore(int storageId) {
        Diag.op("PTP", "⚠ FormatStore 开始 storageId=0x" + Integer.toHexString(storageId) + "（清空该存储全部对象）");
        int rc = ptpOp(OP_FORMAT_STORE, new int[]{storageId}, "FormatStore");
        Diag.op("PTP", "⚠ FormatStore 结束 rc=0x" + Integer.toHexString(rc));
        return rc;
    }

    public int[] getStorageIds() throws PtpException {
        Result r = transact(OP_GET_STORAGE_IDS, null, null);
        Cursor c = new Cursor(r.data);
        long n = c.u32();
        int[] ids = new int[(int) n];
        for (int i = 0; i < n; i++) ids[i] = c.s32();
        Diag.op("PTP", "GetStorageIDs：" + n + " 个存储");
        return ids;
    }

    public StorageInfo getStorageInfo(int storageId) throws PtpException {
        Result r = transact(OP_GET_STORAGE_INFO, new int[]{storageId}, null);
        Cursor c = new Cursor(r.data);
        c.u16(); c.u16(); c.u16();
        StorageInfo si = new StorageInfo();
        si.maxCapacity = c.u64();
        si.freeBytes = c.u64();
        si.freeImages = (int) c.u32();
        si.description = c.str();
        si.volumeLabel = c.str();
        Diag.op("PTP", "GetStorageInfo：" + si.description + " 总容量 " + si.maxCapacity
                + " 剩余 " + si.freeBytes);
        return si;
    }

    public int[] getObjectHandles(int storageId) throws PtpException {
        return getObjectHandles(storageId, 0, 0);
    }

    /**
     * v0.6.1：枚举参数可指定并全程打日志（存储/格式/父对象）——
     * Z 30 外部实测出现「指定存储枚举回 0」，上层会以全通配（storage=0xFFFFFFFF、format=0、parent=0）重试。
     * 失败时 PtpException 携带响应码，日志里带 rc。
     */
    public int[] getObjectHandles(int storageId, int format, int parent) throws PtpException {
        try {
            Result r = transact(OP_GET_OBJECT_HANDLES, new int[]{storageId, format, parent}, null);
            Cursor c = new Cursor(r.data);
            long n = c.u32();
            int[] hs = new int[(int) n];
            for (int i = 0; i < n; i++) hs[i] = c.s32();
            Diag.op("PTP", "GetObjectHandles(storage=0x" + Integer.toHexString(storageId)
                    + " format=0x" + Integer.toHexString(format) + " parent=0x" + Integer.toHexString(parent)
                    + ") rc=0x" + Integer.toHexString(r.responseCode) + "：共 " + n + " 个对象（原始句柄数）");
            return hs;
        } catch (PtpException e) {
            Diag.op("PTP", "GetObjectHandles(storage=0x" + Integer.toHexString(storageId)
                    + " format=0x" + Integer.toHexString(format) + " parent=0x" + Integer.toHexString(parent)
                    + ") 失败 rc=0x" + Integer.toHexString(e.responseCode) + "：" + e.getMessage());
            throw e;
        }
    }

    public ObjectInfo getObjectInfo(int handle) throws PtpException {
        Result r = transact(OP_GET_OBJECT_INFO, new int[]{handle}, null);
        Cursor c = new Cursor(r.data);
        ObjectInfo oi = new ObjectInfo();
        oi.storageId = c.s32();
        oi.format = c.u16();
        oi.size = c.u32();
        c.u16(); // thumb format
        c.u32(); // thumb size
        c.u32(); c.u32(); // thumb w/h
        oi.imageWidth = (int) c.u32();
        oi.imageHeight = (int) c.u32();
        c.u32(); // bit depth
        oi.parent = c.s32();
        c.u16(); c.u32(); c.u32(); // association type/desc/sequence
        oi.filename = c.str();
        oi.captureDate = c.str();
        oi.modDate = c.str();
        return oi;
    }

    public byte[] getThumb(int handle) throws PtpException {
        Result r = transact(OP_GET_THUMB, new int[]{handle}, null);
        return r.data != null ? r.data : new byte[0];
    }

    /** 下载续传事件超限（非 IO 断裂、非相机拒绝）：上层据此给中文失败结论，.part 保留待下次续 */
    public static final int RC_RESUME_EXHAUSTED = -6;
    /** 下载期读超时（无字节停顿口径，socket SO_TIMEOUT：每次读到数据重新计，不是总时长） */
    private static final int DL_STALL_TIMEOUT_MS = 20000;
    private static final int DL_MAX_RESUME_EVENTS = 40;

    /** 流式计数接收端：写出到 out、累计字节、记录 StartData 声明总量、按绝对偏移回调进度 */
    private static class StreamCounter implements DataSink {
        final OutputStream out; final Progress progress; final long base;
        long count = 0; long declared = -1;
        StreamCounter(OutputStream out, Progress progress, long base) {
            this.out = out; this.progress = progress; this.base = base;
        }
        public void onTotal(long total) { declared = total; }
        public void onData(byte[] buf, int off, int len) throws IOException {
            out.write(buf, off, len);
            count += len;
            if (progress != null) progress.onProgress(base + count);
        }
    }

    private static long effectiveExpected(long totalSize, long declared) {
        if (totalSize > 0) return totalSize;
        if (declared > 0 && declared < (1L << 62)) return declared;
        return -1;
    }

    private static int shrinkChunk(int chunk) {
        if (chunk > 1024 * 1024) return 1024 * 1024;
        if (chunk > 256 * 1024) return 256 * 1024;
        return 256 * 1024;
    }

    /**
     * 下载对象到 out（v0.6.2 断点续传型重写）。
     *
     * 背景实锤（Z 30 外部复测）：25.4MB 文件（期望 26,607,616 B）下载时相机在数据阶段
     * 提前收尾（Response 先到），旧实现不核对已收字节、把截断当成功返回，上层长度校验才发现
     * 只收到 11,469,206 B。新链路：
     *  1) startOffset=0 先试 GetObject 整流，但按 StartData 声明总量 / ObjectInfo 期望核对已收字节；
     *     提前收尾不判失败——记 [DL] 后转 GetPartialObject 从已收偏移续拉（通道仍同步，可同会话续）；
     *  2) GetPartialObject 分块拉取：短读（rc=OK 但字节少于请求）接受已到字节并推进偏移，
     *     分块自适应缩小 4MB → 1MB → 256KB；连续 0 字节按数据停顿处理（抛 IO 型异常走上层重连）；
     *  3) 读超时为 socket SO_TIMEOUT 的无字节停顿口径：下载期临时收紧为 20s，结束恢复；
     *  4) 续拉事件有上限（RC_RESUME_EXHAUSTED），全部续完才返回成功，最终长度仍由上层校验。
     * 相机拒绝 GetPartialObject（响应码非 OK / profile 禁用）照旧抛 PtpException，交上层降级整传；
     * IO 断裂抛 rc=-1，交上层重连后从 .part 续。
     */
    public void downloadObject(int handle, long totalSize, long startOffset,
                               OutputStream out, Progress progress) throws PtpException, IOException {
        int prevTimeout = cmdSoTimeoutMs;
        setCmdSoTimeout(DL_STALL_TIMEOUT_MS);
        try {
            long offset = startOffset > 0 ? startOffset : 0;
            long declared = -1;
            // ---- 阶段 1：整流（仅从 0 开始时） ----
            if (startOffset <= 0) {
                StreamCounter sink = new StreamCounter(out, progress, 0);
                transact(OP_GET_OBJECT, new int[]{handle}, sink);
                offset = sink.count;
                declared = sink.declared;
                long expected = effectiveExpected(totalSize, declared);
                if (expected < 0 || offset >= expected) return; // 完整，或无从核对（按整流结果交付）
                Diag.op("DL", "GetObject 整流提前收尾：已收 " + offset + " / 期望 " + expected
                        + "（相机数据阶段提前结束），转 GetPartialObject 续拉");
            }
            // ---- 阶段 2：GetPartialObject 续拉 ----
            if (profile != null && !profile.tryPartialObject) {
                Diag.op("PTP", "profile[" + profile.id + "] 禁用 GetPartialObject，交由上层降级整文件下载");
                throw new PtpException("profile 禁用 GetPartialObject", -4);
            }
            long expected = effectiveExpected(totalSize, declared);
            int chunk = 4 * 1024 * 1024;
            int resumeEvents = 0, zeroStreak = 0;
            while (true) {
                if (expected > 0 && offset >= expected) break;
                if (offset > 0xFFFFFFFFL) {
                    throw new PtpException("续传偏移超过 GetPartialObject 32 位上限 offset=" + offset, -2);
                }
                int want = expected > 0 ? (int) Math.min(chunk, expected - offset) : chunk;
                StreamCounter sink = new StreamCounter(out, progress, offset);
                transact(OP_GET_PARTIAL_OBJECT, new int[]{handle, (int) offset, want}, sink);
                long got = sink.count;
                if (got <= 0) {
                    zeroStreak++;
                    resumeEvents++;
                    int old = chunk; chunk = shrinkChunk(chunk);
                    Diag.op("DL", "续传 0 字节（连续第 " + zeroStreak + " 次）：offset=" + offset
                            + " 已收=" + offset + " 期望=" + expected + "，分块 " + old + "→" + chunk);
                    if (zeroStreak >= 3) {
                        throw new PtpException("GetPartialObject 连续 0 字节 @offset=" + offset,
                                new IOException("相机数据停顿"));
                    }
                    if (resumeEvents > DL_MAX_RESUME_EVENTS) {
                        throw new PtpException("续传事件超限（" + DL_MAX_RESUME_EVENTS + " 次） @offset=" + offset,
                                RC_RESUME_EXHAUSTED);
                    }
                    continue;
                }
                zeroStreak = 0;
                offset += got;
                Diag.op("DL", "续传块完成：已收=" + offset + " 期望=" + expected);
                if (got < want) {
                    if (expected <= 0) break; // 无期望口径：短读即数据结束
                    resumeEvents++;
                    int old = chunk; chunk = shrinkChunk(chunk);
                    Diag.op("DL", "续传：短读 " + got + "/" + want + "，已收=" + offset + " 期望=" + expected
                            + "，分块 " + old + "→" + chunk);
                    if (resumeEvents > DL_MAX_RESUME_EVENTS) {
                        throw new PtpException("续传事件超限（" + DL_MAX_RESUME_EVENTS + " 次） @offset=" + offset,
                                RC_RESUME_EXHAUSTED);
                    }
                }
            }
            if (expected > 0 && offset != expected) {
                throw new PtpException("长度仍不完整：已收 " + offset + " 期望 " + expected, RC_RESUME_EXHAUSTED);
            }
        } finally {
            setCmdSoTimeout(prevTimeout);
        }
    }

    // ================= 包 IO =================

    private static class Packet { int type; byte[] payload; }

    private static Packet readPacket(InputStream in) throws IOException {
        byte[] hdr = new byte[8];
        readFully(in, hdr, 0, 8);
        int len = Le.get32(hdr, 0);
        int type = Le.get32(hdr, 4);
        if (len < 8 || len > 256 * 1024 * 1024) throw new IOException("包长度异常 len=" + len + " type=" + type);
        byte[] payload = new byte[len - 8];
        if (payload.length > 0) readFully(in, payload, 0, payload.length);
        Packet p = new Packet(); p.type = type; p.payload = payload;
        return p;
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(buf, off + n, len - n);
            if (r < 0) throw new EOFException("流结束（读 " + n + "/" + len + "）");
            n += r;
        }
    }

    private static void writeAll(OutputStream out, byte[] b) throws IOException {
        out.write(b); out.flush();
    }

    private static byte[] utf16z(String s) {
        byte[] body = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] r = new byte[body.length + 2];
        System.arraycopy(body, 0, r, 0, body.length);
        return r;
    }

    private static String readUtf16z(byte[] buf, int off, int len) {
        int chars = 0;
        while (off + chars * 2 + 1 < buf.length && chars * 2 < len) {
            if (Le.get16(buf, off + chars * 2) == 0) break;
            chars++;
        }
        return new String(buf, off, chars * 2, StandardCharsets.UTF_16LE);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    // ---- 小端读写 ----
    static final class Le {
        static int get16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
        static int get32(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
        }
        static long get32u(byte[] b, int o) { return get32(b, o) & 0xFFFFFFFFL; }
        static long get64(byte[] b, int o) { return get32u(b, o) | (get32u(b, o + 4) << 32); }
        static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
        static void put32(byte[] b, int o, int v) {
            b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
        }
    }

    /** PTP 数据集游标解析（字符串=1 字节字符数含结尾 0 + UTF-16LE） */
    static final class Cursor {
        private final byte[] d; private int pos = 0;
        Cursor(byte[] d) { this.d = d != null ? d : new byte[0]; }
        int u8() { return pos < d.length ? d[pos++] & 0xFF : 0; }
        int u16() { int v = pos + 1 < d.length ? Le.get16(d, pos) : 0; pos += 2; return v; }
        int s32() { int v = pos + 3 < d.length ? Le.get32(d, pos) : 0; pos += 4; return v; }
        long u32() { return s32() & 0xFFFFFFFFL; }
        long u64() { long v = pos + 7 < d.length ? Le.get64(d, pos) : 0; pos += 8; return v; }
        String str() {
            int n = u8();
            if (n == 0) return "";
            int bytes = (n - 1) * 2;
            if (pos + bytes > d.length) bytes = Math.max(0, d.length - pos);
            String s = new String(d, pos, bytes, StandardCharsets.UTF_16LE);
            pos += n * 2;
            return s;
        }
        List<Integer> u16Array() {
            long n = u32();
            List<Integer> r = new ArrayList<>();
            for (long i = 0; i < n; i++) r.add(u16());
            return r;
        }
        int remaining() { return d.length - pos; }
        /** 按 datatype 解一个属性值并推进游标；失败返回 null */
        PropValue dpv(int datatype) {
            PropValue v = new PropValue(datatype);
            int n = PropValue.decodeInto(v, d, pos);
            if (n < 0) return null;
            pos += n;
            return v;
        }
    }
}
