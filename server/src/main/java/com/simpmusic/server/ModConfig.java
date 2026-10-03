package com.simpmusic.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class ModConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_DIR = Path.of("SimpMusic");
   private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config-server.json");
   private static final Path CONFIG_BACKUP = CONFIG_DIR.resolve("config-server.json.bak");
   private static String apiBaseUrl = "https://music.163.com";
   private static int defaultBitrate = 320000;
   private static boolean showBossBar = true;
   private static int maxQueuePerPlayer = 5;
   private static int maxTotalQueue = 50;
   private static boolean allowDuplicates = false;
   private static double serverVolume = 1.0;
   private static int requestCooldown = 3;
   private static String prefix = "§d[SimpMusic]§r";
   private static boolean updateCheckerEnabled = true;
   private static String updateApiUrl = "https://api.github.com";
   private static String updateRepoPath = "NekoZzz5354/SimpMusic";
   private static String updateToken = "";
   private static boolean updateNotifyOpsOnly = true;
   private static boolean updateNotifyOnJoin = true;
   /** 更新检查间隔，单位分钟 */
   private static int updateCheckIntervalMinutes = 360;
   private static boolean mtrCompatible = false;
   private static String mtrVersion = "";
   private static String preferredSoundCategory = "MASTER";
   private static int queueTickDelay = 0;

   public static void load() {
      try {
         migrateLegacyDir();
         if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
         }

         if (!Files.exists(CONFIG_FILE)) {
            saveDefaults();
            return;
         }

         String content = Files.readString(CONFIG_FILE);
         JsonObject json = JsonParser.parseString(content).getAsJsonObject();
         if (json.has("apiBaseUrl")) {
            apiBaseUrl = json.get("apiBaseUrl").getAsString();
         } else if (json.has("api_base_url")) {
            apiBaseUrl = json.get("api_base_url").getAsString();
         }

         if (json.has("defaultBitrate")) {
            defaultBitrate = json.get("defaultBitrate").getAsInt();
         } else if (json.has("default_bitrate")) {
            defaultBitrate = json.get("default_bitrate").getAsInt();
         }

         if (json.has("netease_cookie") && !json.get("netease_cookie").isJsonNull()) {
            String legacy = json.get("netease_cookie").getAsString();
            if (legacy != null && !legacy.isEmpty() && CookieManager.getNeteaseCookie().isEmpty()) {
               SimpMusicServer.LOGGER.info("Migrating legacy netease_cookie from config to SimpMusic/cookie.json");
               saveLegacyCookie(legacy);
            }
         }

         if (json.has("show_boss_bar")) {
            showBossBar = json.get("show_boss_bar").getAsBoolean();
         }

         if (json.has("maxQueuePerPlayer")) {
            maxQueuePerPlayer = json.get("maxQueuePerPlayer").getAsInt();
         }

         if (json.has("maxTotalQueue")) {
            maxTotalQueue = json.get("maxTotalQueue").getAsInt();
         }

         if (json.has("allowDuplicates")) {
            allowDuplicates = json.get("allowDuplicates").getAsBoolean();
         }

         if (json.has("serverVolume")) {
            serverVolume = json.get("serverVolume").getAsDouble();
         }

         if (json.has("requestCooldown")) {
            requestCooldown = json.get("requestCooldown").getAsInt();
         }

         if (json.has("prefix")) {
            prefix = json.get("prefix").getAsString();
         }

         if (json.has("updateCheckerEnabled")) {
            updateCheckerEnabled = json.get("updateCheckerEnabled").getAsBoolean();
         }

         if (json.has("update_api_url")) {
            updateApiUrl = json.get("update_api_url").getAsString();
         }

         if (json.has("update_repo_path")) {
            updateRepoPath = json.get("update_repo_path").getAsString();
         }

         if (json.has("update_token")) {
            updateToken = json.get("update_token").getAsString();
         }

         if (json.has("updateNotifyOpsOnly")) {
            updateNotifyOpsOnly = json.get("updateNotifyOpsOnly").getAsBoolean();
         }

         if (json.has("updateNotifyOnJoin")) {
            updateNotifyOnJoin = json.get("updateNotifyOnJoin").getAsBoolean();
         }

         if (json.has("updateCheckIntervalMinutes")) {
            updateCheckIntervalMinutes = json.get("updateCheckIntervalMinutes").getAsInt();
         }

         if (json.has("updateMethod")) {
            String oldMethod = json.get("updateMethod").getAsString().toLowerCase();
            if ("disabled".equals(oldMethod)) {
               updateCheckerEnabled = false;
            }
         }

         if (json.has("preferredSoundCategory")) {
            preferredSoundCategory = json.get("preferredSoundCategory").getAsString();
         }

         if (json.has("queueTickDelay")) {
            queueTickDelay = json.get("queueTickDelay").getAsInt();
         }

         save();
         SimpMusicServer.LOGGER.info("SimpMusic config loaded from {} (merged with defaults)", CONFIG_FILE);
         SimpMusicServer.LOGGER.info("  Update checker: {} (API: {})", updateCheckerEnabled ? "ENABLED" : "disabled", updateApiUrl);
         SimpMusicServer.LOGGER.info("  MTR compat: {}", mtrCompatible ? "YES (" + mtrVersion + ")" : "NO");
      } catch (Exception e) {
         SimpMusicServer.LOGGER.error("Failed to load config, using defaults", e);

         try {
            if (Files.exists(CONFIG_FILE)) {
               Files.copy(CONFIG_FILE, CONFIG_BACKUP, StandardCopyOption.REPLACE_EXISTING);
               SimpMusicServer.LOGGER.warn("Corrupted config backed up to {}.bak", CONFIG_FILE);
            }

            saveDefaults();
         } catch (IOException var3) {
         }
      }
   }

   /** 从旧目录 MTRMusic/ 迁移配置（v1.1.1 起改名 SimpMusic） */
   private static void migrateLegacyDir() {
      try {
         Path legacy = Path.of("MTRMusic");
         if (Files.exists(legacy) && !Files.exists(CONFIG_DIR)) {
            Files.move(legacy, CONFIG_DIR);
            SimpMusicServer.LOGGER.info("Migrated legacy config directory MTRMusic/ -> SimpMusic/");
         }
      } catch (Exception e) {
         SimpMusicServer.LOGGER.warn("Legacy config dir migration skipped: {}", e.getMessage());
      }
   }

   private static void save() {
      try {
         if (Files.exists(CONFIG_FILE)) {
            Files.copy(CONFIG_FILE, CONFIG_BACKUP, StandardCopyOption.REPLACE_EXISTING);
         }

         JsonObject json = new JsonObject();
         json.addProperty("apiBaseUrl", apiBaseUrl);
         json.addProperty("defaultBitrate", defaultBitrate);
         json.addProperty("show_boss_bar", showBossBar);
         json.addProperty("maxQueuePerPlayer", maxQueuePerPlayer);
         json.addProperty("maxTotalQueue", maxTotalQueue);
         json.addProperty("allowDuplicates", allowDuplicates);
         json.addProperty("serverVolume", serverVolume);
         json.addProperty("requestCooldown", requestCooldown);
         json.addProperty("prefix", prefix);
         json.addProperty("updateCheckerEnabled", updateCheckerEnabled);
         json.addProperty("update_api_url", updateApiUrl);
         json.addProperty("update_repo_path", updateRepoPath);
         json.addProperty("update_token", updateToken);
         json.addProperty("updateNotifyOpsOnly", updateNotifyOpsOnly);
         json.addProperty("updateNotifyOnJoin", updateNotifyOnJoin);
         // 更新检查间隔（分钟）：每隔多少分钟向 GitHub 查询一次新版本，最小 1
         json.addProperty("updateCheckIntervalMinutes", updateCheckIntervalMinutes);
         json.addProperty("preferredSoundCategory", preferredSoundCategory);
         json.addProperty("queueTickDelay", queueTickDelay);
         json.addProperty("_comment", "Edit values below. Backup auto-saved to config-server.json.bak");
         json.addProperty("_comment_updateCheckIntervalMinutes", "更新检查间隔，单位为分钟（每隔多少分钟检查一次新版本，最小 1）");
         json.addProperty("_comment_update_repo_path", "更新检测指向的 GitHub 仓库，格式 owner/repo");
         json.addProperty("_comment_netease_cookie", "网易云 VIP Cookie 仅需配置服务端：把浏览器导出的 163cookie.json 放到 SimpMusic/163cookie.json");
         Files.writeString(CONFIG_FILE, GSON.toJson(json), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      } catch (IOException e) {
         SimpMusicServer.LOGGER.error("Failed to save config", e);
      }
   }

   private static void saveDefaults() {
      save();
      SimpMusicServer.LOGGER.info("SimpMusic default config created at {}", CONFIG_FILE);
   }

   private static void saveLegacyCookie(String cookie) {
      try {
         CookieManager.save(cookie);
      } catch (Exception e) {
         SimpMusicServer.LOGGER.warn("Failed to migrate legacy cookie: {}", e.getMessage());
      }
   }

   public static String getApiBaseUrl() {
      return apiBaseUrl;
   }

   public static int getDefaultBitrate() {
      return defaultBitrate;
   }

   public static String getNeteaseCookie() {
      return CookieManager.getNeteaseCookie();
   }

   public static boolean isShowBossBar() {
      return showBossBar;
   }

   public static int getMaxQueuePerPlayer() {
      return maxQueuePerPlayer;
   }

   public static int getMaxTotalQueue() {
      return maxTotalQueue;
   }

   public static boolean isAllowDuplicates() {
      return allowDuplicates;
   }

   public static double getServerVolume() {
      return serverVolume;
   }

   public static int getRequestCooldown() {
      return requestCooldown;
   }

   public static String getPrefix() {
      return prefix;
   }

   public static boolean isUpdateCheckerEnabled() {
      return updateCheckerEnabled;
   }

   public static String getUpdateApiUrl() {
      return updateApiUrl != null ? updateApiUrl : "https://api.github.com";
   }

   public static String getUpdateRepoPath() {
      return updateRepoPath != null ? updateRepoPath : "NekoZzz5354/SimpMusic";
   }

   public static String getUpdateToken() {
      return updateToken != null ? updateToken : "";
   }

   public static boolean isUpdateNotifyOpsOnly() {
      return updateNotifyOpsOnly;
   }

   public static boolean isUpdateNotifyOnJoin() {
      return updateNotifyOnJoin;
   }

   public static int getUpdateCheckInterval() {
      // 最小 1 分钟，避免设置为 0 导致每次 tick 都发请求
      return Math.max(1, updateCheckIntervalMinutes);
   }

   public static boolean isMTRCompatible() {
      return mtrCompatible;
   }

   public static String getMTRVersion() {
      return mtrVersion;
   }

   public static String getPreferredSoundCategory() {
      return preferredSoundCategory;
   }

   public static int getQueueTickDelay() {
      return queueTickDelay;
   }

   public static void setApiBaseUrl(String url) {
      apiBaseUrl = url != null ? url : "https://music.163.com";
      save();
   }

   public static void setDefaultBitrate(int br) {
      defaultBitrate = br;
      save();
   }

   public static void setShowBossBar(boolean b) {
      showBossBar = b;
      save();
   }

   public static void setMaxQueuePerPlayer(int n) {
      maxQueuePerPlayer = n;
      save();
   }

   public static void setMaxTotalQueue(int n) {
      maxTotalQueue = n;
      save();
   }

   public static void setAllowDuplicates(boolean b) {
      allowDuplicates = b;
      save();
   }

   public static void setServerVolume(double v) {
      serverVolume = v;
      save();
   }

   public static void setRequestCooldown(int n) {
      requestCooldown = n;
      save();
   }

   public static void setPrefix(String p) {
      prefix = p != null ? p : "§d[SimpMusic]§r";
      save();
   }

   public static void setUpdateCheckerEnabled(boolean e) {
      updateCheckerEnabled = e;
      save();
   }

   public static void setUpdateApiBaseUrl(String url) {
      updateApiUrl = url != null ? url : "https://api.github.com";
      save();
   }

   public static void setUpdateRepoPath(String path) {
      updateRepoPath = path != null ? path : "NekoZzz5354/SimpMusic";
      save();
   }

   public static void setUpdateToken(String token) {
      updateToken = token != null ? token : "";
      save();
   }

   public static void setUpdateNotifyOpsOnly(boolean b) {
      updateNotifyOpsOnly = b;
      save();
   }

   public static void setUpdateNotifyOnJoin(boolean b) {
      updateNotifyOnJoin = b;
      save();
   }

   public static void setUpdateCheckInterval(int min) {
      updateCheckIntervalMinutes = Math.max(1, min);
      save();
   }

   public static void setMTRCompatible(boolean c) {
      mtrCompatible = c;
      save();
   }

   public static void setMTRVersion(String v) {
      mtrVersion = v != null ? v : "";
      save();
   }

   public static void setPreferredSoundCategory(String cat) {
      preferredSoundCategory = cat != null ? cat : "MASTER";
      save();
   }

   public static void setQueueTickDelay(int delay) {
      queueTickDelay = Math.max(0, delay);
      save();
   }
}
