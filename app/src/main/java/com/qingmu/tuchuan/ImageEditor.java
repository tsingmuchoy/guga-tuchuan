package com.qingmu.tuchuan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v0.4 出片渲染器：预览合成 + 全尺寸导出 + 发布到相册 Edited 目录。
 *
 * 内存策略（轻量纪律）：
 *  - 预览：原图降采样解码到 ≤1600px（约 1600×1067×4B ≈ 6.8MB）再按 EXIF 方向转正，
 *    合成预览另出一张同量级图，均为纯内存、用完回收，封顶明确。
 *  - 导出：BitmapRegionDecoder 条带化解码（每条 384 行、6000px 宽约 9MB 瞬时），
 *    条带在 Canvas 的方向变换下逐条画进唯一的输出 Bitmap——源图永不整张驻留。
 *    峰值 ≈ 输出图本身（24MP ARGB 约 96MB；Z fc 的 20.9MP 约 83MB）+ 一条条带。
 *    若整图分配 OOM，自动降到 1/2、1/4 尺寸导出并在结果里如实注明（不静默缩水）。
 *  - 成品只在用户点「保存」时落盘；原图永不覆盖，成品进 Pictures/QingmuTuchuan/Edited。
 *
 * v0.4.3：①导出 JPEG 质量改为可选项（编辑页三档 100/97/88，默认 97；v0.4.2 及以前写死 92，
 *   外部用户实测 20.7MP 原图 8.02MB 经出片只剩 3.07MB、画质损失明显）；
 *   ②成品回写源图 EXIF 白名单（机型/镜头/光圈/快门/ISO/焦距/时间/曝光补偿/白平衡/GPS 等），
 *   在 AlbumSaver 清除 IS_PENDING 之前经 AfterWriteHook 完成，失败只记诊断、不拖垮导出。
 *   说明：Bitmap.compress 的色度抽样（4:2:0）由系统编码器固定、应用层不可控，即便质量 100
 *   也存在二次编码的理论损失下限——目标是把它压到肉眼基本不可感，而不是宣称无损。
 * v0.6：滤镜目录扩到 36 款（Filters 统一数据源）；带 LUT 的 19 款出片改走 LutEngine
 *   17^3 三线性查表（条带内就地处理、无整图中间缓冲，预览图同样走 LUT 保证所见即所得），
 *   载入失败回退矩阵；无 LUT 的款（徕卡构造 3 款 + 原 14 款）继续矩阵 Paint 路径。
 */
public final class ImageEditor {

    private static final int STRIP_ROWS = 384;

    /** EXIF 回写白名单：只带这些标签；缩略图、ImageWidth/Length、厂商 MakerNote 等一律不带，防过期数据 */
    private static final String[] EXIF_WHITELIST = {
            "Make", "Model", "LensMake", "LensModel",
            "FNumber", "ExposureTime", "ISOSpeedRatings", "PhotographicSensitivity",
            "FocalLength", "FocalLengthIn35mmFilm",
            "DateTime", "DateTimeOriginal", "DateTimeDigitized",
            "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "ExposureBiasValue", "WhiteBalance", "ExposureProgram", "ExposureMode",
            "MeteringMode", "Flash", "SceneCaptureType",
            "BrightnessValue", "MaxApertureValue", "SubjectDistance", "DigitalZoomRatio",
            "Contrast", "Saturation", "Sharpness", "GainControl",
            "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude",
            "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp", "GPSDateStamp",
            "GPSProcessingMethod", "GPSImgDirectionRef", "GPSImgDirection",
    };

    public static class ExportResult {
        public Uri uri;
        public String displayName;
        public int width, height;
        public boolean scaledDown; // 内存不足降尺寸导出时为 true（结果文案必须注明）
        public long fileBytes;     // 成品 JPEG 字节数（v0.4.3 起随诊断记录，便于核对画质档效果）
    }

    private ImageEditor() {}

    // ---------- 基础读取 ----------

    public static ExifMeta loadMeta(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return new ExifMeta();
            return ExifMeta.read(in);
        } catch (Exception e) {
            Diag.op("EDIT", "打开原图读 EXIF 失败：" + e.getMessage());
            return new ExifMeta();
        }
    }

    /** 解码预览图（降采样 + 按 EXIF 方向转正）；失败返回 null */
    public static Bitmap loadPreview(Context ctx, Uri uri, int orientation, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                raw = BitmapFactory.decodeStream(in, null, opt);
            }
            if (raw == null) return null;
            if (orientation == 1) return raw;
            Bitmap rot = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(),
                    orientationMatrix(orientation, raw.getWidth(), raw.getHeight()), true);
            if (rot != raw) raw.recycle();
            return rot;
        } catch (Exception e) {
            Diag.op("EDIT", "预览解码失败：" + e.getMessage());
            return null;
        }
    }

    // ---------- 预览合成（小图整张画） ----------

    /** 在已转正的预览图基础上合成成品预览：滤镜 + 边框模板；返回新图（调用方负责回收）。
     *  v0.6：带 LUT 的滤镜在预览上也走 LUT（预览图 ≤1600px，逐像素查表成本可控，所见即所得）；
     *  LUT 载入失败回退矩阵 Paint。其余滤镜仍走矩阵 Paint。 */
    public static Bitmap renderFromBitmap(Context ctx, Bitmap orientedSrc, int template, int filterIdx,
                                          int strength, ExifMeta meta, String fallbackName) {
        int sw = orientedSrc.getWidth(), sh = orientedSrc.getHeight();
        float[] mg = Frames.margins(template);
        int outW = Math.round(sw + 2 * mg[0] * sw);
        int outH = Math.round(sh + (mg[1] + mg[2]) * sw);
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Frames.bgColor(template));
        RectF img = new RectF(mg[0] * sw, mg[1] * sw, mg[0] * sw + sw, mg[1] * sw + sh);
        Bitmap photoSrc = orientedSrc;
        Bitmap lutCopy = null;
        Paint paint;
        byte[] lut = null;
        if (filterIdx >= 0 && strength > 0 && Filters.hasLut(filterIdx)) {
            lut = LutEngine.load(ctx, filterIdx);
        }
        if (lut != null) {
            lutCopy = orientedSrc.copy(Bitmap.Config.ARGB_8888, true);
            if (lutCopy != null) {
                LutEngine.applyInPlace(lutCopy, lut, strength / 100f);
                photoSrc = lutCopy;
                paint = photoPlainPaint();
            } else {
                paint = photoPaint(filterIdx, strength);
            }
        } else {
            paint = photoPaint(filterIdx, strength);
        }
        canvas.drawBitmap(photoSrc, null, img, paint);
        if (lutCopy != null) lutCopy.recycle();
        Frames.drawDecor(canvas, img, outW, outH, template, meta, fallbackName);
        return out;
    }

    // ---------- 全尺寸导出（条带化解码） ----------

    public static ExportResult exportFull(Context ctx, Uri srcUri, int template, int filterIdx,
                                          int strength, ExifMeta meta, String srcName,
                                          int quality) throws Exception {
        // 1) 存储方向尺寸：优先 RegionDecoder，其次 bounds 解码
        int storedW = 0, storedH = 0;
        BitmapRegionDecoder decoder = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(srcUri, "r");
            if (pfd != null) {
                decoder = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
                storedW = decoder.getWidth();
                storedH = decoder.getHeight();
            }
        } catch (Exception e) {
            Diag.op("EDIT", "RegionDecoder 打开失败，导出走整图解码兜底：" + e.getMessage());
            decoder = null;
        }
        if (decoder == null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(srcUri)) {
                if (in == null) throw new Exception("无法打开原图");
                BitmapFactory.decodeStream(in, null, bounds);
            }
            storedW = bounds.outWidth;
            storedH = bounds.outHeight;
            if (storedW <= 0 || storedH <= 0) throw new Exception("原图尺寸读取失败");
        }
        int orientation = meta != null ? meta.orientation : 1;
        boolean swap = swapsAxes(orientation);
        int dispW = swap ? storedH : storedW;
        int dispH = swap ? storedW : storedH;
        // v0.6：带 LUT 的滤镜出片走 17^3 LUT 高精度路径（条带内逐像素三线性查表）；
        // LUT 载入失败自动回退矩阵 Paint，绝不让导出整体失败。
        byte[] lutData = null;
        if (filterIdx >= 0 && strength > 0 && Filters.hasLut(filterIdx)) {
            lutData = LutEngine.load(ctx, filterIdx);
        }
        final float lutT = strength / 100f;
        Diag.op("EDIT", "导出开始：存储 " + storedW + "×" + storedH + "，方向 " + orientation
                + "，显示 " + dispW + "×" + dispH + "，模板=" + Frames.NAMES[template]
                + "，滤镜=" + (filterIdx >= 0 ? Filters.NAMES[filterIdx] + Filters.noteSuffix(filterIdx)
                + " " + strength + "%" : "无")
                + "，出片路径=" + (filterIdx < 0 || strength <= 0 ? "无滤镜"
                : lutData != null ? "17³ LUT 高精度" : "矩阵")
                + "，画质=" + quality);

        float[] mg = Frames.margins(template);
        double[] scales = {1.0, 0.5, 0.25};
        Exception lastErr = null;
        try {
        for (double scale : scales) {
            int outW = (int) Math.round((dispW + 2 * mg[0] * dispW) * scale);
            int outH = (int) Math.round((dispH + (mg[1] + mg[2]) * dispW) * scale);
            Bitmap out = null;
            try {
                out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(out);
                canvas.drawColor(Frames.bgColor(template));
                float imgL = (float) (mg[0] * dispW * scale);
                float imgT = (float) (mg[1] * dispW * scale);
                RectF img = new RectF(imgL, imgT,
                        imgL + (float) (dispW * scale), imgT + (float) (dispH * scale));
                Paint paint = lutData != null ? photoPlainPaint() : photoPaint(filterIdx, strength);
                canvas.save();
                canvas.translate(imgL, imgT);
                canvas.scale((float) scale, (float) scale);
                canvas.concat(orientationMatrix(orientation, storedW, storedH));
                if (decoder != null) {
                    BitmapFactory.Options sopt = new BitmapFactory.Options();
                    sopt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    int strips = 0;
                    for (int y = 0; y < storedH; y += STRIP_ROWS) {
                        int h = Math.min(STRIP_ROWS, storedH - y);
                        Bitmap strip = decoder.decodeRegion(new Rect(0, y, storedW, y + h), sopt);
                        if (strip == null) throw new Exception("条带解码失败 y=" + y);
                        if (lutData != null) LutEngine.applyInPlace(strip, lutData, lutT); // 条带内查表，不增整图缓冲
                        canvas.drawBitmap(strip, 0, y, paint);
                        strip.recycle();
                        strips++;
                    }
                    Diag.op("EDIT", "条带绘制完成：" + strips + " 条（每条 " + STRIP_ROWS + " 行）");
                } else {
                    // 兜底：整图一次性解码（大图可能 OOM，由外层降档重试接住）
                    BitmapFactory.Options fopt = new BitmapFactory.Options();
                    fopt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    fopt.inSampleSize = scale >= 1.0 ? 1 : (scale >= 0.5 ? 2 : 4); // 与降档同步采样，防整图解码先于输出图 OOM
                    Bitmap full;
                    try (InputStream in = ctx.getContentResolver().openInputStream(srcUri)) {
                        if (in == null) throw new Exception("无法打开原图");
                        full = BitmapFactory.decodeStream(in, null, fopt);
                    }
                    if (full == null) throw new Exception("原图整图解码失败");
                    if (lutData != null) LutEngine.applyInPlace(full, lutData, lutT);
                    canvas.drawBitmap(full, 0, 0, paint);
                    full.recycle();
                }
                canvas.restore();
                Frames.drawDecor(canvas, img, outW, outH, template, meta, srcName);

                File tmp = new File(ctx.getCacheDir(), "edited_tmp.jpg");
                try (OutputStream os = new FileOutputStream(tmp)) {
                    if (!out.compress(Bitmap.CompressFormat.JPEG, quality, os)) {
                        throw new Exception("JPEG 编码失败");
                    }
                }
                out.recycle();
                out = null;
                long outBytes = tmp.length();
                String displayName = editedName(srcName);
                Uri uri = publishEdited(ctx, tmp, displayName, srcUri);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                ExportResult r = new ExportResult();
                r.uri = uri;
                r.displayName = displayName;
                r.width = outW;
                r.height = outH;
                r.scaledDown = scale < 1.0;
                r.fileBytes = outBytes;
                Diag.op("EDIT", "导出成功：" + displayName + "（" + outW + "×" + outH
                        + (r.scaledDown ? "，内存不足已降至 " + (int) (scale * 100) + "% 尺寸" : "，全尺寸")
                        + "，画质=" + quality + "，成品="
                        + String.format(java.util.Locale.US, "%.2f", outBytes / 1048576.0) + "MB"
                        + "）uri=" + uri);
                return r;
            } catch (OutOfMemoryError oom) {
                lastErr = new Exception("内存不足");
                Diag.op("EDIT", "导出 OOM（scale=" + scale + "），降档重试");
            } finally {
                if (out != null) out.recycle();
            }
        }
        } finally {
            try { if (decoder != null) decoder.recycle(); } catch (Exception ignore) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignore) {}
        }
        throw lastErr != null ? lastErr : new Exception("导出失败");
    }

    /**
     * 发布成品到 Pictures/QingmuTuchuan/Edited（v0.4.2 起统一走 AlbumSaver，Images 集合标准流程）。
     * v0.4.3：经 AfterWriteHook 在成品对外可见前（scoped 分支为 IS_PENDING 清除前）回写源图 EXIF。
     */
    private static Uri publishEdited(Context ctx, File tmp, String displayName, final Uri srcUri) throws Exception {
        return AlbumSaver.saveToAlbum(ctx, tmp, displayName, "image/jpeg",
                "Pictures/QingmuTuchuan/Edited", true,
                (c, destUri, destFile) -> copyExifWhitelist(c, srcUri, destUri, destFile));
    }

    /**
     * v0.4.3：把源图 EXIF 白名单标签复制到成品，并把 Orientation 置 1（导出已把方向烘焙进像素）。
     * destUri 非空（API 29+）：openFileDescriptor(uri,"rw") + ExifInterface(FileDescriptor) 回写；
     * destFile 非空（API 26–28）：ExifInterface(文件路径) 同理回写。
     * 源图无 EXIF（截图类导入图）则跳过并记一行；任何失败只记 [EDIT] 诊断，绝不让导出整体失败。
     */
    static void copyExifWhitelist(Context ctx, Uri srcUri, Uri destUri, File destFile) {
        try {
            java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
            try (InputStream in = ctx.getContentResolver().openInputStream(srcUri)) {
                if (in == null) {
                    Diag.op("EDIT", "EXIF 回写跳过：源图打不开");
                    return;
                }
                ExifInterface src = new ExifInterface(in);
                for (String tag : EXIF_WHITELIST) {
                    try {
                        String v = src.getAttribute(tag);
                        if (v != null && !v.isEmpty()) attrs.put(tag, v);
                    } catch (Exception ignore) {}
                }
            }
            if (attrs.isEmpty()) {
                Diag.op("EDIT", "EXIF 回写跳过：源图无 EXIF（白名单标签一条都没读到）");
                return;
            }
            attrs.put("Orientation", "1");
            ExifInterface dest;
            ParcelFileDescriptor pfd = null;
            if (destUri != null) {
                pfd = ctx.getContentResolver().openFileDescriptor(destUri, "rw");
                if (pfd == null) {
                    Diag.op("EDIT", "EXIF 回写失败（不影响成品）：成品 openFileDescriptor 返回 null");
                    return;
                }
                dest = new ExifInterface(pfd.getFileDescriptor());
            } else if (destFile != null) {
                dest = new ExifInterface(destFile.getAbsolutePath());
            } else {
                return;
            }
            int ok = 0, fail = 0;
            StringBuilder failTags = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : attrs.entrySet()) {
                try {
                    dest.setAttribute(e.getKey(), e.getValue());
                    ok++;
                } catch (Exception ex) {
                    fail++;
                    if (failTags.length() > 0) failTags.append(',');
                    failTags.append(e.getKey());
                }
            }
            try {
                dest.saveAttributes();
            } catch (Exception se) {
                Diag.op("EDIT", "EXIF 回写失败（不影响成品）：saveAttributes 异常：" + se);
                try { if (pfd != null) pfd.close(); } catch (Exception ignore) {}
                return;
            }
            try { if (pfd != null) pfd.close(); } catch (Exception ignore) {}
            Diag.op("EDIT", "EXIF 回写完成：复制 " + ok + " 项"
                    + (fail > 0 ? "，失败 " + fail + " 项（" + failTags + "）" : ""));
        } catch (Exception e) {
            Diag.op("EDIT", "EXIF 回写失败（不影响成品）：" + e);
        }
    }

    /** 把原始异常翻成用户可读中文：原始英文报错只进诊断日志，不再整段弹到界面上 */
    public static String friendlyError(Throwable e) {
        if (e == null) return "保存失败";
        if (e instanceof OutOfMemoryError) return "照片太大，手机内存不足，关掉其他应用再试";
        String raw = e.getMessage() != null ? e.getMessage() : e.toString();
        // 自家抛出的中文异常本身就是可读文案（不含 MediaStore 字样的），直接透传
        if (raw.matches(".*[\\u4e00-\\u9fff].*") && !raw.contains("MediaStore")) return raw;
        String low = raw.toLowerCase(java.util.Locale.US);
        if (low.contains("not allowed") || low.contains("primary directory")) {
            return "系统不允许写入这个相册目录";
        }
        if (low.contains("no space") || low.contains("enospc") || low.contains("not enough space")) {
            return "手机存储空间不足";
        }
        if (e instanceof SecurityException || low.contains("permission")) {
            return "没有相册写入权限，请到系统设置里允许本应用访问相册";
        }
        if (low.contains("no such file") || low.contains("enoent") || low.contains("file not found")) {
            return "原图文件找不到了（可能被移动或删除）";
        }
        if (low.contains("outofmemory") || low.contains("内存不足")) {
            return "照片太大，手机内存不足，关掉其他应用再试";
        }
        return "保存到相册失败";
    }

    /** DSC_0001.JPG → DSC_0001_edited.jpg */
    public static String editedName(String srcName) {
        String base = srcName == null ? "photo" : srcName.replaceAll("(?i)\\.jpe?g$", "");
        return base + "_edited.jpg";
    }

    // ---------- 方向变换 ----------

    public static boolean swapsAxes(int orientation) { return orientation >= 5 && orientation <= 8; }

    /** 存储坐标 → 显示坐标的变换矩阵（EXIF Orientation 1..8 的标准映射，连续坐标版） */
    public static Matrix orientationMatrix(int orientation, int w, int h) {
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setValues(new float[]{-1, 0, w, 0, 1, 0, 0, 0, 1}); break;
            case 3: m.setValues(new float[]{-1, 0, w, 0, -1, h, 0, 0, 1}); break;
            case 4: m.setValues(new float[]{1, 0, 0, 0, -1, h, 0, 0, 1}); break;
            case 5: m.setValues(new float[]{0, 1, 0, 1, 0, 0, 0, 0, 1}); break;
            case 6: m.setValues(new float[]{0, -1, h, 1, 0, 0, 0, 0, 1}); break;
            case 7: m.setValues(new float[]{0, -1, h, -1, 0, w, 0, 0, 1}); break;
            case 8: m.setValues(new float[]{0, 1, 0, -1, 0, w, 0, 0, 1}); break;
            default: break; // 1：单位阵
        }
        return m;
    }

    private static Paint photoPaint(int filterIdx, int strength) {
        Paint p = new Paint();
        p.setFilterBitmap(true);
        if (filterIdx >= 0 && strength > 0) {
            p.setColorFilter(new ColorMatrixColorFilter(Filters.matrix(filterIdx, strength)));
        }
        return p;
    }

    /** LUT 路径用：像素已逐像素处理，绘制 Paint 不再挂任何 ColorFilter */
    private static Paint photoPlainPaint() {
        Paint p = new Paint();
        p.setFilterBitmap(true);
        return p;
    }
}
