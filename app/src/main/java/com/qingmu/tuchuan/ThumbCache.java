package com.qingmu.tuchuan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.LruCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 缩略图三级缓存：内存 LruCache（上限 ~24MB）→ 磁盘（cacheDir/thumbs，可关）→ 相机 GetThumb 懒加载。
 * 降级链：GetThumb 失败 → 不写任何缓存（UI 显示矢量占位 + 文件名），按 profile 的
 * ThumbFallback 决定滚动回来是否自动重试（PLACEHOLDER_ONCE 的机型记 failedOnce 集合）。
 */
public class ThumbCache {

    public interface Callback { void onThumb(int handle, Bitmap bmp); }

    private final LruCache<Integer, Bitmap> mem;
    private final File diskDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Set<Integer> inFlight = new HashSet<>();
    private final Set<Integer> failedOnce = new HashSet<>();
    public volatile boolean diskEnabled = true;

    public ThumbCache(Context ctx) {
        mem = new LruCache<Integer, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(Integer key, Bitmap value) { return value.getByteCount(); }
        };
        diskDir = new File(ctx.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
    }

    public Bitmap getMem(int handle) { return mem.get(handle); }

    public void load(final PtpIpClient client, final int handle,
                     final CameraProfile profile, final Callback cb) {
        Bitmap hit = mem.get(handle);
        if (hit != null) { cb.onThumb(handle, hit); return; }
        synchronized (inFlight) {
            if (inFlight.contains(handle)) return;
            if (profile != null && profile.thumbFallback == CameraProfile.ThumbFallback.PLACEHOLDER_ONCE
                    && failedOnce.contains(handle)) return;
            inFlight.add(handle);
        }
        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                if (diskEnabled) {
                    File f = new File(diskDir, handle + ".jpg");
                    if (f.exists() && f.length() > 0) {
                        // v0.5：先读字节再解码——EXIF Orientation 在字节里，decodeFile 拿不到就转不正竖拍图；
                        // 磁盘文件本身一个字节都不改，方向只作用于解码出的显示位图
                        byte[] cached = readAll(f);
                        if (cached != null) bmp = orientByExif(BitmapFactory.decodeByteArray(cached, 0, cached.length), cached);
                    }
                }
                if (bmp == null && client != null && client.isConnected()) {
                    byte[] data = client.getThumb(handle);
                    if (data != null && data.length > 0) {
                        bmp = orientByExif(BitmapFactory.decodeByteArray(data, 0, data.length), data);
                        if (bmp != null && diskEnabled) {
                            try {
                                File f = new File(diskDir, handle + ".jpg");
                                FileOutputStream fos = new FileOutputStream(f);
                                fos.write(data); fos.close(); // 存原始字节（未旋转），方向每次解码时现算
                            } catch (Exception ignore) {}
                        }
                    }
                }
                if (bmp == null) {
                    synchronized (inFlight) { failedOnce.add(handle); }
                    Diag.op("THUMB", "缩略图取失败 handle=0x" + Integer.toHexString(handle) + "（占位降级）");
                } else {
                    mem.put(handle, bmp);
                }
            } catch (Exception e) {
                synchronized (inFlight) { failedOnce.add(handle); }
                Diag.op("THUMB", "缩略图异常 handle=0x" + Integer.toHexString(handle) + "：" + e.getMessage());
            } finally {
                synchronized (inFlight) { inFlight.remove(handle); }
            }
            if (bmp != null) cb.onThumb(handle, bmp);
        });
    }

    /** 手动重试：清掉失败标记（占位图点击重试走这里） */
    public void clearFailure(int handle) { synchronized (inFlight) { failedOnce.remove(handle); } }

    private static byte[] readAll(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v0.5 缩略图方向修正：按 JPEG 内 EXIF Orientation 把显示位图转正（竖拍必须正立）。
     * 只变换解码出的位图，绝不改动原始字节（磁盘缓存/大图/出片链都不受影响）；
     * 无 EXIF、无 Orientation 或解析失败一律原样返回——宁可不转，不可转错。
     * 矩阵表为 EXIF 8 种取向的标准映射（与 Glide 等通行实现一致）。
     */
    public static Bitmap orientByExif(Bitmap bmp, byte[] jpegBytes) {
        if (bmp == null || jpegBytes == null || jpegBytes.length == 0) return bmp;
        int ori;
        try {
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(jpegBytes));
            ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return bmp; // 拿不到方向信息就保持原样，不记噪音日志（缩略图量大）
        }
        if (ori <= ExifInterface.ORIENTATION_NORMAL || ori > ExifInterface.ORIENTATION_ROTATE_270) return bmp;
        Matrix m = new Matrix();
        switch (ori) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setRotate(180); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
            default: return bmp;
        }
        try {
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) {
                Diag.op("THUMB", "缩略图按 EXIF Orientation=" + ori + " 旋转显示（仅显示位图，缓存原字节未改）");
                bmp.recycle();
            }
            return out;
        } catch (Exception e) {
            return bmp;
        }
    }
}
