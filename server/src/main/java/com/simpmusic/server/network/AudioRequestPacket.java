package com.simpmusic.server.network;

import com.simpmusic.server.MusicQueueManager;
import com.simpmusic.server.NeteaseApiClient;
import com.simpmusic.server.SimpMusicServer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 客户端 → 服务端：请求由服务端代为获取音频数据。
 *
 * <p><b>为什么必须中转</b>：网易云播放直链带有 {@code authSecret} 反盗链令牌，
 * 该令牌与**发起解析请求的 IP** 绑定。服务端用自己的 Cookie 解析出直链后，
 * 客户端从**另一个 IP** 去下载必然被 CDN 拒绝（日志中即 `Audio download failed: HTTP 403`），
 * 表现就是「VIP 歌曲点了没反应 / 播不出声」。因此改由服务端下载后分块回传。
 */
public record AudioRequestPacket(String songId, int offsetSeconds) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "audio_request");

   /** 单次中转的最大音频体积（防止异常巨物撑爆内存与网络） */
   private static final int MAX_AUDIO_BYTES = 24 * 1024 * 1024;

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
      String songId = buf.readString();
      int offset = buf.readInt();
      SimpMusicServer.LOGGER.info("[Audio] 收到中转请求: song={} offset={}s player={}", songId, offset, player.getName().getString());

      CompletableFuture.runAsync(() -> {
         byte[] data = fetch(songId);

         server.execute(() -> {
            if (data == null || data.length == 0) {
               SimpMusicServer.LOGGER.warn("[Audio] 中转失败: song={}", songId);
               return;
            }

            if (data.length > MAX_AUDIO_BYTES) {
               SimpMusicServer.LOGGER.warn("[Audio] 音频过大，放弃中转: {} bytes", data.length);
               return;
            }

            SimpMusicServer.LOGGER.info("[Audio] 中转下发: song={} {} bytes", songId, data.length);
            AudioChunkPacket.sendAll(player, songId, data);
         });
      });
   }

   /** 用服务端自己的 Cookie 重新解析直链并下载（服务端 IP 与 authSecret 匹配） */
   private static byte[] fetch(String songId) {
      if (songId == null || songId.isEmpty()) {
         return null;
      }

      // 优先用队列里已解析好的地址
      String url = MusicQueueManager.getKnownUrl(songId);

      if (url == null || url.isEmpty()) {
         try {
            NeteaseApiClient.SongUrlResponse resp = NeteaseApiClient.getSongUrl(songId).get();
            url = resp != null && resp.ok ? resp.url : null;
         } catch (Exception e) {
            SimpMusicServer.LOGGER.warn("[Audio] 解析直链失败: {}", e.getMessage());
         }
      }

      if (url == null || url.isEmpty()) {
         return null;
      }

      byte[] data = NeteaseApiClient.downloadAudio(url);
      if (data != null && data.length > 0) {
         return data;
      }

      // 已解析的地址可能已过期/被拒，强制重新解析一次
      try {
         List<String> fresh = NeteaseApiClient.resolveFreshAudioUrl(songId);
         for (String candidate : fresh) {
            if (candidate != null && !candidate.isEmpty() && !candidate.equals(url)) {
               byte[] retry = NeteaseApiClient.downloadAudio(candidate);
               if (retry != null && retry.length > 0) {
                  return retry;
               }
            }
         }
      } catch (Exception e) {
         SimpMusicServer.LOGGER.warn("[Audio] 重新解析失败: {}", e.getMessage());
      }

      return null;
   }
}
