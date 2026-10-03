# Changelog

本文件记录 SimpMusic（原名 MTRMusic）所有版本的更新与修复内容。

> 1.1.1 起项目更名为 **SimpMusic**；以下 1.1.0 及更早条目保留当时的 MTRMusic 名称与版本号，为历史记录。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

---

---

---

---

---

---

---

---

---

---

---

---

---

---

## [1.1.4] - 2026-10-03

### ✨ 新增 - 网易云 VIP Cookie 适配（浏览器导出格式）

- **支持直接识别浏览器扩展（Cookie-Editor / EditThisCookie）导出的 `163cookie.json`**
  —— 即形如 `[{domain, expirationDate, hostOnly, name, path, value, ...}, ...]` 的 JSON 数组，
  **原样放入 `SimpMusic/163cookie.json` 即可生效**，无需手动改写成 Cookie 字符串。
- **投放位置**（按优先级自动探测）：
  1. `SimpMusic/163cookie.json`（推荐，与下载文件名一致）
  2. `SimpMusic/cookie.json` —— 内容为上述数组，或含 `netease` / `music163` / `cookie` 字段
  3. `SimpMusic/cookie.json` 纯文本 `name=value; name=value` 形式
- **解析细节**：
  - 按 `domain` 过滤，仅保留 `163.com` 域下的条目；无 domain 时全部纳入
  - 强制校验 **`MUSIC_U`**（登录凭证）存在，缺失时明确告警而非静默失败
  - 自动过滤统计/广告类 Cookie（`Hm_*`、`_ga`、`HMACCOUNT`、`JSESSIONID-WYYY`、
    `NMTID`、`WEVNSSM`、`gdxidpyhxdE` 等），避免污染请求
  - 核心字段（`MUSIC_U`、`__csrf`、`__remember_me`、`MUSIC_A`）排序前置，便于排查
  - 值经过 `URLDecoder` 解码，兼容导出文件中的 `%5C` / `%2F` 转义
- **登录态校验**：启动后异步请求 `/api/nuser/account/get`，日志输出
  `网易云账号已登录：<昵称>（vipType=<n> · VIP 已生效，可播放 VIP 歌曲）`。

### ✨ 新增 - VIP 歌曲播放地址解析

- 服务端播放地址解析改为**携带 Cookie 直连**官方接口（此前不带 Cookie，VIP 歌曲必然取不到链接）：
  - **双 UA 策略**：移动端 UA 优先（VIP 放行率更高），失败自动回退 PC UA
  - **码率回退**：先按配置码率请求，取不到时自动回退标准 320k
  - **外链兜底**：仍失败才走 `song/media/outer/url`（仅对免费歌曲有效）
  - 成功时记录 `level` / `fee` / `size`，便于确认实际拿到的音质档位
- 搜索、歌曲详情、歌词接口全部改为携带 Cookie（统一 `officialRequest()` 构造器），
  VIP 曲库信息更完整。

### ✨ 新增 - `/music cookie` 命令

- 查看 Cookie 加载状态、来源文件、账号昵称与 VIP 类型；
- 未配置时直接给出 `SimpMusic/163cookie.json` 的放置指引。

### 🔧 修复 / 其他

- **客户端同步支持 Cookie**：新增客户端 `CookieManager`，官网直连模式下
  `getSongUrl` 改为带 Cookie 解析真实地址（移动端 UA 优先 + 320k 回退），
  失败按原逻辑回退 `outer/url`。
- 补齐**服务端 Gradle Wrapper**（`server/gradle/wrapper`、`gradlew`、`gradlew.bat`），
  此前服务端目录缺失导致无法独立构建。
- 版本号 1.1.4。

---

## [1.1.3] - 2026-09-12

### 🔴 修复 - 重连后播放几秒即停止（关键）

- **根因**：v1.1.2 的"接续播放"定位（seek）在**喂数据线程**里执行，需先解码跳过已播部分；
  而渲染线程每帧调用的 `getBuffer()` 有 2 秒等待窗口，等待超时返回 `null` 后被**误判为"数据耗尽"**
  （`streamEofConsumed = true`）→ 播放被清理停止。
- **修复**：
  1. **定位提前到后台解码阶段**（`supplyAsync` 内完成 seek），播放线程启动时数据已就绪；
  2. **EOF 判定改为依据数据源真实结束标志**（新增 `StreamingAudioStream.isEof()`），
     数据暂时未就绪时保持等待，不再误停；
  3. `getBuffer()` 等待窗口 2000ms → **100ms**，避免渲染线程被阻塞造成卡顿。

### 🔴 修复 - 重连后歌词无法对齐

- **根因**：重连时服务端已存在全局时间轴，客户端的校准请求被忽略；客户端 seek 后的实际播放位置
  与服务端计时基准存在偏差。
- **修复**：`play_started` 包携带**客户端实际播放位置**；服务端以此为基准对齐时间轴
  （`currentStartTimeMs = now - offset`）。当场上只有一名玩家（重连场景，不影响他人）时允许再次校准，
  保证歌词与该玩家实际听到的位置严格同步。

### 🔧 其他

- 修复构建脚本缺陷：`processResources` 未声明 `version` 输入，导致版本号变化时
  `fabric.mod.json` 不重新展开（jar 内版本号滞留旧值）。
- 版本号 1.1.3。

---

## [1.1.2] - 2026-09-12

### 🔴 修复 - 退出服务器后仍在播放音乐

- **根因**：客户端只监听了进服事件，未监听断开连接——退出服务器回到主菜单后，本地 OpenAL 播放线程仍在跑。
- **修复**：注册 `ClientPlayConnectionEvents.DISCONNECT`，断开瞬间调用 `MusicAudioStream.stop()`
  并清理播放状态（日志 `Disconnected from server — playback stopped`）。

### 🔴 修复 - 重新加入服务器时从头重新播放

- **根因**：v1.0.16 起服务端在玩家进服时补发 `play_song` 让其听到当前歌曲，但包内**不含播放进度**，
  重连玩家会从第 0 秒重新播放，与全服进度脱节。
- **修复**：`PlaySongPacket` 新增 **起始偏移字段**（秒）：
  - 服务端 `getCurrentPlaybackOffsetSeconds()` 按校准后的播放起点计算当前进度
    （接近歌曲末尾时回退 5 秒，避免接续到即将切歌的位置）；
  - 客户端解码后**跳过对应 PCM 数据**（OpenAL 与 SourceDataLine 两条链路均支持），
    实现重连/中途加入的**无缝接续**，不再从头播放。

### 🔧 其他

- 版本号 1.1.2；`Identifier.of()` 统一命名空间写法。

---

## [1.1.1] - 2026-09-10

### 🏷️ 变更 - 模组更名为 SimpMusic

- 显示名 **MTRMusic → SimpMusic**；mod id `mtrmusic` → `simpmusic`；
  Java 包 `com.mtrmusic` → `com.simpmusic`（含类名 `MTRMusicServer`/`MTRMusicClient`
  → `SimpMusicServer`/`SimpMusicClient`）。
- 命名空间同步切换：网络包 `simpmusic:play_song` 等、音效 `simpmusic:music_stream`、
  语言键 `key.simpmusic.open_menu`、资源目录 `assets/simpmusic/`。
- 日志前缀、命令反馈、GUI 标题统一为 SimpMusic。
- **配置目录 `MTRMusic/` → `SimpMusic/`，旧目录首次启动自动迁移**（含
  config-server.json / config-client.json / cookie.json），无需手动搬移。

### 🔓 变更 - 取消 MTR 依赖声明

- 移除 `fabric.mod.json` 中的 `recommends.mtr`，SimpMusic **不再声明任何 MTR 依赖**，
  可在纯原版/其他整合包环境中独立运行。
- 代码内的 MTR 检测保留为**可选增强**：检测到 MTR 3.2.2 时自动应用兼容补丁
  （队列 tick 2、音效类 MASTER、GUI 延迟打开）；未检测到时零开销独立运行。

### 🔧 其他

- 版本号 1.1.1；产物名 `SimpMusic-Server-1.1.1.jar` / `SimpMusic-Client-1.1.1.jar`。

---

## [1.1.0] - 2026-09-05

### 🚀 升级 - 兼容 Minecraft 1.20.4（主版本）

- **Minecraft 1.19.2 → 1.20.4**，目标平台切换：
  - Yarn 映射 `1.20.4+build.3`、Fabric Loader `0.15.11`、Fabric API `0.97.3+1.20.4`
  - 构建链：Loom 1.6.12 + Gradle 8.8
- **GUI 迁移到 1.20.4 widget API**：`MusicScreen`/`MusicSettingsScreen` 重写为
  `ButtonWidget.builder()`、`DrawContext` 渲染（`renderBackground(DrawContext,...)`、
  `drawCenteredTextWithShadow`）、命令发送改走 `networkHandler.sendCommand()`。
- **注册表 API 迁移**：`SoundRegistry` 改用 `net.minecraft.registry.Registries` +
  `SoundEvent.of()`（1.20 移除 `util.registry`）。
- `fabric.mod.json` 依赖：`minecraft 1.20.4`、`fabricloader >=0.15.0`、
  `fabric-api >=0.97.3+1.20.4`、`mtr >=1.20.4-3.2.2-hotfix-1`。

> ⚠️ 服务端与客户端需同时更新，且须在 **1.20.4** 服务器/客户端上使用。

### 🔴 修复 - 乐曲时长统一显示 4:00

- **根因**：网易云详情/搜索接口的时长字段是 **`dt`**（毫秒），代码只解析
  `duration` 字段 → 时长恒为 0 → 点歌队列兜底为 240 秒（4:00）。
- **修复**：`parseSongInfo`（服务端/客户端）改为兼容 `dt` 与 `duration` 双字段，
  真实时长现在能正确写入队列；BossBar 进度条与超时自动切歌按真实时长工作。

### ➕ 新增 - /skip 玩家投票跳过

- 所有玩家均可使用 **`/skip`**；在线玩家**半数（含一半，向上取整）**使用后
  直接跳过当前歌曲播放下一首。
- 每人对当前曲目仅计一票；换歌/停止后自动清零；投票进度通过 actionbar
  实时广播（如 `Steve 发起了跳过 (1/2) - 输入 /skip 加入`）。
- 原 `/music skip` 保留（OP 强制跳过，不受投票限制）。

---

## [1.0.16] - 2026-08-14

### 🔴 修复 - 玩家中途加入听不到音乐（关键）

- **根因**：`ServerPlayConnectionEvents.JOIN` 处理器只补挂 BossBar，未补发当前播放包——新玩家加入时若已有歌曲在播，收不到 `play_song`，自然无声。
- **修复**：JOIN 时若有 `currentPlaying`，向该玩家**补发 `play_song` 包**（含带音量参数的完整 URL），新玩家立即开始下载播放；日志输出 `Re-sent current song to joining player`。

### 🔴 修复 - 歌词/进度条与音乐不同步（计时校准）

- **根因**（日志确认）：
  ```
  [19:46:52] Starting playback: きゅびびびびずむ by 超てんちゃん
  [19:46:59] Now playing via direct OpenAL: きゅびびびびずむ (48000.0 Hz, 2ch, vol=1.0)   ← 7 秒后才真正出声
  ```
  服务端 BossBar 计时从**广播时刻**起算，但客户端下载+解码（FCL 上尤甚）存在数秒延迟——歌词与进度条领先音乐，超时切歌也偏早。
- **修复**：新增 C→S 包 `play_started`：
  - 客户端在**实际开始播放**（OpenAL source 建立或 SourceDataLine fallback 成功）后发送。
  - 服务端对当前曲目**首次**收到时，将 `currentStartTimeMs` 重置为此刻，歌词/进度/超时检测全部以真实播放起点为准（日志 `Playback timing calibrated by <玩家> (<延迟> ms after broadcast)`）。

### 🔧 其他

- 版本号全项目统一升级为 **1.0.16**（gradle.properties、Java 常量、fabric.mod.json 自动注入、User-Agent、/music status）。
- `fabric.mod.json` 版本字段改为 `${version}` 占位符，由 `processResources` 注入，后续改版无需手改。

---

## [1.0.15] - 2026-08-14

### 🔴 修复 - 播放启动即停止（独立 OpenAL 播放引擎 · 关键）

- **根因**（日志确认）：
  ```
  [18:22:31] Now playing via OpenAL: きゅびびびびずむ (48000.0 Hz, 2ch, vol=1.0)
  [18:22:31] Playback finished (source stopped)   ← 同一秒内立即结束
  ```
  反编译 MC `Source` 类确认死锁机制：
  ```
  Source.tick() → removeProcessedBuffers() → read(count)
  read(): for (i = 0; i < count; i++) { stream.getBuffer(...) ... }
  ```
  `read()` 只在 **已播放完的 buffer 数量 > 0** 时才读流数据。绕过 Channel 的 source 首次 play 时没有任何 buffer → `removeProcessedBuffers()` 永远返回 0 → **永远不读数据** → source 无 buffer → 立即 `AL_STOPPED`。
- **修复**：v1.0.15 完全**脱离 MC 的 Source/Channel 机制**，改用**独立 OpenAL source 自管流式播放**：
  - 渲染线程 tick 直接调用 LWJGL AL 函数：`alGenSources` → `alGenBuffers` + `alBufferData` + `alSourceQueueBuffers`（预缓冲 3 个）→ `alSourcePlay`。
  - 播放完的 buffer 用 `alSourceUnqueueBuffers` 卸载释放。
  - 数据耗尽自动结束；OpenAL context 重建（FCL 音频设备丢失）时自动清理容错。
- 渲染线程可调 AL 函数已由日志验证（此前 `SoundEngine.createSource` 成功）。

---


## [1.0.14] - 2026-08-14

### 🔴 修复 - 播放启动后立即结束（direct buffer 回归 · 关键）

- **根因**（日志确认）：
  ```
  [01:02:25] Now playing via OpenAL: きゅびびびびずむ (48000.0 Hz, 2ch, vol=1.0)  ✅
  [01:02:25] Playback finished (source stopped)   ← 同一秒内立即结束！
  ```
  v1.0.13 的 tick 消费生效了，但 **source 立即停止**：
  - v1.0.13 把 `StreamingAudioStream.getBuffer()` 的返回值从 `allocateDirect` 改成了 `allocate`（heap buffer）。
  - LWJGL 的 `AL10.alBufferData(buffer, format, data, freq)` 内部用 `memAddressSafe(data)` 取 **native 地址**，heap buffer 会返回 **NULL** → OpenAL 报 `AL_INVALID_VALUE` → buffer 未入队 → source 无数据 → 立即停止 → `Playback finished`。
- **修复**：改回 **`ByteBuffer.allocateDirect(size)`**（与 MC 内部 `OggAudioStream` 一致，FCL 的 direct buffer 警告不影响可用性——MC 游戏音效本身就用 direct buffer 正常工作）。

---


## [1.0.13] - 2026-08-13

### 🔴 修复 - 播放音乐无声（流式数据未消费 · 关键）

- **根因**（日志确认）：
  ```
  Now playing via OpenAL: きゅびびびびずむ (48000.0 Hz, 2ch, vol=1.0)   ← v1.0.12 音量已修复
  （播放后 16 秒）OpenAL initialized on device Oboe Default × 4        ← SoundEngine 重载
  ```
  v1.0.12 音量修复后日志显示播放已启动，但依然无声。原因是：
  1. **流式数据从未被消费**：我们绕过 MC 的 Channel 播放系统，直接用 `createSource` + `setStream(stream)`——`Source.setStream()` 只是挂上音频流引用，**实际数据读取由 `source.tick()` 完成**。SoundEngine 只为它自己管理的 Channel 内 source 调 tick，我们创建的 source 无人 tick → OpenAL buffer 队列永远为空 → 无声。
  2. **FCL 上 SoundEngine 会重建**（`OpenAL initialized on device Oboe Default` 反复出现），旧 source 失效。
- **修复**：
  1. **注册客户端 tick**（`ClientTickEvents.END_CLIENT_TICK`）：每帧调用 `currentSource.tick()` 消费 PCM 数据填充 OpenAL buffer 队列（与 SoundEngine 内部机制一致）。
  2. `StreamingAudioStream` 改用 **heap buffer**（FCL 日志明确提示 direct buffer 不可靠）。
  3. 播放结束（`source.isStopped()`）自动清理释放；SoundEngine 重载导致 source 失效时捕获异常并容错清理，不再崩溃。

---


## [1.0.12] - 2026-08-13

### 🔴 修复 - 播放音乐无声（音量解析 bug · 关键）

- **根因**（日志确认）：
  ```
  Now playing via OpenAL: ダミーロマンス (44100.0 Hz, 2ch, vol=0.0)
  ```
  播放链路其实已完全打通（v1.0.11 修复了 SoundEngine 反射），但**音量恒为 0**：
  - URL 中音量参数前缀 `"mtrmusic_volume="` 长度为 **16**，代码误用 `+ 17` 偏移。
  - 结果从 `1.0` 的 `0` 开始截取 → `volStr = ".0"` → `Float.parseFloat(".0") = 0.0` → `setVolume(0)` → **OpenAL 播放的是静音**。
- **修复**：
  - 偏移改为 `"mtrmusic_volume=".length()`（16）。
  - 解析失败或结果 ≤ 0 时**保底使用 1.0 音量**（双保险：解析处 + 播放处）。
- 沙箱实测：`+16` 正确解析 `1.0`，`+17` 解析出 `0.0`——bug 复现确认。

---


## [1.0.11] - 2026-08-13

### 🔴 修复 - 播放音乐无声（SoundEngine 反射修复）

- **根因**（日志确认）：
  ```
  Failed to acquire SoundEngine: soundSystem
  SoundEngine unavailable, use SourceDataLine fallback
  Couldn't load library jsound ... /data/data/com.tungsten.fcl/.../jre17/lib
  PCM fallback playback error: No line matching interface SourceDataLine ...
  ```
  1. **SoundEngine 反射失败**：Fabric 运行时类字段名是 **intermediary**（`field_xxx`），硬编码的 yarn 字段名（`"soundSystem"`/`"soundEngine"`）在**反射字符串中不会重映射** → `NoSuchFieldException` → 拿不到 SoundEngine。
  2. **FCL 无 Java Sound**：Android（FCL）上 `jsound` 库加载失败（`UnsatisfiedLinkError`），SourceDataLine fallback 必然失败。
- **修复**：`getSoundEngine()` 反射改用**按字段类型匹配**（`f.getType() == SoundSystem.class` / `SoundEngine.class`）——编译期类引用会被 Fabric 正确重映射，运行时精确命中，不再依赖字段名字符串。同时向上遍历父类兜底。
- 修复后走 **MC OpenAL**（FCL 的 Oboe 后端，日志确认 `OpenAL initialized on device Oboe Default`）即可正常出声。

---


## [1.0.10] - 2026-08-13

### 🔴 修复 - 播放音乐无声（根本修复）

- **根因**（日志确认 `Audio decode error: Stream of unsupported format`）：v1.0.8 把 mp3spi 打进模组 jar 的方式在**沙箱 classpath 验证通过**，但 **Fabric 运行时模组由独立 classloader 加载**，其 `META-INF/services` 文件不会被 Java Sound 的 `ServiceLoader` 扫描到——所以游戏内 `AudioSystem.getAudioInputStream()` 找不到 MP3 解码器。
- **修复**：解码链路改为**显式 new mp3spi 类**，完全绕开 SPI 扫描：
  - `new MpegAudioFileReader().getAudioInputStream(...)`（识别 MP3）
  - `new MpegFormatConversionProvider().getAudioInputStream(pcm, mpeg)`（转 PCM）
  - 失败时回退 `AudioSystem`（JDK 原生 WAV/AU/AIFF）
- 沙箱实测：真实 MP3 → 显式解码 PCM 完整可用（67ms/秒音频）。

### 📁 修复 - 服务端自动生成 cookie.json

- 首次启动若不存在 `MTRMusic/cookie.json`，**自动创建模板** `{"netease": ""}` 并提示填写。

### 🎤 修复 - 点歌后艺人显示 Unknown

- **根因**：官方 `GET /api/song/detail?ids=xxx` 接口已失效（实测返回 `{"code":400}`），导致 `getSongInfo` 拿不到艺人。
- **修复**：官方模式改用 `GET /api/v3/song/detail?c=[{"id":xxx}]`（URL 编码），实测正常返回 `ar`/`al` 字段。

### 🔄 修复 - 音频设备重建兼容

- `getSoundEngine()` 改为**每次播放实时反射获取**，不再缓存——FCL 上发生 `Audio device was lost!` 后 SoundEngine 会重建，旧缓存引用失效导致后续播放失败。

---


## [1.0.9] - 2026-08-13

### 🔴 修复 - 播放音乐仍然无声（FCL/Android）

- **根因**：v1.0.7/1.0.8 使用 `javax.sound.sampled`（SourceDataLine）播放——在 PC 上正常，但 **Android（FCL 启动器）的 Java Sound 没有音频输出设备**，`getSourceDataLine()` 抛 `LineUnavailableException`，导致无声。
- **修复**：播放引擎再次重构为**复用 MC 自带的 OpenAL 后端**：
  - 后台线程解码 PCM（mp3spi 内置）→ 通过自研 `StreamingAudioStream` 管道 → MC `SoundEngine` 流式 source 播放。
  - 这正是 **MC 唱片机**的播放机制：`createSource(RunMode.STREAMING)` + `setStream(AudioStream)`，由 SoundEngine tick 自动消费数据。
  - PC / Android（FCL）均可靠（MC 游戏音效本身就走 OpenAL）。
  - OpenAL 路径不可用时自动 fallback 到 SourceDataLine（PC 兼容）。

### 🍪 新增 - 网易云 Cookie 独立文件（仿 AllMusic）

- cookie 不再存于主配置，独立文件：**`MTRMusic/cookie.json`**。
- 支持两种格式：
  ```json
  { "netease": "MUSIC_U=xxx; NMTID=yyy; ..." }
  ```
  或直接粘贴 cookie 纯文本。
- 填入后即可通过官方 `enhance/player/url` 获取 **VIP 歌曲**真实播放地址。
- 旧配置中的 `netease_cookie` 字段首次加载时**自动迁移**到新文件。

### 📁 变更 - 所有配置集中到 MTRMusic 文件夹

| 文件 | 用途 |
|------|------|
| `MTRMusic/config-server.json` | 服务端配置 |
| `MTRMusic/config-client.json` | 客户端配置 |
| `MTRMusic/cookie.json` | 网易云 Cookie（可选） |

- 旧位置 `config/mtrmusic-*.json` 不再使用，请删除。

---


## [1.0.8] - 2026-08-13

### 🎧 内置音频解码器（用户零配置播放）

- **变更**：客户端 jar 已内置 **mp3spi + jlayer + tritonus-share** 三个解码器（class 合并 + `META-INF/services` SPI 保留）。
- **效果**：无需再手动把 `mp3spi-1.9.5.4.jar` 放入 `mods/`，放入客户端 jar 即可直接播放 MP3 歌曲。
- **验证**：沙箱实测——内嵌 `MpegAudioFileReader` 被 `ServiceLoader` 正常注册；真实 MP3（192kbps/44.1kHz）识别成功并转换为 PCM_SIGNED 16bit 立体声，1 秒音频解码耗时约 53ms（实时无压力）。
- OGG（vorbisspi）与 FLAC（jflac-codec）未内置，如需播放这两类格式仍需手动放入对应解码库。

### 🈯 日文字符显示说明

- 模组文本链路全链路 UTF-8 + 控制字符清理，**正常保留**日文假名、汉字、全角标点（`ばかばかまたあたしATM¥【グミーロマンス】` 这类歌名/歌词可完整显示）。
- 若在部分环境（如精简版 FCL 启动器）中日文字形显示为方块 **□**，属于 MC 字体系统缺少 `unicode` 字形资源（非模组问题），安装任意含日文字形的 unicode 字体资源包即可解决。

---


## [1.0.7] - 2026-08-13

### 🔴 修复 - 点歌播放没有声音

- **根因**：旧播放实现使用自定义 `SoundInstance`，但 `getSoundSet()`/`getSound()` 均返回 `null`——MC 1.19.2 的 `SoundEngine` 拿不到音效资源（WeightedSoundSet）会**静默丢弃**，因此永远无声。
- **修复**：播放引擎整体重写为 **Java Sound API + SourceDataLine**：
  - 后台线程解码音频（MP3/FLAC → PCM）并直接写入系统音频输出，完全绕开 MC SoundEngine / OpenAL。
  - 不依赖 MC 音效资源、不占用渲染线程，手机端（FCL）与 PC 均适用。
  - 音量通过 `MASTER_GAIN` 控制；停止播放立即中断输出。

### 🈯 修复 - 日文字符不兼容

- 新增 `TextUtil.sanitize()`（服务端/客户端各一）：过滤 MC 颜色码前缀 `§`、C0/C1 控制字符、零宽不可见字符（`​` 等），防止外部文本破坏聊天格式与命令。
- 歌名/艺人/歌词**全链路 UTF-8**，正常保留日文假名、汉字、全角标点等合法 Unicode 字符。

### ⚡ 优化 - 模组加载速度

- 客户端：更新检测从"启动后 60 秒"改为"**进入世界后 90 秒**"，彻底避开 Mojang 加载界面阶段。
- 服务端：队列 tick 任务降频（每 5 tick/0.25s 执行一次 BossBar 更新与切歌判断），降低服务器 tick 负载。
- 移除客户端 MTR 兼容补丁中对废弃 SoundInstance 的依赖（播放引擎已与 MC 音效系统完全隔离）。

### ➕ 新增 - /music list 指令

- 列出正在播放 + 队列全部歌曲：**序号、曲名-艺人、点歌人、时长**。
- 示例：
  ```
  §a▶ 正在播放: ザムザ - 25時、ナイトコードで。 [点歌: Steve] (4:12)
  §71. 夜に駆ける - YOASOBI [点歌: Alex] (3:48)
  ```

---


## [1.0.6] - 2026-08-13

### 🔴 修复 - 点歌后卡死并崩溃（chat_heads 模组兼容）

- **根因**：崩溃日志显示 `chat_heads` 模组的 mixin（`renderChatHeadBeforeName`）在渲染**无发送者（sender=null）的系统消息**时抛出 `NullPointerException`，导致游戏崩溃。触发消息是点歌后广播的"正在播放/点歌"系统消息。
- **修复**：点歌、播放、跳过、停止等**广播通知全部改用 actionbar**（屏幕上方短暂提示），不再写入聊天栏 → 完全绕开 chat_heads 的聊天渲染路径。
- 搜索结果、队列等**玩家主动查询的消息**保留在聊天栏（经实测不触发该问题，且需要点击交互）。

### 🔴 修复 - 点歌时客户端卡死

- **根因**：音频解码（MP3/FLAC → PCM，耗时操作）在 **Minecraft 渲染线程**中执行；手机端解码数 MB 音频时界面卡死。
- **修复**：解码移至**后台线程**，渲染线程只负责创建播放实例并播放，点歌不再卡顿。

### ⚡ 优化 - 启动加载

- 更新检测延迟从 10 秒改为 **60 秒**（进入游戏后再检查），减少启动阶段网络占用与等待感。
- 移除 `fabric.mod.json` 中无效的 `icon` 引用，消除 `Mod mtrmusic has a broken icon` 加载警告。

### ✨ 变更 - 搜索结果格式

- 搜索结果按需求改为：**`[序号] [曲名]-[作者/艺人] [点我播放]`**，"点我播放"带下划线且可点击（悬停提示歌曲名）。

---


## [1.0.5] - 2026-08-13

### 🐛 修复 - 点歌后"无法下载歌曲"

- **根因**：无 Cookie 时网易官方接口对 VIP 歌曲不返回真实播放地址（`outer/url` 直链会重定向到 404 页面），下载到的是 HTML 页面导致解码失败。
- **修复**：
  - 服务端播放地址获取增强：优先调用官方 `enhance/player/url` 接口获取真实 CDN 地址，拿不到时再回退直链。
  - 新增可选配置 **`netease_cookie`**（网易云登录 Cookie，填到 `config/mtrmusic-server.json`），填入后可正常播放 VIP 歌曲。
  - 客户端下载失败自动重试并重新获取播放地址；新增**非音频内容检测**（识别 VIP 404 HTML 页面），给出明确提示"无法下载音频（可能为 VIP 歌曲）"。

### ➕ 新增 - BossBar 歌词进度条（末影龙血条样式）

- 播放歌曲时，屏幕上方显示 **BossBar 进度条**（末影龙血条样式），进度随歌曲实时更新。
- 进度条上方文字**实时显示当前歌词**：支持 LRC 歌词，自动过滤"作词/作曲/编曲"等元数据行。
- 歌曲播完**自动切换下一首**（基于真实时长检测，此前为空占位导致从不自动切歌）。
- 玩家中途加入自动同步进度条；配置 `show_boss_bar` 可开关。

### 🔧 其他

- 版本号全项目统一为 `1.0.5`。
- 服务端配置新增：`netease_cookie`、`show_boss_bar`（默认 true）。

---


## [1.0.4] - 2026-08-12

### 🐛 修复 - 搜索音乐一直显示"未找到"

- **根因**：默认音乐 API（`api.imjad.cn`）为已停用多年的第三方代理，所有搜索/点歌请求全部失败。
- **修复**：默认音乐 API 改为 **网易官方接口**（`https://music.163.com`），国内直连可用；客户端/服务端搜索、播放直链、歌词均走官方接口路径。
- 歌曲解析兼容 `ar`/`al` 字段（NeteaseCloudMusicApi 新版返回格式），自定义 API 同样正常。

### ➕ 新增 - 可自定义音乐 API

- 服务端命令：`/music api <url>` 查看/设置音乐 API（兼容自建 NeteaseCloudMusicApi 实例）。
- 客户端命令：`/mtrmusic api <url>` 查看/设置音乐 API。
- 配置文件：服务端 `apiBaseUrl` / 客户端 `music_api_url`。
- `/music status` 与 `/mtrmusic status` 显示当前音乐 API 地址。

### 🛠️ 修复 - 配置文件无法覆盖

- 配置读取兼容 **camelCase 与 snake_case 两种字段命名**，升级后旧配置文件（1.0.x 任意版本）字段全部生效，不再被重置。
- 新增 `reload` 命令：服务端 `/music reload`、客户端 `/mtrmusic reload`，修改配置文件后无需重启即可生效。

### 🔄 修复 - 无法检查插件更新

- **根因**：`api.github.com` 在国内不可达，更新检测必然失败。
- **修复**：更新检测支持**多地址自动回退**：配置地址 → 官方 `api.github.com` → 国内代理（ghfast / gh-proxy / ghproxy.net）。
- 成功访问的地址自动记忆，后续检查优先使用；失败自动尝试下一候选。

---


## [1.0.3] - 2026-08-12

### 🔴 修复 - 客户端启动崩溃（致命 · 彻底修复）

- **根因**：客户端入口点类型与注册位置不匹配。
  - v1.0.1：`MTRMusicClient` 实现 `ClientModInitializer`，但注册到 `fabric.mod.json` 的 `"main"` 入口点 → `ClassCastException: cannot be cast to net.fabricmc.api.ModInitializer`
  - v1.0.2：改为实现 `ModInitializer` 并注册到 `"client"` 入口点 → **依然类型不匹配**（`"client"` 入口期望 `ClientModInitializer`）
- **修复**：
  - `MTRMusicClient.java` 正确实现 `ClientModInitializer`，`onInitializeClient()` 中保留 MTR 检测、`CLIENT_STARTED` 延迟初始化、网络处理器注册逻辑。
  - 客户端 `fabric.mod.json` 入口点仅保留 `"client": ["com.mtrmusic.client.MTRMusicClient"]`。
  - `ClientMusicCommand` 移出 `fabric.mod.json` 入口点（其并非入口接口实现类），改由主类在 `onInitializeClient()` 中显式调用 `ClientMusicCommand.register()` 注册。
- **影响**：v1.0.0 / v1.0.1 / v1.0.2 在 FCL（Android 启动器）上完全无法进入游戏的直接原因，v1.0.3 彻底修复。

### 🧹 变更

- 版本号全项目统一为 `1.0.3`（Java 常量、fabric.mod.json、build.gradle、gradle.properties、version.json、文档）。
- User-Agent 同步更新为 `MTRMusic/1.0.3`。


## [1.0.2] - 2025-08-12

### 🔴 修复 - 客户端启动崩溃（致命）

- **根因**：`MTRMusicClient` 实现了 `ClientModInitializer`，但被注册到 `fabric.mod.json` 的 `"main"` 入口点。Fabric Loader 对 `"main"` 入口期望 `ModInitializer` 类型，类型不匹配导致 `ClassCastException` 崩溃。
- **修复**：
  - `MTRMusicClient.java` 改为实现 `ModInitializer`，客户端专属逻辑延迟到 `CLIENT_STARTED` 事件执行。
  - 客户端 `fabric.mod.json` 入口点从 `"main"` 改为 `"client"`。
- **影响**：这是 v1.0.1 在 FCL（Android 启动器）上完全无法进入游戏的直接原因。

### 🗑️ 变更 - 更新检测方案精简

- **移除**：方案三（自建 `version.json` 托管）和混合模式。
- **保留**：仅 GitHub Release Tag 检测 + 完全关闭两种状态。
- **新增配置项**（服务端/客户端均支持）：
  - `update_api_url` — GitHub API 地址（默认 `https://api.github.com`），可自定义（如自建反代）。
  - `update_token` — GitHub Personal Access Token，填入后可绕过 60次/小时 的匿名限流。
  - `update_interval_minutes` — 检查间隔（默认 30 分钟）。
- **配置方式**（4 种，双端通用）：
  - 配置文件 `config/mtrmusic-server.json` / `config/mtrmusic-client.json`
  - 服务端命令 `/music update enable|disable|token <t>|url <url>`
  - 客户端命令 `/mtrmusic update enable|disable|token <t>|url <url>`
  - 客户端设置界面（G键 → 设置）

### 🛠️ 修复 - 配置文件无法正常覆盖

- **根因**：旧版配置加载时若字段缺失或类型不匹配，直接抛异常导致配置写回失败，下次启动重复出问题。
- **修复**：
  - 加载后自动比对默认值，缺失字段自动补全并写回。
  - 损坏配置自动备份为 `.bak` 后再覆盖生成新文件。
  - 旧版 `updateMethod` 字段自动迁移为 `updateCheckerEnabled`。
  - 所有 setter 调用后自动触发 `save()`，避免运行时修改丢失。

### 🐛 修复 - Android / FCL 兼容性问题

- **移除** `Desktop.getDesktop()` 调用（Android 环境不存在该类），改为 Android 剪贴板 API。
- 更新通知中的"点击下载"链接改用 `ClickEvent.OPEN_URL`，不再依赖桌面浏览器。
- User-Agent 统一为 `MTRMusic/1.0.2`，避免被 GitHub API 识别为异常客户端。

### 📝 文档拆分

- `README.md` → 仅保留：功能介绍、插件原理（架构图 + 流程图）、协议声明。
- `RELEASE.md` → 仅保留：当前版本号、简短更新教程、无哈希值的更新内容。
- `BUILD.md` → 🆕 完整构建说明（本地 Gradle / 一键脚本 / GitHub Actions）。
- `CHANGELOG.md` → 🆕 本文件，整合所有版本历史。

### 🔧 其他

- 版本号全项目统一为 `1.0.2`（Java 常量、fabric.mod.json、build.gradle、gradle.properties、version.json、文档）。
- `MusicAudioStream.java` 修复一处缺失逗号导致的编译错误。
- `MusicScreen.java` 修复 `Screen` 拼写错误。

---

## [1.0.1] - 2025-08-05

### 🔴 修复 - MTR 3.2.2-hotfix-1 兼容性崩溃

v1.0.0 在与 MTR（Minecraft Transit Railway）同服时，服务端和客户端均会崩溃。本次彻底修复。

| # | 崩溃根因 | 修复方案 |
|---|---------|---------|
| 1 | Fabric API 0.61.0 与 MTR 要求的 ≥0.76.0 冲突 | 升级到 `0.76.0+1.19.2` |
| 2 | `TickableSoundInstance` 与 MTR sound manager 竞争 | 添加 `tickEnabled` 开关，MTR 模式禁用 tick |
| 3 | MTR tick 优先级吞掉按键事件 | 改用 `END_CLIENT_TICK` 事件 |
| 4 | Sound Category `RECORDS` 与 MTR 车站音乐冲突 | MTR 模式自动切换到 `MASTER` |
| 5 | MTR screen 系统覆盖 GUI | 延迟到下一 tick 打开 + MTR 检测 |
| 6 | 音频缓冲区溢出 | MTR 模式自动降为 2048 |

**自动检测机制**：启动时自动扫描 MTR mod container，检测到即应用全部补丁，未检测到则零开销运行。

### ⬆️ 新增 - 更新检测系统（混合方案）

- **GitHub Release Tag 检测**：通过 GitHub API 拉取最新 Release，比较版本号。
- **自建 version.json 检测**：在仓库放置 `version.json`，按 MC 版本索引最新下载地址和 changelog。
- **混合模式**：GitHub 优先，限流时自动降级到 version.json。
- **触发时机**：客户端启动 10 秒后自动检测 / OP 登录通知 / 手动按钮 / 命令触发。
- **切换途径**：设置界面 / 客户端命令 / 服务端命令 / 配置文件。

### 🔧 变更

- Fabric API 依赖从 `0.61.0` 升级到 `0.76.0+1.19.2`。
- Fabric Loader 兼容范围调整。
- 新增 `UpdateChecker.java`（服务端 + 客户端）。
- 新增 `ClientConfig.java`（客户端配置管理）。
- 新增 `version.json`（GitHub Raw 托管）。
- 新增 `.github/workflows/build.yml`（GitHub Actions 自动构建 + Release）。
- 新增 `build-all.sh`（本地一键构建脚本）。

---

## [1.0.0] - 2025-07-28

### 🎉 首个正式版本

### ✨ 功能

- **全服点歌**：玩家搜索网易云曲目提交后，所有在线客户端同步播放。
- **搜索与点歌**：`/music search <关键词>` 搜索，`/music request <ID> <标题>` 点歌。
- **队列管理**：`/music queue` 查看队列，`/music skip` 跳过，`/music stop` 停止。
- **GUI 界面**：默认 G 键打开点歌面板，搜索结果支持聊天栏一键点歌。
- **网易云 API**：通过公开接口获取歌曲直链，默认使用 `api.imjad.cn`，支持自建 API。
- **服务端-客户端架构**：服务端调用 API + 维护队列 + 广播指令，客户端本地解码播放，不占服务器带宽。

### 🏗️ 技术架构

- **服务端模组**（`MTRMusic-Server`）：
  - 依赖 Fabric API `0.61.0+1.19.2`、Fabric Loader `0.14.9+`。
  - 内嵌 Gson 2.10.1（JSON 解析）、Apache HttpClient 4.5.13（HTTP 请求）。
  - 网络包：搜索请求/响应、点歌请求、队列同步、播放指令、停止指令。

- **客户端模组**（`MTRMusic-Client`）：
  - 依赖 Fabric API `0.61.0+1.19.2`、Fabric Loader `0.14.9+`。
  - 音频解码：内置 MP3 / FLAC 解码器，网络流直解。
  - 自定义 `SoundInstance` 接入 Minecraft 音效系统。
  - GUI 基于 SpruceUI 风格实现。

### 📦 构建与发布

- Gradle 7.5 + Fabric Loom 0.12 构建链。
- 服务端/客户端独立构建，产物分别为 `MTRMusic-Server-1.0.0.jar` 和 `MTRMusic-Client-1.0.0.jar`。
- 初始文档：README.md、RELEASE.md、LICENSE（MIT）、PROJECT_STRUCTURE.txt。

### ⚠️ 已知问题（在 v1.0.1 中修复）

- 与 MTR 3.2.2-hotfix-1 同时安装时，服务端和客户端均会崩溃。
- Fabric API 版本锁定过低，与 MTR 依赖冲突。
- 无更新检测机制。

---

## 版本号说明

| 版本段 | 含义 |
|--------|------|
| **主版本** | 不兼容的架构变更（如更换音频引擎、协议重构） |
| **次版本** | 新功能加入（如新增更新检测、新增命令） |
| **修订号** | 问题修复（如崩溃修复、配置覆盖修复） |

当前所有版本均面向 **Minecraft Java 1.19.2 + Fabric**。
