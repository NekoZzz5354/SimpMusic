package com.simpmusic.client.hud;

import com.simpmusic.client.SimpMusicClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/**
 * HUD 专辑封面纹理管理。
 *
 * <p>从网易云封面地址异步下载图片，解码为 {@link NativeImage} 后注册为动态纹理供 HUD 绘制。
 * 统一向图片服务请求正方形缩略图（{@code ?param=200y200}），既保证 1:1 比例，也避免拉取原图浪费显存。
 * 切换歌曲时旧纹理会被销毁并替换。
 */
public final class CoverTexture {
   private static final Identifier ID = Identifier.of("simpmusic", "hud_cover");
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
   /** 请求的方形边长（网易云图片服务支持 param=WxH） */
   private static final int REQUEST_SIZE = 200;

   private static volatile String currentUrl = "";
   private static volatile boolean registered = false;
   private static int texWidth = 0;
   private static int texHeight = 0;

   private CoverTexture() {
   }

   /** 当前可用的封面纹理，未就绪返回 null */
   public static Identifier getId() {
      return registered ? ID : null;
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
         return;
      }

      if (url.equals(currentUrl)) {
         return;
      }

      currentUrl = url;
      release();
      final String wanted = url;

      CompletableFuture.supplyAsync(() -> download(squareUrl(wanted))).thenAccept(bytes -> {
         if (bytes == null) {
            SimpMusicClient.LOGGER.debug("Cover download failed: {}", wanted);
            return;
         }

         MinecraftClient client = MinecraftClient.getInstance();
         client.execute(() -> {
            // 期间可能已切歌，丢弃过期结果
            if (!wanted.equals(currentUrl)) {
               return;
            }

            try {
               NativeImage image = NativeImage.read(bytes);
               NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
               TextureManager tm = client.getTextureManager();
               tm.destroyTexture(ID);
               tm.registerTexture(ID, texture);
               texture.upload();
               texWidth = image.getWidth();
               texHeight = image.getHeight();
               registered = true;
               SimpMusicClient.LOGGER.debug("Cover texture ready: {}x{}", texWidth, texHeight);
            } catch (Exception e) {
               SimpMusicClient.LOGGER.warn("Cover decode failed: {}", e.getMessage());
            }
         });
      });
   }

   /** 释放当前封面纹理（停止播放/换歌时调用） */
   public static void release() {
      registered = false;
      texWidth = 0;
      texHeight = 0;
      final MinecraftClient client = MinecraftClient.getInstance();
      client.execute(() -> {
         try {
            client.getTextureManager().destroyTexture(ID);
         } catch (Exception ignored) {
            // 纹理解绑失败不影响主流程
         }
      });
   }

   /** 清空并允许重新加载同一 URL */
   public static void reset() {
      currentUrl = "";
      release();
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
            .header("User-Agent", "SimpMusic/" + SimpMusicClient.VERSION)
            .header("Referer", "https://music.163.com/")
            .timeout(Duration.ofSeconds(20L))
            .build();
         HttpResponse<byte[]> resp = HTTP.send(req, BodyHandlers.ofByteArray());
         byte[] body = resp.body();
         if (resp.statusCode() == 200 && body != null && body.length > 0) {
            return body;
         }
      } catch (Exception e) {
         SimpMusicClient.LOGGER.debug("Cover download error: {}", e.getMessage());
      }

      return null;
   }
}
