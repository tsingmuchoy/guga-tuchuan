package com.qingmu.tuchuan;

import android.content.ContentValues;
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
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
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
 */
public final class ImageEditor {

    private static final int STRIP_ROWS = 384;
    private static final int JPEG_QUALITY = 92;

    public static class ExportResult {
        public Uri uri;
        public String displayName;
        public int width, height;
        public boolean scaledDown; // 内存不足降尺寸导出时为 true（结果文案必须注明）
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

    /** 在已转正的预览图基础上合成成品预览：滤镜 Paint + 边框模板；返回新图（调用方负责回收） */
    public static Bitmap renderFromBitmap(Bitmap orientedSrc, int template, int filterIdx,
                                          int strength, ExifMeta meta, String fallbackName) {
        int sw = orientedSrc.getWidth(), sh = orientedSrc.getHeight();
        float[] mg = Frames.margins(template);
        int outW = Math.round(sw + 2 * mg[0] * sw);
        int outH = Math.round(sh + (mg[1] + mg[2]) * sw);
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Frames.bgColor(template));
        RectF img = new RectF(mg[0] * sw, mg[1] * sw, mg[0] * sw + sw, mg[1] * sw + sh);
        canvas.drawBitmap(orientedSrc, null, img, photoPaint(filterIdx, strength));
        Frames.drawDecor(canvas, img, outW, outH, template, meta, fallbackName);
        return out;
    }

    // ---------- 全尺寸导出（条带化解码） ----------

    public static ExportResult exportFull(Context ctx, Uri srcUri, int template, int filterIdx,
                                          int strength, ExifMeta meta, String srcName) throws Exception {
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
        Diag.op("EDIT", "导出开始：存储 " + storedW + "×" + storedH + "，方向 " + orientation
                + "，显示 " + dispW + "×" + dispH + "，模板=" + Frames.NAMES[template]
                + "，滤镜=" + (filterIdx >= 0 ? Filters.NAMES[filterIdx] + " " + strength + "%" : "无"));

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
                Paint paint = photoPaint(filterIdx, strength);
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
                    canvas.drawBitmap(full, 0, 0, paint);
                    full.recycle();
                }
                canvas.restore();
                Frames.drawDecor(canvas, img, outW, outH, template, meta, srcName);

                File tmp = new File(ctx.getCacheDir(), "edited_tmp.jpg");
                try (OutputStream os = new FileOutputStream(tmp)) {
                    if (!out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)) {
                        throw new Exception("JPEG 编码失败");
                    }
                }
                out.recycle();
                out = null;
                String displayName = editedName(srcName);
                Uri uri = publishEdited(ctx, tmp, displayName);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                ExportResult r = new ExportResult();
                r.uri = uri;
                r.displayName = displayName;
                r.width = outW;
                r.height = outH;
                r.scaledDown = scale < 1.0;
                Diag.op("EDIT", "导出成功：" + displayName + "（" + outW + "×" + outH
                        + (r.scaledDown ? "，内存不足已降至 " + (int) (scale * 100) + "% 尺寸" : "，全尺寸") + "）");
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

    /** 发布成品到 Pictures/QingmuTuchuan/Edited，返回可分享的 content Uri（尽力而为） */
    private static Uri publishEdited(Context ctx, File tmp, String displayName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/QingmuTuchuan/Edited");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
            if (uri == null) throw new Exception("MediaStore insert 失败");
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("MediaStore 打开输出失败");
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            return uri;
        }
        // API 26-28：先落盘到公共目录，再登记进 MediaStore 拿到 content Uri 供分享
        File destDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "QingmuTuchuan/Edited");
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        File dest = uniqueFile(destDir, displayName);
        try (InputStream in = new FileInputStream(tmp);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, dest.getName());
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.MediaColumns.DATA, dest.getAbsolutePath());
        Uri uri = null;
        try {
            uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception ignore) {}
        MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()},
                new String[]{"image/jpeg"}, null);
        return uri != null ? uri : Uri.fromFile(dest);
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name.replaceAll("(?i)\\.jpe?g$", "");
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, base + "_" + i + ".jpg");
            if (!c.exists()) return c;
        }
        return f;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
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
}
