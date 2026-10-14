package com.qingmu.tuchuan;

/** 照片墙条目（由 GetObjectInfo 填充） */
public class PhotoItem {
    public final int handle;
    public final PtpIpClient.ObjectInfo info;
    public boolean selected = false;
    public boolean downloaded = false;
    /** v0.6.1：GetObjectInfo 失败的占位条目（句柄真实存在、信息读不到）——列表数量与相机一致，缩略图/下载给中文失败提示 */
    public boolean infoFailed = false;
    /** v0.6.1：占位条目的信息读取失败响应码（进提示与诊断） */
    public int infoFailRc = -1;

    public PhotoItem(int handle, PtpIpClient.ObjectInfo info) {
        this.handle = handle; this.info = info;
    }

    public String filename() { return info != null && info.filename != null ? info.filename : ("0x" + Integer.toHexString(handle)); }

    public long size() { return info != null ? info.size : 0; }

    public enum Kind { JPG, NEF, MP4, OTHER }

    /** 格式徽标分类：以文件名扩展名为准（PTP format code 对 NEF 各机不一致，扩展名最可靠） */
    public Kind kind() { return kindOf(filename()); }

    /** 静态版（只有文件名时用：自动回传判定、事件流） */
    public static Kind kindOf(String filename) {
        String f = filename == null ? "" : filename.toUpperCase();
        if (f.endsWith(".JPG") || f.endsWith(".JPEG")) return Kind.JPG;
        if (f.endsWith(".NEF") || f.endsWith(".NRW")) return Kind.NEF;
        if (f.endsWith(".MP4") || f.endsWith(".MOV")) return Kind.MP4;
        return Kind.OTHER;
    }

    /** 去扩展名的基名（大写）：RAW+JPG 配对按同基名判定（尼康同一次拍摄同名 NEF+JPG） */
    public static String baseName(String filename) {
        String f = filename == null ? "" : filename;
        int dot = f.lastIndexOf('.');
        return (dot > 0 ? f.substring(0, dot) : f).toUpperCase();
    }

    public String baseName() { return baseName(filename()); }

    /** 拍摄日期键 yyyyMMdd（captureDate 原始串 "yyyyMMdd'T'HHmmss" 前 8 位）；无日期返回 "" */
    public String dateKey() {
        String d = info != null ? info.captureDate : null;
        return (d != null && d.length() >= 8) ? d.substring(0, 8) : "";
    }

    /** PTP 拍摄日期串 "yyyyMMdd'T'HHmmss"，可直接字典序比较；空串排最后 */
    public String sortKey() {
        String d = info != null ? info.captureDate : null;
        return (d != null && d.length() > 0) ? d : filename();
    }

    public static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
