# 轻舟浏览器 功能登记表（feature\_registry.md）

> 本文件为 AI 辅助开发的功能登记表，记录每个功能的入口、行为、代码位置与测试状态，供后续迭代与回归测试使用。

## 应用基本信息

| 项目                        | 值                                                 |
| ------------------------- | ------------------------------------------------- |
| 应用名称                      | 轻舟浏览器                                             |
| applicationId             | `com.trae.lightboat`                              |
| namespace（Java 包名）        | `com.trae.simplebrowser`                          |
| versionCode / versionName | 56 / 1.15.9                                       |
| minSdk / targetSdk        | 24 / 34                                           |
| 旧版共存说明                    | 旧应用"极简浏览器"（`com.trae.simplebrowser`）仍可同机安装，二者互不覆盖 |

## 功能登记

### F-01 网页浏览核心

| 项目   | 内容                                                             |
| ---- | -------------------------------------------------------------- |
| 入口   | 桌面图标 / 快捷方式                                                    |
| 行为   | WebView 加载主页，地址栏跳转、前进后退、书签、历史                                  |
| 关联代码 | `MainActivity.kt`、`SettingsActivity.kt`、`BookmarksActivity.kt` |
| 测试状态 | 人工回归通过（历史版本功能，本次未改动核心逻辑）                                       |

### F-02 双指下滑 → 直接切换"隐藏所有组件"状态（本次变更：原为打开设置页）

| 项目    | 内容                                                                                                                                                                                                                                                                                                                       |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 入口    | 网页区域内双指同时按下后向下滑动（`TWO_FINGER_SWIPE_DOWN`）                                                                                                                                                                                                                                                                                |
| 行为    | **不打开设置页**，直接切换隐藏状态：读取 `AppPrefs.getHideControls` 取反后经 `setHideControls` 持久化，`applyControlsVisibility()` 立即生效，并弹出 Toast（隐藏时"已隐藏组件，双指下滑可恢复显示"，显示时"已显示组件"）。状态经 SharedPreferences 持久化，进程重启后 `onResume` 自动恢复                                                                                                                 |
| 防误触机制 | 48dp 顶/底边缘排除区（避开系统通知栏与导航手势）、双指垂直位移均超过阈值、垂直分量占优（排除捏合缩放）、一次手势仅触发一次、**800ms 切换冷却**（与刷新冷却相互独立）                                                                                                                                                                                                                               |
| 关联代码  | [MultiFingerGestureTracker.kt](app/src/main/java/com/trae/simplebrowser/core/MultiFingerGestureTracker.kt)、`MainActivity.dispatchTouchEvent`（TWO\_FINGER\_SWIPE\_DOWN 分支）、`MainActivity.toggleControlsVisibility()`、[strings.xml](app/src/main/res/values/strings.xml)（`toast_controls_hidden` / `toast_controls_shown`） |
| 单元测试  | `MultiFingerGestureTrackerTest` 双指下滑用例——JUnit 直跑 `OK (17 tests)`                                                                                                                                                                                                                                                         |
| 测试状态  | ✅ 逻辑验证通过；真机手势待人工确认                                                                                                                                                                                                                                                                                                       |

### F-03 双指上滑 → 刷新网页（本次新增）

| 项目        | 内容                                                                                                                                                                                                                                                 |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口        | 网页区域内双指同时按下后向上滑动（`SWIPE_UP`）                                                                                                                                                                                                                       |
| 行为        | `webView.reload()` 刷新当前网页，并弹出 Toast"页面已刷新"                                                                                                                                                                                                         |
| 与系统手势冲突规避 | ① 顶部/底部 48dp 边缘排除区，避开系统通知栏下拉与全面屏导航手势；② 要求双指**同时**位移且方向一致，单指操作不触发；③ 垂直分量必须占优，避免与捏合缩放冲突；④ 触发后 800ms 冷却 + 一次性锁，防止重复刷新                                                                                                                                 |
| 关联代码      | [MultiFingerGestureTracker.kt](app/src/main/java/com/trae/simplebrowser/core/MultiFingerGestureTracker.kt)、`MainActivity.dispatchTouchEvent`（TWO\_FINGER\_SWIPE\_UP 分支）、[strings.xml](app/src/main/res/values/strings.xml)（`toast_page_refreshed`） |
| 单元测试      | `MultiFingerGestureTrackerTest`（25 例：双指上滑触发刷新、双指下滑触发隐藏切换、双指右滑触发设置、三指下滑/右滑均不触发、方向不一致不触发、阈值不足不触发、捏合不误触、对角占优方向判定、四边边缘排除、第三指落下中断应用手势、一次性锁、reset 恢复、单指不布防）——JUnit 直跑 `OK (25 tests)`                                                                    |
| 测试状态      | ✅ 逻辑验证通过；真机手势待人工确认                                                                                                                                                                                                                                 |

### F-04 应用身份变更（本次变更）

| 项目       | 内容                                                                                                                                                             |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 应用名      | 极简浏览器 → **轻舟浏览器**（`strings.xml: app_name`）                                                                                                                     |
| 包名（签名身份） | `com.trae.simplebrowser` → `com.trae.lightboat`（`build.gradle.kts: applicationId`）                                                                             |
| 图标       | 新帆船主题自适应图标（青绿色系）：`ic_launcher_foreground.xml` / `ic_launcher_background.xml` / `ic_launcher_monochrome.xml` / `ic_launcher.xml` / `mipmap-anydpi-v26/*`        |
| 版本       | versionCode 17→18，versionName 1.1.5→1.2.0                                                                                                                      |
| 快捷方式适配   | [shortcuts.xml](app/src/main/res/xml/shortcuts.xml) 中 `targetPackage` 已同步为 `com.trae.lightboat`；`targetClass` 保持 `com.trae.simplebrowser.*`（类名跟随 namespace，勿改） |
| 测试状态     | ✅ 已构建并安装至真机（华为 FCP-AN10），与旧版共存验证通过                                                                                                                             |

### F-05 双指右滑 → 打开设置页（本次变更：原三指下滑因与系统截图手势冲突已移除）

| 项目   | 内容                                                                                                                                                                                                   |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口   | 网页区域内双指同时按下后向右滑动（`TWO_FINGER_SWIPE_RIGHT`）                                                                                                                                                           |
| 行为   | 打开 `SettingsActivity`                                                                                                                                                                                |
| 变更原因 | 华为/EMUI 的三指下滑截图在**系统框架层**监听实现，Android 无公开 API 允许应用屏蔽或覆盖（`FLAG_SECURE` 会连用户手动截图一并禁止，副作用过大），故移除三指下滑判定、设置职责改为双指右滑                                                                                       |
| 关键设计 | ① 双指均需横向位移超阈值且**横向分量占优**（`abs(dy) < dx`，排除捏合缩放）；② 起点位于屏幕**左右 48dp 边缘排除区**则整组手势作废（避开全面屏返回手势），上下排除区同步保留；③ 三指及以上手势一律不触发，三指下滑交还系统截图；④ 方向判定优先级：下滑 → 上滑 → 右滑（竖向占优时优先竖向手势）                                  |
| 关联代码 | [MultiFingerGestureTracker.kt](app/src/main/java/com/trae/simplebrowser/core/MultiFingerGestureTracker.kt)（`TWO_FINGER_SWIPE_RIGHT`）、`MainActivity.dispatchTouchEvent`（TWO\_FINGER\_SWIPE\_RIGHT 分支） |
| 单元测试 | `MultiFingerGestureTrackerTest` 25 例（含双指右滑触发、斜向横向占优触发、竖向占优不触发右滑、左/右边缘排除、三指下滑/右滑均不触发、阈值不足不触发、水平反向不触发）——JUnit 直跑 `OK (25 tests)`                                                                       |
| 测试状态 | ✅ 逻辑验证通过；真机手势待人工确认                                                                                                                                                                                   |

### F-06 扫码（本次新增）

| 项目    | 内容                                                                                                                                                                                                                                                                                                                                                                     |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | ① 地址栏书签图标右侧扫码图标（`MainActivity` `scanButton`）；② 桌面长按图标快捷方式「扫码」（`shortcuts.xml` `shortcutId=scan`，排第一位）                                                                                                                                                                                                                                                          |
| 行为    | 打开 `ScanActivity`：后摄实时取景 + ZXing 逐帧解码（识别成功震动 50ms）。**网址**（http/https/任意 scheme/裸域名，见 `QrCodeDecoder.isProbablyUrl`）→ 自动经 `MainActivity` 打开；**纯文本**（含 WIFI:/VCARD/tel:/纯 IP 等）→ 弹窗提供「复制 / 搜索 / 取消」，取消后复位识别锁继续扫码。附加能力：手电筒开关、相册识码（`GetContent` 选图 → EXIF 摆正 → 降采样（长边≤1600）→ RGB 解码） |
| 技术选型 | ZXing core 3.5.3（纯 Java 解码内核，无 Play Services 依赖，兼容华为设备）+ CameraX 1.3.4（Preview + ImageAnalysis，STRATEGY_KEEP_ONLY_LATEST，分析分辨率 1280×720）。`YuvNv21` 自行完成 YUV_420_888→NV21（兼容 rowStride 填充与 pixelStride=2 半交错）与顺时针旋转，`QrCodeDecoder` 对每帧先 Hybrid 后 Global 两种二值化回退，CHARACTER_SET=UTF-8（无 ECI 中文码防乱码）                                                       |
| 权限    | `CAMERA`（运行时请求，拒绝则 Toast 并退出）、`VIBRATE`；`uses-feature camera required=false` 兼容无相机设备                                                                                                                                                                                                                                                                                    |
| 关联代码  | [QrCodeDecoder.kt](app/src/main/java/com/trae/simplebrowser/core/QrCodeDecoder.kt)、[YuvNv21.kt](app/src/main/java/com/trae/simplebrowser/core/YuvNv21.kt)、[ScanActivity.kt](app/src/main/java/com/trae/simplebrowser/ScanActivity.kt)、[ScanOverlayView.kt](app/src/main/java/com/trae/simplebrowser/ScanOverlayView.kt)、[activity_scan.xml](app/src/main/res/layout/activity_scan.xml)、[ic_scan_*.xml](app/src/main/res/drawable/ic_scan_qr.xml)、[shortcuts.xml](app/src/main/res/xml/shortcuts.xml)、`MainActivity.setupControls()`（scanButton 一行入口） |
| 单元测试  | `QrCodeDecoderTest`（17 例：编码→解码回环含中文/WIFI/裸域名、非法输入、网址判定含边界、URL 归一化）+ `YuvNv21Test`（9 例：紧凑/填充/半交错三种平面布局、0/90/180/270 旋转、UV 对完整性、非法角度）——JUnitCore 直跑 `OK (51 tests)`（含手势 25 例回归）                                                                                                                                                                                                      |
| 测试状态  | ✅ 逻辑验证通过；真机扫码（实时取景/相册识码/快捷方式）待人工确认                                                                                                                                                                                                                                                                                                                                      |

### F-07 视频下载（本次新增）

| 项目    | 内容                                                                                                                                                                                                                                                                                                                                                                                            |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 视频全屏播放窗口右上角浮动下载按钮（`videoContainer` 内 `btnVideoDownload`，进入全屏显示 `onShowCustomView`，退出隐藏 `onHideCustomView`/返回键分支）                                                                                                                                                                                                                                                          |
| 行为    | 点击后 JS 采集正在播放的 `<video>` 地址（优先播放中/面积最大者），结合网络嗅探地址（`shouldInterceptRequest`/`onLoadResource` 记录最近 24 条 .mp4/.m3u8 等），经 `VideoUrlSniffer.pickDownloadCandidate` 决策（video 元素 m3u8 > 元素直链 > 嗅探直链 > 嗅探 m3u8 > 无扩展名 http 兜底；blob: 走嗅探兜底，均无则提示"未检测到"）。**直链**→系统 DownloadManager（通知栏进度）；**m3u8**→前台服务解析播放列表（主列表选最高带宽）→逐分片下载（AES-128 自动解密、fMP4 含 init 段合并为 .mp4、普通 TS 合并为 .ts）→写入 `Download/轻舟浏览器/`，通知栏进度+完成/失败通知。直播流（无 ENDLIST）、SAMPLE-AES 加密、BYTERANGE 分片明确拒绝并提示 |
| 权限    | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（m3u8 下载服务，targetSdk 34 要求）、`POST_NOTIFICATIONS`（API 33+ 运行时请求，拒绝亦可下载仅无通知）、`WRITE_EXTERNAL_STORAGE`（仅 API≤28 传统路径）                                                                                                                                                                                                                          |
| 关联代码  | [VideoUrlSniffer.kt](app/src/main/java/com/trae/simplebrowser/core/VideoUrlSniffer.kt)（地址识别/m3u8 解析/候选决策，纯 JVM）、[VideoDownloadService.kt](app/src/main/java/com/trae/simplebrowser/VideoDownloadService.kt)（前台下载服务）、`MainActivity`（浮动按钮、`collectVideoUrlJs`、`recordMediaUrl`、`onVideoDownloadClicked`、`downloadDirectVideo`、`beginHlsDownload`）、[activity_main.xml](app/src/main/res/layout/activity_main.xml)（btnVideoDownload） |
| 单元测试  | `VideoUrlSnifferTest` 18 例（classify 边界含查询串/大写/blob、URL 解析四种相对形态、主列表按带宽排序、媒体列表分段/序号/AES-KEY+IV/METHOD=NONE 重置/SAMPLE-AES 拒绝/BYTERANGE/MAP、候选决策优先级全覆盖、文件名清洗）——JUnitCore 直跑 `OK (69 tests)`（含手势 25 + 扫码 26 例回归）                                                                                                                               |
| 仪器测试  | `VideoDownloadServiceTest`（真机：前台服务启动 → 不可达地址走 3 次重试失败路径 → 服务自停，全程无崩溃）——`connectedDebugAndroidTest` 通过                                                                                                                                                                                                                                                        |
| 测试状态  | ✅ 逻辑/服务层验证通过；真机实际视频下载（全屏浮动按钮、m3u8 完整下载、Download 目录文件可播）待人工确认                                                                                                                                                                                                                                                                                |

## 回归测试清单（下次改动前过一遍）

- [ ] 多标签：底部标签按钮显示数量角标；新建/切换/关闭标签正常；第 9 个标签被拒并提示（F-08）

- [ ] 多标签：关闭最后一个标签自动补开主页标签；菜单"新建标签页"可用；各标签登录态/前进后退独立（F-08）

- [ ] 桌面快捷方式：App 后台运行时点网页快捷方式应直接打开该网页（singleTask + onNewIntent，1.5.0 修复）

- [ ] 双指下滑直接切换隐藏/显示组件并出现对应 Toast，杀掉应用重开后状态保持（F-02）

- [ ] 双指右滑打开设置页（F-05）

- [ ] 三指下滑只触发系统截图，不再触发应用内任何手势（F-05 系统手势交还）

- [ ] 双指上滑刷新网页并出现 Toast（F-03）

- [ ] 网页内捏合缩放不触发刷新/隐藏/设置（F-02/F-03/F-05 防误触）

- [ ] 屏幕顶部下拉通知栏、底部上滑返回桌面不误触手势（F-02/F-03/F-05 边缘排除）

- [ ] 长按桌面图标 → 快捷方式"设置/书签"可正常打开（F-04 shortcuts 适配）

- [ ] 地址栏扫码图标可打开扫码页，首次进入弹相机权限授权（F-06）

- [ ] 对准网址二维码自动识别并打开网页；对准纯文本二维码弹「复制/搜索/取消」（F-06）

- [ ] 长按桌面图标 → 快捷方式新增"扫码"（排第一位）可进入扫码页（F-06）

- [ ] 扫码页手电筒开关、相册选图识码可用；取消文本弹窗后可继续扫码（F-06）

- [ ] 视频进入全屏后右上角出现下载按钮，退出全屏后消失（F-07）

- [ ] mp4 直链视频全屏点击下载 → 通知栏进度 → Download/轻舟浏览器/ 内文件可播放（F-07）

- [ ] m3u8 视频全屏点击下载 → 分片进度 → 合并文件（.ts/.mp4）保存且可播放（F-07）

- [ ] 无视频页面/未全屏时无下载按钮；blob 加密流页面点下载有合理提示（F-07）

- [ ] 首次 m3u8 下载在 Android 13+ 弹通知权限请求（F-07）

- [ ] 旧版"极简浏览器"与新"轻舟浏览器"互不影响

- [ ] 首次 m3u8 下载在 Android 13+ 弹通知权限请求；通知权限被拒时**设置页下载管理仍可查看进度与文件**（1.5.0 修复，F-07）

- [ ] 设置页"下载管理"显示进行中进度与历史记录，点击已完成条目可打开文件（F-07）

### F-08 多标签页（1.5.0 新增）

| 项目    | 内容                                                                                                                                                                                                        |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 底部工具栏左侧标签按钮（带数量角标）；「更多」菜单"新建标签页"                                                                                                                                                             |
| 行为    | 最多 8 个标签同时打开（每个标签独立 WebView 实例，Cookie/登录态全局共享）；标签切换器支持切换/逐个关闭/新建；关闭当前标签右邻优先成为当前，关最后一个自动补开主页标签；地址栏/进度条/书签星标/双指上滑刷新等 UI 均跟随当前标签；后台标签暂停渲染（onPause），App 退后台全局暂停计时器 |
| 关联代码  | [TabManager.kt](app/src/main/java/com/trae/simplebrowser/core/TabManager.kt)（纯逻辑）、`MainActivity`（tabWebViews 池、openNewTab/closeTab/attachTabWebView/showTabSwitcher、全部 `binding.webView` 引用改 `currentWebView`）、[activity_main.xml](app/src/main/res/layout/activity_main.xml)（tabButton+tabCountBadge）、[menu_more.xml](app/src/main/res/menu/menu_more.xml) |
| 单元测试  | `TabManagerTest` 10 例（上限、创建/选中/关闭的右邻优先与左邻回退、closeOthers、空值防御）——JUnitCore 直跑 `OK (89 tests)`（含手势/扫码/视频嗅探/下载记录全部回归）                                                                                           |
| 测试状态  | ✅ 逻辑验证通过；真机多标签操作待人工确认                                                                                                                                                                                      |

### F-09 桌面快捷方式路由修复（1.5.0 缺陷修复）

| 项目   | 内容                                                                                                                                              |
| ---- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 现象   | App 在后台时，点击桌面网页快捷方式（B）显示的仍是当前页面（A），需杀掉应用才能打开 B                                                                                  |
| 根因   | MainActivity 为 standard 启动模式；华为桌面（EMUI）点按快捷方式仅将既有任务切至前台而不投递 intent（adb `am start` 显式 intent 复现路径正常，logcat 证实 onNewIntent 会 loadUrl） |
| 修复   | `AndroidManifest.xml` MainActivity `launchMode="singleTask"`（Chrome 同款方案，保证任何启动 intent 投递到已有实例）+ `onNewIntent` 增加 `setIntent(intent)`          |
| 验证   | 真机：Home 后 adb 显式 VIEW intent 打开 baidu → logcat `onNewIntent -> loadUrl: https://www.baidu.com/` ✓；桌面长按快捷方式的场景待用户人工确认                                       |

### F-10 设置页下载管理（1.5.0 缺陷修复）

| 项目   | 内容                                                                                                     |
| ---- | ------------------------------------------------------------------------------------------------------ |
| 现象   | 下载开始后提示"通知栏查看进度"，但真机通知权限为 ignore（用户曾拒绝），任何通知都收不到                                                 |
| 修复   | 设置页新增"下载管理"区块：进行中条目显示进度条与分片进度，历史条目显示完成时间/保存路径/失败原因；点击已完成条目经 MediaStore content uri 直接打开视频 |
| 数据   | [DownloadStoreLogic.kt](app/src/main/java/com/trae/simplebrowser/core/DownloadStoreLogic.kt)（纯序列化/合并/裁剪，上限 50 条）+ [DownloadStoreFile.kt](app/src/main/java/com/trae/simplebrowser/DownloadStoreFile.kt)（filesDir/downloads.txt 持久化 + 刷新广播）；m3u8 由 VideoDownloadService 写入，直链由 DownloadManager ACTION_DOWNLOAD_COMPLETE 接收器更新 |
| 测试   | `DownloadStoreLogicTest` 10 例（JVM）+ 仪器测试 `downloadStoreFile_persistsRoundtripOnDevice`（真机落盘回环）通过；通知权限状态如需恢复可在系统设置 → 应用 → 轻舟浏览器 → 通知 打开 |

### F-11 v1.5.1 四项缺陷修复（用户真机验收反馈）

| # | 现象 | 根因（日志证据） | 修复 |
| - | ---- | ---- | ---- |
| 1 | 点下载后**瞬间报"下载已完成"** | 优酷等站为 blob 加密流，回退嗅探到的"直链"是防盗链错误页/广告小文件，DownloadManager 下载 <100KB 的 HTML 也算 STATUS_SUCCESSFUL | 完成后校验有效性：直链 `COLUMN_TOTAL_SIZE_BYTES` <100KB → FAILED（"疑似防盗链页面"）；m3u8 播放列表总时长 <3s（`isSuspiciouslyShort`）与合并产物 <100KB（`isSuspiciouslySmall`，落盘前拦截）→ FAILED |
| 2 | 设置页点击已下载文件报"无法直接打开" | 直链完成时 content uri 解析失败即留空，openDownload 直接放弃 | openDownload 改为**动态解析**：记录 uri → MediaStore Downloads 集合按 DISPLAY_NAME 查 → Video 集合兜底；另直链进行中条目现在实时查询 DownloadManager 进度 |
| 3 | 偶尔点"下载"**闪退** | `dumpsys dropbox`/crash buffer 实锤：`LinkedHashMap$LinkedHashIterator.remove` ← `MainActivity.recordMediaUrl`——`sniffedMediaUrls.iterator().remove()` 未先 `next()` 必抛 IllegalStateException，嗅探记录超 24 条时必崩 | 改为 `sniffedMediaUrls.remove(sniffedMediaUrls.first())` 移除最老条目 |
| 4 | 更新 APP 后**桌面快捷方式丢失** | 并非 install -r 的问题（同签名覆盖安装保留 pinned shortcut）；根因是历轮交付跑了 `connectedDebugAndroidTest`，测试框架结束时**卸载应用**导致 pinned shortcut 清空 | 流程修复：交付构建后仅 `adb install -r` 覆盖安装，**不再运行会卸载应用的仪器测试** |

| 测试 | `VideoUrlSnifferTest` 补 isSuspiciouslySmall/isSuspiciouslyShort 2 例——JUnitCore 直跑 `OK (91 tests)`；youku.com 真机加载 18s 无崩溃、无 crash 记录 |
| ---- | ---- |

### F-12 媒体流探测重构（1.6.0：对齐主流浏览器下载能力）

> 目标：夸克/Via 等浏览器可下载的视频，轻舟也能下载。采用业界通用技术路线（未参考任何专有实现）：
> 视频站流地址大多无扩展名，仅凭 URL 后缀识别必然漏检 → 记录全部网络请求 + 轻量 HTTP 探测按响应特征判定。

| 项目    | 内容                                                                                                                                                                                                                                                                                                  |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 核心变化 | ① 候选池升级：`shouldInterceptRequest` 记录**全部** http(s) 请求（LRU 120 条，`sniffPool: url→lastSeenAt`），不再只收视频后缀；② 点下载时并发对候选（最近 90s 活跃、粗筛命中流媒体关键词、上限 12 条）发 `Range: bytes=0-1` 探测（4s 超时，带 Referer/Cookie/UA）；③ 按响应判定：`mpegurl` MIME → m3u8；`video/`、octet-stream、空 MIME + 总大小 ≥1MB → 直链；HTML/图片/小文件一律拒绝（防盗链页误判的根治）；④ 单候选直接分发，多候选弹列表让用户选择（显示类型/大小/路径摘要） |
| 关联代码  | [MediaProbeLogic.kt](app/src/main/java/com/trae/simplebrowser/core/MediaProbeLogic.kt)（粗筛+响应分类，纯 JVM）、`MainActivity`（sniffPool/buildProbeCandidates/probeUrl/onProbeFinished/parseContentRangeTotal）、`VideoUrlSniffer.isSuspiciouslySmall/Short`（v1.5.1）延续为第二道校验 |
| 单元测试  | `MediaProbeLogicTest` 7 例（mpegurl 三种 MIME、octet-stream/空 MIME + 大小阈值、HTML/图片/403/302 拒绝、DASH 标记、粗筛正反例）——JUnitCore 直跑 `OK (98 tests)` |
| 已知边界  | ① blob 加密流仍靠候选池兜底（元素地址是 blob: 时由探测到的底层流替代）；② 优酷等站正片的私有加密流（非标准 AES-128）无法解密，会明确失败；③ DASH(.mpd) 仅识别未实现下载 |

### F-13 快捷方式自定义图标（1.7.0 新增）

| 项目    | 内容                                                                                                                                                                                                                                    |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 「添加到主屏幕」弹窗（more 菜单 / 工具栏添加按钮）→「选择图标」区新增「从相册选择」「拍照」两个按钮                                                                                                                                                                         |
| 行为    | 选图/拍照 → 后台解码（按 512px 目标降采样，防大图 OOM）→ EXIF 方向摆正 → 居中方裁剪（`IconCropMath`）→ 重建弹窗，「自定义」图标出现在选项首位并**自动选中**（名称输入保留）；确认添加后临时状态清空。拍照走 MediaStore 临时记录，用完即删不留相册。manifest 已声明 CAMERA，故拍照前先检查/请求权限（未授权时启动相机 intent 会直接崩，这是 Android 的强制约束） |
| 关联代码  | [IconCropMath.kt](app/src/main/java/com/trae/simplebrowser/core/IconCropMath.kt)、`MainActivity`（galleryIconLauncher/cameraIconLauncher/cameraPermissionForIconLauncher、loadCustomIcon/launchIconCapture/decodeSquareBitmap、PinIconKind.CUSTOM）、strings.xml |
| 单元测试  | `IconCropMathTest` 7 例（横/竖/方/极端比例居中裁剪、奇数尺寸取整、非法尺寸拒绝、降采样系数边界）——JUnitCore 直跑 `OK (105 tests)` |
| 测试状态  | ✅ 逻辑验证通过；真机相册/拍照选图流程待人工确认                                                                                                                                                                                                   |

### F-14 快捷方式弹窗残留修复（1.7.1 缺陷修复）

| 项目   | 内容                                                                                                                                                                                                                                |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 现象   | 选照片添加快捷方式，点确定走完系统确认页后，回到的仍是"编辑名称"弹窗而非网页                                                                                                                                                                                   |
| 取证   | adb 驱动 UI 完整复现：选图后重建的弹窗（带自定义图标）**叠**在旧弹窗之上；点确定只关掉上层弹窗，系统页添加后露出底层旧弹窗（该弹窗无"自定义"选项 = 选图前的那层）                                                                                                                               |
| 根因   | `showPinShortcutNameDialog` 结尾 `.show()` 返回值未赋给 `dialogRef`，字段恒为 null → `loadCustomIcon` 里 `dialogRef?.dismiss()` 关不到旧弹窗 → 两层叠放                                                                                                        |
| 修复   | `dialogRef = ...show()`；`pendingDialogText` 用后清空                                                                                                                                                                                              |
| 验证   | adb 驱动完整照片路径回归：确定 → 系统页 → 添加 → **回到网页**（最终 UI 无弹窗残留）✓；单测 105 例通过；普通路径（不选图）回归正常 ✓                                                                                                                                        |

## 已知环境问题（非代码缺陷）

1. **Gradle 单元测试运行器报 ClassNotFoundException**：项目根目录路径含空格与中文（`apk开发范本模板原型 - 极简浏览器`），Gradle test worker 的 classpath 参数文件受影响。测试已通过 JUnitCore 直跑验证（`OK (51 tests)`；Git Bash 下需 `MSYS2_ARG_CONV_EXCL="*"` 阻止其对 `;` 分隔 classpath 的路径改写），测试代码本身无误。若需彻底解决，可将项目移动到纯英文无空格路径。另：本机访问 Maven Central 不稳定，已在 `settings.gradle.kts` 增加 `repo.huaweicloud.com` Maven 镜像。
2. **华为真机** **`adb screencap`** **返回黑帧**：FCP-AN10 系统安全限制，冒烟验证改用 `topResumedActivity` + `AndroidRuntime:E` 崩溃过滤判断。

<br />

### F-15 接收系统分享（图片/音频/视频/压缩包）（本次新增）

| 项目    | 内容                                                                                                                                                                   |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 其他应用（微信/相册/文件管理器等）的系统分享面板中选择「轻舟浏览器」                                                                                                                                   |
| 行为    | v1.7.2 本期仅实现「可被分享选中」：Manifest 为 MainActivity 注册 ACTION_SEND 与 ACTION_SEND_MULTIPLE 两套 intent-filter，覆盖图片/音频/视频通配与压缩包类 MIME（zip、x-zip-compressed、rar、7z、gzip、tar，另含 application/octet-stream 兜底微信等发送方不标具体类型的场景）；收到分享后仅记日志（`收到分享: action/mime/hasStream`）并弹 Toast「轻舟浏览器已收到分享，处理功能开发中」，**具体处理方式下一步再定** |
| 关联代码  | `AndroidManifest.xml`（MainActivity 两个 SEND intent-filter）、`MainActivity.handleIncomingShare()`（onCreate 首次创建与 onNewIntent 复用实例两条路径均接）、strings.xml（`share_received_toast`） |
| 真机验证  | `cmd package query-activities` 实测 image/png、video/mp4、audio/mpeg、application/zip、x-zip-compressed、octet-stream、SEND_MULTIPLE 共 7 项均解析出 `com.trae.lightboat/.MainActivity` ✓；`am start` 模拟 SEND( application/zip + EXTRA_STREAM) 启动：应用进前台、日志正确记录、无崩溃（logcat -b crash 为空）✓ |
| 测试状态  | ✅ 真机冒烟通过；分享面板实机人工确认待用户在微信内实测；分享内容处理逻辑为下一步迭代内容                                                                                                                          |


### F-16 录音分享 → 云端转写（说话人分离/时间戳/方言转普通话）（本次新增）

| 项目    | 内容                                                                                                                                                                                                                                                                                                                                 |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 入口    | 其他应用分享音频类文件（MIME 音频类型）到「轻舟浏览器」→ 弹出「轻舟浏览器 · 录音处理」多选框（4 项可多选：识别成文本 / 标记每段发言起止时间（精确到秒）/ 区分说话人 / 方言翻译为普通话）→ 点「开始处理」                                                                                                                                                |
| 服务端  | asrsvc 部署于**法信 ECS**（8.133.194.29，16G 内存机器）：Flask 应用 `/opt/asrsvc/`（venv + app.py + config.json[600权限] + data/），systemd 服务 `asrsvc`（127.0.0.1:8720）。对外路由：HTTPS 主通道 `https://api.orientlaw.cn/api/asr/`（nginx sites-enabled/api_orientlaw_cn），HTTP 备用 `http://8.133.194.29:8800/api/asr/`（nginx conf.d/faxin.conf），鉴权 X-LZ-Token（config.json 内 token，APP 端 AsrClient.SERVER_TOKEN 同值） |
| 技术路线 | 百炼 **Fun-ASR 录音文件识别**（model=fun-asr，异步）：官方文档明确其说话人分离效果优于 Paraformer 系列；句级+字级时间戳；方言语音直接输出标准文本。「方言转普通话」= qwen-flash（同工作空间 compatible-mode）按 [行号] 映射分批改写，防串行。调用手顺：getPolicy(官方dashscope域名) → OSS私有桶上传 → 提交转写（专属maas域名 + X-DashScope-Async: enable + **X-DashScope-OssResourceResolve: enable[oss://必须]**） → GET /api/v1/tasks/{id} 轮询 → 拉取 transcription_url JSON |
| 关键代码 | `AsrClient.kt`（上传/轮询/Token）、`AsrShareFlow.kt`（多选框+流程编排+UI线程封装）、`AsrResultActivity.kt`+`activity_asr_result.xml`（结果页：说话人着色分段、时间戳、原文/普通话切换、复制/分享）、`MainActivity.handleIncomingShare`（音频分支路由） |
| 隐私设计 | 百炼临时 OSS 私有 ACL 且 48h 自动清理；服务器本地音频在任务结束（无论成败）**立即删除**；仅保留结果 JSON；传输走 HTTPS |
| 已验证  | 服务端全链路（真实 17.7 分钟 m4a：getPolicy→上传→转写→254 段说话人分段→方言译文无错→本地音频已删）✓；`am start` 模拟分享触发音频分支（logcat 确认）✓；APP 编译构建通过 ✓；**APP 端弹框→上传→轮询→结果页 UI 全流程待真机人工确认**（测试时手机 PIN 锁屏，adb 无法代输） |
| 已知限制 | 结果页为原生页面（非网页）；处理进度依赖 MainActivity 存活，用户中途关闭浏览器则进度 UI 丢失（任务在服务端仍继续，但 v1 不做找回）；分享面板可见性受 F-15 manifest 约束 |


### F-17 分享链接（带提取码）+ 说话人声纹记名/性别（F-16 增强）

| 项目    | 内容                                                                                                                                                                                                                                                                                                           |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 分享链接 | 结果页「分享链接」按钮 → POST /api/asr/task/{id}/share → 返回 `https://api.orientlaw.cn/s/{share_id}?pwd=XXXX`（提取码 4 位，链接 30 天有效）；nginx `/s/` 路由 → asrsvc 渲染密码门/结果页（原文/普通话切换、复制全文、人名展示）。提取码输错有错误提示；分享页已剔除声纹向量（生物特征不出服务器） |
| 声纹记名 | 转写完成后（音频删除前）按说话人截取最长 3 段共 ≤30s 音频，sherpa-onnx ERes2NetV2（3D-Speaker 中文模型，192 维）CPU 提取声纹向量；与声纹库（/opt/asrsvc/voiceprints/，按人名归档，滚动 5 样本）余弦比对：≥0.72 自动标人名，0.60~0.72 标「名字？」待确认，<0.60 保持说话人N。结果页点说话人标签即可标注（预填"？"名即确认），可选性别纠正；同名自动并入 |
| 性别判定 | 自相关法基频（F0）中位数：<160Hz 男 / ≥175Hz 女（重叠区就近）；用户标注过的性别优先于自动判定。次要功能，允许手动纠正 |
| 依赖与部署 | aria2 下载模型（GitHub 直连被墙，ghproxy.cn 被反爬，ghfast.top 可用 ~5MB/s）；venv 新增 sherpa-onnx、numpy；ffmpeg 系统自带。模型 `/opt/asrsvc/models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx`（71.4MB） |
| 真机/服务端验证 | 同一录音二次入库自动标出「石律师（男）/胡书记（男）」sim=1.0 ✓；结果页标签点击弹标注框（预填名字+声纹库快捷建议+性别三选）✓；分享页显示人名且不含 embedding ✓；提取 30s 音频约 7.4s（CPU）✓；声纹失败不影响转写主流程 ✓ |
| 已知限制 | 基频性别判定对电话音频有误判率（可手动纠正，库内性别优先）；F-16 时代的旧任务无声纹向量，仅可改名不可建档；阈值 0.72/0.60 为经验值，样本积累后可再调 |


### F-18 分享页段落回听（点击任意段落听对应原音）

| 项目    | 内容                                                                                                                                                                                                                             |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 入口    | 打开分享链接（/s/{share_id}?pwd=XXXX）后，点击任意转写段落                                                                                                                                                                                  |
| 行为    | 段落点击 → 播放该段起止时间（毫秒级）对应的原音频；再次点击暂停/继续，点其他段自动切换并高亮当前段；音频过期时行内提示「音频已过期或不存在」                                                                                                                                              |
| 策略变更 | **音频保留策略从「转写完成即删」改为「成功任务保留 30 天」**（与分享链接同寿命）：/opt/asrsvc/data/{tid}.audio，服务内置每小时清理线程自动删除过期音频；失败任务仍立即删除。APP 端无需更新（回听纯在分享页）                                                                                                            |
| 接口    | GET /s/{sid}/audio?a=<起始ms>&b=<结束ms>&pwd=提取码：校验分享+密码 → ffmpeg -ss 输入快进切片 → libmp3lame CBR 48k mp3 流式返回（audio/mpeg）；单次切片上限 10 分钟；越界自动截断到音频时长                                                                                              |
| 真实验证 | 浏览器（IAB）实测：点击「就是他跟我讲的…」段 → audio 元素 src 正确（a=14820&b=18740）、readyState=4、paused=false、currentTime 前进、duration=4s 精确、无 error、段落高亮 ✓；错误/缺失密码 403 ✓；切片 ffprobe 确认有效 mp3 ✓；页面 JS node --check 语法通过 ✓                                                                 |
| 已知限制 | F-18 之前的旧任务音频已删除，其分享链接点击段落会提示无法回放（文本结果不受影响）；mp3 为 pipe 流式输出无 Xing 头，个别播放器显示的总时长略有偏差（不影响播放）                                                                                                                        |


### F-19 分享链接有效期与打开次数可设置（F-17/F-18 增强）

| 项目    | 内容                                                                                                                                                                                    |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 结果页「分享链接」→ 先弹「分享设置」：有效期（1小时/**24小时(默认)**/3天/7天/30天）+ 可打开次数（1次/**5次(默认)**/10次/不限），再生成链接                                                                        |
| 服务端  | create_share 接收 form 参数 validity_hours / max_opens（缺省 24/5，hours 1~1440，opens 0=不限）；share 页每次密码校验通过并查看内容计一次，超出上限显示「打开次数已用完（上限 N 次）」；回放接口在超出后 403（等于上限时放行，保证第 N 次打开的页内回听可用）；过期分享记录延迟 7 天清理 |
| 真机/服务端验证 | 默认创建返回 validity_hours=24 / max_opens=5 ✓；同一链接打开 5 次均正常、第 6 次提示已用完 ✓；自定义 1 小时/10 次生效 ✓；APP 设置弹窗默认选中 24小时/5次，生成后弹窗显示「有效期：24小时 · 可打开5次」✓；APP 构建装机 ✓ |
| 备注    | 已存在的旧分享链接（30天期）不受影响；「不限」= max_opens=0                                                                                                                                                |


### F-20 分享提取码加强为20位

| 项目   | 内容                                                                                                                     |
| ---- | ---------------------------------------------------------------------------------------------------------------------- |
| 变更   | 提取码从4位加强为20位（32字符无易混淆字符表，约100位熵）：PWD_LENGTH=20；密码门输入框放宽至32位、加宽输入区适配长码；大小写不敏感校验不变                     |
| 兼容性 | 已生成的旧4位提取码链接继续有效（密码按分享记录逐条校验）；APP 无长度假设，无需更新                                                                 |
| 验证   | 新建分享返回20位密码 ✓；正确/大小写变体密码打开内容页 ✓；错误密码显示「提取码不正确」密码门 ✓；音频回放携带新密码正常 ✓ |


### F-21 分享页 AI 摘要与待办（deepseek-flash）

| 项目    | 内容                                                                                                                                                                                                      |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 生成分享链接时后台自动触发（每次任务仅生成一次，结果存入 task result）；打开分享页在转写内容上方展示「AI 摘要」与「待办事项」（带勾选框、责任人、时间）                                                                                              |
| 模型   | DeepSeek 官方 API `deepseek-flash`（推理模型，强制 response_format=json_object，max_tokens=8000）；Key 存服务器 config.json(600)，不入仓库                                                                                      |
| 生成逻辑 | 输入为带人名的分段转写；提示词要求输出 {summary, todos[{task,owner,due}]} 严格 JSON；解析容错（提取首个JSON对象、字段清洗）；失败静默+页面访问自动重试（_gen_inflight 幂等防重入）                                                                              |
| 关键缺陷 | 首版死锁：generate_summary_todos 在 `with _lock:` 内调用 save_task（非重入锁二次加锁）→ 全服务挂起；py-spy 抓栈定位后修复（锁内赋值、锁外落盘）                                                                                                    |
| 验证    | 真实17分钟录音：摘要准确覆盖自书遗嘱/财产清单/王律师转介等要点 ✓；12条待办含责任人（石律师/胡书记/王律师）与时间 ✓；分享页渲染 ✓；创建分享请求0.05s即时返回（异步生成）✓；页面访问自动重试 ✓                                                                    |


### F-22 音频识别多用户令牌体系（设置页入口 + 任务管理 + 数据隔离 + 防爆破）

| 项目    | 内容                                                                                                                                                                                                                                              |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 设置 → 「音频识别」分区 → 「管理上传的录音与转写结果 ›」→ AsrManageActivity（未激活显示令牌输入面板；已激活显示身份+任务列表）                                                                                                                                        |
| 令牌体系 | 3 个独立身份令牌（`LZASR-`+48位hex，54字符 ≥40位要求），存服务器 `/opt/asrsvc/users.json`(600)；`POST /api/asr/auth/verify` 核验通过返回身份名；任务按令牌身份（owner 字段）隔离——同令牌跨设备看到相同数据，不同令牌互相不可见（详情/删除/分享/标注均校验归属，越权一律404） |
| 防爆破  | ① 令牌本身 ~294位熵；② hmac.compare_digest 恒时比较防时序攻击；③ 单IP 15分钟窗口内失败≥8次即封禁（期间连正确令牌也拒绝），每次失败随机延迟0.2~0.6s；④ verify 接口封禁期返回429；⑤ 仅 HTTPS；⑥ 旧版全局令牌已停用                                                        |
| 管理页  | 列表（名称/时长/日期/状态）+ 点击打开结果页 + 长按删除（云端音频/结果/分享链接一并删）+ 刷新 + 更换令牌；令牌失效（401）自动清除本地并回到输入面板                                                                                                                              |
| 关联代码 | `AsrClient.kt`（去硬编码令牌，全部接口带用户令牌；新增 verifyToken/listTasks/deleteTask）、`AsrAuth.kt`（门控+输入弹窗+失效重试）、`AsrManageActivity.kt`+`activity_asr_manage.xml`+`asr_task_item.xml`、`AsrShareFlow`（分享流程令牌门控+失效自动重试）、`SettingsActivity/布局`（入口）、AppPrefs（令牌存储） |
| 服务端  | users.json；authenticate()/auth_blocked()/auth_record_failure()；端点全部改按 authenticate() 归属校验；新增 /auth/verify、GET /tasks、DELETE /task/{id}；启动时旧任务（无 owner）自动归入首位用户                                                                                   |
| 真机验证 | 设置页入口可见可点 ✓；令牌面板输入54位令牌核验通过显示「当前身份：用户A」✓；列表加载本人任务 ✓；点击任务打开结果页 ✓；B令牌列表为空、访问A任务404 ✓；错误token 8次后封禁（9次起一律拒绝，含正确令牌）、重启解封 ✓；删除任务连带音频与分享 ✓（8→7条） |
| 备注    | 用户甲=用户A（本机已登录）；需要增加人数或改名改 users.json 即可，APP 无需更新；封禁窗口15分钟，误锁等待或重启服务解除                                                                                                                              |


### F-23 性别误标修复与机制加固（胡书记案例）

| 项目    | 内容                                                                                                                                                                                                                                        |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 根因   | 胡书记实为女性。两层错误叠加：①基频自动判定为「女」**判对了**，但测试时手动标注传了「男」；②标注优先级高于自动判定，错误值经声纹库传播到全部任务与分享页。另有真实缺陷：160~175Hz 重叠区基频法强行二选一（猜测而非留空）                                                              |
| 修复   | ①数据修正：声纹库胡书记 gender=女，关联 4 个任务全量同步；②基频判定去猜测化：160~175Hz 重叠区返回空（不显示性别，标注弹窗可手动纠正）；③标注弹窗默认选中自动判断的性别并显示判断依据（基频 Hz）；④**修正全量传播**：label 后按 person_id 同步该用户所有任务（tentative「？」名一并确认）；⑤声纹库按用户隔离（owner 字段），不同用户的声纹/人名互不可见、互不污染 |
| 举一反三 | ①「用户纠正权威于自动判定」策略保留，但纠正入口展示自动判断依据，降低误标概率；②同一 person_id 的修正自动传播，一处纠正全局生效；③多用户隔离延伸到声纹库（此前全局共享存在跨用户污染风险）；④不确定时诚实留空，不输出可能错误的猜测                                                                    |
| 验证   | 人为把 23b42a0d 胡书记改回「男」→ 在 637b7ba5 上纠正为「女」→ 自动传播 4 个任务 ✓；分享页实测渲染「胡书记（女）」且「（男）」不再出现 ✓；声纹库 胡书记=女(样本2)/石律师=男 ✓；APP 构建装机 ✓ |
| 备注   | AI 摘要/待办文本如已含错误性别代词不回改（重新分享也不重新生成，避免反复扣费）；如需重生成可删除任务后重新识别                                                                                                                                           |


### F-24 性别判定真值校准（俩男的聊/一男一女 两份标注样本）

| 项目    | 内容                                                                                                                                                                                                                              |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 样本   | 用户提供两份带真值录音：俩男的聊（5.6分钟，双男）、一男一女（3.4分钟，男女各一）                                                                                                                                                                          |
| 实测数据 | 男声基频中位数 122 / 144 / 164 Hz；女声 258 Hz；不同人声纹相似度仅 0.08~0.26（远低于0.60误匹配线，无误标名）                                                                                                                                           |
| 校准   | 男声上限 160→**170**（164Hz 真值男声证明原阈值过紧）、女声下限 175→**190**（258Hz 真值确认，减少高音男声误判女）、170~190 诚实返回「不确定」；判定依据随结果存档（f0 字段）                                                                                             |
| 修复缺陷 | 声纹库性别覆盖无相似度门槛：任何陌生声音都会继承库里最像的人的性别（哪怕相似度仅0.08），可静默推翻基频判定。现要求 sim≥0.60 才允许覆盖                                                                                                                            |
| 真机/服务端验证 | 校准后重新入库：俩男的聊 双男✓；一男一女 男✓女✓（4/4 正确）✓；无错误人名匹配 ✓；重复测试任务已清理（保留 继承事宜及流程探讨 + 两份新样本）                                                                                                                              |
| 备注   | 阈值基于5个真值点（含此前石律师122区间推测），后续每次用户在标注弹窗纠正性别都是新的校准反馈，可继续微调；说话人声纹相似度表现支持 0.60/0.72 阈值设置                                                                                                                          |


### F-25 视频分享 → 与音频同一处理方案

| 项目    | 内容                                                                                                                                                                                                              |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 其他应用分享视频（MIME video类型，mp4/mov/mkv/avi/webm等）到轻舟浏览器 → 与音频相同的多选框（识别成文本/起止时间/区分说话人/方言转普通话）→ 同一流水线                                                                                      |
| 服务端  | ALLOWED_EXT 扩展视频格式；create_task 收到视频先落盘 .upload → ffmpeg 抽取音轨为 16k 单声道 m4a（64kbps）→ 后续转写/声纹/回听全部使用小音频文件；注意 ffmpeg 输出无扩展名文件必须显式 `-f ipod`，否则因无法推断封装格式而失败（首轮部署踩坑）。抽取失败返回422友好错误 |
| 关键代码 | asrsvc create_task（视频分支）、ALLOWED_EXT/VIDEO_EXT；APP MainActivity.handleIncomingShare（video分支）、AsrShareFlow.resolveExt（视频MIME映射）、strings（文案改「音视频」）                                                                        |
| 真机验证 | 服务端：45秒mp4 → 30秒完成，声纹自动匹配石律师(男,0.897)/胡书记(女,0.817) ✓；真机：video/mp4 分享 → 弹「音视频识别」框 → 结果页 人名/时间戳/普通话 ✓；无崩溃 ✓                                                                                             |
| 备注   | 抽取出的音轨替代原视频参与30天保留与回听（体积小）；任务列表/结果页展示原始视频文件名；图片/压缩包分享仍为「已收到」提示（F-15）                                                                                                                              |


### F-26 音频处理全面复审：功能细化 + 系统化debug测试

| 项目    | 内容                                                                                                                                                                                                                                                                      |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 审计修复（服务端） | ① 任务文件非原子写 → 临时文件+os.replace 原子落盘（防并发读到半截JSON）；② 视频音轨提取从HTTP请求线程移入worker（大视频不再阻塞/超时请求，APP轮询可见「视频音轨提取」阶段）；③ 摘要空结果重生成循环 → summary_done 标记（成功后不重复消耗token）；④ 声纹单段提取失败不再拖垮全部说话人（分段容错）；⑤ 失败任务记录30天后自动清理；⑥ 分享记录落盘统一走原子写 helper                                                                |
| 功能细化（APP） | 管理页列表新增：视频标记、已分享条数、处理中阶段展示；文案统一「音视频」；onUi UI等待加5秒超时保护防卡死                                                                                                                                                                                                                    |
| 服务端细化 | list_tasks 增加 is_video/stage 字段；管理页可见「视频 · 已分享N条 · 处理中阶段」                                                                                                                                                                                                                |
| Debug测试 | 15项断言套件：有效/无效令牌核验 ✓、非法扩展名400 ✓、缺文件400 ✓、音频全流程 ✓、视频异步抽取全流程 ✓、无音轨视频=FAILED+明确错误 ✓、B读/删/分享A任务均404 ✓、B列表为空 ✓、打开次数上限（5次后拒绝）✓、删除连带失效分享 ✓、重启恢复（RUNNING→FAILED 服务重启中断）✓。期间发现并修正2处测试脚本自身问题（本地/服务器文件路径、断言过时），非产品缺陷 |
| 真机回归 | 设置页入口 → 管理页（身份/列表/视频标记/分享数）✓；APP 构建装机 ✓                                                                                                                                                                                                                          |
| 已知边界 | 大视频上传仍受 HTTP 单次上传限制（APP 600s / nginx 600s，约支持普通网络下500MB）；Flask 开发服务器承载（1-3用户规模足够，规模化可换 gunicorn）；ACTION_SEND_MULTIPLE 多文件分享暂不处理（维持提示）                                                                                                                              |


### F-27 设置页双标签改造 + 音频识别内点击播放/段落回听

| 项目    | 内容                                                                                                                                                                                                                                                                          |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 设置页   | 重构为双标签页：TabLayout「下载管理 | 音频识别」；隐藏组件开关与用法说明为公共区。音频识别管理界面（令牌面板/身份/列表/删除）从独立 AsrManageActivity 迁入设置页，该 Activity 已删除                                                                                                                                          |
| 列表播放 | 点击任意任务条目 → 播放对应录音（语音消息式，▶/⏸ 切换，点其他条自动切换）；「结果」按钮打开转写详情；长按删除不变                                                                                                                                                            |
| 结果页回听 | 点任意段落文本 → 播放该段对应原音（与分享页同款交互：暂停/继续/自动切换/蓝色高亮）；任务音频过期则提示                                                                                                                                                        |
| 服务端   | 新增 `GET /api/asr/task/{id}/audio`（令牌可走 Header 或 query 参数；不带a/b返回整个音频并支持 Range/206 拉流拖动；带 a/b 毫秒返回 mp3 切片）；create_task 存储 audio_mime；authenticate 支持 query token                                                                                              |
| 部署教训 | 本轮曾出现「加了端点忘 scp」导致 404——已修复重部署；此后以 grep 部署文件确认路由存在为标准动作                                                                                                                                                                                |
| 真机验证 | 双标签切换 ✓；音频识别标签显示身份+列表 ✓；点击条目播放（按钮⏸、服务端 200+206 Range 日志）✓；「结果」打开详情 ✓；点段落切片 200 回听 ✓；全程无崩溃 ✓                                                                                                                              |
| 备注   | 播放直连用户自己的服务器音频（30天保留期内）；令牌经 query 传递会进服务器本地 nginx 日志（仅 root 可读，风险可控）；令牌失效时自动清除并回到令牌输入面板                                                                                                                              |


### F-28 分享页标签化改版：识别结果 / AI 摘要·待办 双标签 + 复制反馈

| 项目    | 内容                                                                                                                                                                                          |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 标签化   | 分享页增加标签条：「识别结果」｜「AI 摘要 · 待办」（位于识别结果右侧）；识别结果页含原文/普通话切换、复制全文、段落回听；AI 页含摘要、待办勾选清单、独立「复制摘要与待办」按钮。无 AI 内容或生成中：AI 页内分别显示占位提示（生成中会自动触发重试），AI 无内容且未在生成时不显示该标签 |
| 复制反馈 | 复制全文/复制摘要与待办成功后弹出「复制成功」浮动提示（1.6秒自动消失）；clipboard API 不可用时自动降级 execCommand 兼容旧内核                                                                                       |
| 真实验证 | 浏览器实测：初始态识别结果激活/AI页隐藏 ✓；切换后 AI 激活、摘要预览正确、12条待办可见 ✓；复制摘要与待办 → toast「复制成功」✓；复制全文 → toast「复制成功」✓；切回识别结果 ✓；页面 JS 逻辑全部经浏览器执行验证                                                              |
| 部署教训 | 测试用旧分享链接打开次数耗尽显示「已用完」页（符合 F-19 设计），验证需新建分享                                                                                                                                          |


### F-29 说话人颜色统一修复（同人同色）

| 项目    | 内容                                                                                                                                                                                                                                        |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 现象   | 识别结果中同一说话人的标记颜色不统一（有的绿有的蓝）                                                                                                                                                                                                    |
| 根因   | 两套颜色分配逻辑都有缺陷：①分享页按「NAMES 里带 person_id 的条目计数」定位颜色——未匹配说话人没有 person_id、同一人被拆成多个 id、或条目缺失时计数错位；②APP 按 HashMap 键序 indexOf 定位——说话人条目缺失时全部落到默认色。任何一种错位都会造成同人不同色/不同人同色                                               |
| 修复   | 两端统一改为「**按说话人在转写中首次出现的顺序分配颜色**」：分享页在 render() 先构建 sid→颜色序映射（cmap）再渲染；APP 结果页在 render() 先构建 colorOrder（LinkedHashMap 按首次出现）再套色。同一说话人全程同色，跨原文/译文视图一致，且 APP 与分享页色序规则相同（蓝/红/绿/紫按首次出现）                          |
| 部署插曲 | 新 JS 的取模符 `%` 与 Python %-格式化冲突导致分享页500，已转义为 `%%` 并复验恢复；验证用分享链接打开次数被自动化测试耗尽属 F-19 正常行为，重新生成即可                                                                                                                  |
| 验证   | 分享页（浏览器实测）：普通话视图 石律师 65段全s0(蓝)、胡书记 60段全s1(红) ✓；原文视图同样一致 ✓；每说话人仅单一颜色 ✓；APP 构建装机 ✓                                                                                                                                 |
| 备注   | 颜色按「页面内首次出现顺序」分配，跨任务/跨设备不保证同一人同色（如需同人固定色可后续在声纹库为每人存固定色号）；若 diarization 把同一人拆成两个说话人 id，颜色仍会不同（那是识别层问题，可通过标注同名+声纹样本逐步纠正）                                                              |


### F-30 APP 结果页双标签（识别结果 | AI 摘要 · 待办）+ 按需生成摘要

| 项目    | 内容                                                                                                                                                                                                                                                          |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 标签化   | APP 结果页与分享页对齐：TabLayout「识别结果」｜「AI 摘要 · 待办」；识别结果页保留原文/普通话切换、点段回听、标注；AI 页含摘要全文、待办勾选清单（含责任人/时间）、复制摘要与待办按钮                                                                                                                              |
| 按需生成 | 未生成过摘要的任务，AI 页提供「生成摘要与待办」按钮 → 调用服务端新接口 `POST /api/asr/task/{id}/summary`（同步调 DeepSeek，APP 读超时放宽至300s）→ 生成后本地结果文件同步更新；已生成过则直接展示，不重复消耗 token；生成失败在页内提示可重试                                                                                                |
| 服务端   | 新增 summary 端点（owner 校验），复用 generate_summary_todos（幂等）                                                                                                                                                                                                       |
| 关联代码 | `AsrClient.generateSummary`、`AsrResultActivity`（TabLayout/双页切换/renderAiPage/generateAi/copyAiText）、`activity_asr_result.xml`（TabLayout + page_result/page_ai 双页）、strings（asr_tab_*/asr_ai_*）                                                                        |
| 真机验证 | 一男一女.m4a（无摘要任务）：AI 标签 → 点生成 → 75秒后摘要与待办正确显示（怀孕报喜/借车练手/AMT 等要点、责任人与时间）✓；「复制摘要与待办」按钮在位 ✓；全程无崩溃 ✓；有摘要任务（继承事宜）AI 页直接展示 ✓                                                                                                                                       |
| 备注   | 摘要按任务只生成一次（summary_done 幂等标记）；分享页与 APP 页内容同源一致                                                                                                                                                                                                              |


### F-31 设置页三标签改版（其他设置 | 下载管理 | 音视频识别）

| 项目    | 内容                                                                                                                                              |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 结构   | 设置页顶部公共区仅保留页面标题；TabLayout 三标签：其他设置（隐藏组件开关+用法说明）、下载管理（下载列表+版本号）、音视频识别（令牌面板/身份/任务列表，即原 F-27 迁入内容）                                                  |
| 命名   | 「音频识别」统一更名为「音视频识别」（F-25 已支持视频），涉及标签名、令牌核验弹窗标题、授权提示语                                                                                             |
| 交互   | 默认选中「其他设置」；切换即时生效；重选下载管理刷新列表、重选音视频识别刷新任务；音频识别管理逻辑（令牌门控/列表/播放/删除）不变                                                                               |
| 关联代码 | activity_settings.xml（三页面 FrameLayout 结构）、SettingsActivity.setupTabs（三标签+页面可见性切换）、strings（settings_tab_other、asr_manage_title 更名）                        |
| 真机验证 | 三标签渲染 ✓；其他设置 ↔ 音视频识别 切换正常 ✓；音视频识别页身份/列表正常 ✓；下载管理回归 ✓                                                                                              |


### F-32 12368 短信自动备份到 SER8（短信备份页签）

> ⚠️ **已于 v1.14.0/46 整体移除**（2026-10-05 用户拍板：功能独立成「短信守护」APP，产品文档与参考代码见 `D:\trae\apk开发范本模板原型 - 短信守护\`；服务端 smsbox 保留且已上线不受影响；APP 端实现可参考轻舟仓 commit 237ee2b）。以下为该功能在轻舟内时的原登记，留作历史与借鉴。

| 项目    | 内容                                                                                                                                                                                                                                                                  |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 入口    | 设置 → 短信备份页签：自动备份开关、①短信权限、②上传令牌、上传地址（高级）、回扫手机已有 12368 短信、待上存状态 + 立即上存按钮                                                                                                                                                                                              |
| 权限    | 新增 READ_SMS（回扫收件箱）+ RECEIVE_SMS（实时监听）运行时权限，页签内点按申请；拒绝后提示到系统设置手动开启                                                                                                                                                                                                    |
| 实时监听 | SmsReceiver 静态注册 SMS_RECEIVED（android:permission=BROADCAST_SMS 仅系统可投递），长短信多段 PDU 按发件号分组合并，仅发件号归一后等于 12368 的短信入队并触发异步上存；总开关关闭时不动作                                                                                                                                                 |
| 离线队列  | 上存失败的短信进本机 SQLite 队列（sms_backup.db pending 表），永不丢弃；打开 APP / 打开设置页 / 收到新短信时自动补传；上存成功记录 sent 表（按服务地址隔离，换地址或重装不污染判重）                                                                                                                                                   |
| 历史回扫 | 「回扫」读取收件箱全部 12368 短信，跳过本机已传记录分批（100 条/批）上存；服务端另按 sha1(发件号\|时间\|正文) 唯一索引幂等判重，重复上存无害；结果摘要持久化展示（手机共 N 条 / 新上存 M / 判重 K）                                                                                                                                                 |
| 服务端   | SER8 smsbox（server/smsbox/smsbox.py，纯标准库）：/ingest 批量落库、/ping 核验+计数、/list 查询、/ 网页查看页、/healthz 探针；公网入口 api.orientlaw.cn/api/sms/（ECS nginx 反代 tailnet→SER8:8730，UFW 限办公内网+tailnet）；**服务端与公网入口本批未部署**（部署清单见 server/smsbox/README.md）                                       |
| 关联代码 | core/SmsBackupLogic.kt（匹配/指纹/报文纯逻辑）、SmsBackupStore.kt（pending/sent 双表队列）、SmsBackupManager.kt（上传/回扫/冲刷/令牌核验）、SmsReceiver.kt、SettingsActivity.setupSmsBackup、AppPrefs（sms_* 键）、AndroidManifest（权限+receiver）、server/smsbox/                                                   |
| 单元测试 | SmsBackupLogicTest 9 例（号码匹配含 +8612368/123x68 负例、指纹与服务端同构对齐、报文往返）OK；既有纯函数回归 126 例全过 OK                                                                                                                                                                                |
| 模拟器验证 | UI 全流程：权限申请弹窗→已授予 ✓；上传地址改指本地 smsbox（adb reverse）✓；令牌输入→/ping 核验通过显示服务器计数 ✓；开关开启 ✓；回扫确认框→结果摘要展示 ✓；仪器测试 SmsBackupPipelineTest（入队→冲刷→HTTP→落库→sent 记账）OK、SmsReceiverPduTest（手工构造真实 SMS-DELIVER PDU→receiver→上传→服务器终态断言）OK；清理后冒烟状态干净 ✓                                      |
| 真机验证 | 华为 FCP-AN10 本批开发期间未连接 adb，**装机与真机冒烟待补**（install -r 覆盖装、真机权限弹窗、真实 12368 短信到达观察实时链路）；手机里真实历史短信回扫须待服务端部署后在真机执行，避免测试数据混入正式库                                                                                                                                              |
| 已知边界 | 实时接收依赖系统广播派发（静态注册，重启后仍有效）；部分 ROM 强杀后台不影响 manifest 接收器；鸿蒙对通知权限的限制与本功能无关（静默上存无通知）                                                                                                                                                                                       |


### F-33 资料夹：录音转写与聊天记录的跨类分组 + 聚合分享

| 项目    | 内容                                                                                                                                                                                                                                    |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 定位    | 把多条录音转写任务与多个聊天记录分享归入同一文件夹（一个文件夹内可混放两类内容），统一浏览、整体分享；服务端存储+APP 双侧功能，本批已部署（asrsvc）并装机（v1.15.0/47）                                                                                                                                    |
| 入口    | 设置页「音视频识别」与「聊天记录分享」两个页签列表顶部各一条「📁 资料夹」入口行（两处进同一套文件夹）；记录长按菜单新增「移入文件夹」（弹出已有文件夹列表 + 新建项）；音视频识别长按菜单改写为 结果/移入文件夹/删除，聊天记录长按菜单改写为 预览/链接/移入文件夹/删除                                                                                |
| 文件夹页 | 新建 FolderActivity：列表态（文件夹名+条目数，长按 打开/改名/删除）↔ 详情态（🎙/💬 图标+标题+元信息，失效条目红字标注，长按移出文件夹不删本体）同页切换，BACK 逐级返回；详情页「分享」按钮（有效期 1h~30天 / 打开次数 1/5/10/不限）生成聚合分享链接，「⋯」菜单改名/删除                                                                        |
| 聚合分享 | 接收方一个链接一张提取码：目录页（/s/&lt;sid&gt;，条目卡片清单+有效状态）→ 条目页（/s/&lt;sid&gt;/item/&lt;idx&gt;）逐条查看——录音条目=转写页（双标签/AI摘要/段落回听），聊天条目=原气泡流页（图片/视频/附件/OCR）；录音段落回听走新端点 /s/&lt;sid&gt;/item/&lt;idx&gt;/audio（文件夹提取码鉴权）；聊天页 Agent 入口改指 folder.json                                    |
| Agent | GET /s/&lt;sid&gt;/folder.json?pwd= 返回整夹结构化 JSON：录音条目含 text/segments/speaker_names(剔声纹)/summary/todos/音频直链；聊天条目含 messages(逐条 realname/is_self/file_url/ocr_text)/attachments 直链/realnames；失效条目带 error 占位                                                                                                                                              |
| 服务端   | app.py 新增：data/&lt;fid&gt;.folder.json 存储（成员仅存引用 kind=task/wxchat+ref，本体删除后条目自动显示已失效）；接口 GET/POST /api/asr/folders、GET/DELETE /api/asr/folders/&lt;fid&gt;、POST rename、items、items/remove、share；分享页 folder_share_page/folder_item_route/folder_item_task_page/folder_item_wxchat_page/folder_item_audio/folder_json；每用户 100 夹、单夹 50 条上限；文件夹分享不设独立寿命字段但聊天条目受原分享 30 天寿命约束                                                                              |
| 关联代码 | FolderActivity.kt、activity_folder.xml、folder_list_item.xml、folder_entry_item.xml、AsrClient（FolderInfo/FolderEntry/FolderDetail + 7 个接口）、SettingsActivity（两页签入口行+长按菜单+showAddToFolderDialog/promptNewFolderAndAdd/addToFolder）、activity_settings.xml、strings（folder_* 15 条）、server/asrsvc/app.py                                                                                     |
| 验证    | 服务端 curl 全链路冒烟 13 项 ✓（建夹/加两类条目/去重/详情/分享/目录页/条目页/切片 mp3 真实产出/错码 403/公网 nginx 200/清理）；真机（华为 FCP-AN10）：建夹→长按移入录音→长按移入聊天→详情两类条目→点录音进结果页→点聊天开页内预览→分享生成链接 ✓；浏览器（IAB）实测：目录页截图 ✓、录音条目页说话人分段+点段真实播放（audio readyState=4 playing）✓、聊天条目页气泡+图片+返回目录 ✓、folder.json 结构完整（分段/全文/摘要/70条消息/附件直链/真名）✓                                        |
| 已知边界 | 文件夹内成员被删除/过期后条目显示「已失效」（不自动移除，可在详情页长按清理）；文件夹分享链接失效仅影响分享，不删文件夹；聊天条目的分享页依赖原 chat 分享存活（30 天）                                                                                                                          |


### F-34 资料夹补强：夹内添加 + 列表多选批量移入

| 项目    | 内容                                                                                                                                                                                                                                            |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | ① 进入某个资料夹后，可直接把外面的录音摘录/聊天记录挑进当前夹；② 在列表里先多选录音或聊天记录，再批量移入指定资料夹（两个方向，2026-10-11 用户追加）                                                                                                                    |
| 夹内添加 | 资料夹详情页右上按钮由「＋ 新建」切换为「＋ 添加」（列表态仍是新建）：弹出挑选面板——按「录音摘录 / 聊天记录」分组（带分组标题），只列**转写已完成且不在本夹**的录音与**有效且不在本夹**的聊天记录，多选后确定批量加入，添加结果 toast（新增/已在夹中/失败分开计数）                                                          |
| 列表多选 | 「音视频识别」「聊天记录分享」两个列表：长按菜单新增「多选」进入选择模式（行首出现复选框，行内操作按钮让位，点行=勾选/取消，长按行=切换）；列表上方出现选择栏（已选 N 项 / 移入文件夹 / 完成）；「移入文件夹」弹出文件夹列表+新建项，选中即批量加入；完成后自动退出选择；BACK 退出选择模式；刷新列表时自动清理已消失条目的勾选                                 |
| 服务端   | 新增 `POST /api/asr/folders/<fid>/items/batch`（body {"items":[{kind,ref}...]}）→ {added, skipped, failed:[...]}：逐条校验归属/存活、夹内去重（skipped）、超上限或成员无效入 failed，其余入夹；仍受 50 条/夹上限约束                                                                                                          |
| 关联代码 | SettingsActivity（asrSelectMode/asrSelected、wxSelectMode/wxSelected、enter/exit/render/toggle×2、showBatchAddDialog/promptNewFolderAndAddBatch/addBatchToFolder）、asr_task_item.xml/wx_share_item.xml（item_check 复选框）、activity_settings.xml（asr/wxchat_selection_bar）、FolderActivity（showAddItemsPicker/showPickDialog/addPickedItems/PickRow）、folder_pick_item.xml、AsrClient.folderAddItems、strings 18 条                                                        |
| 验证    | 服务端 curl：批量加入（1 有效+1 不存在+1 重复）→ added=1/failed=1/skipped=1 ✓；重复再加全 skipped ✓；空 items 400 ✓；真机（华为 FCP-AN10）：列表多选 2 条→移入 case-zhang→服务器 +2 ✓、选择栏自动退出 ✓；夹内「＋添加」→面板正确过滤（处理中/已在夹的不出现）→勾 1 条聊天记录→服务器 +1、详情即时刷新 ✓；多选态按 BACK 退出且不离开页面 ✓                                        |
| 已知边界 | 挑选面板的聊天记录条目受原分享 30 天寿命约束（失效后不再出现在面板）；批量加入时夹满/成员失效的条目在 toast 中提示「N 条失败」，不影响其余条目                                                                                                                            |


### F-35 资料夹模型改造：唯一归属（move 语义）+ 根目录 + 分享参数记忆

| 项目    | 内容                                                                                                                                                                                                                              |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | ① 每条录音摘录/聊天记录最多移入 1 个资料夹；② 已归档条目不再展示在「根目录」（两个列表即根目录）；③ 分享时选择的「有效期/可打开次数」记住，下次作为默认值（v1.15.2 引入，本批一并验证）                                                                                  |
| 唯一归属 | 服务端 move 语义：`_detach_from_other_folders`——单条加入与批量加入时先把条目从该用户其他文件夹移出再入新夹（响应带 moved_from/moved_from_others）；一条记录任一时刻只归属一个夹。旧数据（部署前的双重归属）不自动收敛，下次移动时自动收敛                                                          |
| 根目录  | `GET /api/asr/folders` 每个 folder 携带 items 明细（kind+ref）→ APP 构建归档索引，两个列表（音视频识别/聊天记录分享）只显示未归档条目；入口行动态计数「资料夹：N 个文件夹 · 已归入 M 条」；全部归档时空态文案引导去资料夹查看；移出文件夹后条目回到根目录；移入成功后立即刷新对应列表（条目当场消失）  |
| 挑选面板 | 夹内「＋添加」改为只列**根目录条目**（未归入任何夹的），已归档条目（含本夹）不再出现——面板天然满足唯一归属                                                                                                                              |
| 分享记忆 | AppPrefs 新增 share_validity_hours/share_max_opens（全局，录音分享要求页与文件夹分享弹窗共用）：生成时保存所选参数，下次打开各自界面默认上次的值；首次回落产品默认 24h/5次                                                                                              |
| 缺陷修复 | ① onResume 刷新可见页签列表（从资料夹页移出后回设置页，根目录立即反映）；② refreshFolderIndex 后台线程直接更新 TextView（跨线程 UI）造成 tasks 请求延迟 69s——包进 runOnUiThread 修复                                                                                   |
| 关联代码 | server/asrsvc/app.py（_detach_from_other_folders、folder_add_item/items/batch move 语义、folder_list 带 items）；SettingsActivity（refreshFolderIndex/filedTaskRefs/filedWxRefs、loadTasks/loadWxShares 过滤、onResume、addToFolder/addBatchToFolder 刷新）；FolderActivity.showAddItemsPicker；AsrShareOptionsActivity；AppPrefs（share_*）；AsrClient.FolderInfo.items                                                                                     |
| 验证    | 服务端 curl：加 A→move 到 B（A 归零 B 收下、moved_from_others=1）✓；真机：根目录只显示未归档（录音 8 条、聊天 5 条，已归档的消失）✓、入口行计数 ✓、移出 case-zhang 后条目回根目录（经 onResume 刷新）✓；分享参数：生成用 7天/10次 → 重开弹窗默认 7天/10次 ✓；兼容：Spinner 记忆对录音分享要求页与文件夹弹窗同时生效（同一 AppPrefs）                                                                 |
| 已知边界 | move 语义上线的旧双重归属数据不自动收敛（如「微信-上海象屿符光宇」同时在 pc 和 case-zhang）：两处都显示、根目录不显示；用户从任一夹移出/下次移动时自动收敛，或手动清理                                                                                                          |


### F-36 两个管理列表支持关键词搜索

| 项目    | 内容                                                                                                                                                                     |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | 「音视频识别」「聊天记录分享」两个列表支持关键词搜索（2026-10-12 用户提出）                                                                                                                      |
| 形式    | 每个页签列表上方一个搜索框（白底，hint「搜索录音名称」/「搜索标题或成员」），输入即实时过滤（TextWatcher，无需按钮）；多选模式下搜索框隐藏、退出多选恢复                                                                          |
| 匹配范围 | 音频：录音名称（display_name）；聊天记录：标题 + 成员（微信昵称/已填真名）；均不区分大小写                                                                                                                  |
| 空态   | 有数据但搜索无匹配 →「无匹配"关键词"的结果，换个关键词试试」；无数据仍显示原有原因文案（全部归档/暂无记录）——空态显隐与文案统一收敛到 renderAsrEmpty/renderWxEmpty（列表加载/删除/搜索三处共用）                                                       |
| 实现   | 纯本地过滤（列表数据本就全量拉自服务端，不打服务端）；asrFiltered()/wxFiltered() 过滤视图，适配器 count/getItem/点击/长按全部走过滤视图；搜索词不持久化（进页即清）                                                                  |
| 关联代码 | SettingsActivity（asrQuery/wxQuery、asrFiltered/wxFiltered、renderAsrEmpty/renderWxEmpty、两个 TextWatcher、enter/exitSelection 隐藏搜索框）、activity_settings.xml（asr/wxchat_search_input）、strings 3 条                                            |
| 验证    | 构建+装机 v1.15.4/51；真机交互冒烟因用户正使用手机（前台企业微信）按纪律中止，未逐字验证——过滤/空态/多选隐藏请按「设置→音视频识别→搜索框输入录音名片段；聊天记录分享→输入成员名」自查；逻辑为纯本地字符串过滤，风险低                                                                 |

### F-37 识别结果详情页「移入资料夹」+ 多选批量操作（复制全文/分享/分享链接）

| 项目    | 内容                                                                                                                                                                                                                               |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 需求    | ① 识别结果详情页底栏增加「移入资料夹」按钮（2026-10-12 用户截图标注）；② 一次性分享多个音频文件时批量转写、转写完成后能对多条记录执行详情页同款按钮操作                                                                                                  |
| 多文件分享 | 链路 v1.8.1 已具备（ACTION_SEND_MULTIPLE → 逐项核实 MIME → AsrProcessingService 排队转写 → 悬浮条/通知进度），本批核对代码路径完好，无改动                                                                                          |
| 详情页按钮 | 底栏 4 按钮：复制全文 / **移入资料夹** / 分享 / 分享链接；无 taskId 提示「任务信息缺失，无法归入资料夹」；归夹走 FolderAddFlow.moveSingle（与列表长按移入同一套「选已有夹/新建夹」流程），归档提示含新增/已在夹中/失败计数；横向 inset 归零防窄屏换行                            |
| 批量操作  | 多选操作栏改两行：上行 已选计数+完成，下行 移入文件夹 / 复制全文 / 分享 / 分享链接（未勾选时四钮齐灰）；仅作用于**已完成识别**的勾选条目，无已完成项提示「勾选的记录里没有已完成识别的」；复制=逐条拉取结果汇总为「【文件名】转写正文」拼篇进剪贴板；分享=同一份文本走系统分享；分享链接=逐条 createShare（参数沿用记忆值）汇总为链接清单弹窗（复制/分享两用）；单条失败不阻断其余，失败名单附在汇总尾部        |
| 共用模块 | 新增 core/AsrTranscripts（纯函数渲染，详情页复制与批量汇总同一格式）、AsrTranscriptsJson（JSON 适配，options 缺失时按内容推断开关）、FolderAddFlow（归夹对话框流程，设置页两列表+详情页三处共用）；SettingsActivity 删除 6 个私有归夹函数改委托；AsrClient.folderAddItem（单条接口）与 strings.folder_added 移除（统一走批量接口）；详情页标注说话人后 resultJson 同步更新（否则复制全文用旧名字） |
| 关联代码 | AsrResultActivity（moveToFolder/resultJson/buildPlainFullText）、SettingsActivity（selectedDoneTasks/batchCopyTranscripts/batchShareLinks/showAddToFolderDialog/showBatchAddDialog 委托/renderAsrSelection）、activity_asr_result.xml、activity_settings.xml（asr_selection_bar 两行）、strings 12 条、app/build.gradle.kts |
| 单测    | AsrTranscriptsTest 6 例（具名+回退编号、无说话人前缀、无时间戳、空名回退+非0起点编号、空段落、去空白）✓；全量纯函数回归 128 例 OK（JUnitCore 直跑，含 zxing/stdlib classpath 补齐）✓ |
| 验证    | v1.15.7/54 已 `install -r` 装机（dumpsys 确认 versionCode=54）；冷启动/多 Activity 启动零 FATAL（logcat -b crash 空、dropbox 无崩溃记录）、SettingsActivity 前台运行正常（系统日志佐证）。**元素级点验受限于真机**：该 ROM a11y dump 冻结旧树 + screencap 黑帧 + 冒烟期间用户共机使用，详情页四按钮/多选栏四按钮未逐一点验，留给用户按交付清单自查；冒烟期间唯一副作用=取消了一个「添加到主屏幕」弹窗（未装图标） |

### F-38 多文件分享：整批转完统一出结果 + 批量结果页（同款四按钮管一批）

| 项目    | 内容                                                                                                                                                                                                                                                               |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | 多文件分享转写时「仅部分完成就逐个跳出结果页」→ 应整批全部转完才出结果，并对一整批结果执行四个按钮操作（2026-10-12 用户反馈）                                                                                                                                                                       |
| 流程改造 | AsrShareFlow 不再逐个 deliverResult：成功结果收进 collected，批次收尾统一呈现——单文件照旧直接开详情页；**多文件开新批量结果页**；应用在后台完成时整批排队（含失败数），MainActivity 回前台 drainPending 统一呈现。进度悬浮条/通知/失败汇总口径不变                                                                                           |
| 批量结果页 | 新增 AsrBatchResultActivity（非导出，portrait）：标题「转写完成（N 个文件）」+ 红字「另有 M 个转写失败」（如有）；条目=文件名+时长/选项摘要（后台线程读本地结果 JSON 预载）；底栏四按钮对整批执行——复制全文=本地结果文件直接拼篇（不走网络）、分享=同一文本、分享链接=AsrLinkBatch 逐条清单、移入资料夹=FolderAddFlow 整批归夹；点条目进单篇详情页（参数口径与 openResult 一致） |
| 共用模块 | core/AsrTranscripts 新增 joinDocuments（【文件名】抬头的拼篇格式，设置页批量复制与批量结果页共用）；新增 AsrLinkBatch（批量生成分享链接+汇总弹窗，设置页多选「分享链接」改委托，消除两处重复）                                                                                                                              |
| 关联代码 | AsrShareFlow（runBatch/finishBatch/drainPending/presentResults/openBatch、pendingFailedCount）、AsrBatchResultActivity、AsrLinkBatch、SettingsActivity（batchShareLinks 委托/batchCopyTranscripts 拼篇）、activity_asr_batch_result.xml、asr_batch_item.xml、AndroidManifest、strings 4 条 |
| 单测    | joinDocuments 2 例（命名段落+空行分隔、空列表）；全量纯函数回归 130 例 OK                                                                                                                                                                                                   |
| 验证    | v1.15.8/55 已 install -r 装机；冷启动零 FATAL。**端到端多文件转写留用户验证**（需真实分享多个音频走完上传→转写，shell 的 am 造不了多 Uri 列表且冒烟期间用户共机使用）；隔离验证批量页（伪造结果 JSON + am 直启非导出 Activity）被 shell 权限拦下，页面逻辑与已验证的共用模块同源                                                                    |

### F-39 录音转写去重：相同内容+相同选项的分享直接复用既有结果

| 项目    | 内容                                                                                                                                                                                                                          |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | 反复拿同一组录音测试时，每次分享都重复上传+重复转写（2026-10-12 用户提出）——应当「避免重复摘录」                                                                                                                                                |
| 去重键 | 文件内容 MD5 + 识别选项标签（transcribe/timestamps/diarize/dialect2mandarin 四位 0/1）：**同内容同选项**才命中；换选项（如补勾方言译普通话）视为新键照常重转。MD5 在拷贝分享内容到缓存时用 DigestInputStream 顺带计算，不额外读一遍文件                                                              |
| 命中复用 | 本地索引查到 task_id → getTask 核验服务端任务仍 SUCCEEDED → 结果落盘 asr_result_<taskId>.json 直接当成功返回（悬浮条提示「已转写过，直接复用结果」），全程不上传；任务已被删除/失败/索引未命中 → 照常重转，成功后记入索引。getTask 每命中项只多一次轻量查询                                                                                       |
| 存储   | filesDir/asr_dup_index.tsv（`key	task_id	时间戳` 逐行），读写全程加锁；上限 500 条超出淘汰最早写入，同键重写视为新。纯逻辑在 core/AsrDupIndex（键生成/TSV 解析序列化/淘汰），Android 侧薄封装 AsrDupStore；聊大 zip（微信聊天记录）不在此链路，天然不受影响                                                              |
| 关联代码 | AsrShareFlow.processOne（MD5+查复用+成功记入）、reuseIfTranscribed、AsrDupStore、AsrClient.Options.dedupTag、core/AsrDupIndex、strings asr_progress_dup_reuse                                                                                                            |
| 单测    | AsrDupIndexTest 5 例（键生成/选项区分、TSV 往返、脏行容错、同键重写移位、超限淘汰最早）；全量回归 135 例 OK                                                                                                                                                              |
| 验证    | v1.15.9/56 装机后 adb 实测通过：①升级后第一轮（21:26-21:29，8 文件真实转写约 3 分钟内逐条建索引 8 条，用户误读为"仍重复"）；②第二轮分享同 8 文件（含 18分59秒 长录音）：**约 7 秒出「转写完成（8 个文件）」批量结果页，索引文件 md5/mtime/条数三者不变**（零新转写零上传）；③批量页复制全文按钮点击正常无崩溃。结论：去重生效，第一轮建索引为预期代价。服务端不存文件哈希（grep 确认），升级前存量任务无法纳入去重；重装/换设备后同理需重建索引——如要连这一轮都省，需服务端记内容哈希（改动+部署），暂未做 |

### F-40 微信聊天记录默认名改「参与人真名 + 起止时间」

| 项目    | 内容                                                                                                                                                                                                                          |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | 转存默认名一直是微信 zip 名「聊天记录_2026****」（2026-11-08 用户提出）——改成参与人（备注真名优先，其次微信昵称）+起止时间                                                                                                                              |
| 默认名  | 服务端转存时生成：真名映射（昵称→真名）优先、未映射用昵称；标记过自己昵称（`__self__`）的排除自己；>3 人取前 3 人加「等N人」；时间紧凑格式（同天带时刻 `2026.1.5 14:02~18:35`、跨天 `2026.1.5~3.8`、跨年 `2025.12.30~2026.1.3`，单条/倒置只显日期）；微信默认 zip 名（`聊天记录[\d_]*`）才触发，自定义命名的 zip 原样保留 |
| 追加刷新 | share 带 `auto_title` 标记：追加自动按归并后的时间范围+新参与人+当前真名映射重生成；改名接口默认清标记（手动定制不再动），`auto=true`（APP 真名回填）保留标记；旧分享无标记不受影响                                                                                       |
| APP 侧 | 弹窗预填改用服务端返回的 `title`（旧服务端无字段回退 zip 名）；真名填写保存后，没手动改过记录名的把刚存真名单趟正则回填进标题（长昵称优先命中防子串误伤），以 `auto=true` rename；手动改过的以默认参数 rename（清标记）。关联代码：app.py `_wx_time_span/_wx_auto_title/_wx_is_default_zip_title`、upload/append/rename 三端点、AsrClient.WxChatShare.title+renameWxChatShare(keepAuto)、WxChatShareFlow.applyNamesToTitle |
| 单测    | 服务端 19 例（真源提取函数 exec 实测：时间四形态/倒置/脏值、真名优先/排除自己/截断/去重/空映射回退、zip 名判定）；APP WxChatTitleTest 6 例（含长昵称子串误伤回归，JUnitCore 直跑 OK，连同下载批次回归 26 例全绿）                                                                                      |
| 验证    | APK 已装机华为 FCP-AN10（install -r，版本仍 1.16.3/60 未 bump）；端到端（微信转发→弹窗预填默认名）待 asrsvc 部署后用户验收；装机冒烟时手机正被使用（微信登录页前台），启动冒烟并入验收跑，崩溃缓冲区干净                                                                                                    |

### F-41 聊天记录分享页顶部加「移入资料夹」按钮

| 项目    | 内容                                                                                                                                                                                                                          |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | 分享页顶部只有「复制全文」「Agent 数据（JSON）」（2026-11-08 用户提出）——加「移入资料夹」，转存完不用再回设置页长按操作                                                                                                                                  |
| 鉴权设计 | 页面是公开链接（转发给 AI/他人可开），资料夹接口按 owner 令牌鉴权，页面自己不知道访客是谁。方案：**APP 注入 owner 令牌**——MainActivity 在 onPageFinished 对 asrsvc 域（与 AsrClient.BASE_URL 同 host）注入 `window.__lzAsrToken()` 并揭示按钮（页面默认 `display:none`）；外部浏览器/AI 无注入→看不到按钮也拿不到令牌；令牌仅注入用户自己的服务域，不暴露给任意网页。移动接口沿用 `_member_owned` 归属校验，跨用户的分享 404 |
| 页面交互 | 弹层列资料夹（名称+有效条数），点选即移入（move 语义，自动从其他夹移出）；底部「新建并移入」输入名称先建后移；toast 反馈（已移入/已在该资料夹中/令牌失效/非本人分享）。服务端无新接口，复用 GET/POST /api/asr/folders 与 POST /api/asr/folders/<fid>/items                                                                                       |
| APP 侧 | MainActivity.injectAsrOwnerJs（onPageFinished 挂钩，仅 asrsvc host 注入；AsrClient.BASE_URL 放开为公开常量）；**MainActivity 属下载批次未提交文件，本批次改动经 HEAD+插入构造单独暂存，未混入下载批次**                                                                                                     |
| 验证    | JS 真源提取 node 语法检查 + 桩环境 10 例全过（无令牌不发请求/弹层结构/行 data-id/点击监听/令牌携带/移动目标与 kind/ref/直调路径）；py_compile 过；APK 编译过，装机因设备掉线未完成（华为 USB 掉线+vivo WiFi 不在线）；端到端（分享页点按钮→移入→设置页资料夹可见）待 asrsvc 部署后验收                                                                                                    |

### F-42 记住密码按网站独立记忆（HTTP 认证多系统隔离）

| 项目    | 内容                                                                                                                                                                                                                          |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 需求    | 2026-11-14 用户提出：记住密码有缺陷，同账户名在不同网站应有不同记住的密码；选择题确认场景=同一服务器多个系统串密码，补充要求不同子域名也分别记录                                                                                                  |
| 存储设计 | 键由「域名+区域」单条改为「源(协议+域名+端口)+区域」多条：同源下按系统路径提示（页面 URL 首段）存多条凭据；同 路径+账号 upsert 覆盖（改密场景）、同路径不同账号并存、不同路径同账号名并存；取消记住只清当前系统路径；旧「host\|realm」单条记录读侧兼容预填、重存自动入新键、取消时一并清除。unknown:// 前缀保证新键与旧键永不重合；minSdk 24 故 Base64 序列化留在设备侧（HttpAuthEntryCodec 注入式，纯逻辑零 Android 依赖） |
| 预填规则 | 精确段匹配/祖先前缀匹配取最长 appPath，并列取最近使用；根路径("/")条目匹配一切但让位于更长匹配；无匹配且该源仅一条→回退预填（单系统不受路径提示影响），多条→不猜留空；页面域名与质询域名不一致时路径提示降级为 "/"                                                                                                     |
| 关联代码 | core/HttpAuthEntries（originKey/appPathOf/pick/upsert/removeAppPath）、HttpAuthEntryCodec、HttpAuthStore 重写（get/put/clear 签名带 originKey+appPath）、MainActivity.showHttpAuthPrompt 增 view 参数推导 origin/路径                                                                                     |
| 单测    | HttpAuthEntriesTest 17 例（子域名/端口成键、默认端口归一、段边界不误配 /app≠/application、同源两系统同账号名各取各的——缺陷回归核心、根条目让位、单条回退、多条不猜、同路径多账号取最近、改密覆盖、取消只清本系统、序列化往返含中文/换行/空密码、坏行跳过、旧格式不误读）；全量纯函数回归 175 例 OK（JUnitCore 直跑）                                                        |
| 验证    | v1.17.0/63 assembleRelease 构建过；真机整链验证未做（手机未接 USB，用户指示直接发布）；已知边界：引擎会话级凭据缓存同源同区域内先静默发旧凭据→服务器 401 后弹窗预填正确记录（切系统多一次弹窗，不会错密登录）。**已发布 soft.orientlaw.cn 条目 id=2「轻舟浏览器」**：APK 经 ssh 管道上传（服务端 sha256=本地一致），sqlite 事务直插 software/downloads/passcodes（口令 12368 律所分发），公网解锁→下载→字节比对全链通过（2026-11-14） |
