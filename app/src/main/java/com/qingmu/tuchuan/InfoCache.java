package com.qingmu.tuchuan;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * v0.6.3 ObjectInfo 跨会话缓存（Z 30 实测对策：5505 个对象逐项 GetObjectInfo 约 55ms/项，
 * 全量识别一遍约 7 分钟；Z fc 69 张无感、大卡必堵）。同一张卡重复连接时，文件名/大小/
 * 日期/格式码直接命中缓存渲染，零请求；只对未命中的句柄按需（可视区驱动）向相机取并写回。
 *
 * 存储：App 私有目录 files/object_info_cache.tsv，一行一条：
 *   storageKey \t handle(hex) \t filename \t size \t captureDate \t format(hex) \t ts
 * storageKey = GetStorageInfo 的描述+卷标+总容量（连接时由 MainActivity 组装，见 AppState.storageIdentity）：
 * 换卡/换存储即换键，天然隔离；句柄只在同一存储内有意义，故必须带存储键（与已下载记录
 * 以文件名为主键不同——那里文件名跨卡更稳，这里要的是「同卡同句柄」精确命中）。
 * 单条约 80~110 字节；上限 {@link #MAX_ENTRIES} 条，超限按「最久未用的存储键整表淘汰」
 * （一张 5505 张的卡约 0.6MB 量级，上限约覆盖 3~4 张满卡，相对照片体积极小）。
 * 变更先写内存 + 脏计数，攒满 {@link #FLUSH_EVERY} 条或上层显式 flush() 才整文件重写
 * （先写 .tmp 再改名，防写一半损坏，与 DownloadRecord 同口径）。
 *
 * 错位保护：句柄在某些情况下可能漂移（卡上增删照片后旧句柄指向新文件）。下载阶段若出现
 * 长度校验与缓存大小冲突，DownloadService 会 evict 该条并重新取 Info 一次（见其 [CACHE] 日志），
 * 不让错误缓存把文件下错。
 */
public final class InfoCache {

    static final int MAX_ENTRIES = 20000;
    private static final int FLUSH_EVERY = 64;

    public static class Entry {
        public int handle;
        public String filename = "";
        public long size;
        public String captureDate = "";
        public int format;
        public long ts;
    }

    /** storageKey → (handle → Entry) */
    private static final Map<String, Map<Integer, Entry>> TABLES = new HashMap<>();
    /** storageKey → 最近使用时刻（整表淘汰按此排序） */
    private static final Map<String, Long> LAST_USED = new HashMap<>();
    private static File file;
    private static boolean loaded = false;
    private static int dirty = 0;
    private static int total = 0;

    private InfoCache() {}

    public static synchronized void init(Context ctx) {
        if (loaded) return;
        loaded = true;
        file = new File(ctx.getApplicationContext().getFilesDir(), "object_info_cache.tsv");
        TABLES.clear(); LAST_USED.clear(); total = 0;
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length < 7 || p[0].isEmpty()) continue;
                    Entry e = new Entry();
                    try { e.handle = Integer.parseInt(p[1], 16); } catch (Exception ignore) { continue; }
                    e.filename = p[2];
                    try { e.size = Long.parseLong(p[3]); } catch (Exception ignore) { e.size = 0; }
                    e.captureDate = p[4];
                    try { e.format = Integer.parseInt(p[5], 16); } catch (Exception ignore) { e.format = 0; }
                    try { e.ts = Long.parseLong(p[6]); } catch (Exception ignore) { e.ts = 0; }
                    table(p[0]).put(e.handle, e);
                    total++;
                    Long lu = LAST_USED.get(p[0]);
                    if (lu == null || e.ts > lu) LAST_USED.put(p[0], e.ts);
                }
            } catch (Exception e) {
                Diag.op("CACHE", "ObjectInfo 缓存读取失败（按空缓存继续）：" + e.getMessage());
            }
        }
        Diag.op("CACHE", "ObjectInfo 缓存就绪：" + total + " 条 / " + TABLES.size()
                + " 个存储，文件 " + storeBytes() + " B");
    }

    private static Map<Integer, Entry> table(String storageKey) {
        Map<Integer, Entry> t = TABLES.get(storageKey);
        if (t == null) { t = new HashMap<>(); TABLES.put(storageKey, t); }
        return t;
    }

    /** 命中返回缓存条目（并刷新该存储的最近使用时刻）；未命中返回 null */
    public static synchronized Entry get(String storageKey, int handle) {
        if (storageKey == null || storageKey.isEmpty()) return null;
        Map<Integer, Entry> t = TABLES.get(storageKey);
        if (t == null) return null;
        Entry e = t.get(handle);
        if (e != null) LAST_USED.put(storageKey, System.currentTimeMillis());
        return e;
    }

    /** 写入/更新一条（新从相机取到 Info 后调用）；攒满一批自动落盘 */
    public static synchronized void put(String storageKey, PtpIpClient.ObjectInfo oi, int handle) {
        if (storageKey == null || storageKey.isEmpty() || oi == null) return;
        Map<Integer, Entry> t = table(storageKey);
        Entry e = t.get(handle);
        if (e == null) { e = new Entry(); e.handle = handle; t.put(handle, e); total++; }
        e.filename = oi.filename != null ? oi.filename : "";
        e.size = oi.size;
        e.captureDate = oi.captureDate != null ? oi.captureDate : "";
        e.format = oi.format;
        e.ts = System.currentTimeMillis();
        LAST_USED.put(storageKey, e.ts);
        dirty++;
        evictIfNeeded(storageKey);
        if (dirty >= FLUSH_EVERY) persist();
    }

    /** 淘汰一条（缓存错位保护：下载长度与缓存大小冲突时由 DownloadService 调用） */
    public static synchronized void evict(String storageKey, int handle) {
        Map<Integer, Entry> t = TABLES.get(storageKey);
        if (t != null && t.remove(handle) != null) {
            total--;
            dirty++;
            Diag.op("CACHE", "淘汰缓存条目 handle=0x" + Integer.toHexString(handle)
                    + "（句柄-文件不符，重新取 Info）");
            persist();
        }
    }

    /** 把缓存条目还原成 ObjectInfo（仅填缓存字段，其余字段默认） */
    public static PtpIpClient.ObjectInfo toObjectInfo(Entry e) {
        PtpIpClient.ObjectInfo oi = new PtpIpClient.ObjectInfo();
        oi.filename = e.filename;
        oi.size = e.size;
        oi.captureDate = e.captureDate;
        oi.format = e.format;
        return oi;
    }

    public static synchronized int totalCount() { return total; }

    public static synchronized long storeBytes() {
        return file != null && file.exists() ? file.length() : 0;
    }

    /** 上层在列表加载完成/断开等节点显式落盘（有脏数据才写） */
    public static synchronized void flush() {
        if (dirty > 0) persist();
    }

    /** 超限淘汰：按最久未用的存储键整表淘汰（保护刚写入的当前存储；实在只剩它时裁其最旧条目） */
    private static void evictIfNeeded(String protectKey) {
        while (total > MAX_ENTRIES) {
            String oldestKey = null; long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, Long> kv : LAST_USED.entrySet()) {
                if (kv.getKey().equals(protectKey)) continue;
                Map<Integer, Entry> t = TABLES.get(kv.getKey());
                if (t == null || t.isEmpty()) continue;
                if (kv.getValue() < oldestTs) { oldestTs = kv.getValue(); oldestKey = kv.getKey(); }
            }
            if (oldestKey != null) {
                Map<Integer, Entry> t = TABLES.remove(oldestKey);
                LAST_USED.remove(oldestKey);
                int n = t != null ? t.size() : 0;
                total -= n;
                dirty++;
                Diag.op("CACHE", "缓存超限（>" + MAX_ENTRIES + " 条），整表淘汰最久未用存储的 "
                        + n + " 条（key=" + oldestKey + "）");
                continue;
            }
            // 只剩当前存储一张表且仍超限：裁掉它最旧的条目（极端情况兜底，保证上限不失控）
            Map<Integer, Entry> t = TABLES.get(protectKey);
            if (t == null) break;
            Entry oldest = null;
            for (Entry e : t.values()) if (oldest == null || e.ts < oldest.ts) oldest = e;
            if (oldest == null) break;
            t.remove(oldest.handle);
            total--;
            dirty++;
        }
    }

    private static void persist() {
        if (file == null) return;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Map<Integer, Entry>> kv : TABLES.entrySet()) {
                String key = kv.getKey().replace('\t', ' ').replace('\n', ' ');
                for (Entry e : kv.getValue().values()) {
                    sb.append(key).append('\t')
                            .append(Integer.toHexString(e.handle)).append('\t')
                            .append(e.filename != null ? e.filename.replace('\t', ' ') : "").append('\t')
                            .append(e.size).append('\t')
                            .append(e.captureDate != null ? e.captureDate : "").append('\t')
                            .append(Integer.toHexString(e.format)).append('\t')
                            .append(e.ts).append('\n');
                }
            }
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Diag.op("CACHE", "ObjectInfo 缓存落盘失败：" + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        if (file.exists()) file.delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
        dirty = 0;
    }
}
