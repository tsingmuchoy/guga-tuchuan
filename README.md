# 清木图传 Qingmu Tuchuan 📷🐧

一款直连尼康相机的安卓 Wi-Fi 图传 App：不走 SnapBridge，直接用标准 PTP/IP 协议连相机，
看缩略图墙、批量下载 JPEG + NEF 原图，带断点续传、断线自动重连和全程连接诊断。

> 由 **清木** 与 AI 助手「小咕嘎」共同打造的个人项目。非官方应用，与尼康（Nikon）无隶属关系。
> 首测机型为 Nikon Z fc；协议层机型无关，其余机型为「标准协议兼容、待真机验证」（见兼容性一节）。

## ✨ 功能

- 📶 **双入口连接**：相机 AP 热点直连（默认 192.168.1.1:15740），或「连接至 PC」/STA 模式手动输入相机局域网 IP；上次用的 SSID/IP 自动记住方便复连
- 🖼️ **缩略图墙**：全量照片网格浏览，内存 24MB + 磁盘三级缓存懒加载，JPG/NEF/MP4 格式徽标
- ✅ **多选批量下载**：勾选后显示已选数量与总大小估算，队列顺序下载、逐个进度 + 总进度
- ⏩ **断点续传**：GetPartialObject 按偏移续传；相机不支持时自动降级整文件重下
- 🔁 **断线自动重连**：指数退避重连 + 会话重建，下载队列从 .part 偏移继续
- 💾 **存入相册**：成品经 MediaStore 存到 Pictures/QingmuTuchuan，已传文件自动登记防重复下载
- 🩺 **连接诊断**：协议每个关键节点（握手、操作码/响应码、命中机型 profile、重连、续传偏移）全打日志，诊断页可一键复制
- 🎨 **尼康黄黑风 UI**：纯黑底 + 尼康黄主色；全部图标均为 VectorDrawable，零位图（启动器图标为点阵相机：白点阵机身轮廓 + 黄点阵镜头）

## 📱 兼容机型与物理限制

- **首测**：Nikon Z fc。
- **标准协议兼容、待真机验证**：Z 系列其他机型（Z 50/Z 5/Z 6/Z 7 等）、内置 Wi-Fi 单反（D850/D780/D7500/D500/D750 等）、Coolpix Wi-Fi 机型——连接后先读 GetDeviceInfo，按内置机型 profile 表适配，未知机型走标准 PTP/IP 默认档，不做白名单拦截。
- **老机型**：插 WU-1a/WU-1b 无线适配器后，按 WU 适配器 profile 连接。
- **物理不可用（如实说明）**：无 Wi-Fi 的机型（如仅蓝牙的 D3400/D3500）没有 PTP/IP 入口，本 App 无法连接，不要指望蓝牙传图；这类机型只能加装 WU-1a/1b 适配器或换机。

## 🚀 连接使用步骤

1. 相机上开启 Wi-Fi：选「建立 Wi-Fi 连接」（相机当热点）或「连接至 PC」（相机加入你家局域网）。
2. 打开清木图传「连接」页：AP 模式填相机屏幕显示的 SSID/密码点连接；STA 模式填相机在局域网里的 IP（端口默认 15740）。
3. 连上后进「照片」页，缩略图会自动加载；勾选要传的照片，点底部下载。
4. 下载在前台服务里跑，成品进系统相册 Pictures/QingmuTuchuan；传一半断线会自动重连续传。
5. 连不上或传不动时，去「诊断」页把日志复制出来——响应码和失败阶段都写在里面。

> 小提示：联调/使用前建议先强停 SnapBridge，它会在后台抢占相机的 PTP 会话。

## 📦 安装

到本仓库的 **Releases** 下载最新 APK 直接安装（安卓 8.0+）。当前最新版 v0.1.1。

## ✍️ 作者

**清木 (Tsingmu)**
- B 站主页：https://space.bilibili.com/1432258677
- 邮箱：tsingmu35607@gmail.com

## 📄 许可证

[MIT](LICENSE)

## 📝 更新日志

- **v0.1.1（2026-10-13）**：软件更名为「清木图传」（原名咕嘎图传），包名改为 com.qingmu.tuchuan；启动器图标更换为简约点阵相机；功能与 v0.1.0 完全一致。
- **v0.1.0（2026-10-13）**：首个版本。PTP/IP 双入口连接、缩略图墙、批量下载 + 断点续传 + 自动重连、连接诊断、多机型 profile。

---

以下为工程与协议技术细节。

## 工程与构建

- 目录仿咕嘎音乐：源码 `app/src/main`，手动管线 `build-manual/build.sh <code> <name>`
  （aapt2 → javac -source 8 → d8 → zipalign → apksigner，调试签名与咕嘎音乐同一把）。
- 框架-only：纯 Java + Android framework API，零 AndroidX、零第三方库。
- 产物：`qingmu-tuchuan-v0.1.1.apk`（67,750 字节，minSdk 26 / targetSdk 34）。
- UI：B 尼康黄黑风（纯黑 #000 + 尼康黄 #FFDE00），颜色集中在 `Ui.java` + themes.xml；
  图标全量 VectorDrawable（清单见文末），无 PNG、无 emoji 图标。

## 架构

| 文件 | 职责 |
|---|---|
| `PtpIpClient.java` | PTP/IP 双通道客户端：Init 握手、PTP 操作层、事件线程+10s 心跳/45s 判死、指数退避重连、GetObject/GetPartialObject 流式下载 |
| `CameraProfile.java` | 机型 profile 表（Model 包含匹配）：DEFAULT / Z_FC / Z_50_30 / Z_FULLFRAME / Z_GENERIC / DSLR_WIFI_850_780 / DSLR_WIFI_MID / WU_ADAPTER / COOLPIX_WIFI |
| `CameraWifi.java` | 入口①相机 AP：WifiNetworkSpecifier（API29+），bindProcessToNetwork + Network SocketFactory 双保险；低版本手动连热点兜底 |
| `ThumbCache.java` | 缩略图三级缓存：内存 LruCache 24MB → 磁盘（可关）→ GetThumb 懒加载；失败按 profile 降级 |
| `DownloadService.java` | 前台 Service 下载队列：.part 续传 → Partial 报错整传降级 → IO 断线重连续传（≤4 轮）；MediaStore 发布 Pictures/QingmuTuchuan + 已传登记防重复 |
| `MainActivity.java` | 三页 UI：连接（双入口+设备卡+兼容性说明）/ 照片（网格多选+进度）/ 诊断（300 行日志+复制） |
| `Diag.java` | 全局诊断日志（环形 300 行），协议每个关键节点必打：握手阶段、操作码/响应码、PROFILE 命中、重连次数、续传偏移 |

## 协议要点（libgphoto2 ptpip.c 实证）

- 包头：uint32 小端总长 + uint32 类型。命令通道 InitCommandRequest(1)→Ack(2)，
  负载含 16B initiator GUID + UTF-16LE 名 "QingmuTuchuan" + 版本 0x00010000；
  Ack 负载 = connection number(4B) + 相机 GUID(16B) + 相机名(UTF-16LE)。
- 事件通道 InitEventRequest(3) 带 connection number → Ack(4)；建不起来只降级纯命令通道，不判连接失败。
- OperationRequest 负载：DataPhaseInfo(4B，收数据/无数据=1，发数据=2) + 操作码(2B) + 事务ID(4B) + 参数。
  OpenSession 事务 ID 固定 0，之后自增。响应码非 0x2001 一律记诊断并抛 PtpException。
- 数据阶段：StartDataPacket(9，含 uint64 总长) → DataPacket(10) → EndDataPacket(12) → Response(7)。
- GetPartialObject(0x101B) 参数 = handle/offset(uint32)/maxBytes；偏移超 32 位（>4GB 文件续传）暂不支持，会记诊断并降级。

## 降级链（全部记诊断）

1. GetPartialObject 相机报错或 profile 禁用 → 删 .part、整文件 GetObject 重下一次。
2. GetThumb 失败 → 矢量占位 + 文件名；profile 决定滚动回来是否自动重试（WU 档只试一次，点占位行为待 v0.2 加手动重试）。
3. 事件通道建不起来 / profile 声明不可靠 → 纯命令通道模式，状态栏标注「功能受限」。
4. 任一通道 IO 断裂 → 指数退避（1/2/4/8/15s）重连 + OpenSession 重建 + 队列 .part 续传。

## 已实现 / 未实现（诚实清单）

已实现：双入口连接（AP specifier / STA 手动 IP+端口，上次参数持久化）、GetDeviceInfo/电量/
存储信息、机型 profile 匹配与应用、照片列表（ObjectInfo 全量）、缩略图墙三级缓存与多选、
下载队列全套续传/降级/重连、MediaStore 落盘、诊断页、前台通知进度、尼康黄黑 UI + 全矢量图标。

占位/未实现：
- 遥控拍摄、实时取景（LiveView 0x9203 等）未做，属 v2 规划。
- 多存储卡只取第一个存储（GetStorageIDs 其余仅记日志）。
- 占位图点击手动重试缩略图未接（自动重试已按 profile 生效）。
- STA 模式下相机「连接至 PC」的配对确认弹窗行为（相机屏需按 OK）未实测，可能需要用户在相机上确认一次。
- 密码以明文存 App 私有 SharedPreferences（v0.1 图省事，后续可换加密存储）。

## 待真机验证清单（按机型家族）

### Z 系列（首测：Z fc）
- 入口：相机 AP「建立 Wi-Fi 连接」/「连接至 PC」STA 两条都要测；先强停 SnapBridge 防抢连。
- 风险点：SnapBridge 抢占 PTP 会话；STA 模式配对确认；GetPartialObject 对 NEF 大文件续传偏移正确性；
  断 Wi-Fi 中途拔电/走出范围后的重连与 .part 续传；事件通道在 Z fc 上的稳定性。

### 内置 Wi-Fi 单反（D850/D780/D7500/D500/D750…）
- 入口：「连接至智能设备」AP（192.168.1.1）或「连接至 PC」。
- 风险点：D750 等老机 Wi-Fi 信号弱、GetThumb 慢；部分机型 PC 模式要求先在相机建配置文件；
  Model 字符串实际拼写（回填 profile 匹配表）。

### WU-1a/1b 适配器机型（D3200/D3300/D5200/D7000/D600…）
- 入口：适配器 AP（SSID 形如 WU-1a_xxxx），连接页勾选「经 WU 适配器」。
- 风险点：WMU/SnapBridge 后台抢连（老论坛血泪：需强停/卸载官方 App）；带宽低，大 NEF 整传耗时；
  事件通道不可靠假设是否成立（profile 已按不可靠处理）。

### Coolpix Wi-Fi 机型
- 入口：相机 Wi-Fi AP。
- 风险点：PTP/IP 实现简化程度未知；GetObjectHandles 参数容忍度；事件通道已按不可靠预置。

### 物理不可用（如实标注，不做假兼容）
- 无 Wi-Fi 机型（如仅蓝牙的 D3400/D3500）没有 PTP/IP 入口，本 App 无法连接；
  需 Wi-Fi 时只能加 WU-1a/1b 或换机。

## 矢量图标清单（res/drawable，全部 <vector> 手写 pathData）

- ic_launcher_foreground.xml — 启动器 adaptive icon 前景（定稿：点阵相机——30×30 统一栅格圆点阵，白点勾勒机身双线轮廓、黄点实心镜头盘+白点外环、右上黄点方块、机顶黄点簇，纯黑底由 ic_launcher_background 提供；由 `tools/gen_launcher_icon.py` 生成，白点 213 + 黄点 64 合并为 2 条 path）
- ic_camera.xml — 相机（标题/设备卡）
- ic_wifi.xml — Wi-Fi/连接（胶囊/页签/连接按钮）
- ic_battery.xml — 电池（设备卡）
- ic_sdcard.xml — SD 卡（设备卡）
- ic_check_badge.xml — 黄圆黑勾（照片墙选中标记）
- ic_check.xml — 对勾（全选按钮）
- ic_download.xml — 下载（下载按钮/通知小图标）
- ic_settings.xml — 设置滑杆（缓存开关/关于卡）
- ic_diag.xml — 诊断脉冲（诊断页签）
- ic_back.xml — 返回箭头（预置，详情页 v0.2 用）
- ic_badge_image.xml — 图片徽标 JPG（照片页签/徽标/占位）
- ic_badge_video.xml — 视频徽标 MP4
- ic_badge_nef.xml — NEF 徽标（线条字形路径绘制）
- ic_copy.xml — 复制（诊断页）
- ic_clear.xml — 清除/断开（清空选择/断开按钮）
- 形状类（非图标）：bg_capsule_on/off、bg_card、bg_input、bg_btn_primary/ghost、bg_thumb_selected
