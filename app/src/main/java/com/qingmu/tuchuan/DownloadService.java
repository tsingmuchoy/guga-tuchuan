package com.qingmu.tuchuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下载前台 Service：队列顺序下载，通知栏显示进度，WakeLock 保活。
 *
 * 续传/降级链（全程记诊断）：
 *  0) v0.6.2 起客户端 downloadObject 本身是断点续传型：GetObject 整流被相机提前收尾时，
 *     同会话内自动转 GetPartialObject 从已收偏移续拉（短读接受、分块自适应缩小、[DL] 全程记录），
 *     本 Service 的 .part 机制降为跨会话/跨重连的外层安全网
 *  1) .part 存在 → GetPartialObject 从 .part 长度续传（profile 禁用 Partial 时直接走 3）
 *  2) Partial 相机报错（响应码非 OK）→ 删 .part，整文件 GetObject 重下一次
 *  3) IO 断裂 → PtpIpClient.reconnect() 指数退避重连+重建会话，成功则从 .part 继续（最多 4 轮）
 * 成品先落在 App 专属目录，校验长度后经 MediaStore 发布到 Pictures/QingmuTuchuan 并登记防重复。
 */
public class DownloadService extends Service {

    public static class Job {
        public final int handle; public final String filename; public final long size;
        public Job(int handle, String filename, long size) {
            this.handle = handle; this.filename = filename; this.size = size;
        }
    }

    public interface Listener {
        void onJobStart(int index, int total, Job job);
        void onProgress(Job job, long fileDone);
        void onJobDone(Job job, boolean ok, String error);
        void onQueueDone(int okCount, int failCount);
    }

    private static final List<Job> QUEUE = new ArrayList<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    public static volatile Listener listener;
    private static final String CHANNEL = "guga_dl";
    private static final int NOTIF_ID = 42;

    public static void enqueue(Context ctx, List<Job> jobs) {
        synchronized (QUEUE) { QUEUE.addAll(jobs); }
        synchronized (PENDING) { for (Job j : jobs) PENDING.add(j.filename); }
        Intent i = new Intent(ctx, DownloadService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        Diag.op("DL", "入队 " + jobs.size() + " 个文件，队列共 " + QUEUE.size());
    }

    /** 该文件名是否已在下载队列/正在下载（v0.3 自动回传去重用） */
    public static boolean hasPending(String filename) {
        synchronized (PENDING) { return filename != null && PENDING.contains(filename); }
    }

    public static boolean isRunning() { return RUNNING.get(); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "图传下载", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NOTIF_ID, buildNotif("清木图传", "准备下载…", -1));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (RUNNING.compareAndSet(false, true)) {
            Thread t = new Thread(this::workLoop, "guga-dl");
            // v0.6.2：下载线程未捕获异常只记 [GUARD]，不许直达系统默认处理器杀进程
            t.setUncaughtExceptionHandler((th, ex) ->
                    Diag.op("GUARD", "下载线程未捕获异常（已拦截，App 未退出）：" + ex));
            t.start();
        }
        return START_STICKY;
    }

    private Notification buildNotif(String title, String text, int percent) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true);
        if (percent >= 0) b.setProgress(100, percent, false); else b.setProgress(0, 0, true);
        return b.build();
    }

    private void notifyProgress(String text, int percent) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif("清木图传 · 下载中", text, percent));
    }

    private void workLoop() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guga:dl") : null;
        int ok = 0, fail = 0, index = 0;
        try {
            while (true) {
                Job job;
                int total;
                synchronized (QUEUE) {
                    if (QUEUE.isEmpty()) break;
                    job = QUEUE.remove(0);
                    total = QUEUE.size() + index + 1;
                }
                index++;
                if (wl != null) wl.acquire(10 * 60 * 1000L);
                Listener l = listener;
                if (l != null) l.onJobStart(index, total, job);
                String err = null;
                try {
                    downloadOne(job);
                    ok++;
                } catch (Exception e) {
                    err = e.getMessage();
                    fail++;
                    Diag.op("DL", "文件失败 " + job.filename + "：" + err);
                } finally {
                    if (wl != null && wl.isHeld()) wl.release();
                }
                if (l != null) l.onJobDone(job, err == null, err);
                synchronized (PENDING) { PENDING.remove(job.filename); }
            }
        } finally {
            RUNNING.set(false);
            Listener l = listener;
            if (l != null) l.onQueueDone(ok, fail);
            Diag.op("DL", "队列结束：成功 " + ok + "，失败 " + fail);
            stopForeground(true);
            stopSelf();
        }
    }

    private void downloadOne(Job job) throws Exception {
        PtpIpClient client = AppState.get().client;
        if (client == null) throw new Exception("PTP 客户端不存在（未连接）");
        SharedPreferences prefs = getSharedPreferences("guga_tuchuan", MODE_PRIVATE);
        Set<String> done = new HashSet<>(prefs.getStringSet("done_files", new HashSet<>()));
        if (done.contains(job.filename)) {
            Diag.op("DL", job.filename + " 已在相册记录中，跳过");
            return;
        }
        File dir = new File(getExternalFilesDir(null), "incoming");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(dir, job.filename + ".part");
        File fin = new File(dir, job.filename);

        boolean fullRetried = false;
        int ioCycles = 0;
        while (true) {
            long offset = part.exists() ? part.length() : 0;
            if (job.size > 0 && offset > job.size) {
                Diag.op("DL", ".part 比目标还大，删除重下：" + job.filename);
                //noinspection ResultOfMethodCallIgnored
                part.delete(); offset = 0;
            }
            if (job.size > 0 && offset == job.size && offset > 0) {
                Diag.op("DL", job.filename + " .part 已完整，直接进入发布");
                break;
            }
            Diag.op("DL", "开始下载 " + job.filename + " 总 " + PhotoItem.fmtSize(job.size)
                    + (offset > 0 ? "，从偏移 " + offset + " 续传" : "，整文件"));
            try (FileOutputStream fos = new FileOutputStream(part, offset > 0)) {
                final long off0 = offset;
                final long[] lastNotify = {0};
                client.downloadObject(job.handle, job.size, offset, fos, doneBytes -> {
                    // client 回调口径统一为「文件内绝对字节数」（整传从 0 起、续传含已有偏移）
                    Listener l = listener;
                    if (l != null) l.onProgress(job, doneBytes);
                    long now = System.currentTimeMillis();
                    if (now - lastNotify[0] > 400) {
                        lastNotify[0] = now;
                        int pct = job.size > 0 ? (int) (doneBytes * 100 / job.size) : -1;
                        notifyProgress(job.filename + "  " + PhotoItem.fmtSize(doneBytes) + "/" + PhotoItem.fmtSize(job.size), pct);
                    }
                });
                break; // 下载成功
            } catch (PtpIpClient.PtpException e) {
                if (e.responseCode == PtpIpClient.RC_RESUME_EXHAUSTED) {
                    // v0.6.2：客户端内续拉已达上限——.part 里的已收字节有效，保留待下次继续，不删不重下
                    throw new Exception("下载多次中断未完成（已收部分已保留为断点，下次点下载会继续）：" + e.getMessage());
                } else if (e.responseCode == -1) {
                    // IO 断裂 → 重连后从 .part 续
                    ioCycles++;
                    Diag.op("DL", "IO 断裂（第 " + ioCycles + " 轮）：" + e.getMessage());
                    if (ioCycles > 4) throw new Exception("多次断线重连失败");
                    if (!client.isConnected()) {
                        boolean rok = client.reconnect(AppState.get().network);
                        if (!rok) throw new Exception("自动重连失败");
                    }
                } else {
                    // 相机层拒绝（Partial 不支持/profile 禁用/偏移超限）→ 删 .part 整传一次
                    Diag.op("DL", "相机拒绝 rc=0x" + Integer.toHexString(e.responseCode) + "：" + e.getMessage());
                    if (offset > 0 && !fullRetried) {
                        fullRetried = true;
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        Diag.op("DL", "已删 .part，降级整文件 GetObject 重下");
                    } else {
                        throw new Exception("相机返回错误 rc=0x" + Integer.toHexString(e.responseCode));
                    }
                }
            }
        }

        long got = part.exists() ? part.length() : 0;
        if (job.size > 0 && got != job.size) {
            throw new Exception("长度校验失败：期望 " + job.size + " 实际 " + got);
        }
        //noinspection ResultOfMethodCallIgnored
        if (fin.exists()) fin.delete();
        if (!part.renameTo(fin)) throw new Exception(".part 改名失败");
        publishToGallery(fin, job.filename);
        //noinspection ResultOfMethodCallIgnored
        fin.delete();
        done.add(job.filename);
        prefs.edit().putStringSet("done_files", done).apply();
        // v0.3：同步登记到已下载记录（图库角标/未下载筛选/自动回传去重的统一数据源）
        DownloadRecord.add(getApplicationContext(), job.filename, job.handle, job.size);
        Diag.op("DL", "完成并已存入相册 Pictures/QingmuTuchuan/" + job.filename);
    }

    private void publishToGallery(File src, String filename) throws Exception {
        // v0.4.2 起统一走 AlbumSaver（旧实现误用 Files 集合写 Pictures，API 29+ 必失败，同出片 bug）
        AlbumSaver.saveToAlbum(this, src, filename, mimeOf(filename), "Pictures/QingmuTuchuan", false);
    }

    private static String mimeOf(String filename) {
        String f = filename.toUpperCase();
        if (f.endsWith(".JPG") || f.endsWith(".JPEG")) return "image/jpeg";
        if (f.endsWith(".NEF")) return "image/x-nikon-nef";
        if (f.endsWith(".MP4")) return "video/mp4";
        if (f.endsWith(".MOV")) return "video/quicktime";
        return "application/octet-stream";
    }
}
