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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/**
 * HUD 专辑封面纹理管理（v1.2.1 重写）。
 *
 * <p>修复「封面获取不到」的问题，关键改动：
 * <ul>
 *   <li><b>跟随重定向</b>：网易云图片会 302 到其它 CDN 节点，默认的 {@code Redirect.NEVER} 会拿到空响应</li>
 *   <li><b>复用同一张动态纹理</b>：只注册一次，换歌时用 {@code setImage + upload} 替换像素，
 *       避免反复 destroy/register 造成的竞态（旧实现里销毁与注册可能交错，导致纹理最终不存在）</li>
 *   <li><b>多候选地址回退</b>：方形缩略图 → 原图；并校验响应体确实是一张图片</li>
 *   <li><b>失败自动重试</b>：网络抖动时 2 秒后重试一次</li>
 *   <li>成功/失败都有明确日志，便于定位</li>
 * </ul>
 */
public final class CoverTexture {
   private static final Identifier ID = Identifier.of("simpmusic", "hud_cover");
   private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10L))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
   /** 请求的方形边长（网易云图片服务支持 param=WxH） */
   private static final int REQUEST_SIZE = 256;
   private static final int MAX_BYTES = 6 * 1024 * 1024;
   private static final long RETRY_DELAY_MS = 2000L;

   private static volatile String currentUrl = "";
   private static volatile boolean hasImage = false;
   private static volatile int texWidth = 0;
   private static volatile int texHeight = 0;
   /** 复用的动态纹理对象（只在渲染线程访问） */
   private static NativeImageBackedTexture texture;
   private static boolean registered = false;

   private CoverTexture() {
   }

   /** 纹理标识（首次成功加载后才可用，用于判定是否值得绘制） */
   public static Identifier getId() {
      return registered ? ID : null;
   }

   /** 当前是否已真正加载到像素 */
   public static boolean hasImage() {
      return hasImage;
   }

   public static int getWidth() {
      return texWidth;
   }

   public static int getHeight() {
      return texHeight;
   }

   /** 请求加载某首歌的封面；url 为空或与当前一致时不做任何事 */
   public static void request(String url) {
      if (url == null || url.isEmpty()) {
         SimpMusicClient.LOGGER.info("[Cover] 封面地址为空，跳过加载（服务端未下发封面）");
         return;
      }

      if (url.equals(currentUrl)) {
         return;
      }

      currentUrl = url;
      hasImage = false;      // 旧图立即失效，先显示占位块
      texWidth = 0;
      texHeight = 0;

      loadAsync(url, 0);
   }

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
                  SimpMusicClient.LOGGER.warn("[Cover] 首次下载失败，{}ms 后重试: {}", RETRY_DELAY_MS, url);
                  CompletableFuture.runAsync(() -> {
                     try {
                        Thread.sleep(RETRY_DELAY_MS);
                     } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                     }
                  }).thenRun(() -> loadAsync(url, 1));
               } else {
                  SimpMusicClient.LOGGER.warn("[Cover] 封面加载失败（已重试）: {}", url);
               }
               return;
            }

            applyImage(url, bytes);
         });
      });
   }

   /** 在渲染线程把像素写进动态纹理 */
   private static void applyImage(String url, byte[] bytes) {
      MinecraftClient client = MinecraftClient.getInstance();

      try {
         NativeImage image = NativeImage.read(bytes);
         int w = image.getWidth();
         int h = image.getHeight();

         if (w <= 0 || h <= 0) {
            image.close();
            SimpMusicClient.LOGGER.warn("[Cover] 图片尺寸异常 {}x{}: {}", w, h, url);
            return;
         }

         TextureManager tm = client.getTextureManager();

         if (texture == null) {
            // 首次：创建并注册，之后一直复用这个对象
            texture = new NativeImageBackedTexture(image);
            tm.registerTexture(ID, texture);
            registered = true;
         } else {
            // 换歌：替换像素即可，避免 destroy/register 的竞态
            texture.setImage(image);
            texture.upload();
         }

         texWidth = w;
         texHeight = h;
         hasImage = true;
         SimpMusicClient.LOGGER.info("[Cover] 封面已加载 {}x{} ({} bytes)", w, h, bytes.length);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("[Cover] 封面解码失败 ({} bytes): {} — {}", bytes.length, e.getMessage(), url);
      }
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
      release();
   }

   /** 依次尝试：方形缩略图 → 原图，返回第一个像图片的响应体 */
   private static byte[] downloadWithFallback(String url) {
      List<String> candidates = new ArrayList<>(2);
      candidates.add(squareUrl(url));
      if (!squareUrl(url).equals(url)) {
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

   /** 给封面地址追加方形尺寸参数，保证取回 1:1 图片 */
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
            // 不主动声明支持 webp/avif：Minecraft 的 NativeImage 只认 JPEG/PNG/BMP/GIF
            .header("Accept", "image/jpeg,image/png,image/bmp,image/gif,*/*;q=0.6")
            .timeout(Duration.ofSeconds(20L))
            .build();
         HttpResponse<byte[]> resp = HTTP.send(req, BodyHandlers.ofByteArray());
         byte[] body = resp.body();

         if (resp.statusCode() == 200 && body != null && body.length > 0) {
            if (body.length > MAX_BYTES) {
               SimpMusicClient.LOGGER.warn("[Cover] 图片过大，跳过: {} bytes ({})", body.length, url);
               return null;
            }

            return body;
         }

         SimpMusicClient.LOGGER.warn("[Cover] HTTP {} 或空响应: {}", resp.statusCode(), url);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("[Cover] 下载异常: {} — {}", e.getMessage(), url);
      }

      return null;
   }

   /** 通过文件头判断是否为受支持的图片格式（stb 支持 JPEG/PNG/BMP/GIF） */
   private static boolean looksLikeImage(byte[] b) {
      if (b == null || b.length < 12) {
         return false;
      }

      // JPEG
      if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
         return true;
      }

      // PNG
      if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
         return true;
      }

      // GIF
      if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
         return true;
      }

      // BMP
      if (b[0] == 'B' && b[1] == 'M') {
         return true;
      }

      return false;
   }
}
