# SimpMusic 架构说明

> 基于 v1.1.1（Minecraft 1.20.4）；原名 MTRMusic，源码基线由 v1.0.15 反编译恢复后持续迭代

## 一、总体架构

SimpMusic 是 **C/S 双端模组**：服务端负责网易云 API 网关、点歌队列与全服广播；客户端负责音频下载、解码与本地播放（不占服务器带宽）。

```
┌───────────── 服务端 (SimpMusic-Server) ─────────────┐
│ /music 命令树 · 队列状态机 · 网易云 API · BossBar  │
│            歌词同步 · 更新检测 · Cookie            │
└──────────────────────┬─────────────────────────────┘
                       │ Fabric 网络包 (play_song / stop_song /
                       │ sync_queue / search_result)
┌──────────────────────▼─────────────────────────────┐
│ 客户端 (SimpMusic-Client)                           │
│ /simpmusic 命令 · G 键 GUI · 下载/解码(Mp3spi)     │
│ 独立 OpenAL 播放引擎 · SourceDataLine fallback     │
└─────────────────────────────────────────────────────┘
```

- 服务端**零第三方运行时依赖**：HTTP 用 JDK `java.net.http`，JSON 用 Gson（由 Fabric API 提供）
- 客户端**内嵌解码库**：mp3spi + jlayer + tritonus-share（class 合并进 jar，编译期 compileOnly）

## 二、服务端模块（com.simpmusic.server）

| 类 | 职责 |
|----|------|
| `SimpMusicServer` | 入口：注册命令/网络包/生命周期事件；MTR 检测与 3.2.2 兼容补丁（tickDelay=2、MASTER 音效类） |
| `MusicCommand` | `/music` 命令树：search / request / queue / list / skip / stop / remove / update / api / reload / status / help（skip/stop/remove/update/api/reload 需 OP 2 级） |
| `MusicQueueManager` | **核心状态机**：`ConcurrentLinkedQueue` 队列、当前播放项、每玩家冷却、BossBar 进度+逐行歌词、时长+3s 超时自动切歌 |
| `NeteaseApiClient` | 网易云 API 网关：search / getSongUrl / getSongInfo / getLyric；双模式（官方 music.163.com ↔ 自建 NeteaseCloudMusicApi） |
| `ModConfig` | `SimpMusic/config-server.json`：camelCase/snake_case 兼容读取、损坏自动备份 .bak、修改即存 |
| `CookieManager` | `SimpMusic/cookie.json`：网易云 Cookie（JSON 或纯文本），填后可播 VIP |
| `UpdateChecker` | GitHub Release 检测；地址回退链 `api.github.com` → ghfast/gh-proxy/ghproxy.net；360 分钟默认间隔 |
| `TextUtil` | `sanitize()` 过滤 § 颜色码/控制字符/零宽字符，限长 256 |
| `network/*` | 全部为 record：`PlaySongPacket` / `StopSongPacket` / `SyncQueuePacket`（S→C 单向）；`RequestSearchPacket` / `SearchResultPacket`（预留） |

### 服务端关键状态机（MusicQueueManager）
- 入队校验链：冷却 → 队列满（50）→ 重复（默认禁）→ 每玩家上限（5）→ 标题合法性 → URL 有效性
- `playNext()`：弹队列 → 广播 PlaySongPacket → BossBar 建立 → 异步加载 LRC 歌词
- tick 驱动（`END_SERVER_TICK`）：每 5 tick 更新 BossBar 进度 + 超时检测；MTR 模式降到每 2 tick
- 玩家中途 JOIN → BossBar 自动补挂

### 网络包协议（Fabric Custom Payload，id 前缀 `simpmusic:`）

| 包 | 方向 | 内容 |
|----|------|------|
| `play_song` | S→C | songId, title, artist, url(含 `simpmusic_volume=`), coverUrl |
| `stop_song` | S→C | 空 |
| `sync_queue` | S→C | 当前曲目 + 队列（含点歌人名） |
| `search_result` | S→C | 搜索结果（GUI 用） |

## 三、客户端模块（com.simpmusic.client）

| 类 | 职责 |
|----|------|
| `SimpMusicClient` | 入口：G 键绑定（`END_CLIENT_TICK` 检测）、MTR 检测、初始化音频/更新检查、进入世界 90s 后检查更新 |
| `MusicAudioStream` | **v1.0.15 播放引擎核心**（独立 OpenAL source 自管流式，见下） |
| `StreamingAudioStream` | 实现 MC `AudioStream` 接口的 PCM 管道：`BlockingQueue<byte[]>` + `allocateDirect`，2s 无数据/EOF 返回 null |
| `ClientNetworkHandler` | 接收 play_song / stop_song / sync_queue / search_result |
| `ClientConfig` | `SimpMusic/config-client.json`：音量倍率、码率、缓冲、音效类、更新配置 |
| `ClientMusicCommand` | `/simpmusic` 命令树：gui / settings / stop / search / update / api / reload / status |
| `MusicScreen` | 点歌 GUI：搜索框 + 播放/停止/跳过/更新按钮 + 结果列表（聊天栏点歌为主路径） |
| `MusicSettingsScreen` | 设置 GUI：音量、码率、API URL、Token、更新间隔、MTR 兼容开关 |
| `NeteaseApiClient` | 客户端版：search / getSongUrl / downloadAudio（与服务端独立副本） |
| `audio/MusicSoundInstance` | **历史遗留**：v1.0.9 前 MC SoundEngine 方案的残余类，当前播放链路不再使用 |
| `audio/SoundRegistry` | 同上，仅注册占位 SoundEvent |
| `audio/Mp3AudioFileReader` / `FlacAudioFileReader` | 自定义 SPI 读取器（简单封装） |

### v1.0.15 播放引擎（MusicAudioStream）—— 最关键模块

**设计**：完全脱离 MC 的 Source/Channel/SoundEngine，渲染线程 tick 直接驱动 LWJGL OpenAL。

```
后台解码线程                      渲染线程（每帧 tickPlayback）
┌─────────────────┐              ┌──────────────────────────────┐
│ 下载(30s超时)    │              │ alGetSourcei(BUFFERS_PROCESSED)│
│ 校验 isAudioData │              │ → unqueue + delete 已播 buffer │
│ mp3spi 显式解码  │ → push →     │ → getBuffer 补充至 3 个 buffer │
│ (Mpeg→PCM 16bit) │ StreamingAudio│ → alBufferData+queue          │
└─────────────────┘              │ → alSourcePlay（停止则续播）    │
                                  └──────────────────────────────┘
```

- **预缓冲 3 个**，每 buffer 0.25s（`BUFFER_SECONDS`），`alBufferQueue` 自管
- 音量：URL `simpmusic_volume=` 参数（偏移 **16**）+ 客户端倍率，clamp [0,2]；解析失败保底 1.0
- EOF 判定：`stream.getBuffer()` 返回 null（数据耗尽或 2s 超时）→ 队列空即 `Playback finished (all data consumed)` → 清理
- **fallback 链**：OpenAL 失败 → `SourceDataLine`（PC 用）；解码失败 → 重新拉取 URL 重试一次
- 容错：OpenAL context 重建（FCL 音频设备丢失）→ tick 异常捕获 → `cleanupDirect()` 安全清理

**历史修复要点（防止回归）**：
1. `allocateDirect`（v1.0.14 教训：heap buffer 会让 `alBufferData` 拿 NULL native 地址）
2. 显式 `new MpegAudioFileReader()` / `new MpegFormatConversionProvider()`（Fabric 独立 classloader 不扫 `META-INF/services`）
3. 每 tick 主动读流（v1.0.13 前无人 tick，buffer 队列恒空）

## 四、端到端点歌流程

```
玩家 /music search キーワード
  → 服务端 NeteaseApiClient.search()（官方 /api/search/get）
  → 聊天栏输出 [n] 歌名-艺人 点我播放（ClickEvent RUN_COMMAND）
玩家点击 → /music request <id> <标题>
  → 服务端 getSongUrl（官方 enhance/player/url + Cookie 放行 VIP）
  → getSongInfo（v3/song/detail）补艺人/时长 → addToQueue()
  → 队列空则 playNext() → 全服广播 play_song（url 追加 simpmusic_volume=服务端音量）
客户端收到 play_song → MusicAudioStream.play()
  → 下载 → isAudioData 校验 → mp3spi 解码 PCM → 独立 OpenAL 播放
  → actionbar 提示"正在播放"
同时服务端：BossBar 显示歌名+歌词进度，异步加载 LRC 实时滚动
播完（时长+3s）→ skipCurrent → playNext；队列空 → BossBar 隐藏
```

## 五、配置与文件

| 文件 | 位置 | 说明 |
|------|------|------|
| `config-server.json` | 游戏根目录 `SimpMusic/` | 服务端：API 地址、码率(320k)、BossBar、队列上限、音量、更新检测 |
| `config-client.json` | 同上 | 客户端：音量倍率、缓冲、音效类、更新 |
| `cookie.json` | 同上 | 网易云 Cookie（VIP 播放） |

## 六、与 MTR 兼容

- 启动扫描 `mtr` mod container；3.2.2-hotfix-1 命中后：队列 tick 2、音效类 MASTER、G 键走 `client.execute` 延迟打开 GUI
- 广播消息用 **actionbar**（`sendMessage(msg, true)`），规避 chat_heads 的聊天渲染 NPE（v1.0.6 修复）

## 七、构建注意

- 双独立 Gradle 工程（server/ client/），Loom 1.5.8 + Gradle 8.8，JDK 17 目标
- 仓库：fabricmc maven + libraries.minecraft.net + 腾讯云镜像（settings.gradle 的 `dependencyResolutionManagement` 不可移除）
- 客户端 jar 合并解码库时排除 `junit/**`；版本号统一改 `gradle.properties`
