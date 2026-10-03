package com.simpmusic.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端网易云 Cookie 管理器（v1.1.4 新增）
 *
 * <p>客户端在"官网直连"模式下需要自己解析播放地址，带 VIP Cookie 才能拿到
 * 付费歌曲的真实链接。投放方式与服务端一致：
 * <ol>
 *   <li>{@code SimpMusic/163cookie.json} —— 浏览器导出的 JSON 数组原文；</li>
 *   <li>{@code SimpMusic/cookie.json} —— 上述数组或 {@code netease} 字段。</li>
 * </ol>
 */
public class CookieManager {
   private static final Path EXPORT_FILE = Path.of("SimpMusic", "163cookie.json");
   private static final Path COOKIE_FILE = Path.of("SimpMusic", "cookie.json");

   private static final String[] TRACKING_PREFIXES = {
      "Hm_", "_ga", "_gid", "HMACCOUNT", "_iuqxldmzr_", "ntes_kaola_ad",
      "WNMCID", "WEVNSM", "NMTID", "JSESSIONID-WYYY", "gdxidpyhxdE", "__snaker__id"
   };

   private static final String[] ESSENTIAL = {"MUSIC_U", "__csrf", "__remember_me", "MUSIC_A", "MUSIC_R_T"};

   private static volatile String neteaseCookie = "";
   private static volatile boolean loaded = false;

   private CookieManager() {
   }

   public static void load() {
      try {
         if (Files.exists(EXPORT_FILE) && tryLoadExport(Files.readString(EXPORT_FILE, StandardCharsets.UTF_8), EXPORT_FILE)) {
            return;
         }

         if (Files.exists(COOKIE_FILE)) {
            String content = Files.readString(COOKIE_FILE, StandardCharsets.UTF_8).trim();
            if (!content.isEmpty() && tryLoadExport(content, COOKIE_FILE)) {
               return;
            }
         }

         SimpMusicClient.LOGGER.info(
            "未检测到网易云 Cookie —— VIP 歌曲不可播放。将浏览器导出的 163cookie.json 放到 {} 即可解锁。",
            EXPORT_FILE.toAbsolutePath()
         );
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("Failed to load client cookie: {}", e.getMessage());
      }
   }

   private static boolean tryLoadExport(String content, Path source) {
      content = content.trim();
      if (content.isEmpty()) {
         return false;
      }

      if (content.startsWith("[")) {
         try {
            JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
            Map<String, String> map = new LinkedHashMap<>();
            Map<String, String> fallback = new LinkedHashMap<>();

            for (JsonElement el : arr) {
               if (!el.isJsonObject()) {
                  continue;
               }

               JsonObject c = el.getAsJsonObject();
               if (!c.has("name") || !c.has("value") || c.get("value").isJsonNull()) {
                  continue;
               }

               String name = c.get("name").getAsString().trim();
               String value = safeValue(c.get("value").getAsString());
               if (name.isEmpty() || value.isEmpty()) {
                  continue;
               }

               String domain = c.has("domain") && !c.get("domain").isJsonNull() ? c.get("domain").getAsString().toLowerCase() : "";
               if (domain.isEmpty() || domain.endsWith("163.com")) {
                  map.put(name, value);
               } else {
                  fallback.put(name, value);
               }
            }

            if (map.isEmpty()) {
               map = fallback;
            }

            if (!map.containsKey("MUSIC_U")) {
               SimpMusicClient.LOGGER.warn("{} 中缺少 MUSIC_U 字段，无法用于 VIP 鉴权。", source);
               return false;
            }

            apply(map, source);
            return true;
         } catch (Exception e) {
            SimpMusicClient.LOGGER.warn("解析 {} 失败：{}", source, e.getMessage());
            return false;
         }
      }

      if (content.startsWith("{")) {
         try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            for (String key : new String[]{"netease", "music163", "cookie", "neteaseCookie", "netease_cookie"}) {
               if (!json.has(key) || json.get(key).isJsonNull()) {
                  continue;
               }

               JsonElement el = json.get(key);
               if (el.isJsonArray()) {
                  return tryLoadExport(el.toString(), source);
               }

               String raw = el.getAsString().trim();
               if (raw.isEmpty()) {
                  continue;
               }

               if (raw.startsWith("[") || raw.startsWith("{")) {
                  return tryLoadExport(raw, source);
               }

               Map<String, String> map = parseCookieString(raw);
               if (map.containsKey("MUSIC_U")) {
                  apply(map, source);
                  return true;
               }
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.warn("解析 {} 失败：{}", source, e.getMessage());
         }

         return false;
      }

      Map<String, String> map = parseCookieString(content);
      if (map.containsKey("MUSIC_U")) {
         apply(map, source);
         return true;
      }

      return false;
   }

   private static Map<String, String> parseCookieString(String raw) {
      Map<String, String> map = new LinkedHashMap<>();

      for (String pair : raw.split(";")) {
         pair = pair.trim();
         if (pair.isEmpty()) {
            continue;
         }

         int eq = pair.indexOf('=');
         if (eq <= 0) {
            continue;
         }

         String name = pair.substring(0, eq).trim();
         String value = decode(pair.substring(eq + 1).trim());
         if (!name.isEmpty() && !value.isEmpty()) {
            map.put(name, value);
         }
      }

      return map;
   }

   private static void apply(Map<String, String> map, Path source) {
      StringBuilder sb = new StringBuilder();

      for (String key : ESSENTIAL) {
         String value = map.remove(key);
         if (value == null) {
            continue;
         }

         if (sb.length() > 0) {
            sb.append("; ");
         }

         sb.append(key).append('=').append(value);
      }

      for (Map.Entry<String, String> e : map.entrySet()) {
         if (isTracking(e.getKey())) {
            continue;
         }

         if (sb.length() > 0) {
            sb.append("; ");
         }

         sb.append(e.getKey()).append('=').append(e.getValue());
      }

      neteaseCookie = sb.toString();
      loaded = true;
      SimpMusicClient.LOGGER.info(
         "Loaded netease cookie from {} (len={}, MUSIC_U={})",
         source.toAbsolutePath(),
         neteaseCookie.length(),
         neteaseCookie.contains("MUSIC_U=") ? "OK" : "MISSING"
      );
   }

   private static boolean isTracking(String name) {
      for (String prefix : TRACKING_PREFIXES) {
         if (name.startsWith(prefix)) {
            return true;
         }
      }

      return false;
   }

   private static String safeValue(String value) {
      return value == null ? "" : decode(value.trim());
   }

   private static String decode(String value) {
      try {
         return URLDecoder.decode(value, StandardCharsets.UTF_8);
      } catch (Exception e) {
         return value;
      }
   }

   public static String getNeteaseCookie() {
      return neteaseCookie;
   }

   public static boolean hasCookie() {
      return loaded && neteaseCookie != null && !neteaseCookie.isEmpty();
   }
}
