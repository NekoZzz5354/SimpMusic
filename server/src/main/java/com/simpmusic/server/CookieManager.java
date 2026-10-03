package com.simpmusic.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云 Cookie 管理器（仅服务端使用）
 *
 * <p>v1.1.5 起会员 Cookie 统一由服务端持有：客户端不再读取任何 Cookie 文件，
 * VIP 歌曲的播放地址由服务端解析后下发。
 *
 * <p>支持三种投放方式，按优先级依次尝试：
 * <ol>
 *   <li>{@code SimpMusic/163cookie.json} —— 浏览器扩展（Cookie-Editor / EditThisCookie）
 *       导出的 JSON 数组原文，直接扔进去即可；</li>
 *   <li>{@code SimpMusic/cookie.json} —— 该文件内若为 JSON 数组同样识别，否则读取
 *       {@code netease} / {@code music163} / {@code cookie} 字段；</li>
 *   <li>纯文本 —— 单行 {@code name=value; name=value} 形式的 Cookie 字符串。</li>
 * </ol>
 */
public class CookieManager {
   /** 浏览器导出文件的推荐落点（与用户下载文件名一致，直接复制即可） */
   private static final Path EXPORT_FILE = Path.of("SimpMusic", "163cookie.json");   private static final Path COOKIE_FILE = Path.of("SimpMusic", "cookie.json");

   /** 参与请求的网易云相关域名 */
   private static final String[] NETEASE_DOMAINS = {"music.163.com", ".music.163.com", ".163.com", "163.com"};

   /** 与鉴权/播放无关的统计与广告类 Cookie，转发给接口只会污染请求 */
   private static final String[] TRACKING_PREFIXES = {
      "Hm_", "_ga", "_gid", "HMACCOUNT", "_iuqxldmzr_", "ntes_kaola_ad",
      "WNMCID", "WEVNSM", "NMTID", "JSESSIONID-WYYY", "gdxidpyhxdE", "__snaker__id"
   };

   /** 必须保留的核心字段（顺序靠前，便于日志排查） */
   private static final String[] ESSENTIAL = {"MUSIC_U", "__csrf", "__remember_me", "MUSIC_A", "MUSIC_R_T"};

   private static volatile String neteaseCookie = "";
   private static volatile String loadedFrom = "（未加载）";
   private static volatile String accountName = "";
   private static volatile int vipType = -1;
   private static volatile String country = "";

   private CookieManager() {
   }

   public static void load() {
      try {
         Files.createDirectories(COOKIE_FILE.getParent());

         // 1) 优先读取浏览器导出文件
         if (Files.exists(EXPORT_FILE)) {
            if (tryLoadExport(Files.readString(EXPORT_FILE, StandardCharsets.UTF_8), EXPORT_FILE)) {
               return;
            }
         }

         // 2) 回退到 cookie.json
         if (Files.exists(COOKIE_FILE)) {
            String content = Files.readString(COOKIE_FILE, StandardCharsets.UTF_8).trim();
            if (!content.isEmpty() && tryLoadExport(content, COOKIE_FILE)) {
               return;
            }
         }

         // 3) 无有效 Cookie —— 生成带说明的模板文件
         if (!Files.exists(COOKIE_FILE)) {
            JsonObject json = new JsonObject();
            json.addProperty("netease", "");
            json.addProperty(
               "_usage",
               "两种填法任选其一：①把浏览器导出的 163cookie.json 直接放到 SimpMusic/163cookie.json；②把 Cookie 字符串填到下面的 netease 字段。带 VIP 账号 Cookie 即可播放 VIP 歌曲。"
            );
            Files.writeString(COOKIE_FILE, json.toString(), StandardCharsets.UTF_8);
            SimpMusicServer.LOGGER.info("Created default cookie file: {}", COOKIE_FILE.toAbsolutePath());
         }

         SimpMusicServer.LOGGER.info(
            "未检测到有效的网易云 Cookie —— 仅能播放免费歌曲。将浏览器导出的 163cookie.json 放到 {} 即可解锁 VIP。",
            EXPORT_FILE.toAbsolutePath()
         );
      } catch (Exception e) {
         SimpMusicServer.LOGGER.warn("Failed to load cookie file: {}", e.getMessage());
      }
   }

   /**
    * 尝试按"浏览器导出"语义解析文本；解析不出有效条目时返回 false。
    */
   private static boolean tryLoadExport(String content, Path source) {
      content = content.trim();
      if (content.isEmpty()) {
         return false;
      }

      if (content.startsWith("[")) {
         // ---------- 浏览器扩展导出：JSON 数组 ----------
         try {
            JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
            List<JsonObject> relevant = new ArrayList<>();
            List<JsonObject> fallback = new ArrayList<>();

            for (JsonElement el : arr) {
               if (!el.isJsonObject()) {
                  continue;
               }

               JsonObject c = el.getAsJsonObject();
               if (!c.has("name") || !c.has("value") || c.get("value").isJsonNull()) {
                  continue;
               }

               String domain = c.has("domain") && !c.get("domain").isJsonNull() ? c.get("domain").getAsString() : "";
               if (isNeteaseDomain(domain)) {
                  relevant.add(c);
               } else if (domain.isEmpty()) {
                  fallback.add(c);
               }
            }

            List<JsonObject> picked = relevant.isEmpty() ? fallback : relevant;
            if (picked.isEmpty()) {
               SimpMusicServer.LOGGER.warn("Cookie file {} 中未找到 163.com 域下的条目，已忽略。", source);
               return false;
            }

            Map<String, String> map = new LinkedHashMap<>();
            for (JsonObject c : picked) {
               String name = c.get("name").getAsString().trim();
               String value = safeValue(c.get("value").getAsString());
               if (name.isEmpty() || value.isEmpty()) {
                  continue;
               }

               // MUSIC_U 存在同名重复项时以最后一条为准（与浏览器语义一致）
               map.put(name, value);
            }

            if (map.isEmpty() || !map.containsKey("MUSIC_U")) {
               SimpMusicServer.LOGGER.warn(
                  "Cookie file {} 中缺少 MUSIC_U（登录凭证）字段，无法用于 VIP 鉴权。请确认导出的是已登录 music.163.com 的 Cookie。",
                  source
               );
               return false;
            }

            apply(map, source);
            return true;
         } catch (Exception e) {
            SimpMusicServer.LOGGER.warn("解析 {} 失败（JSON 数组格式异常）：{}", source, e.getMessage());
            return false;
         }
      }

      if (content.startsWith("{")) {
         // ---------- 简单键值对象：{"netease": "..."} 或 {"netease": [ {...}, ... ]} ----------
         try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            for (String key : new String[]{"netease", "music163", "cookie", "neteaseCookie", "netease_cookie"}) {
               if (!json.has(key) || json.get(key).isJsonNull()) {
                  continue;
               }

               JsonElement el = json.get(key);
               if (el.isJsonArray()) {
                  // 字段本身就是导出数组，递归处理
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
            SimpMusicServer.LOGGER.warn("解析 {} 失败：{}", source, e.getMessage());
         }

         return false;
      }

      // ---------- 纯文本 Cookie 字符串 ----------
      Map<String, String> map = parseCookieString(content);
      if (map.containsKey("MUSIC_U")) {
         apply(map, source);
         return true;
      }

      if (!map.isEmpty()) {
         SimpMusicServer.LOGGER.warn("{} 中的 Cookie 字符串缺少 MUSIC_U，无法用于 VIP 鉴权。", source);
      }

      return false;
   }

   /** 解析 {@code a=1; b=2} 形式的 Cookie 字符串 */
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

   /** 组装最终请求头并缓存账号信息 */
   private static void apply(Map<String, String> map, Path source) {
      StringBuilder sb = new StringBuilder();

      // 核心字段排前面
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

      // 其余字段过滤掉统计类
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
      loadedFrom = source.toAbsolutePath().toString();

      SimpMusicServer.LOGGER.info("Loaded netease cookie from {}", loadedFrom);
      SimpMusicServer.LOGGER.info(
         "  cookie entries={}, total length={}, MUSIC_U={}",
         map.size() + countEssentialPresent(sb.toString()),
         neteaseCookie.length(),
         neteaseCookie.contains("MUSIC_U=") ? "OK" : "MISSING"
      );

      verifyAccountAsync();
   }

   private static int countEssentialPresent(String cookie) {
      int n = 0;
      for (String key : ESSENTIAL) {
         if (cookie.contains(key + "=")) {
            n++;
         }
      }

      return n;
   }

   /** 启动后异步校验登录态，把昵称与 VIP 类型写进日志与 /music status */
   private static void verifyAccountAsync() {
      Thread t = new Thread(() -> {
         String cookie = neteaseCookie;
         if (cookie == null || cookie.isEmpty()) {
            return;
         }

         try {
            NeteaseApiClient.AccountInfo info = NeteaseApiClient.fetchAccountInfo(cookie);
            if (info == null || !info.loggedIn) {
               SimpMusicServer.LOGGER.warn("网易云 Cookie 校验失败（可能已过期），VIP 歌曲将无法播放。");
               return;
            }

            accountName = info.nickname;
            vipType = info.vipType;
            country = info.country;
            SimpMusicServer.LOGGER.info(
               "网易云账号已登录：{} {}（vipType={}{}）",
               accountName,
               country.isEmpty() ? "" : "@" + country,
               vipType,
               vipType > 10 ? " · VIP 已生效，可播放 VIP 歌曲" : " · 该账号非 VIP"
            );
         } catch (Exception e) {
            SimpMusicServer.LOGGER.warn("Account verification failed: {}", e.getMessage());
         }
      }, "SimpMusic-Cookie-Verify");
      t.setDaemon(true);
      t.start();
   }

   private static boolean isNeteaseDomain(String domain) {
      if (domain == null || domain.isEmpty()) {
         return false;
      }

      String d = domain.toLowerCase();
      for (String allowed : NETEASE_DOMAINS) {
         if (d.equals(allowed)) {
            return true;
         }
      }

      return d.endsWith(".163.com") || d.endsWith(".music.163.com");
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
         // 浏览器导出的值可能含未转义的 \ 等非法序列，保持原样
         return value;
      }
   }

   public static String getNeteaseCookie() {
      return neteaseCookie;
   }

   public static boolean hasCookie() {
      return neteaseCookie != null && !neteaseCookie.isEmpty();
   }

   public static String getLoadedFrom() {
      return loadedFrom;
   }

   public static String getAccountName() {
      return accountName;
   }

   public static int getVipType() {
      return vipType;
   }

   public static String getCountry() {
      return country;
   }

   public static void save(String cookie) {
      if (cookie == null || cookie.isEmpty()) {
         return;
      }

      try {
         Files.createDirectories(COOKIE_FILE.getParent());
         JsonObject json = new JsonObject();
         json.addProperty("netease", cookie);
         Files.writeString(COOKIE_FILE, json.toString(), StandardCharsets.UTF_8);
         neteaseCookie = cookie;
         SimpMusicServer.LOGGER.info("Saved netease cookie to {}", COOKIE_FILE.toAbsolutePath());
      } catch (Exception e) {
         SimpMusicServer.LOGGER.warn("Failed to save cookie file: {}", e.getMessage());
      }
   }
}
