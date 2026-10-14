package com.qingmu.tuchuan;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v0.5 应用内在线更新：GitHub Releases 单源（本项目仅 GitHub 发布，不做 Gitee 镜像）。
 *
 * 网络纪律（关键）：连接相机 AP 时进程被 CameraWifi 绑到相机网络（无互联网），
 * 更新检查/下载绝不能走绑定网络——一律显式挑选「非进程绑定、且声明有互联网能力」的
 * Network 并用 network.openConnection() 发请求；挑不到可用网络就直接判失败。
 * 失败策略：自动检查失败全程静默（只记 [UPDATE] 诊断，不弹任何提示、不阻塞启动/连接）；
 * 手动检查失败由 UI 给一句中文提示。
 */
public final class Updater {

    private static final String LATEST_URL =
            "https://api.github.com/repos/tsingmuchoy/qingmu-tuchuan/releases/latest";

    public static class ReleaseInfo {
        public String version;      // tag 去掉前导 v，如 0.5.1
        public String tag;          // 原始 tag，如 v0.5.1
        public String notesExcerpt; // 发布说明摘录（纯文本，已裁行数/字数）
        public String apkName;      // 资产文件名
        public String apkUrl;       // 资产下载直链
        public long apkSize;        // 资产字节数（下载后据此校验）
    }

    public interface CheckCallback {
        /** newer!=null 有新版；newer==null 且 error==null 已是最新；error!=null 检查失败（原因只进诊断） */
        void onResult(ReleaseInfo newer, String error);
    }

    public interface DownloadCallback {
        void onProgress(long done, long total);
        void onDone(File apk);
        void onError(String error);
    }

    private Updater() {}

    /** 当前安装的 versionName（取自包信息，与构建注入的 --version-name 同源，天然准确） */
    public static String currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    /** 语义版本比较：按点分段逐段比数值（缺段按 0）；a>b 返回正、相等 0、a<b 返回负 */
    public static int compareVersions(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("\\.");
        String[] pb = b.replaceFirst("^[vV]", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseSeg(pa[i]) : 0;
            int vb = i < pb.length ? parseSeg(pb[i]) : 0;
            if (va != vb) return va - vb;
        }
        return 0;
    }

    private static int parseSeg(String s) {
        try {
            // 段里可能带后缀（如 0-beta），只取前导数字部分
            int i = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 挑一张能真正上网的 Network：排除进程当前绑定网络（相机 AP），
     * 必须声明 NET_CAPABILITY_INTERNET，优先已验证（VALIDATED）与蜂窝。
     * 挑不到返回 null（调用方据此判失败，连默认连接也不试——默认路由此刻多半就是相机网）。
     */
    private static Network pickInternetNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network bound = cm.getBoundNetworkForProcess();
            Network best = null;
            int bestScore = -1;
            for (Network n : cm.getAllNetworks()) {
                if (bound != null && n.equals(bound)) continue; // 绝不用相机绑定网络
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                int score = 0;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 10;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 2;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 1;
                if (score > bestScore) { bestScore = score; best = n; }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpURLConnection openVia(Network net, String urlStr, int connectMs, int readMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(url) : url.openConnection());
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "QingmuTuchuan-Updater");
        return c;
    }

    /** 异步检查更新（内部起线程；回调在该线程，UI 侧自行切主线程） */
    public static void checkAsync(final Context ctx, final CheckCallback cb) {
        new Thread(() -> {
            String cur = currentVersion(ctx);
            try {
                Network net = pickInternetNetwork(ctx);
                if (net == null) {
                    // 无可用互联网网络：若进程没绑相机网，退一步用系统默认连接试一次（家里 STA 场景进程未绑定）
                    ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm == null || cm.getBoundNetworkForProcess() != null) {
                        Diag.op("UPDATE", "检查更新跳过：没有可用的互联网网络（可能正连着相机热点且无其他网络）");
                        cb.onResult(null, "无可用互联网网络");
                        return;
                    }
                }
                HttpURLConnection c = openVia(net, LATEST_URL, 6000, 8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) {
                    Diag.op("UPDATE", "检查更新失败：GitHub API 返回 HTTP " + code);
                    cb.onResult(null, "HTTP " + code);
                    return;
                }
                String json = readAll(c.getInputStream());
                JSONObject root = new JSONObject(json);
                String tag = root.optString("tag_name", "");
                ReleaseInfo info = new ReleaseInfo();
                info.tag = tag;
                info.version = tag.replaceFirst("^[vV]", "");
                info.notesExcerpt = excerpt(root.optString("body", ""));
                JSONArray assets = root.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.endsWith(".apk")) {
                            info.apkName = name;
                            info.apkUrl = a.optString("browser_download_url", "");
                            info.apkSize = a.optLong("size", 0);
                            break;
                        }
                    }
                }
                if (info.version.isEmpty() || info.apkUrl == null || info.apkUrl.isEmpty()) {
                    Diag.op("UPDATE", "检查更新失败：发布信息不完整（tag=" + tag + "，无 APK 资产）");
                    cb.onResult(null, "发布信息不完整");
                    return;
                }
                if (compareVersions(info.version, cur) > 0) {
                    Diag.op("UPDATE", "发现新版 " + info.tag + "（当前 v" + cur + "），资产 " + info.apkName
                            + " " + info.apkSize + "B");
                    cb.onResult(info, null);
                } else {
                    Diag.op("UPDATE", "已是最新：当前 v" + cur + "，远端 latest=" + tag);
                    cb.onResult(null, null);
                }
            } catch (Exception e) {
                Diag.op("UPDATE", "检查更新异常（静默处理）：" + e.getClass().getSimpleName() + " " + e.getMessage());
                cb.onResult(null, String.valueOf(e.getMessage()));
            }
        }, "qingmu-update-check").start();
    }

    /** 发布说明摘录：取正文前几行纯文本（剥掉 Markdown 记号），限行数与总字数，防止卡片被长文撑爆 */
    private static String excerpt(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String raw : body.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            s = s.replaceAll("^[#>*\\-]+\\s*", "").replace("**", "").replace("`", "").trim();
            if (s.isEmpty() || s.startsWith("---")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
            if (++lines >= 5 || sb.length() >= 320) break;
        }
        String r = sb.toString();
        return r.length() > 320 ? r.substring(0, 320) + "…" : r;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    /**
     * 异步下载 APK 到 cacheDir/qm-update/（先清旧包防堆积），边下边报进度；
     * 下完严格校验文件大小 == 资产元数据大小，不一致删文件报错（防半包/被劫持替换的尺寸异常）。
     */
    public static void downloadAsync(final Context ctx, final ReleaseInfo info, final DownloadCallback cb) {
        new Thread(() -> {
            File dir = UpdateFileProvider.updateDir(ctx.getCacheDir());
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) { /*noinspection*/ f.delete(); } // 轻量纪律：更新包只留当前一个
                Network net = pickInternetNetwork(ctx);
                if (net == null) {
                    Diag.op("UPDATE", "下载更新失败：没有可用的互联网网络");
                    cb.onError("无可用互联网网络");
                    return;
                }
                File out = new File(dir, info.apkName);
                HttpURLConnection c = openVia(net, info.apkUrl, 8000, 20000);
                int code = c.getResponseCode();
                if (code != 200) {
                    Diag.op("UPDATE", "下载更新失败：HTTP " + code);
                    cb.onError("服务器返回 " + code);
                    return;
                }
                InputStream in = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[32768];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    cb.onProgress(done, info.apkSize);
                }
                fos.close();
                in.close();
                long actual = out.length();
                if (info.apkSize > 0 && actual != info.apkSize) {
                    Diag.op("UPDATE", "下载更新校验失败：文件大小 " + actual + " != 资产元数据 " + info.apkSize + "，已删除");
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    cb.onError("文件大小校验不一致");
                    return;
                }
                Diag.op("UPDATE", "更新包下载完成并校验通过：" + info.apkName + " " + actual + "B（存 cacheDir/qm-update，系统可随时清）");
                cb.onDone(out);
            } catch (Exception e) {
                Diag.op("UPDATE", "下载更新异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
                cb.onError(String.valueOf(e.getMessage()));
            }
        }, "qingmu-update-dl").start();
    }
}
