package com.qingmu.tuchuan;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * v0.6.1：未捕获异常最后一行防线——把崩溃堆栈同步落盘到 filesDir/last_crash.txt，
 * 再交回系统默认处理器（崩溃行为不被吞）；下次启动时由 MainActivity 读出打进诊断 [CRASH] 并清除。
 * 起因：外部用户 Z 30 在「连接至 PC」（STA）模式点任何按钮就退出，但手上没有堆栈可定性，
 * 本机制保证下轮复现必能拿到实锤。
 */
public final class CrashLog {
    private static final String FILE_NAME = "last_crash.txt";
    private static volatile boolean installed = false;

    private CrashLog() {}

    public static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final File file = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("thread=" + thread.getName() + " time=" + System.currentTimeMillis());
                ex.printStackTrace(pw);
                pw.flush();
                FileWriter fw = new FileWriter(file, false);
                fw.write(sw.toString());
                fw.close();
            } catch (Exception ignore) {
                // 落盘失败也不许挡住系统默认处理
            }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    /** 启动时调用：有上次崩溃记录则逐行打进诊断 [CRASH] 并删除文件；无记录静默 */
    public static void dumpPending(Context ctx) {
        File file = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
        if (!file.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                Diag.op("CRASH", (first ? "上次运行未捕获异常（已持久化，本次启动回放）： " : "") + line);
                first = false;
            }
            br.close();
        } catch (Exception e) {
            Diag.op("CRASH", "读取上次崩溃记录失败：" + e.getMessage());
        } finally {
            try { file.delete(); } catch (Exception ignore) {}
        }
    }
}
