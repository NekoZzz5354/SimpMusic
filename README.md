# 🎵 SimpMusic

> Minecraft Java Edition **1.20.4** · Fabric · 全服点歌模组
>
> 原名 MTRMusic（v1.1.1 起更名）；**不依赖 MTR**，可在纯原版/任意整合包独立运行

## ✨ 功能特性

- 🎶 **全服点歌** — 所有玩家搜索网易云音乐并点歌，全服同步播放
- 🔍 **实时搜索** — 聊天栏可点击结果 / `G` 键 GUI 搜索
- 📋 **点歌队列** — 队列管理、跳过、停止、移除，含每玩家冷却与上限
- 📊 **BossBar 歌词** — 末影龙样式进度条 + LRC 歌词实时滚动
- ⏭ **/skip 投票跳过** — 在线玩家半数投票即可切歌
- 🎧 **高品质音频** — 内置 MP3 解码，独立 OpenAL 流式播放引擎
- 🔄 **中途加入同步** — 播放中进服的玩家自动接上当前歌曲
- 🎵 **可选 MTR 兼容** — 检测到 MTR 时自动应用兼容补丁（非必需）

## 📦 安装

| 文件 | 放置位置 |
|------|---------|
| `SimpMusic-Server-1.1.5.jar` | 服务器 `mods/` |
| `SimpMusic-Client-1.1.5.jar` | 客户端 `mods/` |

> 服务端与客户端**都要装**，且版本必须一致。

## 🚀 快速开始

```
/music search <关键词>     — 搜索歌曲（聊天栏点"点我播放"）
/music request <ID> <标题> — 点歌
/music queue / list        — 查看队列
/skip                      — 投票跳过（半数通过）
/music skip / stop         — 强制跳过 / 停止（OP）
/music help                — 帮助
```

客户端：`G` 键打开点歌 GUI，`/simpmusic settings` 打开设置。

## ⚙️ 配置

所有配置集中在游戏根目录 `SimpMusic/`：

| 文件 | 说明 |
|------|------|
| `config-server.json` | 服务端：API、码率、队列上限、音量、BossBar、更新检查间隔 |
| `config-client.json` | 客户端：音量倍率、缓冲、音效类、更新检查间隔 |
| `163cookie.json` | 网易云 Cookie（**仅服务端**，填后可播放 VIP 歌曲） |

> 从 MTRMusic 升级时，旧 `MTRMusic/` 目录会在首次启动自动改名为 `SimpMusic/`。
>
> **v1.1.5 起 VIP Cookie 仅需服务端配置一份**，客户端不再读取 Cookie；
> 更新检查间隔（分钟）可在两端配置文件中分别设置，默认 `360`。

## 📋 依赖要求

| 组件 | 最低版本 |
|------|---------|
| Minecraft | 1.20.4 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | ≥ 0.97.3+1.20.4 |
| Java | 17 |

## 🔨 构建

```bash
./gradlew -p server build    # 服务端
./gradlew -p client build    # 客户端
```

详见 [BUILD.md](BUILD.md)；架构说明见 [ARCHITECTURE.md](ARCHITECTURE.md)；版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 📄 许可

MIT License
