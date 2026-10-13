package com.qingmu.tuchuan;

import android.media.ExifInterface;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.4 EXIF 读取与文案格式化（框架 android.media.ExifInterface，minSdk 26 可用）。
 * 原则：读到什么用什么——任一项缺失就优雅省略，绝不编造参数。
 * 标签名直接用 EXIF 标准字符串，避免依赖某个 SDK 版本的常量命名。
 */
public class ExifMeta {

    public String make = "", model = "", lens = "";
    public String fNumber = "";   // 已格式化，如 "2.8"
    public String shutter = "";    // 已格式化，如 "1/250" 或 "2.5s"
    public String focal = "";      // 已格式化，如 "35mm"
    public int iso = 0;
    public String dateRaw = "";    // "yyyy:MM:dd HH:mm:ss"
    public int orientation = 1;

    public static ExifMeta read(InputStream in) {
        ExifMeta m = new ExifMeta();
        try {
            ExifInterface ex = new ExifInterface(in);
            m.make = clean(ex.getAttribute("Make"));
            m.model = clean(ex.getAttribute("Model"));
            m.lens = clean(ex.getAttribute("LensModel"));
            double f = ex.getAttributeDouble("FNumber", 0);
            if (f > 0) m.fNumber = trimNum(f);
            double t = ex.getAttributeDouble("ExposureTime", 0);
            if (t > 0) m.shutter = fmtShutter(t);
            int iso = ex.getAttributeInt("ISOSpeedRatings", 0);
            if (iso <= 0) iso = ex.getAttributeInt("PhotographicSensitivity", 0);
            if (iso > 0) m.iso = iso;
            int f35 = ex.getAttributeInt("FocalLengthIn35mmFilm", 0);
            double fl = ex.getAttributeDouble("FocalLength", 0);
            if (f35 > 0) m.focal = f35 + "mm";
            else if (fl > 0) m.focal = trimNum(fl) + "mm";
            String d = ex.getAttribute("DateTimeOriginal");
            if (d == null || d.isEmpty()) d = ex.getAttribute("DateTime");
            m.dateRaw = d == null ? "" : d.trim();
            m.orientation = ex.getAttributeInt("Orientation", 1);
            if (m.orientation < 1 || m.orientation > 8) m.orientation = 1;
        } catch (Exception e) {
            Diag.op("EDIT", "EXIF 读取失败（按无 EXIF 处理）：" + e.getMessage());
        }
        return m;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    /** 2.80 → "2.8"；35.0 → "35" */
    private static String trimNum(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        String s = String.format(java.util.Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String fmtShutter(double t) {
        if (t >= 1) return trimNum(t) + "s";
        long denom = Math.round(1.0 / t);
        return denom > 0 ? "1/" + denom : trimNum(t) + "s";
    }

    /** 标题行：机型（make 与 model 前缀重复时去重）+ 镜头；全缺时用文件名兜底 */
    public String titleLine(String fallbackName) {
        String cam = model;
        if (!make.isEmpty() && !model.isEmpty()
                && !model.toUpperCase().startsWith(make.toUpperCase())) {
            cam = make + " " + model;
        }
        if (cam.isEmpty()) cam = make;
        StringBuilder sb = new StringBuilder();
        if (!cam.isEmpty()) sb.append(cam);
        if (!lens.isEmpty() && !lens.equalsIgnoreCase(cam)) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(lens);
        }
        return sb.length() > 0 ? sb.toString() : (fallbackName == null ? "" : fallbackName);
    }

    /** 参数行：只拼读到的项，如 "35mm · ƒ/2.8 · 1/250 · ISO 100"；全缺返回 "" */
    public String paramsLine() {
        List<String> parts = new ArrayList<>();
        if (!focal.isEmpty()) parts.add(focal);
        if (!fNumber.isEmpty()) parts.add("ƒ/" + fNumber);
        if (!shutter.isEmpty()) parts.add(shutter);
        if (iso > 0) parts.add("ISO " + iso);
        return String.join("  ·  ", parts);
    }

    /** 胶片风日期戳："2026.10.13"；无日期返回 "" */
    public String dateLabel() {
        if (dateRaw.length() >= 10 && dateRaw.charAt(4) == ':') {
            return dateRaw.substring(0, 10).replace(':', '.');
        }
        return "";
    }

    /** EXIF 是否一条都没读到（状态行提示用） */
    public boolean isEmpty() {
        return model.isEmpty() && make.isEmpty() && fNumber.isEmpty()
                && shutter.isEmpty() && iso == 0 && focal.isEmpty();
    }

    /** 一行摘要（编辑页状态行）：读到的关键项，缺的直接不提 */
    public String summary(String fallbackName) {
        String t = titleLine(fallbackName);
        String p = paramsLine();
        return p.isEmpty() ? t : t + "  ｜  " + p;
    }
}
