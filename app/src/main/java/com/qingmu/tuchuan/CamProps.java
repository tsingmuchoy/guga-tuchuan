package com.qingmu.tuchuan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 相机属性元数据表 + 显示格式化（v0.2 状态面板/参数调节共用）。
 *
 * 诚实原则：数值与取值集合一律以相机 GetDevicePropDesc 自报为准，这里只做显示层格式化；
 * 语义名称只给 PTP 规范明确定义且无歧义的（曝光程序 0x500E、连拍模式 0x5013 的标准值），
 * 白平衡/对焦模式等各厂枚举值域不完全一致，只显示原始数值，不编造名称。
 * 格式化依据：光圈 UINT16=值/100（PTP FNumber 定义）、快门 UINT32=1/10000 秒单位、
 * 曝光补偿 INT16=千分之一 EV（均为 PTP 规范对 DeviceProp 的单位定义）。
 */
public final class CamProps {

    public static class Def {
        public final int code;
        public final String name;
        public final boolean adjustable; // true=进参数调节区（仍须相机 desc 说可写才放行）
        Def(int code, String name, boolean adjustable) {
            this.code = code; this.name = name; this.adjustable = adjustable;
        }
    }

    /** 状态面板 + 调节区的属性顺序表 */
    public static final List<Def> DEFS = new ArrayList<>();
    static {
        DEFS.add(new Def(PtpIpClient.PROP_EXPOSURE_PROGRAM, "拍摄模式", false));
        DEFS.add(new Def(PtpIpClient.PROP_F_NUMBER, "光圈", true));
        DEFS.add(new Def(PtpIpClient.PROP_EXPOSURE_TIME, "快门", true));
        DEFS.add(new Def(PtpIpClient.PROP_EXPOSURE_INDEX, "ISO", true));
        DEFS.add(new Def(PtpIpClient.PROP_EXPOSURE_BIAS, "曝光补偿", true));
        DEFS.add(new Def(PtpIpClient.PROP_WHITE_BALANCE, "白平衡", true));
        DEFS.add(new Def(PtpIpClient.PROP_FOCUS_MODE, "对焦模式", false));
        DEFS.add(new Def(PtpIpClient.PROP_STILL_CAPTURE_MODE, "拍摄驱动", false));
    }

    public static Def byCode(int code) {
        for (Def d : DEFS) if (d.code == code) return d;
        return null;
    }

    /** 把相机自报的属性值格式化为显示文本；无法语义化的枚举只给原值 */
    public static String format(int code, PtpIpClient.PropValue v) {
        if (v == null) return "—";
        if (v.datatype == PtpIpClient.DTC_STR) return v.str != null ? v.str : "—";
        long n = v.num;
        switch (code) {
            case PtpIpClient.PROP_F_NUMBER: {
                if (n <= 0) return "—";
                double f = n / 100.0;
                return "f/" + trimNum(f);
            }
            case PtpIpClient.PROP_EXPOSURE_TIME: {
                if (n <= 0) return "—（原值 " + n + "）";
                if (n >= 10000) return trimNum(n / 10000.0) + "s";
                long denom = Math.round(10000.0 / n);
                return "1/" + denom;
            }
            case PtpIpClient.PROP_EXPOSURE_BIAS: {
                double ev = n / 1000.0;
                if (ev == 0) return "0 EV";
                return String.format(Locale.US, "%+.1f EV", ev);
            }
            case PtpIpClient.PROP_EXPOSURE_PROGRAM: {
                // PTP 规范 0x500E 标准值：1 手动 / 2 程序自动 / 3 光圈优先 / 4 快门优先
                switch ((int) n) {
                    case 1: return "M（手动）";
                    case 2: return "P（程序自动）";
                    case 3: return "A（光圈优先）";
                    case 4: return "S（快门优先）";
                    default: return "模式值 " + n + "（相机定义）";
                }
            }
            case PtpIpClient.PROP_STILL_CAPTURE_MODE: {
                // PTP 规范 0x5013 标准值：1 单张 / 2 连拍 / 3 自拍定时
                switch ((int) n) {
                    case 1: return "单张";
                    case 2: return "连拍";
                    case 3: return "自拍/定时";
                    default: return "驱动值 " + n + "（相机定义）";
                }
            }
            case PtpIpClient.PROP_EXPOSURE_INDEX:
                return n > 0 && n < 0x10000000L ? String.valueOf(n) : "—（原值 " + n + "）";
            default:
                return "值 " + n + "（相机枚举）";
        }
    }

    private static String trimNum(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        String s = String.format(Locale.US, "%.2f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private CamProps() {}
}
