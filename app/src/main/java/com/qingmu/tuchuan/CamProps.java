package com.qingmu.tuchuan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 相机属性元数据表 + 显示格式化（v0.2 状态面板/参数调节；v0.5 参数面板全量分组共用）。
 *
 * 诚实原则：数值与取值集合一律以相机 GetDevicePropDesc 自报为准，这里只做显示层格式化；
 * 语义名称只给 PTP 规范明确定义且无歧义的（曝光程序 0x500E、连拍模式 0x5013 的标准值），
 * 白平衡/对焦模式等各厂枚举值域不完全一致，只显示原始数值，不编造名称。
 * 格式化依据：光圈 UINT16=值/100（PTP FNumber 定义）、快门 UINT32=1/10000 秒单位、
 * 曝光补偿 INT16=千分之一 EV（均为 PTP 规范对 DeviceProp 的单位定义）。
 *
 * v0.5 全量化：展示范围 = 相机 DeviceInfo 自报支持的属性码全集（RemoteController 逐个读 desc），
 * 本表 CATALOG 只提供「已知码的名称与分组」——PTP 规范 0x5000 段标准属性 + 已在用的尼康码；
 * 目录外的码（多为厂商私有属性）用 defOf() 兜底成「属性 0xXXXX」进其他组，值照相机原样显示，
 * 名称未知就不编造。视频组只收名称确为视频类的码；尼康电影类多为厂商私有码、名称无把握，
 * 不硬塞——它们会落在其他组照样可见。
 */
public final class CamProps {

    public static final int GROUP_EXPOSURE = 0;
    public static final int GROUP_FOCUS = 1;
    public static final int GROUP_IMAGE = 2;
    public static final int GROUP_VIDEO = 3;
    public static final int GROUP_OTHER = 4;
    public static final String[] GROUP_NAMES = {"曝光", "对焦", "图像", "视频", "其他"};

    public static class Def {
        public final int code;
        public final String name;
        public final boolean adjustable; // v0.2 语义：true=经典可调项（v0.5 面板改按 desc 可写性统一判定，保留字段兼容）
        public final int group;
        Def(int code, String name, boolean adjustable, int group) {
            this.code = code; this.name = name; this.adjustable = adjustable; this.group = group;
        }
    }

    /** 经典 8 项（v0.2 起的状态面板顺序；v0.5 仍保证这几项在各自分组内优先陈列） */
    public static final List<Def> DEFS = new ArrayList<>();
    /** 已知属性目录：名称与分组的唯一出处（PTP 规范标准码 + 已核实的尼康码） */
    public static final List<Def> CATALOG = new ArrayList<>();
    static {
        Def mode = new Def(PtpIpClient.PROP_EXPOSURE_PROGRAM, "拍摄模式", false, GROUP_EXPOSURE);
        Def fnum = new Def(PtpIpClient.PROP_F_NUMBER, "光圈", true, GROUP_EXPOSURE);
        Def shut = new Def(PtpIpClient.PROP_EXPOSURE_TIME, "快门", true, GROUP_EXPOSURE);
        Def iso  = new Def(PtpIpClient.PROP_EXPOSURE_INDEX, "ISO", true, GROUP_EXPOSURE);
        Def bias = new Def(PtpIpClient.PROP_EXPOSURE_BIAS, "曝光补偿", true, GROUP_EXPOSURE);
        Def wb   = new Def(PtpIpClient.PROP_WHITE_BALANCE, "白平衡", true, GROUP_IMAGE);
        Def fm   = new Def(PtpIpClient.PROP_FOCUS_MODE, "对焦模式", false, GROUP_FOCUS);
        Def drv  = new Def(PtpIpClient.PROP_STILL_CAPTURE_MODE, "拍摄驱动", false, GROUP_OTHER);
        DEFS.add(mode); DEFS.add(fnum); DEFS.add(shut); DEFS.add(iso);
        DEFS.add(bias); DEFS.add(wb); DEFS.add(fm); DEFS.add(drv);
        CATALOG.addAll(DEFS);
        // 注：0x501C（对焦区域模式）见下方 PTP 标准段；0x500C 是闪光模式（ptp.h:1952 + ptp.c
        // 尼康值表 32784=Auto），不是 AF 区域，勿混淆。尼康 Z 的 AF 区域正是 0x501C。
        // —— PTP 规范 0x5000 段标准属性（名称为规范 DeviceProp 语义的中文直译） ——
        CATALOG.add(new Def(0x5001, "电池电量", false, GROUP_OTHER));       // BatteryLevel
        CATALOG.add(new Def(0x5002, "功能模式", false, GROUP_OTHER));       // FunctionalMode
        CATALOG.add(new Def(0x5003, "图像尺寸", false, GROUP_IMAGE));       // ImageSize（STR，如 6000x4000）
        CATALOG.add(new Def(0x5004, "画质（压缩设置）", false, GROUP_IMAGE)); // CompressionSetting
        CATALOG.add(new Def(0x5006, "RGB 增益", false, GROUP_IMAGE));       // RGBGain
        CATALOG.add(new Def(0x5008, "焦距", false, GROUP_FOCUS));           // FocalLength
        CATALOG.add(new Def(0x5009, "对焦距离", false, GROUP_FOCUS));     // FocusDistance
        CATALOG.add(new Def(0x500B, "测光模式", false, GROUP_EXPOSURE));   // ExposureMeteringMode
        CATALOG.add(new Def(0x500C, "闪光模式", false, GROUP_EXPOSURE));   // FlashMode
        CATALOG.add(new Def(0x5011, "日期时间", false, GROUP_OTHER));       // DateTime（STR）
        CATALOG.add(new Def(0x5012, "拍摄质量", false, GROUP_IMAGE));       // CaptureQuality
        CATALOG.add(new Def(0x5014, "对比度", false, GROUP_IMAGE));         // Contrast
        CATALOG.add(new Def(0x5015, "锐度", false, GROUP_IMAGE));           // Sharpness
        CATALOG.add(new Def(0x5016, "数码变焦", false, GROUP_IMAGE));     // DigitalZoom
        CATALOG.add(new Def(0x5017, "色彩效果", false, GROUP_IMAGE));       // EffectMode
        CATALOG.add(new Def(0x5018, "连拍张数", false, GROUP_OTHER));       // BurstNumber
        CATALOG.add(new Def(0x5019, "连拍间隔", false, GROUP_OTHER));       // BurstInterval
        CATALOG.add(new Def(0x501A, "间隔拍摄张数", false, GROUP_OTHER)); // TimelapseNumber
        CATALOG.add(new Def(0x501B, "间隔拍摄间隔", false, GROUP_OTHER)); // TimelapseInterval
        CATALOG.add(new Def(0x501C, "对焦区域模式", true, GROUP_FOCUS)); // FocusMeteringMode（v0.5.1 改名：尼康 Z 的 AF 区域模式即此属性，取值名见 focusMeteringName）
        // 注：尼康取景帧尺寸 0xD1AC 不进本目录——它由遥控页尺寸档开关 + RemoteController 状态机专管，
        // 参数面板若再给一个写入入口会与 lvSizeIdx 状态脱同步；loadProps 枚举时也会显式跳过该码。
    }

    public static Def byCode(int code) {
        for (Def d : CATALOG) if (d.code == code) return d;
        return null;
    }

    /** 取任意码的 Def：目录命中用目录项；未命中兜底成未命名厂商/未知属性（其他组，只显示码与相机原值） */
    public static Def defOf(int code) {
        Def d = byCode(code);
        if (d != null) return d;
        return new Def(code, "属性 0x" + Integer.toHexString(code).toUpperCase(Locale.US), false, GROUP_OTHER);
    }

    /** 目录内全部属性码（相机未在 DeviceInfo 报告支持列表时的回退试探集） */
    public static List<Integer> catalogCodes() {
        List<Integer> r = new ArrayList<>();
        for (Def d : CATALOG) r.add(d.code);
        return r;
    }

    /** 拍摄模式短名（顶栏速览用）：PTP 标准映射 1=M/2=P/3=A/4=S + 尼康厂商 32784=AUTO（ptp.c 值表核实），不确定返回 null（调用方只显示原值，不编造） */
    public static String programShort(long n) {
        switch ((int) n) {
            case 1: return "M";
            case 2: return "P";
            case 3: return "A";
            case 4: return "S";
            case 32784: return "AUTO";
            default: return null;
        }
    }

    /**
     * 对焦区域模式（0x501C FocusMeteringMode）尼康取值名。
     * 出处：libgphoto2 config.c nikon_d850_focus_metering 表（含 Z 机型条目，Z fc 同属 Z 系）
     * 与 ptp.c 通用表（0x8010=Single Area 两表一致）；表外值返回 null → 调用方显示原值不编造。
     */
    public static String focusMeteringName(long n) {
        switch ((int) n) {
            case 0x0001: return "中央点";
            case 0x0002: return "动态区域 AF";
            case 0x8010: return "单点 AF";
            case 0x8011: return "自动区域 AF";
            case 0x8012: return "3D 跟踪";
            case 0x8013: return "动态区域 AF（72 点）";
            case 0x8014: return "动态区域 AF（153 点）";
            case 0x8015: return "群组区域 AF";
            case 0x8016: return "动态区域 AF（9 点）";
            case 0x8017: return "精准点 AF";
            case 0x8018: return "宽区域 AF（小）";
            case 0x8019: return "宽区域 AF（大）";
            case 0x801E: return "宽区域 AF（C1）";
            case 0x801F: return "宽区域 AF（C2）";
            default: return null;
        }
    }

    /** 是否「单点类」区域模式（点按对焦坐标能被相机原样采用）：单点 AF / 精准点 AF（libgphoto2 Z 表核实） */
    public static boolean isSinglePointClass(long n) {
        return n == 0x8010 || n == 0x8017;
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
                // PTP 规范 0x500E 标准值：1 手动 / 2 程序自动 / 3 光圈优先 / 4 快门优先；
                // 尼康厂商场景值（libgphoto2 ptp.c 值表）：32784 起为全自动与场景模式
                switch ((int) n) {
                    case 1: return "M（手动）";
                    case 2: return "P（程序自动）";
                    case 3: return "A（光圈优先）";
                    case 4: return "S（快门优先）";
                    case 32784: return "AUTO（全自动）";
                    case 32785: return "人像（相机场景）";
                    case 32786: return "风景（相机场景）";
                    case 32787: return "微距（相机场景）";
                    case 32788: return "运动（相机场景）";
                    case 32789: return "夜景人像（相机场景）";
                    case 32790: return "夜景风景（相机场景）";
                    default: return "模式值 " + n + "（相机定义）";
                }
            }
            case PtpIpClient.PROP_FOCUS_METERING: {
                String nm = focusMeteringName(n);
                return nm != null ? nm : "区域值 " + n + "（相机定义）";
            }
            case 0x500C: {
                // 闪光模式：PTP 标准小值 + 尼康厂商值（libgphoto2 ptp.c 值表核实）
                switch ((int) n) {
                    case 1: return "自动闪光";
                    case 2: return "闪光关";
                    case 3: return "填充闪光";
                    case 4: return "自动防红眼";
                    case 5: return "防红眼填充";
                    case 6: return "外接同步";
                    case 32784: return "自动（尼康）";
                    case 32785: return "自动慢同步（尼康）";
                    case 32786: return "后帘慢同步（尼康）";
                    case 32787: return "防红眼慢同步（尼康）";
                    default: return "值 " + n + "（相机枚举）";
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
            case 0x5001: // BatteryLevel：UINT8 百分比（PTP 规范定义 0-100）
                return n >= 0 && n <= 100 ? n + "%" : "值 " + n + "（相机枚举）";
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
