package com.simpmusic.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NeteaseApiClient {
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
   private static String apiBaseUrl = "https://music.163.com";
   private static final String OFFICIAL_HOST = "music.163.com";

   /** 移动端 UA：网易云对移动端放行的音质档位更高 */
   private static final String UA_MOBILE =
      "Mozilla/5.0 (Linux; Android 11; V2123A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

   public static boolean isOfficialMode() {
      return apiBaseUrl != null && apiBaseUrl.contains("music.163.com");
   }

   public static void setApiBaseUrl(String url) {
      apiBaseUrl = url != null && !url.isEmpty() ? url : "https://music.163.com";
   }

   public static String getApiBaseUrl() {
      return apiBaseUrl;
   }

   public static CompletableFuture<List<NeteaseApiClient.SongInfo>> search(String keyword) {
      return CompletableFuture.supplyAsync(
         () -> {
            List<NeteaseApiClient.SongInfo> results = new ArrayList<>();

            try {
               String url;
               if (isOfficialMode()) {
                  url = apiBaseUrl.replaceAll("/$", "") + "/api/search/get?s=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&type=1&limit=10";
               } else {
                  url = apiBaseUrl + "search?keywords=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&limit=10";
               }

               HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                  .header("User-Agent", "SimpMusic/" + SimpMusicClient.VERSION)
                  .header("Referer", "https://music.163.com/")
                  .timeout(Duration.ofSeconds(15L))
                  .build();
               HttpResponse<String> resp = HTTP.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
               if (resp.statusCode() != 200) {
                  return results;
               }

               JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
               if (root.has("result") && root.get("result").isJsonObject()) {
                  JsonObject result = root.getAsJsonObject("result");
                  if (result.has("songs")) {
                     JsonArray songs = result.getAsJsonArray("songs");

                     for (int i = 0; i < songs.size(); i++) {
                        JsonObject s = songs.get(i).getAsJsonObject();
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

                        // 时长毫秒：网易云字段为 dt（部分代理返回 duration），双字段兼容
                        if (s.has("dt") && !s.get("dt").isJsonNull() && s.get("dt").getAsInt() > 0) {
                           info.duration = s.get("dt").getAsInt() / 1000;
                        } else if (s.has("duration") && !s.get("duration").isJsonNull() && s.get("duration").getAsInt() > 0) {
                           info.duration = s.get("duration").getAsInt() / 1000;
                        }

                        results.add(info);
                     }
                  }
               }
            } catch (Exception e) {
               SimpMusicClient.LOGGER.error("Client search error: ", e);
            }

            return results;
         }
      );
   }

   public static CompletableFuture<String> getSongUrl(String songId, int bitrate) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            if (isOfficialMode()) {
               // v1.1.5：客户端不再持有 Cookie，VIP 鉴权由服务端完成并下发直链
               String real = resolveOfficialSongUrl(songId, bitrate);
               if (real != null) {
                  return real;
               }

               // 兜底：外链重定向（免费歌曲可用）
               return "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
            }

            String url = apiBaseUrl + "song/url?id=" + songId + "&br=" + bitrate;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "SimpMusic/" + SimpMusicClient.VERSION).timeout(Duration.ofSeconds(15L)).build();
            HttpResponse<String> resp = HTTP.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
               return null;
            }

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (root.has("data") && root.get("data").isJsonArray()) {
               JsonArray data = root.getAsJsonArray("data");
               if (data.size() > 0) {
                  JsonObject d = data.get(0).getAsJsonObject();
                  return d.has("url") && !d.get("url").isJsonNull() ? d.get("url").getAsString() : null;
               }
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.error("Client getSongUrl error: ", e);
         }

         return null;
      });
   }

   public static CompletableFuture<byte[]> downloadAudio(String url) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30L)).build();
            HttpResponse<byte[]> resp = HTTP.send(req, BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
               return resp.body();
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.error("Download audio error: ", e);
         }

         return null;
      });
   }

   /**
    * 解析官方播放地址（v1.1.5：客户端不再持有 Cookie，仅作无凭证解析）。
    *
    * <p>VIP 歌曲的鉴权统一由服务端完成，服务端下发的是已解析好的直链；
    * 此处仅在服务端模式下作为兜底，解析不出则回退 outer/url。
    */
   private static String resolveOfficialSongUrl(String songId, int bitrate) {
      int[] bitrates = bitrate == 320000 ? new int[]{320000} : new int[]{bitrate, 320000};

      for (int br : bitrates) {
         try {
            String url = "https://music.163.com/api/song/enhance/player/url?ids=["
               + songId + "]&br=" + br + "&encodeType=mp3";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
               .header("User-Agent", UA_MOBILE)
               .header("Referer", "https://music.163.com/")
               .timeout(Duration.ofSeconds(10L))
               .build();

            HttpResponse<String> resp = HTTP.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            if (d.has("url") && !d.get("url").isJsonNull()) {
               String real = d.get("url").getAsString();
               if (real != null && !real.isEmpty()) {
                  SimpMusicClient.LOGGER.info("Client song url: id={} br={}", songId, br);
                  return real;
               }
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.debug("Client resolve song url failed (br={}): {}", br, e.getMessage());
         }
      }

      return null;
   }

   public static class SongInfo {
      public String id;
      public String title;
      public String artist;
      public String album;
      public String coverUrl;
      public int duration;
   }
}
