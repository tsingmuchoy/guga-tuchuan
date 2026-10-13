package com.qingmu.tuchuan;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v0.4.2 共用相册保存：相机下载落盘（DownloadService）与出片成品（ImageEditor）统一经这里
 * 发布到公共相册，杜绝两处实现再次漂移。
 *
 * API 29+ 分区存储铁律：往 Pictures 存图必须用 Images 集合、存视频用 Video 集合；
 * 误用 Files 集合（content://media/external/file）写 Pictures 必被系统拒绝——
 * 「Primary directory Pictures not allowed ... allowed directories are [Download, Documents]」，
 * 这正是 v0.4.1 外部用户实报的出片保存失败（且旧下载落盘同病、同会中招）。
 * 流程：IS_PENDING=1 插入 → 写流 → IS_PENDING=0 发布；中途失败删行，防相册留下 0 字节幽灵文件。
 * pending 语义只由 IS_PENDING 字段实现，不再用拼 .pending 文件名的土办法。
 *
 * API 26–28：直写公共 Pictures 目录 + MediaStore 登记 / scanFile（旧逻辑已确认正确，保留语义）。
 */
public final class AlbumSaver {

    private AlbumSaver() {}

    /**
     * 把 src 文件发布到相册 relPath 目录，返回可分享的 content Uri（低版本尽力而为）。
     *
     * @param relPath 形如 "Pictures/QingmuTuchuan" 或 "Pictures/QingmuTuchuan/Edited"
     * @param unique  仅 API 26–28 分支：目标同名时是否改名避让（出片 true；下载沿用同名覆盖 false）
     */
    public static Uri saveToAlbum(Context ctx, File src, String displayName, String mime,
                                  String relPath, boolean unique) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            return saveScoped(ctx, src, displayName, mime, relPath);
        }
        return saveLegacy(ctx, src, displayName, mime, relPath, unique);
    }

    /** 集合按 mime 分流：video/* → Video，image/*（含 NEF）与其余 → Images */
    private static Uri collectionFor(String mime) {
        if (mime != null && mime.startsWith("video/")) {
            return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    private static Uri saveScoped(Context ctx, File src, String displayName, String mime,
                                  String relPath) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(collectionFor(mime), v);
        if (uri == null) throw new Exception("MediaStore insert 返回 null（mime=" + mime + "）");
        try {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("MediaStore openOutputStream 返回 null");
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            return uri;
        } catch (Exception e) {
            // 失败删掉 pending 行，不留幽灵文件；原始异常完整进诊断后再上抛
            Diag.op("SAVE", "相册写入失败，删 pending 行 uri=" + uri + " 原始错误：" + e);
            try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignore) {}
            throw e;
        }
    }

    private static Uri saveLegacy(Context ctx, File src, String displayName, String mime,
                                  String relPath, boolean unique) throws Exception {
        String sub = relPath.startsWith("Pictures/")
                ? relPath.substring("Pictures/".length()) : relPath;
        File destDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), sub);
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        File dest = unique ? uniqueFile(destDir, displayName) : new File(destDir, displayName);
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        Uri uri = null;
        if (mime != null && mime.startsWith("image/")) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, dest.getName());
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.DATA, dest.getAbsolutePath());
            try {
                uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            } catch (Exception ignore) {}
        }
        MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()},
                new String[]{mime}, null);
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
}
