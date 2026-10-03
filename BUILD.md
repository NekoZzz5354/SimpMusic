# SimpMusic 构建指南

> Minecraft 1.20.4 · Fabric · 双端模组（Server / Client）

## 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 17（编译目标；JDK 20/21 可运行构建） |
| Gradle | 8.8（wrapper 已内置，自动下载） |
| Minecraft | 1.20.4 |
| Fabric Loader | ≥ 0.15.0（构建使用 0.15.11） |
| Fabric API | ≥ 0.97.3+1.20.4（构建使用 0.97.3） |
| Yarn Mappings | 1.20.4+build.3 |

## 构建命令

工程为**双独立 Gradle 工程**（规避 Loom 多项目缓存冲突），分别构建：

```bash
# 服务端
./gradlew -p server build

# 客户端
./gradlew -p client build
```

Windows 下使用 `gradlew.bat`：

```bat
gradlew.bat -p server build
gradlew.bat -p client build
```

产物位于各子工程 `build/libs/`：

| 文件 | 说明 |
|------|------|
| `server/build/libs/SimpMusic-Server-<ver>.jar` | 服务端模组 |
| `client/build/libs/SimpMusic-Client-<ver>.jar` | 客户端模组（内置 MP3 解码库） |

## 网络配置（国内环境）

构建依赖仓库已在 `server/settings.gradle` / `client/settings.gradle` 中配置：

- `maven.fabricmc.net` — Fabric 生态（Loom / Loader / Fabric API / Yarn 映射）
- `libraries.minecraft.net` — Mojang 专属库（text2speech 等）
- 腾讯云镜像 — Maven Central 替代（LWJGL、Gson 等）

> ⚠️ 不要移除 `dependencyResolutionManagement` 块：`PREFER_SETTINGS` 模式保证被墙的
> `repo.maven.apache.org` 永远不被访问，同时 `loom-cache` 本地仓库必须保留在列表中，
> 否则 Loom 的 remap 依赖无法解析。

## 版本号修改

改 `gradle.properties` 中的 `mod_version` 即可，`fabric.mod.json` 通过
`processResources` 自动注入。

## 源码结构

```
server/src/main/java/com/simpmusic/server/    服务端：队列管理、网易云 API、命令、更新检查
client/src/main/java/com/simpmusic/client/    客户端：OpenAL 播放引擎、GUI、命令、网络处理
```

- 服务端无第三方运行时依赖（HTTP 用 JDK 内置 `java.net.http`，Gson 由 Fabric API 提供）。
- 客户端内嵌解码库：mp3spi + jlayer + tritonus-share（`bundled` 配置合并进 jar，
  编译期以 `compileOnly` 引入）。junit 已从合并中排除。

## 接手说明（2026-08-14）

- 本项目源码基线由 v1.0.15 发布 jar 反编译恢复（Vineflower + tiny-remapper +
  yarn v2 映射逆向），已还原为可编译的 named 源码。
- 服务端发布 jar 原为混合命名（部分 named / 部分 intermediary），本工程构建产物
  忠实复现该形态，运行时行为与 v1.0.15 一致。
- 已知修复（相对反编译基线）：`SimpMusicClient` 中一处 `ex` 未定义变量修正为 `e`。
- GitHub 仓库（NekoZzz5354/MTRMusic）当前仅含 README/License/version.json，无源码。
