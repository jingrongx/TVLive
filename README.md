# 新闻直播 (NewsLive)

Android TV / 手机直播应用，聚合央视多频道直播源，支持开机自启动、区县级定位天气、遥控器与触屏双模式操作。

## 主要功能

### 播放与无缝续播
- 央视多频道直播（CCTV1/2/3/4/5/7/13 等）
- 央视新闻直播（m-live.cctvnews.cctv.com）
- 双模式播放：
  - **网页模式**：WebView 加载直播页，嗅探视频流后自动交由 ExoPlayer 全屏播放（DRM 流留在 WebView 播放）
  - **播放器模式**：直接播放"直播源列表"配置的源（支持 m3u8/mp4/flv，含仅音频流）
- **真·无缝续播（key 透明重写）**：直播流 auth_key 过期时，在数据源层透明改用最新签名地址重新拉取播放列表——同一直播窗口连续，画面零中断（等效网页播放器原机制）
- 双播放器无缝切换兜底（预载新流 + 定位续播 + 宽高比就绪后原子换入）
- 换台遮罩：切换频道显示"频道名 + 加载中"，视频就绪才露出画面（原生电视换台体验）
- 稳定性：渲染进程崩溃自动重建、卡顿看门狗自续、多级恢复兜底，播放永不"一卡到底"
- 仅音频源自动清屏（不残留上一频道冻结画面）
- ABR 冷启动带宽初始值修复，消除"第一次启动播 5 秒卡一下"

### 顶部信息横幅
- 竖屏三行（农历行 / 日期行 / 定位天气行），横屏单行，字号自适应不换行
- 农历日期、时辰、节气
- 公历日期时间（每秒刷新）
- 定位 + 今/明/后三天天气（每 30 分钟刷新）

### 定位与天气（支持区县级 / 乡镇级）
- 自动定位：百度 qifu 区级 IP 定位 → pconline 兜底
- 坐标解析链：Open-Meteo（省市归属验证）→ OSM Photon（乡镇/街道级收录）
- **手动地区配置**：配置页输入地区（如"江西省抚州市临川区"，乡镇名如"上顿渡镇"也可），点"查询验证"实时试查天气，✅ 查得到才生效；配置后不再自动定位
- 手动地区支持省+市+区/县/乡镇多种写法，保存前自动验证，杜绝假地区

### 交互
- TV：遥控器上下键换台、菜单键打开配置、开机自启动、横屏全屏
- 手机：
  - 点屏幕显示/隐藏控制面板
  - 上/下滑切换频道
  - ⇄ 按钮切换 网页/播放器 模式
  - 双击返回键退出
  - 控制按钮带汉字说明
- 方向锁定/解锁按钮（锁定立即生效）、旋转按钮手动横竖屏

### 配置管理（浏览器打开 App 显示的配置地址）
- 网页频道列表 / 直播源列表（按住 ☰ 手柄拖拽排序）
- 顶部横幅显隐 / 字号 / 高度
- 缓冲参数、手动地区设置（带实时查询验证）
- 远程配置 URL + 启动自动更新

## 配置文件格式

本地配置页与远程配置（JSON）字段一致：

```json
{
  "sources": [
    { "name": "CCTV13新闻FM（仅音频）", "url": "https://....m3u8" }
  ],
  "websites": [
    { "name": "央视新闻直播", "url": "https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=16265686808730585228", "enabled": true }
  ],
  "useWebMode": true,
  "playerModeEnabled": true,
  "bufferMin": 10000,
  "bufferMax": 60000,
  "bannerVisible": true,
  "bannerFontSize": 13,
  "bannerHeight": 28,
  "manualLocation": "江西省抚州市临川区",
  "remoteUrl": "https://gitee.com/xujingrong/tv-live-config/raw/master/tv-live-source.json",
  "autoUpdate": true
}
```

| 字段 | 说明 |
|---|---|
| `sources` | 播放器模式的直播源列表（上下滑/▲▼ 切换） |
| `websites` | 网页模式的频道列表（`enabled` 控制启用，频道+/-键切换） |
| `useWebMode` | 启动默认模式（true=网页模式） |
| `playerModeEnabled` | 是否启用直播源列表 |
| `bufferMin` / `bufferMax` | 播放缓冲区间（毫秒），建议 10000/60000 |
| `bannerVisible` | 顶部信息横幅显隐 |
| `bannerFontSize` / `bannerHeight` | 横幅字号基准（9-18）与单行高度（20-60dp） |
| `manualLocation` | 手动地区（**留空=自动IP定位**；需能查到真实天气才生效，查不到会提示且不保存） |
| `remoteUrl` / `autoUpdate` | 远程配置地址与启动自动更新 |

> **v1.0.6 起**：移除 `playerVideoUrls` 字段（与 `sources` 功能重复，App 已不再读取，远程配置中保留也不会报错）。

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
├── MainActivity.java      # 主界面、播放逻辑、无缝续播、配置管理
├── BootReceiver.java      # 开机自启动接收器
├── LunarCalendar.java     # 农历日期计算
└── ShichenUtil.java       # 时辰计算工具

app/src/main/res/
├── layout/activity_main.xml        # 主布局（WebView + ExoPlayer + 多行横幅 + 换台遮罩）
└── drawable/ic_swap_mode.xml       # 切换模式图标

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
4. 手机浏览器打开控制面板显示的配置地址（如 `http://192.168.x.x:8765`）可修改频道/源/地区等设置

## 仓库

- **GitHub**: https://github.com/jingrongx/TVLive
