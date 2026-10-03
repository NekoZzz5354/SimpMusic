# 🎵 SimpMusic v1.1.5

> Minecraft **1.20.4** · Fabric · 原名 MTRMusic · 不再依赖 MTR

---

## 📦 下载

| 文件 | 用途 |
|------|------|
| `SimpMusic-Server-1.1.5.jar` | 服务端 → 放服务器 `mods/` |
| `SimpMusic-Client-1.1.5.jar` | 客户端 → 放游戏 `mods/` |

---

## 🍪 解锁 VIP 歌曲（本版：Cookie 仅需服务端一份）

带 VIP 账号的网易云 Cookie 后，即可播放付费歌曲（含无损档）。

> **v1.1.5 起客户端不再读取 Cookie** —— 只需在**服务端**放一份即可，
> 玩家客户端无需任何配置，直接进服即可听 VIP 歌曲。

**放置方式**（二选一，推荐第一种）：

1. 把浏览器扩展（Cookie-Editor / EditThisCookie）导出的 **`163cookie.json`** 直接放到：
   - **仅服务端**：服务器根目录 `SimpMusic/163cookie.json`
2. 或把 Cookie 字符串填入 `SimpMusic/cookie.json` 的 `netease` 字段（同样只需服务端）

> 文件格式**无需改动**，直接原样放进去即可识别。
> 加载成功后，服务端日志会打印：`网易云账号已登录：xxx（vipType=110 · VIP 已生效）`

**校验是否生效** —— 游戏内执行 `/music cookie`：

```
[SimpMusic] === 网易云 Cookie 状态 ===
✓ Cookie 已加载
来源: /path/SimpMusic/163cookie.json
账号: NekoZzz_PNC @CN
VIP: ✓ 有效 (vipType=110) · 可播放 VIP 歌曲
```

---

## 🔄 更新教程

1. **停服** — 关闭服务器和客户端
2. **替换** — 删掉旧 jar（服务端与客户端**都要**），放入 1.1.5
3. **放置 Cookie** — 把 `163cookie.json` 放进**服务端**的 `SimpMusic/` 目录（客户端无需）
4. **启动** — 先开服务器，再开客户端

> 已有配置目录 `SimpMusic/` 无需改动，自动兼容。

---

## 📝 更新内容（v1.1.5）

### 🔧 会员 Cookie 改为仅服务端配置
- **客户端不再读取任何 Cookie 文件**，删除客户端 `CookieManager` 及其加载调用
- VIP 歌曲鉴权统一由服务端完成，服务端下发已解析好的直链
- Cookie 只留服务端一份，降低敏感凭据分发与泄露风险

### 🔧 更新检测指向当前 GitHub 项目
- 更新检测仓库地址由 `NekoZzz5354/MTRMusic` 修正为 **`NekoZzz5354/SimpMusic`**
- 修复更名后更新检测仍指向旧仓库、永远检测不到新版本的问题

### ✨ 更新检测缓存破坏
- 请求 GitHub Release 接口追加 `?_cb=<时间戳>` 查询参数 +
  `Cache-Control: no-cache` / `Pragma: no-cache` 请求头
- 避免 CDN / 代理缓存旧响应导致漏检新版本

### ✨ 更新检查间隔可配置（单位：分钟）
- 服务端 `config-server.json` → `updateCheckIntervalMinutes`（默认 360）
- 客户端 `config-client.json` → `update_interval_minutes`（默认 360）
- 强制下限 1 分钟，配置项均附中文注释

### 📝 其他
- 版本号 1.1.5。

---

## 📋 依赖要求

| 组件 | 最低版本 |
|------|---------|
| Minecraft | 1.20.4 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | ≥ 0.97.3+1.20.4 |
| Java | 17 |
| MTR（可选，不再必需） | 1.20.4-3.2.2-hotfix-1 |

---

## ⚠️ 说明

- Cookie 为账号级凭证，**请勿公开分享**；本模组仅在请求网易云接口时附带，不做任何上传。
- 少数歌曲（如部分版权受限曲目）即使 VIP 也可能无播放资源，接口返回 `code=404`，属正常现象。
