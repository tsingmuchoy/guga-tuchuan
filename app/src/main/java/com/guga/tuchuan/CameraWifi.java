package com.guga.tuchuan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

/**
 * 相机热点连接（入口①相机 AP 模式）。
 * API 29+ 用 WifiNetworkSpecifier 发起（系统弹确认框）；低版本降级：回调 onUnavailable，
 * UI 提示用户去系统 Wi-Fi 设置手动连相机热点，连上后走 findCameraNetwork() 兜底找 Network。
 * 拿到 Network 后 bindProcessToNetwork，且 PtpIpClient 的 Socket 一律经该 Network 创建，
 * 保证流量只走相机热点（手机同时连着互联网也不会串）。
 * 入口②「连接至 PC」/STA 模式不需要本类：手机与相机同在一个局域网，直接用相机 IP 连。
 */
public class CameraWifi {

    public interface Callback {
        void onAvailable(Network network);
        void onLost();
        /** specifier 路线不可用（低版本/被拒），UI 应引导系统设置手动连接 */
        void onUnavailable(String reason);
    }

    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private volatile Network network;

    public CameraWifi(Context ctx) {
        cm = (ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public Network getNetwork() { return network; }

    public void connect(String ssid, String password, Callback callback) {
        disconnect();
        if (Build.VERSION.SDK_INT < 29) {
            Diag.op("WIFI", "API<29 无 WifiNetworkSpecifier，降级：请去系统设置手动连相机热点");
            callback.onUnavailable("系统版本低于 Android 10，请在系统 Wi-Fi 设置里手动连接相机热点后回到本页点「连接」");
            return;
        }
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();
        cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                network = n;
                cm.bindProcessToNetwork(n);
                Diag.op("WIFI", "已连上相机热点并绑定进程网络");
                callback.onAvailable(n);
            }
            @Override public void onLost(Network n) {
                Diag.op("WIFI", "相机热点连接丢失");
                if (network == n) network = null;
                callback.onLost();
            }
            @Override public void onUnavailable() {
                Diag.op("WIFI", "相机热点连接不可用（用户取消/超时/密码错）");
                callback.onUnavailable("连接未建立：可能取消了系统确认框、密码错误或相机热点未开启");
            }
        };
        Diag.op("WIFI", "请求连接相机热点 SSID=" + ssid);
        cm.requestNetwork(req, cb);
    }

    /** 低版本/手动连热点后的兜底：在已连接的 Wi-Fi 网络里找一个（STA/手动场景） */
    public Network findWifiNetwork() {
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    public void disconnect() {
        if (cb != null && cm != null) {
            try { cm.unregisterNetworkCallback(cb); } catch (Exception ignore) {}
            cb = null;
        }
        if (cm != null) cm.bindProcessToNetwork(null);
        network = null;
    }
}
