# 新闻直播 (NewsLive)

Android TV 直播应用，聚合央视多频道直播源，支持开机自启动、自动定位天气、遥控器操作。

## 主要功能

### 直播播放
- 央视多频道直播（CCTV1/2/3/4/5/7/13 等）
- 央视新闻直播（m-live.cctvnews.cctv.com）
- 音频流播放（CCTV13新闻FM、音乐FM）
- WebView 与 ExoPlayer 智能切换：
  - DRM 流（tv.cctv.com 的 cdrm/kcdnvip）走 WebView 全屏播放
  - 普通流走 ExoPlayer 全屏播放
- 视频卡顿检测：3 秒检查一次，卡顿超过 15 秒自动刷新页面
- 后台超过 30 秒恢复时自动刷新过期直播流

### 顶部信息横幅
- 农历日期（如「丙午年 六月廿六」）
- 时辰名称及几刻（如「未正二刻」）
- 节气提示（如「距处暑 15天」）
- 公历日期时间（每秒刷新）
- 当前位置（公网IP定位）
- 天气状况（今天/明天/后天，每30分钟刷新）

### 定位与天气
- 定位链路：pconline（中文名）→ ip-api（坐标）→ Open-Meteo（天气）
- 定位失败时明确提示原因，不显示默认位置
- 总耗时控制在约 8 秒

### TV 交互
- 遥控器上下键切换频道
- 遥控器菜单键打开设置
- 开机自启动
- 横屏全屏显示

### 配置管理
- 频道启用/停用可配置
- 顶部横幅字号可配置
- 直播源列表可配置
- 配置自动更新（可开关）
- 配置版本号机制，启动时自动刷新

## 技术栈

- **语言**: Java
- **最低 SDK**: 21 (Android 5.0)
- **目标 SDK**: 28 (Android 9.0)
- **播放器**: AndroidX Media3 / ExoPlayer 1.2.1
- **构建工具**: Gradle 8.14.3
- **签名**: release keystore 签名

## 项目结构

```
app/src/main/java/com/newslive/app/
├── MainActivity.java      # 主界面、播放逻辑、配置管理
├── BootReceiver.java      # 开机自启动接收器
├── LunarCalendar.java     # 农历日期计算
└── ShichenUtil.java       # 时辰计算工具

app/src/main/res/layout/
└── activity_main.xml      # 主布局（WebView + ExoPlayer + 信息横幅）

version.properties          # 版本号配置（CI 自动递增）
```

## 构建

### 本地构建

```bash
./gradlew assembleDebug      # Debug 版本
./gradlew assembleRelease    # Release 签名版本
```

### CI 自动构建发版

项目配置了 GitHub Action（`.github/workflows/build-release.yml`）：
- **触发条件**: push 到 main 分支
- **自动流程**: 打包 → 签名 → 发版 → 版本号递增
- **APK 命名**: `newslive-v<版本号>.apk`
- **Release Notes**: 自动使用 commit 内容生成
- **版本递增**: 每次发版后 VERSION_CODE +1，VERSION_NAME patch +1

下载地址：[Releases 页面](https://github.com/jingrongx/TVLive/releases)

## 安装

1. 从 [Releases](https://github.com/jingrongx/TVLive/releases) 下载最新 APK
2. 在 Android TV/手机上安装（需允许"安装未知来源应用"）
3. 启动应用，首次使用可设置为默认桌面/开机自启动

## 仓库

- **GitHub**: https://github.com/jingrongx/TVLive
