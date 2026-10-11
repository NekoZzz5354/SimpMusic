# 🎵 SimpMusic v1.2.3

> Minecraft **1.20.4** · Fabric · 原名 MTRMusic · 不再依赖 MTR

![歌曲 HUD 预览](SimpMusic-1.2.3-hud-preview.png)

---

## 📦 下载

| 文件 | 用途 |
|------|------|
| `SimpMusic-Server-1.2.3.jar` | 服务端 → 放服务器 `mods/` |
| `SimpMusic-Client-1.2.3.jar` | 客户端 → 放游戏 `mods/` |
| `SimpMusic-1.2.3-src.zip` | 源码包 |

> 服务端与客户端**都要装**，且版本必须一致。

---

## 🔍 本版依据玩家日志定位到了确切崩溃点

```
java.lang.OutOfMemoryError: Out of stack space.
    at org.lwjgl.system.MemoryStack.nmalloc(MemoryStack.java:322)
    at net.minecraft.class_1011.method_49277(...)        ← NativeImage.read(byte[])
    at com.simpmusic.client.hud.CoverTexture.applyImage(...)
```

---

## 🔧 本版修复

### 1️⃣ 封面导致客户端崩溃 → 已修复

`NativeImage.read(byte[])` 内部是 `MemoryStack.malloc(图片文件大小)` ——
把**整张图片文件**先放到 **LWJGL 线程局部栈**上，而这个栈**默认只有 64 KB**。

> **实测**：`malloc(64KB)` 正常，`malloc(65KB)` 立刻抛
> `OutOfMemoryError: Out of stack space.`

也就是说，**只要封面图片超过 64 KB 就必然抛错**；而它抛的是 `Error` 不是 `Exception`，
原来的 `catch (Exception)` 接不住，直接把渲染线程打崩、游戏退出。

**修复**：

- 改用 **`NativeImage.read(InputStream)`**（流式重载，走堆外分配，**没有 64 KB 限制**）
- 解码放到**后台线程**，渲染线程只做最后的 GL 上传
- 兜底改为 **`catch (Throwable)`**：封面出任何问题都只记日志，**绝不崩游戏**
- HUD 每帧绘制再加一层 `Throwable` 兜底

### 2️⃣ 播放音乐时内存暴涨（内存泄漏）→ 已修复

PCM 缓冲队列原先**无界**，解码线程会尽可能快地把**整首歌**解码完全塞进队列，
而消费者每帧只取 0.25 秒，根本追不上 ——
一首 4 分钟 44.1kHz 立体声 16bit 的歌约 **40 MB+** PCM 长期驻留内存，连播几首就吃满。

**修复**：队列改为**有界**（24 块 ≈ 6 秒音频），写满时阻塞形成**背压**，
解码速度被限制到与播放速度相当，常驻内存只剩几秒音频的量级。

### 3️⃣ 顺带修好两处

- 音频下载改用**浏览器 UA** 并**跟随重定向**（日志里出现过 `Audio download failed: HTTP 403`，
  部分 CDN 对未知 UA / 未跟随跳转直接拒绝）
- 旧配置里的 `NekoZzz5354/MTRMusic` 自动迁移为新仓库（日志显示更新检测一直在查旧仓库，
  自然永远检测不到新版本）

---

## 🔄 更新教程

1. **停服** — 关闭服务器和客户端
2. **替换** — 删掉旧 jar（服务端与客户端**都要**），放入 1.2.3
3. **启动** — 先开服务器，再开客户端

---

## 🧪 排障

| 现象 | 检查 |
|------|------|
| 封面不显示 | 游戏内 `/simpmusic hud` 看「封面」一栏 |
| 内存在播放时暴涨 | 看是否已换到 1.2.3（1.2.2 及以前无界缓冲） |
| 播到一半停了 | 客户端日志 `Audio appears truncated`；服务端日志 `reports early playback end` |

---

## 📋 依赖要求

| 组件 | 最低版本 |
|------|---------|
| Minecraft | 1.20.4 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | ≥ 0.97.3+1.20.4 |
| Java | 17 |

MTR 为可选组件，不再必需。

---

## 🍪 解锁 VIP 歌曲（仅服务端配置）

把浏览器扩展（Cookie-Editor / EditThisCookie）导出的 `163cookie.json`
原样放到**服务端**根目录 `SimpMusic/163cookie.json` 即可，客户端无需任何配置。

> ⚠️ Cookie 为账号级凭证，**请勿公开分享**。
