package com.simpmusic.client.hud;

import com.simpmusic.client.SimpMusicClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/**
 * HUD 专辑封面纹理管理。
 *
 * <p>v1.2.2：新增**服务端中转兜底**。客户端直连图片 CDN 失败的场景太多
 * （DNS、CDN 分区、运营商限制、HTTP/2 协商等），因此在直连失败后，
 * 会请服务端代为下载图片并把字节回传（服务端能正常访问网易云接口，
 * 说明其网络路径是通的），从根本上保证封面能显示。
 *
 * <p>另外：
 * <ul>
 *   <li>HTTP 跟随重定向（网易云图片会 302 到其它 CDN 节点）</li>
 *   <li>封面纹理只注册一次，换歌时用 setImage + upload 替换像素，避免销毁/注册竞态</li>
 *   <li>多候选地址（方形缩略图 → 原图），并校验文件头确认是图片</li>
 *   <li>全过程 INFO 级日志，便于定位</li>
 * </ul>
 */
public final class CoverTexture {
   private static final Identifier ID = Identifier.of("simpmusic", "hud_cover");
   private static final Identifier REQUEST_ID = Identifier.of("simpmusic", "request_cover");
   private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10L))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
   /** 请求的方形边长（网易云图片服务支持 param=WxH） */
   private static final int REQUEST_SIZE = 256;
   private static final int MAX_BYTES = 6 * 1024 * 1024;
   private static final long RETRY_DELAY_MS = 1500L;

   private static volatile String currentUrl = "";
   private static volatile String currentSongId = "";
   private static volatile boolean hasImage = false;
   private static volatile int texWidth = 0;
   private static volatile int texHeight = 0;
   /** 复用的动态纹理对象（只在渲染线程访问） */
   private static NativeImageBackedTexture texture;
   private static boolean registered = false;

   private CoverTexture() {
   }

   /** 纹理标识，首次成功加载后可用 */
   public static Identifier getId() {
      return registered ? ID : null;
   }

   public static boolean hasImage() {
      return hasImage;
   }

   public static int getWidth() {
      return texWidth;
   }

   public static int getHeight() {
      return texHeight;
   }

   /** 状态描述，供调试命令输出 */
   public static String describe() {
      if (hasImage) {
         return "已加载 " + texWidth + "x" + texHeight;
      }

      if (currentSongId.isEmpty() && currentUrl.isEmpty()) {
         return "未开始";
      }

      return currentUrl.isEmpty() ? "无地址（已请求服务端中转）" : "直连/中转进行中";
   }

   /**
    * 请求加载某首歌的封面。
    *
    * @param songId 歌曲 ID（服务端中转时需要）
    * @param url    服务端下发的封面地址，可能为空
    */
   public static void request(String songId, String url) {
      currentSongId = songId != null ? songId : "";

      if (url == null || url.isEmpty()) {
         SimpMusicClient.LOGGER.info("[Cover] 服务端未下发封面地址，直接请求服务端中转: song={}", currentSongId);
         requestFromServer();
         return;
      }

      if (url.equals(currentUrl) && hasImage) {
         return;
      }

      currentUrl = url;
      hasImage = false;      // 旧图立即失效，先显示占位块
      texWidth = 0;
      texHeight = 0;
      SimpMusicClient.LOGGER.info("[Cover] 开始加载: song={} url={}", currentSongId, url);

      loadAsync(url, 0);
   }

   /** 服务端中转回来的图片字节 */
   public static void accept(String songId, byte[] data) {
      if (songId == null || !songId.equals(currentSongId)) {
         SimpMusicClient.LOGGER.debug("[Cover] 丢弃过期中转图片: {} (current={})", songId, currentSongId);
         return;
      }

      if (data == null || data.length == 0) {
         return;
      }

      MinecraftClient client = MinecraftClient.getInstance();
      client.execute(() -> applyImage("服务端中转", data));
   }

   /** 释放当前封面（停止播放时调用）。纹理对象保留复用，仅标记为无图。 */
   public static void release() {
      hasImage = false;
      texWidth = 0;
      texHeight = 0;
   }

   /** 清空并允许重新加载同一 URL */
   public static void reset() {
      currentUrl = "";
      currentSongId = "";
      release();
   }

   // ------------------------------------------------------------------
   // 直连下载
   // ------------------------------------------------------------------

   private static void loadAsync(String url, int attempt) {
      CompletableFuture.supplyAsync(() -> downloadWithFallback(url)).thenAccept(bytes -> {
         MinecraftClient client = MinecraftClient.getInstance();
         client.execute(() -> {
            // 期间可能已切歌，丢弃过期结果
            if (!url.equals(currentUrl)) {
               return;
            }

            if (bytes == null) {
               if (attempt == 0) {
                  SimpMusicClient.LOGGER.warn("[Cover] 直连下载失败，{}ms 后重试: {}", RETRY_DELAY_MS, url);
                  CompletableFuture.runAsync(() -> {
                     try {
                        Thread.sleep(RETRY_DELAY_MS);
                     } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                     }
                  }).thenRun(() -> loadAsync(url, 1));
               } else {
                  // 直连两次都失败 → 请服务端代为下载
                  SimpMusicClient.LOGGER.warn("[Cover] 直连两次均失败，改用服务端中转: {}", url);
                  requestFromServer();
               }
               return;
            }

            applyImage("直连", bytes);
         });
      });
   }

   private static void requestFromServer() {
      if (currentSongId.isEmpty()) {
         SimpMusicClient.LOGGER.warn("[Cover] 缺少歌曲 ID，无法请求服务端中转");
         return;
      }

      try {
         ClientPlayNetworking.send(
            REQUEST_ID,
            PacketByteBufs.create().writeString(currentSongId).writeString(currentUrl)
         );
         SimpMusicClient.LOGGER.info("[Cover] 已向服务端请求封面中转: song={}", currentSongId);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("[Cover] 发送中转请求失败: {}", e.getMessage());
      }
   }

   /** 在渲染线程把像素写进动态纹理 */
   private static void applyImage(String source, byte[] bytes) {
      MinecraftClient client = MinecraftClient.getInstance();

      try {
         NativeImage image = NativeImage.read(bytes);
         int w = image.getWidth();
         int h = image.getHeight();

         if (w <= 0 || h <= 0) {
            image.close();
            SimpMusicClient.LOGGER.warn("[Cover] 图片尺寸异常 {}x{}", w, h);
            return;
         }

         TextureManager tm = client.getTextureManager();

         if (texture == null) {
            texture = new NativeImageBackedTexture(image);
            tm.registerTexture(ID, texture);
            registered = true;
         } else {
            texture.setImage(image);
            texture.upload();
         }

         texWidth = w;
         texHeight = h;
         hasImage = true;
         SimpMusicClient.LOGGER.info("[Cover] 封面已加载（{}）{}x{} ({} bytes)", source, w, h, bytes.length);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("[Cover] 封面解码失败（{}）({} bytes): {}", source, bytes.length, e.getMessage());
      }
   }

   /** 依次尝试：方形缩略图 → 原图，返回第一个像图片的响应体 */
   private static byte[] downloadWithFallback(String url) {
      List<String> candidates = new ArrayList<>(2);
      String square = squareUrl(url);
      candidates.add(square);
      if (!square.equals(url)) {
         candidates.add(url);
      }

      for (String candidate : candidates) {
         byte[] body = download(candidate);
         if (body != null && looksLikeImage(body)) {
            return body;
         }
      }

      return null;
   }

   private static String squareUrl(String url) {
      if (url.contains("param=")) {
         return url;
      }

      return url + (url.contains("?") ? "&" : "?") + "param=" + REQUEST_SIZE + "y" + REQUEST_SIZE;
   }

   private static byte[] download(String url) {
      try {
         HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://music.163.com/")
            // 不声明支持 webp/avif：Minecraft 的 NativeImage 只认 JPEG/PNG/BMP/GIF
            .header("Accept", "image/jpeg,image/png,image/bmp,image/gif,*/*;q=0.6")
            .timeout(Duration.ofSeconds(15L))
            .GET()
            .build();
         HttpResponse<byte[]> resp = HTTP.send(req, BodyHandlers.ofByteArray());
         byte[] body = resp.body();

         if (resp.statusCode() == 200 && body != null && body.length > 0) {
            if (body.length > MAX_BYTES) {
               return null;
            }

            return body;
         }

         SimpMusicClient.LOGGER.warn("[Cover] HTTP {}: {}", resp.statusCode(), url);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("[Cover] 下载异常: {} — {}", e.getMessage(), url);
      }

      return null;
   }

   /** 通过文件头判断是否为受支持的图片格式 */
   private static boolean looksLikeImage(byte[] b) {
      if (b == null || b.length < 12) {
         return false;
      }

      if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
         return true;
      }

      if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
         return true;
      }

      if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
         return true;
      }

      return b[0] == 'B' && b[1] == 'M';
   }
}
