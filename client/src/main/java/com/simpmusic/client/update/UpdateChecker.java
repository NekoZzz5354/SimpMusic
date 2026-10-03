package com.simpmusic.client.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simpmusic.client.ClientConfig;
import com.simpmusic.client.SimpMusicClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.minecraft.client.MinecraftClient;
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
   private static long checkIntervalMs = 21600000L;
   private static String latestVersion = null;
   private static String downloadUrl = null;
   private static String changelog = null;
   private static boolean updateAvailable = false;
   private static long lastCheckTime = 0L;
   private static String workingApiBaseUrl = null;

   public static void init() {
      apiBaseUrl = ClientConfig.getUpdateApiUrl();
      repoPath = ClientConfig.getUpdateRepoPath();
      authToken = ClientConfig.getUpdateToken();
      enabled = ClientConfig.isUpdateEnabled();
      checkIntervalMs = ClientConfig.getUpdateIntervalMinutes() * 60L * 1000L;
      SimpMusicClient.LOGGER.info("[UpdateChecker] initialized");
      SimpMusicClient.LOGGER.info("  Enabled: {}", enabled);
      SimpMusicClient.LOGGER.info("  API: {}", apiBaseUrl);
      SimpMusicClient.LOGGER.info("  Repo: {}", repoPath);
      SimpMusicClient.LOGGER.info("  Token: {}", authToken.isEmpty() ? "none" : "configured");
      SimpMusicClient.LOGGER.info("  Interval: {} min", checkIntervalMs / 60000L);
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static void setEnabled(boolean e) {
      enabled = e;
      ClientConfig.setUpdateEnabled(e);
   }

   public static String getApiBaseUrl() {
      return apiBaseUrl;
   }

   public static void setApiBaseUrl(String url) {
      apiBaseUrl = url != null && !url.isEmpty() ? url : "https://api.github.com";
      ClientConfig.setUpdateApiBaseUrl(apiBaseUrl);
   }

   public static String getRepoPath() {
      return repoPath;
   }

   public static void setRepoPath(String path) {
      repoPath = path != null && !path.isEmpty() ? path : "NekoZzz5354/SimpMusic";
      ClientConfig.setUpdateRepoPath(repoPath);
   }

   public static String getToken() {
      return authToken;
   }

   public static void setToken(String token) {
      authToken = token != null ? token : "";
      ClientConfig.setUpdateToken(authToken);
   }

   public static String getLatestVersion() {
      return latestVersion;
   }

   public static String getDownloadUrl() {
      return downloadUrl;
   }

   public static boolean isUpdateAvailable() {
      return updateAvailable;
   }

   public static String getChangelog() {
      return changelog;
   }

   public static void checkForUpdates() {
      if (!enabled) {
         SimpMusicClient.LOGGER.debug("[UpdateChecker] Disabled, skipping check.");
      } else if (!shouldCheck()) {
         if (updateAvailable) {
            showUpdateNotification();
         }
      } else {
         SimpMusicClient.LOGGER.info("[UpdateChecker] Checking for updates...");
         performCheck(success -> {
            if (success && updateAvailable) {
               SimpMusicClient.LOGGER.warn("[UpdateChecker] New version available: {}", latestVersion);
               showUpdateNotification();
            } else if (success) {
               SimpMusicClient.LOGGER.info("[UpdateChecker] Already on latest version: v{}", getCurrentVersion());
            } else {
               SimpMusicClient.LOGGER.warn("[UpdateChecker] Check failed (rate limited or network error). Will retry later.");
            }
         });
      }
   }

   public static void manualCheck() {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client != null && client.player != null) {
         client.player.sendMessage(Text.literal("§6[SimpMusic] §e正在检查更新..."), false);
      }

      performCheck(success -> {
         if (success && updateAvailable) {
            showUpdateNotification();
         } else if (success) {
            notifyNoUpdate();
         } else if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("§6[SimpMusic] §c更新检查失败，可能是网络问题或 GitHub API 限流").formatted(Formatting.RED), false);
         }
      });
   }

   private static void performCheck(Consumer<Boolean> callback) {
      CompletableFuture.<Boolean>supplyAsync(
            () -> {
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
                     SimpMusicClient.LOGGER.debug("[UpdateChecker] Requesting: {}", url);
                     Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "SimpMusic-Client/" + getCurrentVersion())
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
                        latestVersion = cleanTag;
                        if (json.has("html_url")) {
                           downloadUrl = json.get("html_url").getAsString();
                        }

                        if (json.has("body") && !json.get("body").isJsonNull()) {
                           changelog = json.get("body").getAsString();
                        }

                        updateAvailable = isNewerVersion(cleanTag, getCurrentVersion());
                        lastCheckTime = System.currentTimeMillis();
                        return true;
                     }

                     if (resp.statusCode() == 403) {
                        SimpMusicClient.LOGGER.warn("[UpdateChecker] GitHub API rate limited (403) at {}. Configure a token to increase limit.", base);
                        return false;
                     }

                     if (resp.statusCode() == 404) {
                        SimpMusicClient.LOGGER.warn("[UpdateChecker] No releases found at {}/repos/{}/releases/latest", base, repoPath);
                        return false;
                     }

                     SimpMusicClient.LOGGER.debug("[UpdateChecker] GitHub API error: HTTP {} from {}", resp.statusCode(), base);
                  } catch (Exception e) {
                     SimpMusicClient.LOGGER.debug("[UpdateChecker] Candidate {} failed: {}", base, e.getMessage());
                  }
               }

               return false;
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

   private static void showUpdateNotification() {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client != null && client.player != null) {
         String cur = getCurrentVersion();
         MutableText msg = Text.literal("")
            .append(Text.literal("\n§6╔══ SimpMusic 更新通知 ══╗\n").formatted(new Formatting[]{Formatting.GOLD, Formatting.BOLD}))
            .append(Text.literal(String.format("§e当前: §c%s  §e→ 最新: §a%s\n", cur, latestVersion != null ? latestVersion : "?")).formatted(Formatting.YELLOW))
            .append(Text.literal("§b§n点击此处下载最新版本\n").formatted(new Formatting[]{Formatting.AQUA, Formatting.UNDERLINE}))
            .append(Text.literal("╚════════════════════╝\n").formatted(Formatting.GOLD));
         if (changelog != null && !changelog.isEmpty()) {
            String[] lines = changelog.split("\n");
            int count = Math.min(5, lines.length);
            msg.append(Text.literal("§7更新内容:\n").formatted(Formatting.GRAY));

            for (int i = 0; i < count; i++) {
               String line = lines[i].trim();
               if (line.length() > 60) {
                  line = line.substring(0, 60) + "...";
               }

               msg.append(Text.literal("§8  " + line + "\n").formatted(Formatting.DARK_GRAY));
            }
         }

         MutableText clickable = Text.literal("").append(msg);
         if (downloadUrl != null && !downloadUrl.isEmpty()) {
            clickable.styled(style -> style.withClickEvent(new ClickEvent(Action.OPEN_URL, downloadUrl)));
            clickable.styled(style -> style.withHoverEvent(new HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§b点击打开下载页面"))));
         }

         client.player.sendMessage(clickable, false);
      }
   }

   private static void notifyNoUpdate() {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client != null && client.player != null) {
         client.player.sendMessage(Text.literal("§6[SimpMusic] §a已是最新版本 (v" + getCurrentVersion() + ")").formatted(Formatting.GREEN), false);
      }
   }

   private static boolean shouldCheck() {
      return System.currentTimeMillis() - lastCheckTime >= checkIntervalMs;
   }

   public static String getCurrentVersion() {
      try {
         return FabricLoader.getInstance().getModContainer("simpmusic").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(SimpMusicClient.VERSION);
      } catch (Exception e) {
         return SimpMusicClient.VERSION;
      }
   }
}
