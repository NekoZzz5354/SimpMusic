# 🎵 SimpMusic v1.1.4

> Minecraft **1.20.4** · Fabric · 原名 MTRMusic · 不再依赖 MTR

---

## 📦 下载

| 文件 | 用途 |
|------|------|
| `SimpMusic-Server-1.1.4.jar` | 服务端 → 放服务器 `mods/` |
| `SimpMusic-Client-1.1.4.jar` | 客户端 → 放游戏 `mods/` |

---

## 🍪 解锁 VIP 歌曲（本版新增）

带 VIP 账号的网易云 Cookie 后，即可播放付费歌曲（含无损档）。

**放置方式**（二选一，推荐第一种）：

1. 把浏览器扩展（Cookie-Editor / EditThisCookie）导出的 **`163cookie.json`** 直接放到：
   - 服务端：服务器根目录 `SimpMusic/163cookie.json`
   - 客户端：游戏根目录 `SimpMusic/163cookie.json`
2. 或把 Cookie 字符串填入 `SimpMusic/cookie.json` 的 `netease` 字段

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
2. **替换** — 删掉旧 jar（服务端与客户端**都要**），放入 1.1.4
3. **放置 Cookie** — 把 `163cookie.json` 放进 `SimpMusic/` 目录
4. **启动** — 先开服务器，再开客户端

> 已有配置目录 `SimpMusic/` 无需改动，自动兼容。

---

## 📝 更新内容（v1.1.4）

### ✨ 新增 网易云 VIP Cookie 适配
- 直接识别浏览器导出的 **`163cookie.json`** 数组格式（含 domain / name / value / expirationDate 字段），
  自动提取 163.com 域下的鉴权项并拼装为请求 Cookie 头；统计与广告类 Cookie 自动过滤
- 自动校验 **MUSIC_U** 登录凭证是否存在，缺失时给出明确提示而非静默失败
- 启动时异步校验登录态，日志打印账号昵称与 VIP 类型
- 新增 `/music cookie` 命令查看 Cookie / 账号 / VIP 实时状态

### ✨ 新增 VIP 歌曲播放地址解析
- 播放地址解析改为**携带 Cookie 直连**官方接口：
  - 移动端 UA 优先（VIP 放行率更高），失败自动回退 PC UA
  - 先按配置码率请求，失败自动回退 320k
  - 解析失败再走 `outer/url` 外链兜底
- 搜索 / 详情 / 歌词接口全部携带 Cookie，VIP 曲库信息更完整

### 🔧 其他
- 客户端同步支持 `163cookie.json`，官网直连模式下同样可播放 VIP 歌曲
- 补齐服务端 Gradle Wrapper（此前服务端目录缺少 `gradlew`）
- 版本号 1.1.4。

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
