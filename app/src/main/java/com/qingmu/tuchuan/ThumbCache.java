package com.qingmu.tuchuan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;
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
                        bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    }
                }
                if (bmp == null && client != null && client.isConnected()) {
                    byte[] data = client.getThumb(handle);
                    if (data != null && data.length > 0) {
                        bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bmp != null && diskEnabled) {
                            try {
                                File f = new File(diskDir, handle + ".jpg");
                                FileOutputStream fos = new FileOutputStream(f);
                                fos.write(data); fos.close();
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
}
