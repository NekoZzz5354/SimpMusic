package com.simpmusic.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
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

public class NeteaseApiClient {
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
   private static final String OFFICIAL_HOST = "music.163.com";

   /** 移动端 UA：网易云对移动端放的音质档位通常更高，VIP 鉴权也最稳定 */
   private static final String UA_MOBILE =
      "Mozilla/5.0 (Linux; Android 11; V2123A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
   private static final String UA_PC =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
   private static final String UA_REFERER = "https://music.163.com/";

   public static boolean isOfficialMode() {
      String base = ModConfig.getApiBaseUrl() == null ? "" : ModConfig.getApiBaseUrl();
      return base.contains("music.163.com");
   }

   /**
    * 统一的官方请求构造：自动附带已配置的网易云 Cookie 与浏览器 Referer。
    * 带 Cookie 后网易云才会下发 VIP 歌曲的真实播放地址。
    */
   private static Builder officialRequest(String url, String ua) {
      Builder b = HttpRequest.newBuilder(URI.create(url))
         .header("User-Agent", ua)
         .header("Referer", UA_REFERER)
         .header("Accept", "*/*")
         .timeout(Duration.ofSeconds(15L));

      String cookie = CookieManager.getNeteaseCookie();
      if (cookie != null && !cookie.isEmpty()) {
         b.header("Cookie", cookie);
      }

      return b;
   }

   public static CompletableFuture<NeteaseApiClient.SearchResponse> search(String keyword) {
      return CompletableFuture.supplyAsync(
         () -> {
            NeteaseApiClient.SearchResponse resp = new NeteaseApiClient.SearchResponse();

            try {
               String url;
               if (isOfficialMode()) {
                  url = ModConfig.getApiBaseUrl().replaceAll("/$", "")
                     + "/api/search/get?s="
                     + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                     + "&type=1&limit=10";
               } else {
                  url = ModConfig.getApiBaseUrl() + "search?keywords=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&limit=10";
               }

               HttpResponse<String> httpResp = HTTP.send(
                  officialRequest(url, UA_PC).build(), BodyHandlers.ofString(StandardCharsets.UTF_8)
               );
               if (httpResp.statusCode() != 200) {
                  resp.errorMsg = "HTTP " + httpResp.statusCode();
                  return resp;
               }

               JsonObject root = JsonParser.parseString(httpResp.body()).getAsJsonObject();
               if (root.has("result") && root.get("result").isJsonObject()) {
                  JsonObject result = root.getAsJsonObject("result");
                  if (result.has("songs")) {
                     JsonArray songs = result.getAsJsonArray("songs");

                     for (int i = 0; i < songs.size(); i++) {
                        JsonObject s = songs.get(i).getAsJsonObject();
                        NeteaseApiClient.SongInfo info = parseSongInfo(s);
                        if (info != null) {
                           resp.songs.add(info);
                        }
                     }
                  }
               }

               resp.ok = true;
            } catch (Exception e) {
               SimpMusicServer.LOGGER.error("Search error: {}", e.getMessage());
               resp.errorMsg = e.getMessage();
            }

            return resp;
         }
      );
   }

   public static CompletableFuture<NeteaseApiClient.SongUrlResponse> getSongUrl(String songId) {
      return CompletableFuture.supplyAsync(() -> {
         NeteaseApiClient.SongUrlResponse resp = new NeteaseApiClient.SongUrlResponse();

         try {
            if (!isOfficialMode()) {
               String url = ModConfig.getApiBaseUrl() + "song/url?id=" + songId + "&br=" + ModConfig.getDefaultBitrate();
               HttpRequest req = officialRequest(url, UA_PC).build();
               HttpResponse<String> httpResp = HTTP.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
               if (httpResp.statusCode() != 200) {
                  return resp;
               }

               JsonObject root = JsonParser.parseString(httpResp.body()).getAsJsonObject();
               if (root.has("data") && root.get("data").isJsonArray()) {
                  JsonArray data = root.getAsJsonArray("data");
                  if (data.size() > 0) {
                     JsonObject d = data.get(0).getAsJsonObject();
                     resp.url = d.has("url") && !d.get("url").isJsonNull() ? d.get("url").getAsString() : null;
                     resp.br = d.has("br") && !d.get("br").isJsonNull() ? d.get("br").getAsInt() : ModConfig.getDefaultBitrate();
                     resp.ok = resp.url != null && !resp.url.isEmpty();
                  }
               }

               return resp;
            }

            // ---- 官方接口：带 Cookie 直连，VIP 歌曲依赖此路径 ----
            String realUrl = requestOfficialSongUrl(songId);
            if (realUrl != null) {
               resp.url = realUrl;
               resp.br = ModConfig.getDefaultBitrate();
               resp.ok = true;
               return resp;
            }

            // 兜底：外链重定向（仅对免费歌曲有效，VIP 会 302 到 404 页）
            resp.url = "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
            resp.br = ModConfig.getDefaultBitrate();
            resp.ok = true;
            return resp;
         } catch (Exception e) {
            SimpMusicServer.LOGGER.error("GetSongUrl error: {}", e.getMessage());
         }

         return resp;
      });
   }

   /**
    * 解析官方播放地址。
    *
    * <p>策略：先按用户配置码率请求；若返回 code 404 / url 为空（通常是音质档位不足或
    * 该曲目无此码率资源），再退一步请求标准 320k。移动端 UA 命中率高于 PC UA。
    */
   private static String requestOfficialSongUrl(String songId) {
      int[] bitrates = distinctBitrates(ModConfig.getDefaultBitrate(), 320000);

      for (String ua : new String[]{UA_MOBILE, UA_PC}) {
         for (int br : bitrates) {
            try {
               String url = "https://music.163.com/api/song/enhance/player/url?ids=["
                  + songId + "]&br=" + br + "&encodeType=mp3";
               HttpResponse<String> resp = HTTP.send(
                  officialRequest(url, ua).build(), BodyHandlers.ofString(StandardCharsets.UTF_8)
               );

               if (resp.statusCode() != 200) {
                  continue;
               }

               JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
               if (!root.has("data") || !root.get("data").isJsonArray()) {
                  continue;
               }

               JsonArray data = root.getAsJsonArray("data");
               if (data.size() == 0) {
                  continue;
               }

               JsonObject d = data.get(0).getAsJsonObject();
               int code = d.has("code") ? d.get("code").getAsInt() : -1;

               if (d.has("url") && !d.get("url").isJsonNull()) {
                  String real = d.get("url").getAsString();
                  if (real != null && !real.isEmpty()) {
                     String level = d.has("level") && !d.get("level").isJsonNull() ? d.get("level").getAsString() : "unknown";
                     int fee = d.has("fee") ? d.get("fee").getAsInt() : 0;
                     SimpMusicServer.LOGGER.info(
                        "Song url resolved: id={} br={} level={} fee={} size={}",
                        songId, br, level, fee, d.has("size") ? d.get("size").getAsInt() : 0
                     );
                     return real;
                  }
               }

               if (code != 200) {
                  SimpMusicServer.LOGGER.debug("Song url attempt rejected: id={} br={} code={}", songId, br, code);
               }
            } catch (Exception e) {
               SimpMusicServer.LOGGER.debug("Official song url error (br={}): {}", br, e.getMessage());
            }
         }
      }

      return null;
   }

   private static int[] distinctBitrates(int first, int second) {
      return first == second ? new int[]{first} : new int[]{first, second};
   }

   /** 查询当前 Cookie 对应的账号信息（昵称 / VIP 类型），供启动日志与 /music cookie 命令使用 */
   public static AccountInfo fetchAccountInfo(String cookie) {
      try {
         HttpResponse<String> resp = HTTP.send(
            officialRequest("https://music.163.com/api/nuser/account/get", UA_PC).build(),
            BodyHandlers.ofString(StandardCharsets.UTF_8)
         );

         if (resp.statusCode() != 200) {
            return null;
         }

         JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
         int code = root.has("code") ? root.get("code").getAsInt() : -1;

         AccountInfo info = new AccountInfo();
         info.loggedIn = code == 200 && root.has("profile") && !root.get("profile").isJsonNull();

         if (info.loggedIn) {
            JsonObject profile = root.getAsJsonObject("profile");
            info.nickname = profile.has("nickname") && !profile.get("nickname").isJsonNull()
               ? profile.get("nickname").getAsString()
               : "未知";
            info.vipType = profile.has("vipType") && !profile.get("vipType").isJsonNull()
               ? profile.get("vipType").getAsInt()
               : 0;

            if (profile.has("userId") && !profile.get("userId").isJsonNull()) {
               info.userId = profile.get("userId").getAsString();
            }

            // 地区信息在 account 字段里（部分账号为空）
            if (root.has("account") && root.get("account").isJsonObject()) {
               JsonObject account = root.getAsJsonObject("account");
               if (account.has("country") && !account.get("country").isJsonNull()) {
                  info.country = account.get("country").getAsString();
               }
            }
         }

         return info;
      } catch (Exception e) {
         SimpMusicServer.LOGGER.debug("Fetch account info error: {}", e.getMessage());
         return null;
      }
   }

   public static CompletableFuture<NeteaseApiClient.SongInfo> getSongInfo(String songId) {
      return CompletableFuture.supplyAsync(
         () -> {
            try {
               String url;
               if (isOfficialMode()) {
                  url = ModConfig.getApiBaseUrl().replaceAll("/$", "")
                     + "/api/v3/song/detail?c="
                     + URLEncoder.encode("[{\"id\":" + songId + "}]", StandardCharsets.UTF_8);
               } else {
                  url = ModConfig.getApiBaseUrl() + "song/detail?ids=" + songId;
               }

               HttpResponse<String> resp = HTTP.send(
                  officialRequest(url, UA_PC).build(), BodyHandlers.ofString(StandardCharsets.UTF_8)
               );
               if (resp.statusCode() != 200) {
                  return null;
               }

               JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
               if (root.has("songs") && root.get("songs").isJsonArray()) {
                  JsonArray songs = root.getAsJsonArray("songs");
                  if (songs.size() > 0) {
                     return parseSongInfo(songs.get(0).getAsJsonObject());
                  }
               }
            } catch (Exception e) {
               SimpMusicServer.LOGGER.error("GetSongInfo error: {}", e.getMessage());
            }

            return null;
         }
      );
   }

   public static void getSongInfoAsync(String songId, Consumer<NeteaseApiClient.SongInfo> callback) {
      getSongInfo(songId).thenAccept(info -> {
         if (callback != null) {
            callback.accept(info);
         }
      });
   }

   public static CompletableFuture<String> getLyric(String songId) {
      return CompletableFuture.supplyAsync(
         () -> {
            try {
               String url;
               if (isOfficialMode()) {
                  url = ModConfig.getApiBaseUrl().replaceAll("/$", "") + "/api/song/lyric?id=" + songId + "&lv=-1";
               } else {
                  url = ModConfig.getApiBaseUrl() + "lyric?id=" + songId;
               }

               HttpResponse<String> resp = HTTP.send(
                  officialRequest(url, UA_PC).build(), BodyHandlers.ofString(StandardCharsets.UTF_8)
               );
               if (resp.statusCode() != 200) {
                  return null;
               }

               JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
               if (root.has("lrc") && root.getAsJsonObject("lrc").has("lyric")) {
                  return root.getAsJsonObject("lrc").get("lyric").getAsString();
               }
            } catch (Exception e) {
               SimpMusicServer.LOGGER.error("GetLyric error: {}", e.getMessage());
            }

            return null;
         }
      );
   }

   /**
    * 拉取歌词 + 翻译（v1.2.0 起 HUD 需要一并展示原文与译文）。
    *
    * <p>官方接口 {@code /api/song/lyric?lv=-1&tv=-1} 会同时返回：
    * <ul>
    *   <li>{@code lrc.lyric} —— 原文（可能含时间标签）</li>
    *   <li>{@code tlyric.lyric} —— 中文翻译（外语歌才有，可能为空）</li>
    *   <li>{@code romalrc.lyric} —— 罗马音（日文歌常有，可选）</li>
    * </ul>
    */
   public static CompletableFuture<NeteaseApiClient.LyricsData> getLyricWithTranslation(String songId) {
      return CompletableFuture.supplyAsync(() -> {
         NeteaseApiClient.LyricsData data = new NeteaseApiClient.LyricsData();

         try {
            String url;
            if (isOfficialMode()) {
               url = ModConfig.getApiBaseUrl().replaceAll("/$", "") + "/api/song/lyric?id=" + songId + "&lv=-1&kv=-1&tv=-1";
            } else {
               url = ModConfig.getApiBaseUrl() + "lyric?id=" + songId;
            }

            HttpResponse<String> resp = HTTP.send(
               officialRequest(url, UA_PC).build(), BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (resp.statusCode() != 200) {
               return data;
            }

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            data.lrc = readNestedLyric(root, "lrc");
            data.translation = readNestedLyric(root, "tlyric");
            data.roman = readNestedLyric(root, "romalrc");
            data.ok = data.lrc != null && !data.lrc.isEmpty();
         } catch (Exception e) {
            SimpMusicServer.LOGGER.debug("GetLyricWithTranslation error: {}", e.getMessage());
         }

         return data;
      });
   }

   /** 从 {@code {"<field>": {"lyric": "..."}}} 结构中取出歌词文本，缺失返回 null */
   private static String readNestedLyric(JsonObject root, String field) {
      if (root.has(field) && root.get(field).isJsonObject()) {
         JsonObject node = root.getAsJsonObject(field);
         if (node.has("lyric") && !node.get("lyric").isJsonNull()) {
            String text = node.get("lyric").getAsString();
            return text != null && !text.isEmpty() ? text : null;
         }
      }
      return null;
   }

   public static void searchAsync(String keyword, Consumer<List<NeteaseApiClient.SongInfo>> callback) {
      search(keyword).thenAccept(resp -> {
         if (callback != null) {
            callback.accept(resp.ok ? resp.songs : new ArrayList<>());
         }
      });
   }

   public static void getSongUrlAsync(String songId, Consumer<String> callback) {
      getSongUrl(songId).thenAccept(resp -> {
         if (callback != null) {
            callback.accept(resp.ok ? resp.url : null);
         }
      });
   }

   private static NeteaseApiClient.SongInfo parseSongInfo(JsonObject s) {
      try {
         NeteaseApiClient.SongInfo info = new NeteaseApiClient.SongInfo();
         info.id = s.get("id").getAsString();
         info.title = s.has("name") ? s.get("name").getAsString() : "Unknown";
         if (s.has("artists") && s.getAsJsonArray("artists").size() > 0) {
            info.artist = s.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();
         } else if (s.has("ar") && s.getAsJsonArray("ar").size() > 0) {
            info.artist = s.getAsJsonArray("ar").get(0).getAsJsonObject().get("name").getAsString();
         } else {
            info.artist = "Unknown";
         }

         if (s.has("album")) {
            JsonObject album = s.getAsJsonObject("album");
            info.album = album.has("name") ? album.get("name").getAsString() : "";
            info.coverUrl = album.has("picUrl") ? album.get("picUrl").getAsString() : "";
         } else if (s.has("al")) {
            JsonObject album = s.getAsJsonObject("al");
            info.album = album.has("name") ? album.get("name").getAsString() : "";
            info.coverUrl = album.has("picUrl") ? album.get("picUrl").getAsString() : "";
         }

         // 时长毫秒：网易云详情/搜索接口字段为 dt，部分代理返回 duration，双字段兼容
         if (s.has("dt") && !s.get("dt").isJsonNull() && s.get("dt").getAsInt() > 0) {
            info.duration = s.get("dt").getAsInt() / 1000;
         } else if (s.has("duration") && !s.get("duration").isJsonNull() && s.get("duration").getAsInt() > 0) {
            info.duration = s.get("duration").getAsInt() / 1000;
         }

         // 付费标识：fee=1 VIP / fee=8 低音质免费试听，用于界面上做提示
         if (s.has("fee") && !s.get("fee").isJsonNull()) {
            info.fee = s.get("fee").getAsInt();
         }

         return info;
      } catch (Exception e) {
         SimpMusicServer.LOGGER.error("Parse song info error: {}", e.getMessage());
         return null;
      }
   }

   public static class LyricsData {
      /** 原文歌词（LRC） */
      public String lrc;
      /** 中文翻译（外语歌才有，可能为 null） */
      public String translation;
      /** 罗马音（日/韩文歌常有，可能为 null） */
      public String roman;
      public boolean ok;
   }

   public static class SearchResponse {
      public List<NeteaseApiClient.SongInfo> songs = new ArrayList<>();
      public boolean ok;
      public String errorMsg;
   }

   public static class SongInfo {
      public String id;
      public String title;
      public String artist;
      public String album;
      public String coverUrl;
      public int duration;
      public int fee;
   }

   public static class SongUrlResponse {
      public String url;
      public int br;
      public boolean ok;
   }

   public static class AccountInfo {
      public boolean loggedIn;
      public String nickname = "";
      public String userId = "";
      public String country = "";
      public int vipType = -1;
   }
}
