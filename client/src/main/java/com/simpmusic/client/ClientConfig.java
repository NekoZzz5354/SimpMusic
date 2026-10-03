package com.simpmusic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class ClientConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_DIR = Path.of("SimpMusic");
   private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config-client.json");
   private static final Path CONFIG_BACKUP = CONFIG_DIR.resolve("config-client.json.bak");
   private static String musicApiUrl = "https://music.163.com";
   private static boolean updateEnabled = true;
   private static String updateApiUrl = "https://api.github.com";
   private static String updateRepoPath = "NekoZzz5354/MTRMusic";
   private static String updateToken = "";
   private static int updateIntervalMinutes = 360;
   private static int audioBufferSize = 4096;
   private static boolean tickSoundEnabled = true;
   private static String preferredSoundCategory = "MASTER";
   private static int defaultBitrate = 320000;
   private static float volumeMultiplier = 1.0F;
   private static boolean mtrCompatMode = true;

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
         if (json.has("music_api_url")) {
            musicApiUrl = json.get("music_api_url").getAsString();
         } else if (json.has("musicApiUrl")) {
            musicApiUrl = json.get("musicApiUrl").getAsString();
         }

         if (json.has("update_enabled")) {
            updateEnabled = json.get("update_enabled").getAsBoolean();
         }

         if (json.has("update_api_url")) {
            updateApiUrl = json.get("update_api_url").getAsString();
         } else if (json.has("updateApiUrl")) {
            updateApiUrl = json.get("updateApiUrl").getAsString();
         }

         if (json.has("update_repo_path")) {
            updateRepoPath = json.get("update_repo_path").getAsString();
         }

         if (json.has("update_token")) {
            updateToken = json.get("update_token").getAsString();
         }

         if (json.has("update_interval_minutes")) {
            updateIntervalMinutes = json.get("update_interval_minutes").getAsInt();
         }

         if (json.has("update_method")) {
            String oldMethod = json.get("update_method").getAsString().toLowerCase();
            if ("disabled".equals(oldMethod)) {
               updateEnabled = false;
            }
         }

         if (json.has("audio_buffer_size")) {
            audioBufferSize = json.get("audio_buffer_size").getAsInt();
         }

         if (json.has("tick_sound_enabled")) {
            tickSoundEnabled = json.get("tick_sound_enabled").getAsBoolean();
         }

         if (json.has("preferred_sound_category")) {
            preferredSoundCategory = json.get("preferred_sound_category").getAsString();
         }

         if (json.has("default_bitrate")) {
            defaultBitrate = json.get("default_bitrate").getAsInt();
         }

         if (json.has("volume_multiplier")) {
            volumeMultiplier = json.get("volume_multiplier").getAsFloat();
         }

         if (json.has("mtr_compat_mode")) {
            mtrCompatMode = json.get("mtr_compat_mode").getAsBoolean();
         }

         save();
         SimpMusicClient.LOGGER.info("SimpMusic client config loaded (merged with defaults)");
      } catch (Exception e) {
         SimpMusicClient.LOGGER.error("Failed to load client config, using defaults", e);

         try {
            if (Files.exists(CONFIG_FILE)) {
               Files.copy(CONFIG_FILE, CONFIG_BACKUP, StandardCopyOption.REPLACE_EXISTING);
               SimpMusicClient.LOGGER.warn("Corrupted config backed up to {}.bak", CONFIG_FILE);
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
            SimpMusicClient.LOGGER.info("Migrated legacy config directory MTRMusic/ -> SimpMusic/");
         }
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("Legacy config dir migration skipped: {}", e.getMessage());
      }
   }

   private static void save() {
      try {
         if (Files.exists(CONFIG_FILE)) {
            Files.copy(CONFIG_FILE, CONFIG_BACKUP, StandardCopyOption.REPLACE_EXISTING);
         }

         JsonObject json = new JsonObject();
         json.addProperty("music_api_url", musicApiUrl);
         json.addProperty("update_enabled", updateEnabled);
         json.addProperty("update_api_url", updateApiUrl);
         json.addProperty("update_repo_path", updateRepoPath);
         json.addProperty("update_token", updateToken);
         json.addProperty("update_interval_minutes", updateIntervalMinutes);
         json.addProperty("audio_buffer_size", audioBufferSize);
         json.addProperty("tick_sound_enabled", tickSoundEnabled);
         json.addProperty("preferred_sound_category", preferredSoundCategory);
         json.addProperty("default_bitrate", defaultBitrate);
         json.addProperty("volume_multiplier", volumeMultiplier);
         json.addProperty("mtr_compat_mode", mtrCompatMode);
         json.addProperty("_comment", "Edit values below. Backup is auto-saved to config-client.json.bak");
         Files.writeString(CONFIG_FILE, GSON.toJson(json), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      } catch (IOException e) {
         SimpMusicClient.LOGGER.error("Failed to save client config", e);
      }
   }

   private static void saveDefaults() {
      save();
      SimpMusicClient.LOGGER.info("SimpMusic default client config created at {}", CONFIG_FILE);
   }

   public static String getMusicApiUrl() {
      return musicApiUrl != null ? musicApiUrl : "https://music.163.com";
   }

   public static boolean isUpdateEnabled() {
      return updateEnabled;
   }

   public static String getUpdateApiUrl() {
      return updateApiUrl != null ? updateApiUrl : "https://api.github.com";
   }

   public static String getUpdateRepoPath() {
      return updateRepoPath != null ? updateRepoPath : "NekoZzz5354/MTRMusic";
   }

   public static String getUpdateToken() {
      return updateToken != null ? updateToken : "";
   }

   public static int getUpdateIntervalMinutes() {
      return Math.max(1, updateIntervalMinutes);
   }

   public static int getAudioBufferSize() {
      return audioBufferSize;
   }

   public static boolean isTickSoundEnabled() {
      return tickSoundEnabled;
   }

   public static String getPreferredSoundCategory() {
      return preferredSoundCategory;
   }

   public static int getDefaultBitrate() {
      return defaultBitrate;
   }

   public static float getVolumeMultiplier() {
      return volumeMultiplier;
   }

   public static boolean isMtrCompatMode() {
      return mtrCompatMode;
   }

   public static void setMusicApiUrl(String url) {
      musicApiUrl = url != null ? url : "https://music.163.com";
      save();
   }

   public static void setUpdateEnabled(boolean e) {
      updateEnabled = e;
      save();
   }

   public static void setUpdateApiBaseUrl(String url) {
      updateApiUrl = url != null ? url : "https://api.github.com";
      save();
   }

   public static void setUpdateRepoPath(String path) {
      updateRepoPath = path != null ? path : "NekoZzz5354/MTRMusic";
      save();
   }

   public static void setUpdateToken(String token) {
      updateToken = token != null ? token : "";
      save();
   }

   public static void setUpdateIntervalMinutes(int min) {
      updateIntervalMinutes = Math.max(1, min);
      save();
   }

   public static void setAudioBufferSize(int size) {
      audioBufferSize = Math.max(512, Math.min(16384, size));
      save();
   }

   public static void setTickSoundEnabled(boolean e) {
      tickSoundEnabled = e;
      save();
   }

   public static void setPreferredSoundCategory(String cat) {
      preferredSoundCategory = cat != null ? cat : "MASTER";
      save();
   }

   public static void setDefaultBitrate(int br) {
      defaultBitrate = br;
      save();
   }

   public static void setVolumeMultiplier(float v) {
      volumeMultiplier = Math.max(0.0F, Math.min(2.0F, v));
      save();
   }

   public static void setMtrCompatMode(boolean e) {
      mtrCompatMode = e;
      save();
   }

   static {
      load();
   }
}
