package com.simpmusic.server.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simpmusic.server.SimpMusicServer;
import com.simpmusic.server.ModConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent.Action;
import net.minecraft.util.Formatting;

public class UpdateChecker {
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
   private static String apiBaseUrl = "https://api.github.com";
   private static String repoPath = "NekoZzz5354/SimpMusic";
   private static String authToken = "";
   private static boolean enabled = true;
   private static boolean notifyOpsOnly = true;
   private static boolean notifyOnJoin = true;
   private static int checkIntervalMinutes = 360;
   private static Instant lastCheckTime = Instant.EPOCH;
   private static String cachedLatestVersion = null;
   private static String cachedDownloadUrl = null;
   private static String cachedChangelog = null;
   private static boolean cachedIsNewer = false;
   private static String workingApiBaseUrl = null;

   public static void init() {
      apiBaseUrl = ModConfig.getUpdateApiUrl();
      repoPath = ModConfig.getUpdateRepoPath();
      authToken = ModConfig.getUpdateToken();
      enabled = ModConfig.isUpdateCheckerEnabled();
      notifyOpsOnly = ModConfig.isUpdateNotifyOpsOnly();
      notifyOnJoin = ModConfig.isUpdateNotifyOnJoin();
      checkIntervalMinutes = ModConfig.getUpdateCheckInterval();
      SimpMusicServer.LOGGER.info("[UpdateChecker] initialized");
      SimpMusicServer.LOGGER.info("  Enabled: {}", enabled);
      SimpMusicServer.LOGGER.info("  API: {}", apiBaseUrl);
      SimpMusicServer.LOGGER.info("  Repo: {}", repoPath);
      SimpMusicServer.LOGGER.info("  Token: {}", authToken.isEmpty() ? "none" : "configured");
      SimpMusicServer.LOGGER.info("  Interval: {} min", checkIntervalMinutes);
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static void setEnabled(boolean e) {
      enabled = e;
      ModConfig.setUpdateCheckerEnabled(e);
   }

   public static String getApiBaseUrl() {
      return apiBaseUrl;
   }

   public static void setApiBaseUrl(String url) {
      apiBaseUrl = url != null && !url.isEmpty() ? url : "https://api.github.com";
      ModConfig.setUpdateApiBaseUrl(apiBaseUrl);
   }

   public static String getRepoPath() {
      return repoPath;
   }

   public static void setRepoPath(String path) {
      repoPath = path != null && !path.isEmpty() ? path : "NekoZzz5354/SimpMusic";
      ModConfig.setUpdateRepoPath(repoPath);
   }

   public static String getToken() {
      return authToken;
   }

   public static void setToken(String token) {
      authToken = token != null ? token : "";
      ModConfig.setUpdateToken(authToken);
   }

   public static String getCachedLatestVersion() {
      return cachedLatestVersion;
   }

   public static String getCachedDownloadUrl() {
      return cachedDownloadUrl;
   }

   public static String getCachedChangelog() {
      return cachedChangelog;
   }

   public static boolean isUpdateAvailable() {
      return cachedIsNewer;
   }

   public static void checkForUpdates() {
      if (enabled) {
         if (shouldCheck()) {
            SimpMusicServer.LOGGER.info("[UpdateChecker] Checking for updates...");
            lastCheckTime = Instant.now();
            performCheck(result -> {
               if (result.success && result.isNewer) {
                  cacheResult(result);
                  SimpMusicServer.LOGGER.warn("[UpdateChecker] New version: {} → {}", getCurrentVersion(), result.latestVersion);
                  notifyOnlineOps();
               } else if (result.success) {
                  SimpMusicServer.LOGGER.info("[UpdateChecker] Already on latest: v{}", getCurrentVersion());
               } else {
                  SimpMusicServer.LOGGER.warn("[UpdateChecker] Check failed (rate limited or network error)");
               }
            });
         }
      }
   }

   public static void notifyOnPlayerJoin(ServerPlayerEntity player) {
      if (notifyOnJoin) {
         if (cachedIsNewer) {
            if (!notifyOpsOnly || player.hasPermissionLevel(2)) {
               CompletableFuture.delayedExecutor(3L, TimeUnit.SECONDS).execute(() -> sendUpdateNotification(player));
            }
         }
      }
   }

   public static void manualCheck(ServerPlayerEntity player) {
      player.sendMessage(Text.literal("§6[SimpMusic] §e正在检查更新..."), false);
      performCheck(result -> {
         if (result.success && result.isNewer) {
            cacheResult(result);
            sendUpdateNotification(player);
         } else if (result.success) {
            player.sendMessage(Text.literal("§6[SimpMusic] §a已是最新版本 (v" + getCurrentVersion() + ")"), false);
         } else {
            player.sendMessage(Text.literal("§6[SimpMusic] §c更新检查失败，可能是网络问题或 API 限流").formatted(Formatting.RED), false);
         }
      });
   }

   private static void performCheck(Consumer<UpdateChecker.CheckResult> callback) {
      CompletableFuture.<UpdateChecker.CheckResult>supplyAsync(
            () -> {
               UpdateChecker.CheckResult result = new UpdateChecker.CheckResult();
               List<String> candidates = new ArrayList<>();
               if (workingApiBaseUrl != null) {
                  candidates.add(workingApiBaseUrl);
               }

               if (apiBaseUrl != null && !apiBaseUrl.isEmpty()) {
                  candidates.add(apiBaseUrl);
               }

               candidates.add("https://api.github.com");
               candidates.add("https://ghfast.top/https://api.github.com");
               candidates.add("https://gh-proxy.com/https://api.github.com");
               candidates.add("https://ghproxy.net/https://api.github.com");

               for (String base : candidates) {
                  try {
                     // 缓存破坏：拼时间戳查询参数 + no-cache 头，避免 CDN/代理返回旧结果导致检测不到新版本
                     String url = base.replaceAll("/$", "") + "/repos/" + repoPath + "/releases/latest"
                        + "?_cb=" + System.currentTimeMillis();
                     SimpMusicServer.LOGGER.debug("[UpdateChecker] Requesting: {}", url);
                     Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "SimpMusic-Server/" + getCurrentVersion())
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Cache-Control", "no-cache, no-store, max-age=0")
                        .header("Pragma", "no-cache")
                        .timeout(Duration.ofSeconds(8L))
                        .GET();
                     if (authToken != null && !authToken.isEmpty()) {
                        builder.header("Authorization", "Bearer " + authToken);
                     }

                     HttpResponse<String> resp = HTTP.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
                     if (resp.statusCode() == 200) {
                        workingApiBaseUrl = base;
                        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                        String tagName = json.get("tag_name").getAsString();
                        String cleanTag = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                        result.latestVersion = cleanTag;
                        result.success = true;
                        result.isNewer = isNewerVersion(cleanTag, getCurrentVersion());
                        if (json.has("html_url")) {
                           result.downloadUrl = json.get("html_url").getAsString();
                        }

                        if (json.has("body") && !json.get("body").isJsonNull()) {
                           result.changelog = json.get("body").getAsString();
                        }
                        break;
                     }

                     if (resp.statusCode() == 403) {
                        SimpMusicServer.LOGGER.warn("[UpdateChecker] GitHub API rate limited (403) at {}. Configure a token to increase limit.", base);
                        result.success = false;
                        break;
                     }

                     if (resp.statusCode() == 404) {
                        SimpMusicServer.LOGGER.warn("[UpdateChecker] No releases found at {}/repos/{}/releases/latest", base, repoPath);
                        result.success = false;
                        break;
                     }

                     SimpMusicServer.LOGGER.debug("[UpdateChecker] GitHub API error: HTTP {} from {}", resp.statusCode(), base);
                  } catch (Exception e) {
                     SimpMusicServer.LOGGER.debug("[UpdateChecker] Candidate {} failed: {}", base, e.getMessage());
                  }
               }

               return result;
            }
         )
         .thenAccept(callback);
   }

   public static boolean isNewerVersion(String remoteVersion, String currentVersion) {
      try {
         String r = remoteVersion.split("\\+")[0].trim();
         String c = currentVersion.split("\\+")[0].trim();

         try {
            SemanticVersion rVer = SemanticVersion.parse(r);
            SemanticVersion cVer = SemanticVersion.parse(c);
            return rVer.compareTo(cVer) > 0;
         } catch (Exception var6) {
            return compareFallback(r, c) > 0;
         }
      } catch (Exception e) {
         return false;
      }
   }

   private static int compareFallback(String v1, String v2) {
      String[] p1 = v1.split("\\.");
      String[] p2 = v2.split("\\.");
      int len = Math.max(p1.length, p2.length);

      for (int i = 0; i < len; i++) {
         int n1 = i < p1.length ? parseIntSafe(p1[i]) : 0;
         int n2 = i < p2.length ? parseIntSafe(p2[i]) : 0;
         if (n1 != n2) {
            return n1 - n2;
         }
      }

      return 0;
   }

   private static int parseIntSafe(String s) {
      try {
         return Integer.parseInt(s.replaceAll("[^0-9]", ""));
      } catch (Exception e) {
         return 0;
      }
   }

   private static void notifyOnlineOps() {
      MinecraftServer server = SimpMusicServer.getServer();
      if (server != null) {
         for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!notifyOpsOnly || player.hasPermissionLevel(2)) {
               sendUpdateNotification(player);
            }
         }
      }
   }

   private static void sendUpdateNotification(ServerPlayerEntity player) {
      String cur = getCurrentVersion();
      String latest = cachedLatestVersion != null ? cachedLatestVersion : "unknown";
      MutableText header = Text.literal("§6╔══ SimpMusic 更新通知 ══╗\n").formatted(new Formatting[]{Formatting.GOLD, Formatting.BOLD});
      MutableText versionLine = Text.literal(String.format("§e当前: §c%s  §e→ 最新: §a%s\n", cur, latest));
      MutableText dlLine = Text.literal("§b§n点击此处下载最新版本\n").formatted(new Formatting[]{Formatting.AQUA, Formatting.UNDERLINE});
      MutableText footer = Text.literal("╚════════════════════╝").formatted(Formatting.GOLD);
      MutableText combined = Text.literal("").append(header).append(versionLine).append(dlLine).append(footer);
      if (cachedChangelog != null && !cachedChangelog.isEmpty()) {
         String[] lines = cachedChangelog.split("\n");
         int count = Math.min(3, lines.length);
         combined.append(Text.literal("§7更新内容:\n").formatted(Formatting.GRAY));

         for (int i = 0; i < count; i++) {
            String line = lines[i].trim();
            if (line.length() > 60) {
               line = line.substring(0, 60) + "...";
            }

            combined.append(Text.literal("§8  " + line + "\n").formatted(Formatting.DARK_GRAY));
         }
      }

      if (cachedDownloadUrl != null && !cachedDownloadUrl.isEmpty()) {
         combined.styled(style -> style.withClickEvent(new ClickEvent(Action.OPEN_URL, cachedDownloadUrl)));
         combined.styled(style -> style.withHoverEvent(new HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§b点击打开下载页面"))));
      }

      player.sendMessage(combined, false);
   }

   private static boolean shouldCheck() {
      long minutesSince = Duration.between(lastCheckTime, Instant.now()).toMinutes();
      return minutesSince >= checkIntervalMinutes;
   }

   private static void cacheResult(UpdateChecker.CheckResult result) {
      if (result.latestVersion != null) {
         cachedLatestVersion = result.latestVersion;
      }

      if (result.downloadUrl != null) {
         cachedDownloadUrl = result.downloadUrl;
      }

      if (result.changelog != null) {
         cachedChangelog = result.changelog;
      }

      cachedIsNewer = result.isNewer;
   }

   public static String getCurrentVersion() {
      try {
         return FabricLoader.getInstance().getModContainer("simpmusic").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(SimpMusicServer.VERSION);
      } catch (Exception e) {
         return SimpMusicServer.VERSION;
      }
   }

   public static class CheckResult {
      public boolean success = false;
      public boolean isNewer = false;
      public String latestVersion = null;
      public String downloadUrl = null;
      public String changelog = null;
   }
}
