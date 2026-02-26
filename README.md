# 极简浏览器 (LiteBrowser)

## 项目简介
一个基于 Mozilla GeckoView 的极简浏览器应用。

## 功能特性
- 极简界面，无多余干扰。
- 采用 GeckoView 内核，提供更完整的 Web 标准支持。
- 支持网页浏览与搜索。
- 支持文件上传（图片/视频）。
- 支持应用快捷方式（设置、书签）。
- 支持书签管理与添加至桌面。
- 隐私保护：支持清除浏览数据。

## 更新日志

### v1.1.4 (VersionCode 16)
- **内核升级**: 将浏览器内核从 Android WebView 替换为 Mozilla GeckoView，以提供更一致的浏览体验和更好的兼容性。
- **功能重构**:
  - 重新实现了进度条、导航控制、文件上传等核心功能。
  - 优化了书签添加逻辑，自动获取当前网页标题。
  - 增加了清除浏览数据的功能。
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
- **最低 SDK**: 26
- **目标 SDK**: 36
- **内核**: GeckoView (Nightly build)
