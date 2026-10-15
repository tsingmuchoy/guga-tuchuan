package com.qingmu.tuchuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v0.3 间隔拍摄前台 Service：按固定间隔走 v0.2 遥控拍摄链（PtpIpClient.nikonCapture，
 * 即 0x9207 两级降级链）连拍，WakeLock + 通知保活，进度实时回 UI。
 *
 * 纪律（用户要求）：
 *  - 任一张拍摄返回非 OK（机身模式不支持遥控拍摄等）→ 立刻明确报错并停止，
 *    不静默重试、不跳过装作没事；失败原因（响应码）进通知、UI 与诊断。
 *  - 可随时停：requestStop() 置位，睡眠分片检查，最迟 200ms 内响应（拍摄事务进行中则等该张出结果）。
 *  - 与自动回传组合：每张照片写入卡后相机发 ObjectAdded 事件，遥控中枢的自动回传会接力下载，
 *    本服务不重复处理下载。
 */
public class IntervalService extends Service {

    public interface Listener {
        void onProgress(int done, int total); // total=0 表示无限
        void onFinished(int done, String error); // error=null 正常结束（拍满/手动停见文案区分时由 done 与 total 判断）
    }

    public static final String EXTRA_INTERVAL_SEC = "interval_sec";
    public static final String EXTRA_TOTAL = "total";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean STOP = new AtomicBoolean(false);
    public static volatile Listener listener;

    private static final String CHANNEL = "qingmu_interval";
    private static final int NOTIF_ID = 43;

    public static boolean isRunning() { return RUNNING.get(); }

    public static void start(Context ctx, int intervalSec, int total) {
        Intent i = new Intent(ctx, IntervalService.class);
        i.putExtra(EXTRA_INTERVAL_SEC, intervalSec);
        i.putExtra(EXTRA_TOTAL, total);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    public static void requestStop() {
        STOP.set(true);
        Diag.op("INTERVAL", "收到手动停止请求");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "间隔拍摄", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NOTIF_ID, buildNotif("间隔拍摄", "准备中…", -1));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && RUNNING.compareAndSet(false, true)) {
            final int intervalSec = Math.max(1, intent.getIntExtra(EXTRA_INTERVAL_SEC, 5));
            final int total = Math.max(0, intent.getIntExtra(EXTRA_TOTAL, 0));
            STOP.set(false);
            Thread t = new Thread(() -> workLoop(intervalSec, total), "qingmu-interval");
            // v0.6.2：间隔拍摄线程未捕获异常只记 [GUARD]，不许直达系统默认处理器杀进程
            t.setUncaughtExceptionHandler((th, ex) ->
                    Diag.op("GUARD", "间隔拍摄线程未捕获异常（已拦截，App 未退出）：" + ex));
            t.start();
        }
        return START_STICKY;
    }

    private Notification buildNotif(String title, String text, int percent) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        return b.build();
    }

    private void notifyText(String text, int percent) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif("清木图传 · 间隔拍摄", text, percent));
    }

    private void workLoop(int intervalSec, int total) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qingmu:interval") : null;
        if (wl != null) wl.acquire(4 * 60 * 60 * 1000L); // 最长兜底 4 小时，正常结束会释放
        int done = 0;
        String error = null;
        Diag.op("INTERVAL", "间隔拍摄开始：间隔 " + intervalSec + "s，总张数 "
                + (total > 0 ? String.valueOf(total) : "无限（手动停）"));
        try {
            PtpIpClient client = AppState.get().client;
            if (client == null || !client.isConnected()) {
                error = "相机未连接，间隔拍摄无法开始";
                Diag.op("INTERVAL", error);
            } else {
                while (!STOP.get()) {
                    if (total > 0 && done >= total) break;
                    long t0 = System.currentTimeMillis();
                    int rc = client.nikonCapture();
                    if (rc != PtpIpClient.RC_OK) {
                        error = "第 " + (done + 1) + " 张拍摄失败，相机返回 rc=0x"
                                + Integer.toHexString(rc) + "（机身模式可能不支持遥控拍摄），已停止，不重试";
                        Diag.op("INTERVAL", error);
                        break;
                    }
                    done++;
                    Diag.op("INTERVAL", "已拍 " + done + (total > 0 ? "/" + total : "") + " 张");
                    Listener l = listener;
                    if (l != null) l.onProgress(done, total);
                    notifyText("已拍 " + done + (total > 0 ? "/" + total : "") + " 张 · 间隔 " + intervalSec + "s",
                            total > 0 ? done * 100 / total : -1);
                    if (total > 0 && done >= total) break;
                    if (STOP.get()) break;
                    long spent = System.currentTimeMillis() - t0;
                    long wait = intervalSec * 1000L - spent;
                    if (wait < 0) {
                        Diag.op("INTERVAL", "单张拍摄耗时 " + spent + "ms 超过间隔 " + intervalSec + "s，立即续拍下一张");
                    }
                    while (wait > 0 && !STOP.get()) {
                        long slice = Math.min(200, wait);
                        try { Thread.sleep(slice); } catch (InterruptedException ie) { STOP.set(true); break; }
                        wait -= slice;
                    }
                    if (!client.isConnected()) {
                        error = "连接已断开，间隔拍摄停止（已拍 " + done + " 张）";
                        Diag.op("INTERVAL", error);
                        break;
                    }
                }
            }
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
            RUNNING.set(false);
            boolean manual = STOP.get() && error == null;
            Diag.op("INTERVAL", "间隔拍摄结束：共 " + done + " 张"
                    + (error != null ? "（" + error + "）" : manual ? "（手动停止）" : "（拍满）"));
            Listener l = listener;
            if (l != null) l.onFinished(done, error);
            stopForeground(true);
            stopSelf();
        }
    }
}
