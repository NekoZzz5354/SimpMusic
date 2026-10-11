package com.simpmusic.server.network;

import com.simpmusic.server.NeteaseApiClient;
import com.simpmusic.server.SimpMusicServer;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 客户端 → 服务端：请求代下专辑封面。
 *
 * <p>客户端直连图片 CDN 失败时使用。服务端先试客户端给的地址，
 * 不行再用自己的接口按 songId 重新解析封面地址，
 * 下载成功后通过 {@link CoverPacket} 把字节回传给该玩家。
 */
public record RequestCoverPacket(String songId, String coverUrl) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "request_cover");

   public static int SIZE = 256;

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
      String songId = buf.readString();
      String coverUrl = buf.readString();
      SimpMusicServer.LOGGER.info("[Cover] 收到中转请求: song={} url={}", songId, coverUrl.isEmpty() ? "(空)" : coverUrl);

      CompletableFuture.runAsync(() -> {
         byte[] data = fetch(songId, coverUrl);

         // 客户端给的地址不行时，用服务端自己解析出的地址再试一次
         if (data == null) {
            String resolved = NeteaseApiClient.resolveCoverUrl(songId);
            if (resolved != null && !resolved.isEmpty()) {
               data = fetch(songId, resolved);
            }
         }

         final byte[] out = data;
         server.execute(() -> {
            if (out != null && out.length > 0) {
               CoverPacket.send(player, songId, out);
               SimpMusicServer.LOGGER.info("[Cover] 中转成功: song={} {} bytes", songId, out.length);
            } else {
               SimpMusicServer.LOGGER.warn("[Cover] 中转失败: song={}", songId);
            }
         });
      });
   }

   private static byte[] fetch(String songId, String url) {
      if (url == null || url.isEmpty()) {
         return null;
      }

      return NeteaseApiClient.downloadImage(withSquareParam(url));
   }

   private static String withSquareParam(String url) {
      if (url.contains("param=")) {
         return url;
      }

      return url + (url.contains("?") ? "&" : "?") + "param=" + SIZE + "y" + SIZE;
   }
}
