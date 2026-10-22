# P9FilmShare（胶片传）— 让老安卓手机经热点桥接给鸿蒙/卓易通设备传照片

> 一个 **Android APK 同时包含“发送”与“接收”两种角色**，帮助老机型（如华为 P9 / EMUI 4 / Android 6.0）
> 以近乎“华为分享”的体验，向鸿蒙设备（HarmonyOS，经官方“卓易通”安卓兼容容器运行）批量传照片。
>
> **无需流量、无需华为账号、不依赖华为分享私有协议。**

---

## 📥 下载与安装（先从这里开始）

**最新稳定版：`v1.0`**（已签名发布版，下载后直接安装即可使用）

| 平台 | 下载地址 |
| --- | --- |
| **Gitee 发行版**（国内更快） | [P9FilmShare-v1.0-release.apk](https://gitee.com/sigmav/p9-film-share/releases/download/v1.0/P9FilmShare-v1.0-release.apk) |
| **GitHub Release** | [P9FilmShare-v1.0-release.apk](https://github.com/sigmax6/P9FilmShare/releases/download/v1.0/P9FilmShare-v1.0-release.apk) |

> 如果直链不便，也可进仓库的「发行版 / Releases」页面手动下载：
> Gitee → https://gitee.com/sigmav/p9-film-share/releases ｜ GitHub → https://github.com/sigmax6/P9FilmShare/releases

### 安装指引

**老安卓手机（发送端 · 如华为 P9 / EMUI 4 / Android 6）**
1. 把下载好的 `P9FilmShare-v1.0-release.apk` 传到手机（数据线、微信文件传输、网盘均可）。
2. 用文件管理器点击 APK → 若提示「禁止安装未知应用」，按系统引导**开启「允许来自此来源」**，再点安装。
3. 安装完成打开，选「我要发送」，授予相机与存储权限即可。

**鸿蒙设备（接收端 · HarmonyOS，经官方「卓易通」运行）**
1. 打开官方「卓易通」→「本地安装包」导入并安装该 APK。
2. 打开后选「我要接收」，按提示开个人热点、生成二维码等待扫码。

> ⚠️ 本应用为**全新包名 `com.p9filmshare.app`**：若之前装过旧开发预览版（旧包名），请先卸载旧版再装，否则无法覆盖安装。

---

## 它是怎么工作的

```
鸿蒙设备（卓易通里运行 P9FilmShare）
   │  开个人热点（SSID/密码由 App 生成，可改；自动开或手动开）
   │  前台 HTTP 上传服务（8421：GET /api/hello 免 Token；POST /api/upload 收照片）
   │  生成二维码 P9SHARE2|ssid|pass|ip|port|token
   ▼
老安卓手机 扫码 → 自动连上热点 → 弹出相册批量勾选 → 逐张上传
   ▼
鸿蒙设备 收到 → 写入系统图库「华为分享」相册（卓易通“文件互通”可直达宿主图库）
```

**设计要点**

- **鸿蒙设备当“热点主机 + 上传接收端”**，老手机当“客户端 + 上传者”。
  原因：老手机（Android 6）只要能 `addNetwork` + `enableNetwork` 连一个**已知密码的 WPA 热点**即可，
  这是系统公开能力；而鸿蒙/新机可靠地开热点、跑服务。
- **不用 Wi‑Fi 直连**：在 EMUI 4 上 `createGroup` 必然返回错误码 2（BUSY），所以彻底放弃，
  改用“个人热点 + 普通 Wi‑Fi 连接”。
- **所有请求都是出站**：老手机是客户端、鸿蒙设备是服务端，天然穿透卓易通 NAT（只要真连到了热点子网）。
- **增量传输**：接收端按 `文件名|字节大小` 记录已收照片；发送端连上后拉取“已收列表”，
  「全选」默认只选未传过的；再次传同一张时，接收端弹「覆盖 / 复制为新文件 / 跳过」让用户决定。

---

## 特性

- 🎞️ **胶片照片风 UI**：35mm 齿孔胶片条、暖色相纸配色、颗粒噪点背景、等宽字体的“胶片盒标签”观感。
- 🌗 **深色 / 浅色 / 跟随系统** 三档主题，首页一键切换，全局统一套用。
- 📷 **P9 扫码即连**：相机扫鸿蒙设备二维码 → 自动解析 SSID/密码/IP/端口/Token → 自动连热点。
- 🗂️ **相册批量选择**：缩略图网格多选，支持按文件夹（相册）过滤、全选/清空、已传标记。
- 🕘 **历史连接一键重连**：连成功一次后记住配置；之后热点开着、或双方连同一 Wi‑Fi 时，点一下即自动重连。
- 🔁 **重复照片策略**：询问 / 覆盖 / 复制 / 跳过，可逐张或批量决定。
- 🛡️ **全链路异常保护 + 全局崩溃记录**：所有后台线程 `catch (Throwable)`，崩溃后主界面红字提示堆栈。

---

## 构建

标准 Android Gradle 工程（AGP 7.4.2 / compileSdk 33 / **minSdk 23** / targetSdk 30），
仅依赖 `com.google.zxing:core`（生成 + 识别二维码），HTTP 用系统 `HttpURLConnection`，**无其他第三方库**。

```bash
git clone <your-fork-url> P9FilmShare
cd P9FilmShare
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 首次构建会按 `gradle/wrapper/gradle-wrapper.properties` 下载 Gradle 7.6.2；
> 依赖走 Maven Central（国内可改用阿里云镜像，见 `settings.gradle` 注释）。

**安装**
- 老安卓手机：开启「未知来源」后侧载 APK。
- 鸿蒙设备：用官方“卓易通” → 本地安装包导入安装，再打开即可。

---

## 使用流程

**鸿蒙设备（接收端，先开热点的一端）**

1. 打开 P9FilmShare → 选「我要接收」。
2. 界面带出上次保存的热点名称/密码（首次随机），可改；点「开启热点并生成二维码」即保存。
3. App 尽量自动开热点；若系统拦截，按屏幕上的 SSID/密码到系统「个人热点」手动开。
4. 屏幕出现二维码（含 SSID/密码掩码/IP/端口/Token），同时启动上传服务等待传照片。
5. 收到照片写入图库「华为分享」相册；结束点「停止并关闭热点」。

**老安卓手机（发送端，扫码连热点）**

1. 打开 P9FilmShare → 选「我要发送」，授予相机与存储权限。
2. 点「📷 扫码连接并传照片」扫鸿蒙设备二维码 → 自动连热点 → 弹出相册勾选。
3. 勾选照片（点格子任意位置都可选中；顶部「相册：」可按文件夹过滤）→ 点「开始传输」。
4. 之后可直接点「🕘 历史连接」一键重连，无需再扫码。

---

## 目录结构

```
app/src/main/java/com/p9filmshare/app/
├── MainActivity.java        # 首页：角色选择 + 主题切换 + 介绍
├── ReceiverActivity.java    # 接收端（热点主机 + 二维码 + 重复策略）
├── SenderActivity.java      # 发送端（扫码/历史连接 → 选图 → 上传）
├── PickerActivity.java      # 相册批量选择（胶片网格 + 相册过滤 + 增量标记）
├── ScanActivity.java        # Camera2 + ZXing 扫码
├── AppTheme.java            # 胶片风统一主题（深浅双调色板 + 控件工厂）
├── HotspotHelper.java       # 反射开/关热点 + 取热点 IP
├── HttpFileServer.java      # 零依赖 HTTP（上传接收 + 去重 + /api/received）
├── ShareService.java        # 前台服务，持有上传服务 + 去重分发
├── ConnStore.java           # 两端连接配置 / 主题 / 重复策略 持久化
├── TransferLog.java         # 已收照片记录（增量传输）
├── QrUtil.java              # P9SHARE2 二维码编解码
├── NetUtil.java / CrashGuard.java / LanBeacon.java
└── client/                  # P9Client(上传) / WifiJoiner(连热点) / LanProbe(同Wi-Fi发现) / PhotoSaver / PhotoItem
```

---

## 兼容性说明（重要）

- **ZXing 必须锁 `3.3.3`**：3.4+ 的 `FinderPatternFinder` 内部调用 `List.sort(Comparator)`，
  该默认方法需 API 24+；而本项目 minSdk 23（Android 6），用新版会在扫码时抛
  `NoSuchMethodError` 直接崩溃。3.3.3 内部走 `Collections.sort`，API 1 起可用。
- **Wi‑Fi 直连在 EMUI 4 不可用**：`createGroup` 必返 BUSY，已放弃，改用个人热点。
- **卓易通容器可能是 NAT**：若接收端「ℹ 网络诊断」只看到 `172.17/10.x` 容器地址、
  看不到 `192.168.x.x`，说明 App 服务在容器内，P9 可在「手动输入」里填 `192.168.43.1` 再传。
- **自动开热点可能被系统权限拦截**：已做手动兜底（按屏幕 SSID/密码到系统开），属正常限制。

---

## 合规与边界

本项目是**兼容桥接**：用标准 HTTP + 个人热点复刻近场传照体验，
**不包含、不逆向**任何厂商的私有分享协议；卓易通为官方安卓兼容工具。
对外命名建议避免与官方 Huawei Share 混淆。

---

## License

[MIT](./LICENSE) © P9FilmShare Contributors
