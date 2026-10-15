package com.qingmu.tuchuan;

import android.net.Network;

import java.util.ArrayList;
import java.util.List;

/** 全进程共享状态（Activity 与 DownloadService 同进程直读，避免 IPC 复杂度） */
public final class AppState {
    private static final AppState INST = new AppState();
    public static AppState get() { return INST; }

    public PtpIpClient client;
    public CameraWifi wifi;
    /** PTP Socket 应走的网络：AP 模式=相机热点 Network；STA 模式=null（默认局域网路由） */
    public Network network;
    public PtpIpClient.DeviceInfo deviceInfo;
    public CameraProfile profile = CameraProfile.DEFAULT;
    public int battery = -1;
    public int storageId = 0;
    public String storageDesc = "";
    /** v0.6.3：ObjectInfo 缓存的存储标识（GetStorageInfo 描述+卷标+总容量组装，连接时写入；换卡即换键） */
    public String storageIdentity = "";
    public final List<PhotoItem> photos = new ArrayList<>();

    private AppState() {}

    public boolean sessionReady() { return client != null && client.isConnected(); }

    public void resetSession() {
        deviceInfo = null; battery = -1; storageId = 0; storageDesc = ""; storageIdentity = "";
        photos.clear();
    }
}
