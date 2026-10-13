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
- 🎥 **遥控监看全套（v0.2.0，待真机逐项验证）**：新增「遥控」页——
  - **相机状态面板**：电量、剩余可拍张数（存储信息相机估算）、光圈/快门/ISO/曝光补偿/白平衡/拍摄模式/对焦模式/拍摄驱动；取值与可写性全部来自相机 GetDevicePropDesc 自报，读不到的置灰并记诊断
  - **实时取景 LiveView**：尼康私有流程拉 JPEG 帧流，三级流水线（拉帧→独立解码→按帧时钟均匀呈现 pacing），工程目标**持续稳定 30fps**（稳定 > 帧率 > 分辨率：守不住自动降帧尺寸档 0xD1AC、再降帧率档 30→24→20→15 锁均匀，会话内只降不升；真实帧率以真机联调实测为准，本版未经真机验证）；网格线开关、直方图、点按对焦框
  - **点按对焦**：点画面 → 换算到 LV 图像像素坐标 → ChangeAfArea(0x9205) + AfDrive(0x90C1)，成败以相机响应码为准
  - **遥控快门与录像**：InitiateCaptureRecInMedia(0x9207) 拍照（两级降级到标准 0x100E）、MovieRec(0x920A/0x920B) 录像启停；拍照后监听 ObjectAdded 自动拉预览「拍完即览」（只预览不下原图）
  - **曝光参数调节**：光圈/快门/ISO/曝光补偿/白平衡可调，选项严格来自相机自报枚举/范围，SetDevicePropValue 写入后回读确认，失败展示相机返回码
  - **实时直方图**：取景帧降采样本地统计 RGB 三通道，纯内存不落盘，可开关
  - **机型门控**：Z 系 profile 遥控全开尝试；老单反/WU/Coolpix 默认仅状态面板+快门，其余标注「未验证」，诊断页有实验开关可手动放行；任何遥控操作失败只降级该功能，不拖垮图传主链路
- 🔁 **图传增强（v0.3.0，待真机逐项验证）**：
  - **拍完自动回传**：「照片」页开关（默认关）开启后，连接期间相机拍的新照片（遥控拍摄或直接按相机快门都算）经 ObjectAdded 事件自动排队下载到相册，可选仅 JPG / JPG+RAW；与已下载记录去重不重复传；开启状态在图库页顶部黄色横幅明示。依赖事件通道推送——事件通道不可靠的机型（WU/Coolpix 等）不会触发，界面会注明
  - **已下载记录与筛选**：本地轻量记录已下载对象（文件名/handle/大小，App 私有 TSV 文件，约 50 字节/条）；图库筛选条：全部 / 未下载 / 仅 JPG / 仅 RAW / 按日期（日期列表只列卡上实际存在的日期，自绘深色卡选择）；每格角标区分 已下载（绿）/ 未下载（灰）/ 部分已传（黄）
  - **RAW+JPG 配对折叠**：同名 NEF+JPG 折叠为一条（双徽标），点开可选 两个都下 / 只下 JPG / 只下 RAW，多选计数按文件数算；长按一条可整行全选/全清
- 🗂️ **相机管理（v0.3.0，待真机逐项验证）**：
  - **删除照片**：单张/批量走标准 DeleteObject(0x100B)，先弹自绘深色确认卡（列明张数与「删的是相机卡上的原文件、不可恢复」），逐张执行、失败逐张点名报告；删后同步刷新列表与已下载记录
  - **格式化存储卡**：FormatStore(0x100F)。高危三重防护：入口默认被诊断页实验开关门控（未开不可见）→ 普通确认卡讲清后果 → 长按确认按钮满 2 秒才执行。**未真机验证前请勿对主力卡使用**
  - **间隔拍摄**：遥控页可设间隔秒数与总张数（0=无限至手动停），前台服务保活执行、进度可见、可随时停；走 v0.2 遥控拍摄链，任一张失败立刻报错停下（不静默重试）；与自动回传同开可拍一张传一张

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

到本仓库的 **Releases** 下载最新 APK 直接安装（安卓 8.0+）。当前最新版 v0.3.0。

## ✍️ 作者

**清木 (Tsingmu)**
- B 站主页：https://space.bilibili.com/1432258677
- 邮箱：tsingmu35607@gmail.com

## 📄 许可证

[MIT](LICENSE)

## 📝 更新日志

- **v0.3.0（2026-10-13）**：图传增强 + 相机管理（均待真机逐项验证）：拍完自动回传（事件驱动、开关默认关、仅 JPG 或 JPG+RAW、已下载记录去重、图库顶部横幅明示）、已下载记录（轻量 TSV，约 50B/条）与筛选条（未下载/仅 JPG/仅 RAW/按日期）+ 已下载角标、RAW+JPG 配对折叠（可选只下其一、计数按文件数）；删除照片（DeleteObject，确认卡+逐张报告）、格式化存储卡（FormatStore，实验开关门控+双重确认+长按 2 秒，未真机验证前勿对主力卡使用）、间隔拍摄（前台服务保活、失败即停报错、可与自动回传组合）。
- **v0.2.0（2026-10-13）**：遥控监看全套（均待真机逐项验证）：相机状态面板、实时取景（流水线冲稳定 30fps + 自适应锁档）、点按对焦、遥控快门/录像、曝光参数调节、实时直方图、拍完即览；遥控功能按机型 profile 门控。连接层完成 libgphoto2 开源对标审计并修复 4 处（事务 ID 统一管理、事件通道连接重试、命令通道空闲保活、InitFail 诊断释义，详见「开源对标结论」）。
- **v0.1.1（2026-10-13）**：软件更名为「清木图传」（原名咕嘎图传），包名改为 com.qingmu.tuchuan；启动器图标更换为简约点阵相机；功能与 v0.1.0 完全一致。
- **v0.1.0（2026-10-13）**：首个版本。PTP/IP 双入口连接、缩略图墙、批量下载 + 断点续传 + 自动重连、连接诊断、多机型 profile。

---

以下为工程与协议技术细节。

## 工程与构建

- 目录仿咕嘎音乐：源码 `app/src/main`，手动管线 `build-manual/build.sh <code> <name>`
  （aapt2 → javac -source 8 → d8 → zipalign → apksigner，调试签名与咕嘎音乐同一把）。
- 框架-only：纯 Java + Android framework API，零 AndroidX、零第三方库。
- 产物：`qingmu-tuchuan-v0.3.0.apk`（117,615 字节，minSdk 26 / targetSdk 34）。
- UI：B 尼康黄黑风（纯黑 #000 + 尼康黄 #FFDE00），颜色集中在 `Ui.java` + themes.xml；
  图标全量 VectorDrawable（清单见文末），无 PNG、无 emoji 图标。

## 架构

| 文件 | 职责 |
|---|---|
| `PtpIpClient.java` | PTP/IP 双通道客户端：Init 握手、PTP 操作层（含发数据事务）、事件线程+回调、10s 心跳/45s 判死、命令通道空闲保活、指数退避重连、GetObject/GetPartialObject 流式下载、属性体系（PropDesc/类型化读写）、尼康私有操作封装；v0.3 加标准 DeleteObject/FormatStore |
| `CameraProfile.java` | 机型 profile 表（Model 包含匹配）：DEFAULT / Z_FC / Z_50_30 / Z_FULLFRAME / Z_GENERIC / DSLR_WIFI_850_780 / DSLR_WIFI_MID / WU_ADAPTER / COOLPIX_WIFI；v0.2 加遥控等级（FULL / BASIC） |
| `RemoteController.java` | v0.2 遥控中枢：profile 门控 + 功能级失败降级、状态面板/参数读写编排、LiveView 三级流水线与自适应锁档、点按对焦/快门/录像、事件→拍完即览；v0.3 事件流加自动回传判定（格式范围+会话去重，入队交给 MainActivity 的处理器并二次查记录/队列去重） |
| `DownloadRecord.java` | v0.3 已下载记录：App 私有 TSV（文件名/handle/大小/时间，约 50B/条）+ 内存 Map，变更整文件重写（tmp+改名）；首次启动把 v0.2 的 SharedPreferences done_files 登记迁移进来 |
| `IntervalService.java` | v0.3 间隔拍摄前台 Service：固定间隔循环 nikonCapture、WakeLock+通知进度、随时可停、任一张失败报错即停 |
| `ConfirmCard.java` | v0.3 自绘深色确认卡组件：普通确认 + 长按满 2 秒确认（格式化专用）两种模式 |
| `CamProps.java` | 属性元数据表 + 显示格式化（只对 PTP 规范明确的单位/枚举做语义化，其余显示相机原值不编造） |
| `HudView.java` | 取景叠加层（Canvas 自绘）：三分网格、AF 框、RGB 直方图；与点击共用 FIT_CENTER 显示矩形换算 |
| `CameraWifi.java` | 入口①相机 AP：WifiNetworkSpecifier（API29+），bindProcessToNetwork + Network SocketFactory 双保险；低版本手动连热点兜底 |
| `ThumbCache.java` | 缩略图三级缓存：内存 LruCache 24MB → 磁盘（可关）→ GetThumb 懒加载；失败按 profile 降级 |
| `DownloadService.java` | 前台 Service 下载队列：.part 续传 → Partial 报错整传降级 → IO 断线重连续传（≤4 轮）；MediaStore 发布 Pictures/QingmuTuchuan + 已传登记防重复；v0.3 成功后同步写 DownloadRecord，并提供队列文件名集合供自动回传去重 |
| `MainActivity.java` | 四页 UI：连接（双入口+设备卡+兼容性说明+相机管理/格式化）/ 照片（网格多选+进度+v0.3 自动回传卡/筛选条/配对折叠/已下载角标/删除）/ 遥控（状态面板+取景+拍摄+参数调节+拍完即览+v0.3 间隔拍摄）/ 诊断（300 行日志+复制+遥控实验开关） |
| `Diag.java` | 全局诊断日志（环形 300 行），协议每个关键节点必打：握手阶段、操作码/响应码、PROFILE 命中、重连次数、续传偏移、遥控每操作响应码与耗时、LiveView 每 5 秒帧率/耗时/帧间隔抖动汇总 |

## 协议要点（libgphoto2 ptpip.c 实证）

- 包头：uint32 小端总长 + uint32 类型。命令通道 InitCommandRequest(1)→Ack(2)，
  负载含 16B initiator GUID + UTF-16LE 名 "QingmuTuchuan" + 版本 0x00010000；
  Ack 负载 = connection number(4B) + 相机 GUID(16B) + 相机名(UTF-16LE)。
- 事件通道 InitEventRequest(3) 带 connection number → Ack(4)；建不起来只降级纯命令通道，不判连接失败。
- OperationRequest 负载：DataPhaseInfo(4B，收数据/无数据=1，发数据=2) + 操作码(2B) + 事务ID(4B) + 参数。
  OpenSession 事务 ID 固定 0，之后自增。响应码非 0x2001 一律记诊断并抛 PtpException。
- 数据阶段：StartDataPacket(9，含 uint64 总长) → DataPacket(10) → EndDataPacket(12) → Response(7)。
- GetPartialObject(0x101B) 参数 = handle/offset(uint32)/maxBytes；偏移超 32 位（>4GB 文件续传）暂不支持，会记诊断并降级。
- v0.2 发数据阶段（SetDevicePropValue 0x1016 用）：DataPhaseInfo=2，StartData(负载=事务ID+uint64 总长) → Data 分块 ≤64KB → EndData 收尾，与 gphoto `ptp_ptpip_senddata` 对齐。
- v0.2 事务 ID 改由 transact 统一分配递增（仅 OpenSession 固定 0）；事件通道新增回调分发（ObjectAdded 0x4002 / CaptureComplete 0x400D / DevicePropChanged 0x4006）。
- v0.2 空闲保活：心跳线程监测命令通道，>10s 无成功事务且通道空闲时发一次轻量电量查询刷新机身空闲计时器。
- v0.3 标准管理操作：DeleteObject(0x100B) 双参数 (handle, objectFormatCode)——libgphoto2 ptp.c 的 ptp_deleteobject 用 PTP_CNT_INIT(handle, ofc) 原样对齐；FormatStore(0x100F) 只发 1 参数 (storageID)——libgphoto2 ptp.h:4476 宏 ptp_formatstore 定义为 ptp_generic_no_data(...,1,storageid)，规范的可选第二参（文件系统格式）不发、由相机取默认。
- v0.3 自动回传触发条件：事件通道 ObjectAdded(0x4002) + 开关开 + 格式在所选范围；去重三层——RemoteController 会话内 handle 集合 → DownloadRecord 文件名 → DownloadService 队列文件名集合。

## 开源对标结论（v0.2 连接审计 + 操作码出处）

参考来源：libgphoto2 master（github.com/gphoto/libgphoto2，camlibs/ptp2 的 ptpip.c / ptp.c / ptp.h / ptp-pack.c / library.c / config.c）、同行开源项目 N-Link（github.com/wan-1230/n-link，README 与更新日志）、digiCamControl 与 libmtp 仅作常量旁证（本版实际取值均以 libgphoto2 为准）。

### 连接层逐项审计（对照 v0.1.1 现有实现）

| # | 审计项 | 结论 |
|---|---|---|
| 1 | InitCommandRequest 格式（GUID@8 + UTF-16LE 名含 NUL@24 + minor u16=0/major u16=1） | ✅ 与 ptpip.c 一致（本端写 0x00010000 等价） |
| 2 | InitCommandAck 解析（connNum@0 / 相机 GUID@4 / 名@20） | ✅ 一致 |
| 3 | InitEventRequest（12B，connNum@8）与事件包解析（code@0/tid@2/参数@6） | ✅ 一致 |
| 4 | DataPhaseInfo（收/无数据=1、发数据=2） | ✅ 一致，v0.2 发数据事务按此实现 |
| 5 | 读包防半包（循环读满整包长度 + 包长上限校验） | ✅ 一致（gphoto generic_read 同策略） |
| 6 | StartData 总长字段 | ✅ 实质等价：gphoto 只写/读 u32 低半并清零高半，本端按规范 uint64，相机高半为 0 时一致；保留规范实现 |
| 7 | 发起端 GUID 持久化（首次生成后固定） | ✅ 一致（gphoto 存 settings，本端存 SharedPreferences） |
| 8 | OpenSession 时序（双通道 Init 完成后才 OpenSession） | ✅ 一致 |
| 9 | **事务 ID 管理** | 🔧 **已修复**：原实现各方法手动自增、异常路径漏增有 tid 复用风险；v0.2 改由 transact 统一分配 |
| 10 | **事件通道连接重试** | 🔧 **已修复**：gphoto 对事件通道 connect 被拒会重试（相机未就绪）；原实现失败即降级，v0.2 改为 300ms×3 次重试后再降级 |
| 11 | **空闲保活** | 🔧 **已新增**：gphoto 桌面轮询模型无主动保活，但 N-Link 实证尼康机身有空闲计时器（其做法为空闲 10 秒刷新）；v0.2 新增命令通道空闲保活（事件通道原有 10s Ping/45s 判死保留） |
| 12 | **InitFail 诊断** | 🔧 **已增强**：补充 reason 释义（N-Link 实证：STA 模式多为未注册主机/相机不在等待画面；AP 模式多为已有客户端占用） |
| 13 | GetPartialObject 分块大小 | libgphoto2 无统一规定（按调用方请求透传）；维持 4MB + profile 开关不变 |
| 14 | 事件通道 Ping/Pong | 本端实现比 gphoto 完整（gphoto 该处为 TODO 未处理相机 Ping）；保留现有立即回 Pong + 主动心跳 |

### 尼康私有操作码出处（全部 libgphoto2 ptp.h 核实，代码注释标注行号）

| 功能 | 操作码 | 出处 |
|---|---|---|
| AfDrive | 0x90C1（无参） | ptp.h PTP_OC_NIKON_AfDrive |
| ChangeCameraMode（进控制模式） | 0x90C2（参=1） | ptp.h + library.c enable_liveview 前置 |
| DeviceReady（wait-busy 轮询，忙=0x2019） | 0x90C8 | ptp.h + library.c nikon_wait_busy |
| StartLiveView / EndLiveView | 0x9201 / 0x9202 | ptp.h |
| GetLiveViewImage | 0x9203（帧内扫描 FFD8/FFD9 取 JPEG；gphoto 源码自承帧头约 128B 未解析） | ptp.h + library.c |
| ChangeAfArea | 0x9205（x/y = LV 图像像素坐标） | ptp.h + config.c 原样透传；坐标域另有 N-Link v3.1.0 实证 |
| AfDriveCancel | 0x9206 | ptp.h |
| InitiateCaptureRecInMedia | 0x9207（2 参：先 AF=0xFFFFFFFE、存卡=0；参数不支持降级 1 参版） | ptp.h ptp_nikon_capture2 / capture2_1 |
| StartMovieRecInCard / EndMovieRec | 0x920A / 0x920B | ptp.h |
| LiveView 帧尺寸属性 LiveViewImageSize | 0xD1AC（UINT8 枚举 1=QVGA/2=VGA/3=XGA，实际取值以相机 desc 自报为准） | ptp.h + config.c nikon_liveviewsize 表 |
| DeleteObject（删除卡上对象） | 0x100B（参数 handle + objectFormatCode，与 gphoto 调用方一致传对象实际格式码） | ptp.h:234 + ptp.c ptp_deleteobject |
| FormatStore（格式化存储） | 0x100F（仅 1 参数 storageID） | ptp.h:238 + ptp.h:4476 ptp_formatstore 宏 |
| 响应码 NotLiveView / OutOfFocus / InvalidStatus | 0xA00B / 0xA002 / 0xA004 | ptp.h |

注：0x5013 经核实是 StillCaptureMode（连拍模式），**不是**剩余可拍张数；剩余张数取自 GetStorageInfo 的 NumberOfImages（相机估算）并在界面注明来源。

## 降级链（全部记诊断）

1. GetPartialObject 相机报错或 profile 禁用 → 删 .part、整文件 GetObject 重下一次。
2. GetThumb 失败 → 矢量占位 + 文件名；profile 决定滚动回来是否自动重试（WU 档只试一次，点占位行为待 v0.2 加手动重试）。
3. 事件通道建不起来 / profile 声明不可靠 → 纯命令通道模式，状态栏标注「功能受限」。
4. 任一通道 IO 断裂 → 指数退避（1/2/4/8/15s）重连 + OpenSession 重建 + 队列 .part 续传。
5. v0.2 遥控降级：任一遥控功能连续失败 3 次 → 本次会话内禁用该功能并在遥控页置灰提示，图传主链路不受影响；LiveView 取帧反复 NotLiveView 自动重启取景，超限才降级。
6. v0.3 专项：自动回传在事件通道不可用时不触发（界面明示原因，不假装开启有效）；删除逐张执行、单张失败不中断其余、结果点名失败文件与响应码；间隔拍摄任一张失败立即停并报错，不重试不跳张；格式化只有相机回 OK 才清本地列表/记录并提示成功，回任何其他码都按失败处理。

## 已实现 / 未实现（诚实清单）

已实现：双入口连接（AP specifier / STA 手动 IP+端口，上次参数持久化）、GetDeviceInfo/电量/
存储信息、机型 profile 匹配与应用、照片列表（ObjectInfo 全量）、缩略图墙三级缓存与多选、
下载队列全套续传/降级/重连、MediaStore 落盘、诊断页、前台通知进度、尼康黄黑 UI + 全矢量图标。
v0.2 新增（代码完整、**待真机逐项验证**，不视为已实测）：状态面板（属性全走相机 desc 自报）、
LiveView 流水线取景 + 自适应锁档、点按对焦、遥控快门/录像、曝光参数调节、实时直方图、
拍完即览、机型遥控门控 + 诊断页实验开关、连接层审计 4 项修复。
v0.3 新增（代码完整、**待真机逐项验证**，不视为已实测）：拍完自动回传、已下载记录与
图库筛选（全部/未下载/仅 JPG/仅 RAW/按日期）、已下载角标、RAW+JPG 配对折叠与分开勾选、
删除照片（单张/批量）、格式化存储卡（实验开关门控+双重确认+长按 2 秒）、间隔拍摄
（前台服务保活、与自动回传可组合）。

占位/未实现：
- LiveView 帧头里的 AF 框字段未解析（gphoto 同样未解析）：当前 AF 框显示的是用户点按位置，首帧诊断会打印帧头前 32B 十六进制，真机回来按实测偏移再解析相机回传的合焦框。
- 多存储卡只取第一个存储（GetStorageIDs 其余仅记日志）。
- 占位图点击手动重试缩略图未接（自动重试已按 profile 生效）。
- STA 模式下相机「连接至 PC」的配对确认弹窗行为（相机屏需按 OK）未实测，可能需要用户在相机上确认一次。
- 密码以明文存 App 私有 SharedPreferences（v0.1 图省事，后续可换加密存储）。
- v0.3 已下载记录以文件名为主键：同一张卡内文件名唯一，但**换卡后若新卡出现同名文件**（如另一张卡也有 DSC_0001.JPG），会被误判为已下载——真机联调时留意；后续可考虑加入卡序列号维度。
- v0.3 自动回传在 App 被系统切到后台时，入队拉起前台下载服务可能被安卓 12+ 的后台启动限制拦截（已记诊断明确提示，不会静默丢失）；间隔拍摄+自动回传组合使用时建议保持 App 在前台。

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

## 遥控功能待真机验证机型表（v0.2.0）

> 状态说明：本表全部为「待验证」——代码已按 libgphoto2 源码实现并通过编译与静态验真，
> 但开发方无相机在手，任何一行都不得视为已实测可用。真机联调后按诊断日志逐项回填结果。

| 机型组（profile） | 遥控等级 | 状态面板 | LiveView | 点按对焦 | 快门 | 录像 | 参数调节 | 帧尺寸档 0xD1AC |
|---|---|---|---|---|---|---|---|---|
| Z fc（Z_FC，首测） | FULL | 待验证 | 待验证（目标稳定 30fps） | 待验证 | 待验证 | 待验证 | 待验证 | 待验证（该机是否提供未知） |
| Z 50/Z 30（Z_50_30） | FULL | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 |
| Z 5/6/7/8/9（Z_FULLFRAME） | FULL | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 |
| 其他 Z 系（Z_GENERIC） | FULL | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 | 待验证 |
| D850/D780（DSLR_WIFI_850_780） | BASIC | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 未验证锁定* |
| 中端单反（DSLR_WIFI_MID） | BASIC | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 未验证锁定* |
| WU-1a/1b 适配器（WU_ADAPTER） | BASIC | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 未验证锁定* |
| Coolpix Wi-Fi（COOLPIX_WIFI） | BASIC | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 未验证锁定* |
| 未知机型（DEFAULT） | BASIC | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 待验证（默认开放） | 未验证锁定* | 未验证锁定* | 未验证锁定* |

\* 「未验证锁定」= 默认禁用并标注未验证；用户在诊断页开启实验开关后可尝试，失败自动按功能降级。

LiveView 帧率口径：**30fps 是工程目标，不是已达成指标**。达标判据看诊断页「LIVEVIEW 5s 汇总」行——呈现 fps、帧间隔 min/max/p95/标准差三项齐看；真机实测值待联调回填本表。

## 图传增强 + 相机管理待真机验证表（v0.3.0）

> 状态说明：与 v0.2 同口径——全部「待验证」，代码已按 libgphoto2 源码实现并通过编译与静态验真，无相机在手，任何一行都不得视为已实测可用。

| 机型组（profile） | 自动回传 | 筛选/配对/角标 | 删除照片 | 格式化 | 间隔拍摄 |
|---|---|---|---|---|---|
| Z fc（Z_FC，首测） | 待验证（事件通道理应可用） | 纯本地逻辑，连接后可用 | 待验证 | 待验证 ⚠ 先用空卡试 | 待验证 |
| Z 50/Z 30 / Z 全幅 / 其他 Z 系 | 待验证 | 纯本地逻辑 | 待验证 | 待验证 ⚠ | 待验证 |
| D850/D780 / 中端单反（BASIC） | 待验证（事件通道视机型） | 纯本地逻辑 | 待验证 | 待验证 ⚠（实验开关门控） | 待验证（快门默认开放；机身模式不支持时应报错即停） |
| WU-1a/1b 适配器（WU_ADAPTER） | **预期不可用**：profile 按事件通道不可靠处理，自动回传不会触发（界面明示） | 纯本地逻辑 | 待验证（带宽低，逐张较慢） | 待验证 ⚠（实验开关门控） | 待验证（快门默认开放） |
| Coolpix Wi-Fi（COOLPIX_WIFI） | **预期不可用**：同 WU（事件通道按不可靠处理） | 纯本地逻辑 | 待验证 | 待验证 ⚠（实验开关门控） | 待验证 |
| 未知机型（DEFAULT） | 待验证（取决于事件通道是否建成） | 纯本地逻辑 | 待验证 | 待验证 ⚠（实验开关门控） | 待验证 |

联调重点：①自动回传与手动下载/间隔拍摄并发时去重是否严密（同一文件不许下两遍）；
②删除写保护/正在被相机占用的文件时相机回什么码、逐张报告是否属实；
③格式化在不同机型上 FormatStore 的实际行为（**首试务必用空卡**）；
④间隔拍摄在机身处于不支持遥控的模式（如某些机身需切到特定模式）时是否如实报错停下。

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
- ic_remote.xml — 取景框四角 + 中心圆（遥控页签/取景卡，v0.2）
- ic_shutter.xml — 快门圆环 + 内圆（拍摄按钮，v0.2）
- ic_rec.xml — 录像实心圆点（录像按钮，v0.2）
- ic_stop.xml — 停止实心方块（停止录像按钮，v0.2）
- ic_delete.xml — 垃圾桶（删除所选/格式化按钮，v0.3）
- ic_filter.xml — 漏斗（筛选条，v0.3）
- ic_auto.xml — 上下双箭头（拍完自动回传卡，v0.3）
- ic_calendar.xml — 日历（日期筛选，v0.3）
- ic_timer.xml — 秒表（间隔拍摄卡/服务通知，v0.3）
- 形状类（非图标）：bg_capsule_on/off、bg_card、bg_input、bg_btn_primary/ghost、bg_thumb_selected、bg_chip（v0.3 图库角标底衬）
