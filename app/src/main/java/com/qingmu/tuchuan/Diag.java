package com.qingmu.tuchuan;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局诊断日志：协议层每个关键节点都打这里（连接阶段/操作码/响应码/重连/续传偏移）。
 * 上限 300 行环形缓冲，UI 诊断页订阅 Listener 实时刷新；一键复制全部靠 getAllText()。
 * 真机联调的灵魂——没有相机在手时，代码正确性全靠这份日志回溯。
 */
public final class Diag {
    public static final int MAX_LINES = 300;

    public interface Listener { void onNewLine(); }

    private static final List<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile Listener listener;

    private Diag() {}

    public static void setListener(Listener l) { listener = l; }

    public static synchronized void log(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        LINES.add(line);
        while (LINES.size() > MAX_LINES) LINES.remove(0);
        Listener l = listener;
        if (l != null) l.onNewLine();
    }

    /** 带标签的协议日志：op 名 + 结果，格式统一方便联调时 grep */
    public static void op(String op, String detail) { log("[" + op + "] " + detail); }

    public static synchronized String getAllText() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized List<String> snapshot() { return new ArrayList<>(LINES); }

    public static synchronized void clear() { LINES.clear(); }
}
