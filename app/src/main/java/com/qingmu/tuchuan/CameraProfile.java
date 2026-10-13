package com.qingmu.tuchuan;

/**
 * 机型 profile 表（集中一处，按 GetDeviceInfo 的 Model 字符串包含匹配）。
 *
 * 原则：协议层机型无关，未知机型一律走 DEFAULT 标准档并允许连接，绝不白名单拦截。
 * 字段：
 *  - tryPartialObject：是否尝试 GetPartialObject 断点续传（报错/禁用都会降级整文件 GetObject）
 *  - eventReliable：事件通道是否可靠；不可靠则主动关事件通道、仅命令通道轮询
 *  - thumbFallback：缩略图取不到时的降级方式（见 ThumbFallback）
 *  - note：会话特殊说明，进诊断日志 + 关于页
 * 差异先按公开常识填，真机数据回来再修；命中哪张 profile 必打诊断日志。
 */
public final class CameraProfile {

    public enum ThumbFallback {
        /** 占位矢量图 + 文件名，滚动回来可自动重试 */
        PLACEHOLDER_RETRY,
        /** 占位矢量图 + 文件名，本次会话不再自动重试（缩略图通道特别弱的机型） */
        PLACEHOLDER_ONCE
    }

    /**
     * v0.2 遥控等级：
     *  FULL —— 状态面板 + LiveView + 点按对焦 + 快门 + 录像 + 参数调节全开尝试（Z 系）。
     *  BASIC —— 默认仅状态面板（只读）+ 遥控快门；其余遥控功能标注「未验证」，
     *           用户在诊断页开「实验开关」后才放行尝试（老单反/WU/Coolpix/未知机型）。
     * 任一遥控操作失败只降级该功能本身，不影响图传主链路。
     */
    public enum RemoteLevel { FULL, BASIC }

    public final String id;
    public final boolean tryPartialObject;
    public final boolean eventReliable;
    public final ThumbFallback thumbFallback;
    public final RemoteLevel remote;
    public final String note;

    private CameraProfile(String id, boolean partial, boolean evt, ThumbFallback tf, RemoteLevel remote, String note) {
        this.id = id; this.tryPartialObject = partial; this.eventReliable = evt;
        this.thumbFallback = tf; this.remote = remote; this.note = note;
    }

    public static final CameraProfile DEFAULT = new CameraProfile(
            "DEFAULT", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.BASIC,
            "标准 PTP/IP 默认档：未知机型按规范直连，行为以真机日志为准");

    public static final CameraProfile Z_FC = new CameraProfile(
            "Z_FC", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.FULL,
            "首测机型。相机 AP「建立 Wi-Fi 连接」或「连接至 PC」均可；联调时先强停 SnapBridge 防抢连");

    public static final CameraProfile Z_50_30 = new CameraProfile(
            "Z_50_30", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.FULL,
            "Z 50/Z 30 等与 Z fc 同代机型，入口相同，标准协议兼容、待真机验证");

    public static final CameraProfile Z_FULLFRAME = new CameraProfile(
            "Z_FULLFRAME", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.FULL,
            "Z 5/Z 6/Z 7（含 II 代）与 Z 8/Z 9：PTP/IP 成熟，标准协议兼容、待真机验证");

    public static final CameraProfile Z_GENERIC = new CameraProfile(
            "Z_GENERIC", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.FULL,
            "Z 系列通用档（Z f、Z 50II、Z 6III、Z R 等）：菜单多在「网络菜单」，标准协议兼容、待真机验证");

    public static final CameraProfile DSLR_850_780 = new CameraProfile(
            "DSLR_WIFI_850_780", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.BASIC,
            "D850/D780 内置 Wi-Fi：「连接至智能设备」或「连接至 PC」入口，标准协议兼容、待真机验证");

    public static final CameraProfile DSLR_MID = new CameraProfile(
            "DSLR_WIFI_MID", true, true, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.BASIC,
            "D7500/D500/D750/D7200 等内置 Wi-Fi 单反：相机作 AP（192.168.1.1:15740）；D750 Wi-Fi 较弱、距离敏感");

    public static final CameraProfile WU_ADAPTER = new CameraProfile(
            "WU_ADAPTER", false, false, ThumbFallback.PLACEHOLDER_ONCE, RemoteLevel.BASIC,
            "经 WU-1a/1b 无线适配器：带宽低、事件通道不可靠，禁用 PartialObject 整文件传，缩略图只试一次");

    public static final CameraProfile COOLPIX_WIFI = new CameraProfile(
            "COOLPIX_WIFI", true, false, ThumbFallback.PLACEHOLDER_RETRY, RemoteLevel.BASIC,
            "Coolpix Wi-Fi 机型：PTP/IP 实现较简，事件通道按不可靠处理，标准协议兼容、待真机验证");

    /** 按 Model 匹配；wuHint=true（用户勾选经 WU 适配器）时无条件优先 WU 档 */
    public static CameraProfile match(String model, boolean wuHint) {
        String m = model == null ? "" : model.trim();
        CameraProfile hit;
        if (wuHint) {
            hit = WU_ADAPTER;
        } else if (contains(m, "Z fc")) {
            hit = Z_FC;
        } else if (contains(m, "Z 50") || contains(m, "Z 30")) {
            hit = Z_50_30;
        } else if (contains(m, "Z 5") || contains(m, "Z 6") || contains(m, "Z 7")
                || contains(m, "Z 8") || contains(m, "Z 9")) {
            hit = Z_FULLFRAME;
        } else if (looksLikeZ(m)) {
            hit = Z_GENERIC;
        } else if (contains(m, "D850") || contains(m, "D780")) {
            hit = DSLR_850_780;
        } else if (contains(m, "D7500") || contains(m, "D500") || contains(m, "D750")
                || contains(m, "D7200") || contains(m, "D5600") || contains(m, "D5300")) {
            hit = DSLR_MID;
        } else if (contains(m, "COOLPIX")) {
            hit = COOLPIX_WIFI;
        } else if (contains(m, "D3200") || contains(m, "D3300") || contains(m, "D5200")
                || contains(m, "D7000") || contains(m, "D600") || contains(m, "D610")) {
            hit = WU_ADAPTER; // 典型 WU-1a 搭配机型（无内置 Wi-Fi、有 WU 接口）
        } else {
            hit = DEFAULT;
        }
        Diag.op("PROFILE", "Model=\"" + m + "\"" + (wuHint ? "（用户勾选 WU 适配器）" : "")
                + " → 命中 profile[" + hit.id + "]：" + hit.note);
        return hit;
    }

    private static boolean contains(String s, String sub) {
        return s != null && s.toUpperCase().contains(sub.toUpperCase());
    }

    private static boolean looksLikeZ(String s) {
        // 尼康机身 Model 常见形如 "NIKON Z 6_2"、"Z 9"、"NIKON Z f"
        String t = s == null ? "" : s.toUpperCase();
        return t.contains(" Z ") || t.startsWith("Z ") || t.contains(" Z");
    }
}
