# 极简浏览器 (LiteBrowser)

## 项目简介
一个基于 Android WebView 的极简浏览器应用。

## 功能特性
- 极简界面，无多余干扰。
- 支持网页浏览与搜索。
- 支持文件上传（图片/视频）。
- 支持应用快捷方式（设置、书签）。

## 更新日志

### v1.1.5 (VersionCode 17)
- **修复**: 解决了部分网页播放器（如全屏视频）无法正常播放或显示的问题。
  - 优化了 WebView 配置：
    - 启用了 `databaseEnabled` 以支持更多本地存储需求。
    - 更新 UserAgent 为 Android 14 / Chrome 120，提高现代网页兼容性。
    - 显式开启硬件加速。
  - 重构了全屏视频实现：
    - 使用独立的 `FrameLayout` 容器覆盖全屏，解决播放器 UI 被遮挡或布局错乱的问题。
    - 修复了退出全屏时的视图清理逻辑。

### v1.1.4 (VersionCode 16)
- **修复**: 解决了折叠屏手机（如荣耀 Magic V3）在展开/折叠屏幕时页面刷新或回退的问题。
  - 在 AndroidManifest.xml 的 `configChanges` 中补充了 `smallestScreenSize`，以适配屏幕尺寸的剧烈变化。

### v1.1.3 (VersionCode 15)
- **修复**: 解决了屏幕旋转导致页面刷新或回退的问题。
  - 通过在 AndroidManifest.xml 中添加 `configChanges` 配置，使 Activity 在屏幕旋转时不重新创建，从而保持 WebView 状态。

### v1.1.2 (VersionCode 14)
- **修复**: 恢复了 WebView 的生命周期管理（onResume, onPause, onDestroy），解决了页面跳转倒计时卡住或消失的问题。
- **新增**: 添加了页面加载进度条，现在加载网页时会在底部显示进度。

### v1.1.1 (VersionCode 13)
- **修复**: 解决了在 Yandex.com 搜索结果页面点击链接无效的问题。
  - 原因：WebView 开启了多窗口支持 (`setSupportMultipleWindows(true)`) 但未实现 `WebChromeClient.onCreateWindow`，导致 `target="_blank"` 的链接点击无响应。
  - 修复：禁用了多窗口支持，强制所有链接在当前窗口打开。

### v1.1.0
- **修复**: 解决了在努比亚（Nubia）手机上出现重复“设置”快捷方式的问题。
  - 移除了动态注册的快捷方式逻辑，仅保留 `shortcuts.xml` 中的静态定义，确保跨设备一致性。

## 开发说明
- **构建**: 使用 Gradle 构建。
- **最低 SDK**: 24
- **目标 SDK**: 34
