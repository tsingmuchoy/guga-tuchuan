package com.qingmu.tuchuan;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * v0.3 已下载记录（轻量）：记录「相机卡上的哪些对象已下载到相册」，供图库角标、
 * 未下载筛选与自动回传去重使用。
 *
 * 存储：App 私有目录 files/downloaded_records.tsv，一行一条：
 *   filename \t handle(hex) \t size \t epochMillis
 * 相机文件名形如 DSC_0001.JPG，单条约 45~60 字节；一卡 2000 张照片的记录文件约 100KB 量级，
 * 相对照片本身体积可忽略。查/增/删全走内存 Map，变更时整文件重写（先写 .tmp 再改名，防写一半损坏）。
 *
 * 主键用文件名（同一张卡内唯一）：PTP handle 在重新枚举/换卡后可能漂移，故 handle 只作参考记录。
 * v0.2 及更早的已传登记在 SharedPreferences done_files（只有文件名）：首次加载时自动并入本记录迁移。
 */
public final class DownloadRecord {

    public static class Entry {
        public String name;
        public int handle;
        public long size;
        public long ts;
    }

    private static final Map<String, Entry> MAP = new HashMap<>();
    private static File file;
    private static boolean loaded = false;

    private DownloadRecord() {}

    /** 幂等初始化：首次调用读文件（并迁移旧 done_files 登记），之后直接返回 */
    public static synchronized void init(Context ctx) {
        if (loaded) return;
        loaded = true;
        file = new File(ctx.getApplicationContext().getFilesDir(), "downloaded_records.tsv");
        MAP.clear();
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split("\t");
                    if (p.length < 4 || p[0].isEmpty()) continue;
                    Entry e = new Entry();
                    e.name = p[0];
                    try { e.handle = Integer.parseInt(p[1], 16); } catch (Exception ignore) { e.handle = 0; }
                    try { e.size = Long.parseLong(p[2]); } catch (Exception ignore) { e.size = 0; }
                    try { e.ts = Long.parseLong(p[3]); } catch (Exception ignore) { e.ts = 0; }
                    MAP.put(e.name, e);
                }
            } catch (Exception e) {
                Diag.op("RECORD", "已下载记录读取失败（按空记录继续）：" + e.getMessage());
            }
        }
        // 迁移 v0.2 的 done_files（文件名集合）：记录文件里没有的补进来（handle/size 未知记 0）
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("guga_tuchuan", Context.MODE_PRIVATE);
            Set<String> done = prefs.getStringSet("done_files", null);
            if (done != null) {
                int migrated = 0;
                for (String name : done) {
                    if (!MAP.containsKey(name)) {
                        Entry e = new Entry();
                        e.name = name; e.handle = 0; e.size = 0; e.ts = System.currentTimeMillis();
                        MAP.put(name, e);
                        migrated++;
                    }
                }
                if (migrated > 0) {
                    persist();
                    Diag.op("RECORD", "从旧版登记迁移 " + migrated + " 条已下载记录");
                }
            }
        } catch (Exception e) {
            Diag.op("RECORD", "旧登记迁移失败（忽略）：" + e.getMessage());
        }
        Diag.op("RECORD", "已下载记录就绪：" + MAP.size() + " 条，记录文件 " + storeBytes() + " B");
    }

    public static synchronized boolean isDownloaded(String filename) {
        return filename != null && MAP.containsKey(filename);
    }

    public static synchronized void add(Context ctx, String filename, int handle, long size) {
        init(ctx);
        if (filename == null || filename.isEmpty()) return;
        Entry e = new Entry();
        e.name = filename; e.handle = handle; e.size = size; e.ts = System.currentTimeMillis();
        MAP.put(filename, e);
        persist();
        Diag.op("RECORD", "登记已下载：" + filename + "（共 " + MAP.size() + " 条，文件 " + storeBytes() + " B）");
    }

    /** 删除一条（相机端对象被删后调用，避免同名新文件被误判已下载） */
    public static synchronized void remove(String filename) {
        if (filename != null && MAP.remove(filename) != null) {
            persist();
            Diag.op("RECORD", "移除已下载记录：" + filename + "（共 " + MAP.size() + " 条）");
        }
    }

    /** 全部清除（格式化成功后调用：原对象已不存在，记录整体失效） */
    public static synchronized void clearAll(Context ctx) {
        init(ctx);
        if (!MAP.isEmpty()) {
            MAP.clear();
            persist();
            Diag.op("RECORD", "已下载记录已全部清除");
            // 旧 done_files 也要同步清掉，否则 DownloadService 会继续按文件名跳过新文件
            try {
                ctx.getSharedPreferences("guga_tuchuan", Context.MODE_PRIVATE)
                        .edit().remove("done_files").apply();
            } catch (Exception ignore) {}
        }
    }

    public static synchronized int count() { return MAP.size(); }

    /** 记录文件当前字节数（体积量级汇报/诊断用） */
    public static synchronized long storeBytes() {
        return file != null && file.exists() ? file.length() : 0;
    }

    private static void persist() {
        if (file == null) return;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            StringBuilder sb = new StringBuilder();
            for (Entry e : MAP.values()) {
                sb.append(e.name).append('\t')
                        .append(Integer.toHexString(e.handle)).append('\t')
                        .append(e.size).append('\t')
                        .append(e.ts).append('\n');
            }
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Diag.op("RECORD", "已下载记录落盘失败：" + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        if (file.exists()) file.delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }
}
