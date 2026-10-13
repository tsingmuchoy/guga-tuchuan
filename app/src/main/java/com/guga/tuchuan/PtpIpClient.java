package com.guga.tuchuan;

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

    public static final int RC_OK = 0x2001;
    public static final int PROP_BATTERY_LEVEL = 0x5001;

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

    // ---- 数据集模型 ----
    public static class DeviceInfo {
        public String manufacturer = "", model = "", version = "", serial = "";
        public int functionalMode;
        public List<Integer> operationsSupported = new ArrayList<>();
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
    private final Object cmdLock = new Object();
    private int transactionId = 0;
    private long connectionNumber = -1;
    private String cameraName = "";
    private String cameraGuidHex = "";
    private int reconnectCount = 0;
    private volatile ConnectionListener connListener;

    public PtpIpClient(byte[] initiatorGuid, String initiatorName) {
        this.initiatorGuid = initiatorGuid != null && initiatorGuid.length == 16
                ? initiatorGuid.clone() : new byte[16];
        this.initiatorName = initiatorName != null ? initiatorName : "GugaTuchuan";
    }

    public void setConnectionListener(ConnectionListener l) { connListener = l; }
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
        this.host = h; this.port = p;
        Diag.op("PTPIP", "连接命令通道 " + h + ":" + p + (network != null ? "（经相机热点 Network）" : "（默认网络）"));
        cmdSock = newSocket(network);
        cmdSock.connect(new InetSocketAddress(h, p), 8000);
        cmdSock.setTcpNoDelay(true);
        cmdSock.setSoTimeout(30000);
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
            evtSock = newSocket(network);
            evtSock.connect(new InetSocketAddress(h, p), 8000);
            evtSock.setTcpNoDelay(true);
            evtIn = evtSock.getInputStream();
            evtOut = evtSock.getOutputStream();
            byte[] ereq = new byte[12];
            Le.put32(ereq, 0, 12);
            Le.put32(ereq, 4, PKT_INIT_EVT_REQ);
            Le.put32(ereq, 8, (int) connectionNumber);
            writeAll(evtOut, ereq);
            Packet eack = readPacket(evtIn);
            if (eack.type == PKT_INIT_FAIL) throw new IOException("事件通道 InitFail");
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
                            StringBuilder sb = new StringBuilder();
                            for (int off = 6; off + 4 <= pkt.payload.length; off += 4) {
                                if (sb.length() > 0) sb.append(',');
                                sb.append("0x").append(Integer.toHexString(Le.get32(pkt.payload, off)));
                            }
                            Diag.op("EVENT", "相机事件 code=0x" + Integer.toHexString(code)
                                    + " tid=" + tid + (sb.length() > 0 ? " params=" + sb : ""));
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
        for (Socket s : new Socket[]{cmdSock, evtSock}) {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
        ConnectionListener l = connListener;
        if (l != null) l.onDisconnected(reason);
    }

    // ================= 操作层 =================

    private static class Result {
        int responseCode; int[] respParams = new int[0]; byte[] data;
    }

    /** 通用事务：发请求 → 收数据阶段 → 收响应。dataSink 非空时数据流式回调，否则攒进 Result.data */
    private Result transact(int opCode, int[] params, DataSink sink) throws PtpException {
        synchronized (cmdLock) {
            if (!connected || cmdOut == null) throw new PtpException("未连接", new IOException("not connected"));
            int tid = transactionId;
            int n = params == null ? 0 : params.length;
            byte[] req = new byte[18 + 4 * n];
            Le.put32(req, 0, req.length);
            Le.put32(req, 4, PKT_OP_REQUEST);
            Le.put32(req, 8, 1); // DataPhaseInfo：接收数据或无数据 = 1（libgphoto2 实证）
            Le.put16(req, 12, opCode);
            Le.put32(req, 14, tid);
            for (int i = 0; i < n; i++) Le.put32(req, 18 + 4 * i, params[i]);
            String opName = "0x" + Integer.toHexString(opCode);
            try {
                writeAll(cmdOut, req);
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
                if (respCode != RC_OK) {
                    Diag.op("PTP", opName + " tid=" + tid + " 响应码 0x" + Integer.toHexString(respCode) + "（非 OK）");
                    throw new PtpException("op " + opName + " rc=0x" + Integer.toHexString(respCode), respCode);
                }
                Result r = new Result();
                r.responseCode = respCode; r.respParams = respParams;
                r.data = mem != null ? mem.toByteArray() : null;
                return r;
            } catch (PtpException e) {
                throw e;
            } catch (IOException e) {
                Diag.op("PTP", opName + " tid=" + tid + " IO 异常：" + e.getMessage());
                markDead("命令通道 IO 异常");
                throw new PtpException("op " + opName + " IO: " + e.getMessage(), e);
            }
        }
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
    /** OpenSession 固定用事务 0（PTP 惯例），之后自增 */
    public void openSession() throws PtpException {
        transactionId = 0;
        transact(OP_OPEN_SESSION, new int[]{1}, null);
        transactionId = 1;
        Diag.op("PTP", "OpenSession 成功（session=1）");
    }

    public void closeSession() throws PtpException {
        transact(OP_CLOSE_SESSION, null, null);
        Diag.op("PTP", "CloseSession 完成");
    }

    // ---- 设备/存储 ----
    public DeviceInfo getDeviceInfo() throws PtpException {
        Result r = transact(OP_GET_DEVICE_INFO, null, null);
        transactionId++;
        DeviceInfo di = new DeviceInfo();
        Cursor c = new Cursor(r.data);
        c.u16(); // StandardVersion
        c.u32(); // VendorExtensionID
        c.u16(); // VendorExtensionVersion
        c.str(); // VendorExtensionDesc
        di.functionalMode = c.u16();
        di.operationsSupported = c.u16Array();
        c.u16Array(); // events
        c.u16Array(); // device properties
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
            transactionId++;
            if (r.data != null && r.data.length >= 1) return r.data[0] & 0xFF;
        } catch (Exception e) {
            Diag.op("PTP", "电量读取失败（忽略）：" + e.getMessage());
        }
        return -1;
    }

    public int[] getStorageIds() throws PtpException {
        Result r = transact(OP_GET_STORAGE_IDS, null, null);
        transactionId++;
        Cursor c = new Cursor(r.data);
        long n = c.u32();
        int[] ids = new int[(int) n];
        for (int i = 0; i < n; i++) ids[i] = c.s32();
        Diag.op("PTP", "GetStorageIDs：" + n + " 个存储");
        return ids;
    }

    public StorageInfo getStorageInfo(int storageId) throws PtpException {
        Result r = transact(OP_GET_STORAGE_INFO, new int[]{storageId}, null);
        transactionId++;
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
        Result r = transact(OP_GET_OBJECT_HANDLES, new int[]{storageId, 0, 0}, null);
        transactionId++;
        Cursor c = new Cursor(r.data);
        long n = c.u32();
        int[] hs = new int[(int) n];
        for (int i = 0; i < n; i++) hs[i] = c.s32();
        Diag.op("PTP", "GetObjectHandles：共 " + n + " 个对象");
        return hs;
    }

    public ObjectInfo getObjectInfo(int handle) throws PtpException {
        Result r = transact(OP_GET_OBJECT_INFO, new int[]{handle}, null);
        transactionId++;
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
        transactionId++;
        return r.data != null ? r.data : new byte[0];
    }

    /**
     * 下载对象到 out。
     * startOffset=0 → GetObject 整流；>0 → GetPartialObject 分块续传（每块 4MB）。
     * 相机不支持 GetPartialObject（响应码非 OK）会抛 PtpException，由上层降级整文件重下。
     */
    public void downloadObject(int handle, long totalSize, long startOffset,
                               OutputStream out, Progress progress) throws PtpException, IOException {
        if (startOffset <= 0) {
            final long[] done = {0};
            transact(OP_GET_OBJECT, new int[]{handle}, new DataSink() {
                public void onTotal(long total) {}
                public void onData(byte[] buf, int off, int len) throws IOException {
                    out.write(buf, off, len);
                    done[0] += len;
                    if (progress != null) progress.onProgress(done[0]);
                }
            });
            transactionId++;
            return;
        }
        if (startOffset > 0 && profile != null && !profile.tryPartialObject) {
            Diag.op("PTP", "profile[" + profile.id + "] 禁用 GetPartialObject，交由上层降级整文件下载");
            throw new PtpException("profile 禁用 GetPartialObject", -4);
        }
        long offset = startOffset;
        final long base = startOffset;
        final int CHUNK = 4 * 1024 * 1024;
        while (offset < totalSize) {
            if (offset > 0xFFFFFFFFL) {
                throw new PtpException("续传偏移超过 GetPartialObject 32 位上限 offset=" + offset, -2);
            }
            int want = (int) Math.min(CHUNK, totalSize - offset);
            final long[] got = {0};
            final long offSnapshot = offset;
            transact(OP_GET_PARTIAL_OBJECT, new int[]{handle, (int) offset, want}, new DataSink() {
                public void onTotal(long total) {}
                public void onData(byte[] buf, int o, int len) throws IOException {
                    out.write(buf, o, len);
                    got[0] += len;
                    if (progress != null) progress.onProgress(base + (offSnapshot - base) + got[0]);
                }
            });
            transactionId++;
            if (got[0] <= 0) throw new PtpException("GetPartialObject 返回 0 字节 @offset=" + offset, -3);
            offset += got[0];
            Diag.op("PTP", "续传块完成 offset=" + offset + "/" + totalSize);
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
    }
}
