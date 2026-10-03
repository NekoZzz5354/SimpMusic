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
 * 服务端 → 客户端：开始播放指定歌曲。
 * startOffsetSeconds 为起始播放位置（秒）：新歌为 0；中途加入/重连的玩家按当前进度跳过，
 * 实现无缝接续而不是从头播放。
 */
public record PlaySongPacket(String songId, String title, String artist, String url, String coverUrl, int startOffsetSeconds) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "play_song");

   public static void send(
      ServerPlayerEntity player, String songId, String title, String artist, String url, String coverUrl, int startOffsetSeconds
   ) {
      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeString(songId);
      buf.writeString(title);
      buf.writeString(artist);
      buf.writeString(url);
      buf.writeString(coverUrl != null ? coverUrl : "");
      buf.writeInt(Math.max(0, startOffsetSeconds));
      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
