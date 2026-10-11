package com.simpmusic.server.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：下发专辑封面图片字节。
 *
 * <p>客户端优先自行下载封面；若直连失败（网络/DNS/CDN 限制等），
 * 会改用 {@link RequestCoverPacket} 请服务端代为下载并回传，
 * 保证只要有网就能显示封面。
 */
public record CoverPacket(String songId, byte[] data) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "cover");

   /** 单张封面字节上限（1 MiB 是自定义包上限，这里留足余量） */
   public static final int MAX_BYTES = 512 * 1024;

   public static void send(ServerPlayerEntity player, String songId, byte[] data) {
      if (data == null || data.length == 0 || data.length > MAX_BYTES) {
         return;
      }

      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeString(songId != null ? songId : "");
      buf.writeByteArray(data);
      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
