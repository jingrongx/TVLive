# 新闻直播 (NewsLive)

Android TV / 手机直播应用 + Windows 桌面源筛选工具。App 聚合央视多频道直播源，支持开机自启动、区县级定位天气、遥控器与触屏双模式操作；**内置直播源自动优选引擎**，配合桌面端工具与 Gitee Release 自动发版，实现"订阅 → 验证 → 测速 → 优选 → 下发"全自动源维护。

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
- TV：遥控器上下键换台、**OK键呼出节目单**（混合模式：直播频道按分组+测速展示，**网页频道直接混在节目单里点击即看**，无需切模式）、面板打开时 OK=点击焦点按钮、菜单键控制面板、开机自启动、横屏全屏
- **退出记忆**：重启 App 自动回到上次播放的台（优选/远程配置更新后也会保持当前频道）
- 手机：
  - 点屏幕显示/隐藏控制面板（含 📋 节目单 / ⚙ 设置 / ✕ 退出）
  - 上/下滑切换频道
  - 双击返回键退出
  - 控制按钮带汉字说明
- 方向锁定/解锁按钮（锁定立即生效）、旋转按钮手动横竖屏

### 播放体验
- **卡顿自动换源**：单次缓冲 8 秒即重连、重连 2 次无效自动切下一条线路；**频繁短卡（30 秒内 6 次进出缓冲）也会直接换线**——测速高分但实际不稳的源会被自动跳过
- **频道节目单**：按分组浏览全部频道，速度分级着色（⚡绿≥500/黄200-499/橙<200/灰无数据），点击秒切
- **App 内设置**：控制面板 ⚙ 按钮直接打开本机配置页（内置 WebView 走 127.0.0.1 回环，手机上无需外部浏览器）
- 退出按钮：控制面板 ✕ 一键退出（沉浸式下找不到返回键的兜底）

### 配置管理（浏览器打开 App 显示的配置地址）
- 网页频道列表 / 直播源列表（**自动分页 + 搜索过滤**，几百个源也不卡页面）
- 顶部横幅显隐 / 字号 / 高度
- 缓冲参数、手动地区设置（带实时查询验证）
- 远程配置 URL + 启动自动更新
- 手机上打不开配置页时：直接在 App 控制面板点 ⚙ 设置（内置 WebView 走 127.0.0.1 回环）

### 直播源自动优选（App 内置检测引擎）
- 配置页（浏览器 或 App 内 ⚙ 设置）「🔍 直播源自动优选」区块：填订阅 URL（TVBox txt / m3u 均可，已预填两个持续维护的聚合源）
- 一键启动：拉取订阅 → 逐条连通验证（m3u8 追到分片）→ 分片级测速（HLS 按分片大小÷耗时计速）→ 频道名归一化合并 → 按分组排序 → **每频道保留最快 1 条线路** → 自动替换直播源列表并开始播放
- 优选结果自带 **group（分组）与 speed（测速）** 字段，App 节目单按分组展示并标注速度
- 参数可调：淘汰速度线（默认 250 KB/s；**高清 1080p 参考 ≥250，⚡≥500 最稳**，节目单按速度着色可辨识）、并发数（默认 15）；支持中途停止（App 内 ⏹ 停止按钮 / 重新进配置页再点即可）
- 建议 WiFi 环境运行，约 1~3 分钟；进度实时显示在页面

### 远程下发（Gitee Release 直链，无下载限制）
- App 内置远程配置机制：配置页填「远程配置 URL」+ 勾选「启动时自动更新」→ 每次启动自动拉取最新优选结果
- 默认地址（Gitee Release 附件直链，**无 raw 下载限制**，已实测匿名 200）：
  `https://gitee.com/xujingrong/tv-live-config/releases/download/live/tv_live_config.json`
- 固定 tag `live` 方案：桌面工具每次发布 = 删除旧 release → 同 tag 重建 → 上传新附件，**URL 永不变，无版本号匹配问题**
- 旧版 gitee raw 地址（`.../raw/master/tv-live-source.json`）已内置迁移逻辑，升级 App 后自动切到新地址
- 也支持局域网下发：任意静态 HTTP 服务指向该文件，App 远程 URL 填 `http://<PC_IP>:<端口>/tv_live_config.json`（App 已允许明文 HTTP）

## IPTV 源筛选工具（Windows 桌面版）

仓库 `iptv_tool/` 目录，单文件免安装 exe（PyInstaller 打包），双击即用。

### 功能
- **多订阅管理**：每行一个 URL，同时支持 TVBox txt 与 m3u 格式，预填两个持续维护的聚合源
- **全自动筛选**：拉取订阅 → 逐条连通验证（m3u8 追到分片级）→ 分片级测速（HLS 按「分片大小÷下载耗时」计速，规避分片间隙假低速）→ 频道名归一化合并（CCTV-1综合 → CCTV1）→ 自动 12 分组（央视/卫视/电影/体育/少儿/纪录/音乐/春晚/港澳台及国际/地方/其他/海外）→ 每频道保留最快 N 条线路
- **Gitee 自动发版**：填入 Gitee 私人令牌后，筛选完成自动上传 `tv_live_config.json` 到 release 附件（固定 tag 覆盖更新），也可单独点「立即发布」
- **产物**：`final_live.txt`（精选订阅）、`final_live_full.txt`（完整订阅）、`tv_live_config.json`（App 配置格式）、`report.md`（全量明细+每频道线路速度）
- 参数可调：并发数、超时、淘汰速度线（默认 80 KB/s）、每频道保留线路数；支持代理与中途停止；设置持久化（config.json）

### 使用流程
1. 双击 exe（或从 Releases 下载）→ 点「开始筛选」（约 1 分钟）
2. 完成后自动发布到 Gitee Release（已配置 token 时），App 重启即拿到最新源
3. 结果浏览页可按分组查看频道与线路速度，明细见 `report.md`

### 本地构建（Python 3.10+）
```bash
cd iptv_tool
pip install aiohttp pyinstaller pillow
pyinstaller --onefile --noconsole --clean --name IPTVSourceTool --icon app.ico main.py
# 产物: dist/IPTVSourceTool.exe
```

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
  "remoteUrl": "https://gitee.com/xujingrong/tv-live-config/releases/download/live/tv_live_config.json",
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
>
> **远程配置按字段合并**：JSON 里只放 `sources` 不会覆盖其他设置——`tv_live_config.json` 即只含 `sources` + `playerModeEnabled`。

## 技术栈

- **App**: Java，minSdk 21 (Android 5.0)，targetSdk 28，AndroidX Media3 / ExoPlayer 1.2.1，Gradle 8.14.3
- **桌面工具**: Python 3（tkinter + aiohttp），PyInstaller 单文件打包
- **签名**: release keystore 签名

## 项目结构

```
app/src/main/java/com/newslive/app/
├── MainActivity.java      # 主界面、播放逻辑、无缝续播、配置管理、HTTP 配置面板
├── SourceScanner.java     # 直播源自动优选引擎（下载订阅/连通验证/分片测速/归一化分类）
├── BootReceiver.java      # 开机自启动接收器
├── LunarCalendar.java     # 农历日期计算
├── ShichenUtil.java       # 时辰计算工具
└── LogUtil.java           # 日志工具

iptv_tool/                          # Windows 桌面 IPTV 源筛选工具
├── main.py                         # 全部逻辑（GUI + 检测引擎 + CLI 模式 + Gitee 发版）
├── app.ico                         # 应用图标
└── dist/IPTV源筛选工具.exe          # 构建产物（CI 同步发布到 Releases）

.github/workflows/build-release.yml # CI：APK + exe 一起发版
version.properties                  # 版本号配置（CI 自动递增）
```

## 构建

### 本地构建

```bash
./gradlew assembleDebug      # Debug 版本
./gradlew assembleRelease    # Release 签名版本
```

### CI 自动构建发版

项目配置了 GitHub Action（`.github/workflows/build-release.yml`），**push 到 main 分支自动把 APK 和桌面工具一起发版**：

| Job | 环境 | 产物 |
|---|---|---|
| `build-release` | ubuntu-latest | `newslive-v<版本号>.apk`（签名 release） |
| `build-tool` | windows-latest | `IPTVSourceTool.exe`（PyInstaller 单文件） |

- **触发条件**: push 到 main 分支（也支持手动 workflow_dispatch）
- **自动流程**: APK 打包签名 → exe 打包 → 两个产物上传到**同一个 GitHub Release** → 版本号递增（VERSION_CODE +1，patch +1）并提交回仓库
- **Release Notes**: 自动使用本次 push 的 commit 内容生成，并附 APK/工具下载说明
- **工具图标**: `iptv_tool/app.ico` 随仓库提交，CI 打包时直接使用

下载地址：[Releases 页面](https://github.com/jingrongx/TVLive/releases)

## 安装

**App（Android TV / 手机）**
1. 从 [Releases](https://github.com/jingrongx/TVLive/releases) 下载最新 `newslive-v*.apk`
2. 在 Android TV/手机上安装（需允许"安装未知来源应用"）
3. 启动应用，首次使用可设置为默认桌面/开机自启动
4. 手机浏览器打开控制面板显示的配置地址（如 `http://192.168.x.x:8765`）可修改频道/源/地区等设置
5. 配置页「直播源自动优选」可一键优选；或在「远程配置」填 Gitee Release 直链开启自动更新

**桌面工具（Windows）**
- 从 Releases 下载 `IPTVSourceTool.exe`，双击即用（免安装）；填入 Gitee 令牌后筛选完成自动发版

## 仓库

- **GitHub**: https://github.com/jingrongx/TVLive
